package com.arohi.ai.automation

import android.app.RemoteInput
import android.content.Intent
import android.content.Context
import com.arohi.ai.brain.GeminiClient
import com.arohi.ai.brain.SystemPromptBuilder
import com.arohi.ai.commands.CommandRouter
import com.arohi.ai.core.ArohiBus
import com.arohi.ai.core.SettingsStore
import com.arohi.ai.memory.MemoryRepository
import com.arohi.ai.services.ArohiAccessibilityService
import com.arohi.ai.services.ArohiNotificationListener
import com.arohi.ai.voice.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v4 — REAL message-sending engine (সর্বোচ্চ গুরুত্ব).
 *
 * Send pipeline, tried in order until something REALLY works:
 *  1. INLINE REPLY (RemoteInput) — the OS-blessed path used by WhatsApp,
 *     Telegram, Messenger, Instagram DM, Messages… No app opening needed.
 *     If PendingIntent.send() succeeds, the message IS handed to the app.
 *  2. OPEN CONVERSATION (contentIntent) → wait → SET_TEXT → click send.
 *  3. SET_TEXT blocked → clipboard PASTE → click send (gesture if needed).
 *  4. Anything less → honest failure + draft saved to chat. A "sent" state is
 *     NEVER shown unless a real path actually completed.
 *
 * Modes (Settings): AUTO (sends by herself, default per owner's policy) /
 * ASK (speaks draft, waits for yes/no) / OFF.
 */
object AutoReplyEngine {

    private var appCtx: Context? = null

    data class SendOutcome(val success: Boolean, val how: String, val detail: String)

    fun init(context: Context) { appCtx = context.applicationContext }

    suspend fun handle(context: Context, item: ArohiNotificationListener.Companion.NotifItem) {
        val s = SettingsStore.get(context)
        if (!com.arohi.ai.core.NetworkMonitor.isOnline) return

        // wait politely for the announcement to finish (v3 bug: reply was lost)
        var waited = 0
        while (ArohiBus.speaking.value && waited < 18_000) { delay(600); waited += 600 }
        if (ArohiBus.speaking.value) return

        val reply = try {
            val system = SystemPromptBuilder.build(context, mode = "autoreply")
            val userMsg = "প্ল্যাটফর্ম: ${item.appLabel}। প্রেরক: ${item.title}। মেসেজ: ${item.text.take(300)}। " +
                "ছোট, বন্ধুসুলভ বাংলা রিপ্লাই লেখো — শুধু রিপ্লাই টেক্সট দাও।"
            com.arohi.ai.brain.ArohiBrain.chat(context, system, emptyList(), userMsg)
                .getOrNull()?.second?.trim()
        } catch (_: Exception) { null } ?: run {
            // offline / brain busy → short generic warm reply instead of silence
            "ঠিক আছে, দেখছি। একটু পরে বিস্তারিত বলবো।"
        }
        if (reply.isBlank() || reply.length > 400) return

        val mem = MemoryRepository.get(context)
        mem.addChat("system", "অটো-রিপ্লাই ড্রাফট (${item.appLabel}, ${item.title}): $reply")

        when (s.autoReplyMode) {
            SettingsStore.AUTO_REPLY_ASK -> {
                val say = "${item.appLabel}-এ ${item.title} থেকে মেসেজ এসেছে। রিপ্লাই পাঠাবো? ড্রাফটটা হলো: $reply — বলুন হ্যাঁ বা না।"
                CommandRouter.get(context).setPendingCustom(say) { approved ->
                    if (approved) sendAsync(context, item, reply)
                    else CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        mem.addChat("arohi", "ঠিক আছে, রিপ্লাই পাঠাইনি।")
                    }
                }
                TtsManager.speak(say)
            }
            SettingsStore.AUTO_REPLY_AUTO -> {
                mem.addChat("arohi", "${item.appLabel}-এ নিজে থেকে রিপ্লাই দিচ্ছি: $reply")
                sendAsync(context, item, reply)
            }
        }
    }

    private fun sendAsync(context: Context, item: ArohiNotificationListener.Companion.NotifItem, reply: String) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val outcome = sendToItem(context, item, reply)
            if (outcome.success) {
                ArohiBus.toast("অটো-রিপ্লাই পাঠানো হয়েছে (${item.appLabel} — ${outcome.how})")
                try {
                    MemoryRepository.get(context).logActivity("AUTO_REPLY",
                        "${item.appLabel} → ${item.title}: ${reply.take(60)} [${outcome.how}]")
                } catch (_: Exception) {}
            } else {
                ArohiBus.toast("অটো-রিপ্লাই ব্যর্থ: ${outcome.detail} — ড্রাফট চ্যাটে সেভ আছে")
                TtsManager.speak("রিপ্লাই পাঠাতে পারিনি: ${outcome.detail}। ড্রাফটটা আমি চ্যাটে রেখে দিয়েছি — চাইলে আবার চেষ্টা করবো।")
            }
        }
    }

    /** Public entry used by auto-reply, the voice REPLY_LAST action and MESSAGES screen. */
    suspend fun sendToItem(
        context: Context,
        item: ArohiNotificationListener.Companion.NotifItem,
        reply: String
    ): SendOutcome = withContext(Dispatchers.IO) {

        // ---------- 1) INLINE REPLY (RemoteInput) — the reliable path ----------
        for (a in item.replyActions) {
            try {
                val fill = Intent()
                val results = android.os.Bundle()
                results.putCharSequence(a.remoteInputKey, reply)
                val ri = RemoteInput.Builder(a.remoteInputKey).setLabel(a.label).build()
                RemoteInput.addResultsToIntent(arrayOf(ri), fill, results)
                a.pendingIntent.send(context, 0, fill)
                return@withContext SendOutcome(true, "Inline Reply",
                    "${item.appLabel}-এর নোটিফিকেশন থেকে সরাসরি পাঠানো হয়েছে")
            } catch (_: android.app.PendingIntent.CanceledException) {
                continue // stale action → try the next / fall through
            } catch (_: Exception) {
                continue
            }
        }

        // ---------- 2/3) OPEN + TYPE (SET_TEXT → PASTE) ----------
        if (!ArohiAccessibilityService.isConnected) {
            return@withContext SendOutcome(false, "",
                "অ্যাক্সেসিবিলিটি সার্ভিস চালু নেই — সেটিংসে অনুমতি দিন")
        }
        try {
            item.contentIntent?.send()
        } catch (_: Exception) {
            return@withContext SendOutcome(false, "", "কথোপকথন খোলা যায়নি (${item.appLabel})")
        }

        val svc = ArohiAccessibilityService.instance ?: return@withContext SendOutcome(false, "",
            "অ্যাক্সেসিবিলিটি সার্ভিস সংযোগ হারিয়েছে")

        // apps take a while to open — hunt for the input field up to ~12s
        var typed = false
        var editable: android.view.accessibility.AccessibilityNodeInfo? = null
        for (attempt in 1..12) {
            delay(1000)
            val root = svc.rootWindow() ?: continue
            val ed = svc.findEditable(root)
            if (ed != null) { editable = ed; typed = true; break }
        }
        if (!typed || editable == null) {
            return@withContext SendOutcome(false, "",
                "কথোপকথন খুলেছি কিন্তু মেসেজ ইনপুট খুঁজে পাইনি — ${item.appLabel}-এর ইউআই সমর্থন করছে না")
        }

        val how = svc.typeIntoEditable(reply, editable)
        if (how == "type_failed") {
            return@withContext SendOutcome(false, "",
                "টাইপ করা গেল না — এই অ্যাপ SET_TEXT ও PASTE দুটোই ব্লক করে")
        }

        // find send button across up to 6s (button may appear after text is set)
        var sendNode: android.view.accessibility.AccessibilityNodeInfo? = null
        for (attempt in 1..6) {
            val root = svc.rootWindow()
            val s = root?.let { svc.findSendButton(it) }
            if (s != null) { sendNode = s; break }
            delay(1000)
        }
        if (sendNode != null) {
            val clicked = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                svc.clickNode(sendNode) { ok -> if (cont.isActive) cont.resumeWith(Result.success(ok)) }
            }
            if (clicked) {
                return@withContext SendOutcome(true,
                    if (how == "typed_paste") "পেস্ট+সেন্ড" else "টাইপ+সেন্ড",
                    "কথোপকথনে টাইপ করে সেন্ড করা হয়েছে")
            }
        }
        // send button missing → message is typed but NOT sent. NEVER claim sent.
        return@withContext SendOutcome(false,
            if (how == "typed_paste") "পেস্ট" else "টাইপ",
            "মেসেজ লিখেছি কিন্তু সেন্ড বাটন খুঁজে পাইনি — পাঠানো হয়নি, অ্যাপে গিয়ে চাপ দিন")
    }
}
