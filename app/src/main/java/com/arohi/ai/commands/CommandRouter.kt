package com.arohi.ai.commands

import android.content.Context
import com.arohi.ai.brain.ArohiBrain
import com.arohi.ai.brain.BengaliGuard
import com.arohi.ai.brain.GeminiClient
import com.arohi.ai.brain.IntentParser
import com.arohi.ai.brain.SystemPromptBuilder
import com.arohi.ai.core.ArohiBus
import com.arohi.ai.core.ConvState
import com.arohi.ai.core.Emotion
import com.arohi.ai.core.NetworkMonitor
import com.arohi.ai.memory.MemoryRepository
import com.arohi.ai.proactive.EmotionEngine
import com.arohi.ai.services.ConversationHolder
import com.arohi.ai.voice.ToneTracker
import com.arohi.ai.voice.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Arohi's central command pipeline:
 * user text → confirmation gate → local intent → Gemini brain → action → voice reply.
 * Every failure path answers honestly (offline / no Gemini response / voice issues).
 */
class CommandRouter private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // pending confirmation for risky actions
    @Volatile private var pendingAction: PendingAction? = null
    @Volatile private var pendingCustom: Pair<String, ((Boolean) -> Unit)>? = null

    data class PendingAction(val say: String, val action: String, val params: Map<String, String>)

    companion object {
        @Volatile private var inst: CommandRouter? = null
        fun get(context: Context): CommandRouter =
            inst ?: synchronized(this) { inst ?: CommandRouter(context.applicationContext).also { inst = it } }
    }

    // ---------- Confirmation ----------
    fun setPendingConfirmation(say: String, action: String, params: Map<String, String>) {
        pendingAction = PendingAction(say, action, params)
        ArohiBus.pendingConfirmation.value = say
    }

    fun setPendingCustom(say: String, callback: (Boolean) -> Unit) {
        pendingCustom = say to callback
        ArohiBus.pendingConfirmation.value = say
    }

    /** v4: Emergency Stop support — drop every pending yes/no gate. */
    fun clearPendingActions() {
        pendingAction = null
        pendingCustom = null
        ArohiBus.pendingConfirmation.value = null
    }

    fun answerConfirmation(approved: Boolean) {
        val pa = pendingAction
        val pc = pendingCustom
        pendingAction = null
        pendingCustom = null
        ArohiBus.pendingConfirmation.value = null
        if (pc != null) {
            scope.launch { pc.second(approved) }
            return
        }
        if (pa == null) return
        if (approved) {
            scope.launch {
                val result = ActionExecutor.execute(context, pa.action, pa.params)
                reply(result.speak.ifBlank { "কাজ সম্পন্ন করলাম শুভ্র।" })
            }
        } else {
            reply("ঠিক আছে শুভ্র, আমি বাতিল করে দিলাম।")
        }
    }

    // ---------- Main entry ----------
    fun handle(text: String, source: String = "voice") {
        scope.launch {
            val mem = MemoryRepository.get(context)
            ArohiBus.setState(ConvState.THINKING)

            // v4: emergency stop latch — any user utterance releases it
            if (ArohiBus.emergencyStopped.value) ArohiBus.emergencyStopped.value = false

            // v4: multi-agent routing — publish which agent owns this request
            com.arohi.ai.agents.AgentOrchestrator.publish(text)

            // 0) emotion first (mood feeds the persona)
            EmotionEngine.analyze(text, mem)

            // v5 — শুভ্রের গলার টোন + বলার ধরন শনাক্ত: prosody (মাইক এনার্জি)
            // + টেক্সট সিগন্যাল মিলিয়ে টোন বের করে ব্রেইনে পাঠানো হয়,
            // যাতে আরোহী সেই ভঙ্গিতেই উত্তর দেয়।
            if (source == "voice" || source == "wake") {
                ToneTracker.classify(text, ToneTracker.snapshot())
                ToneTracker.reset()
            } else {
                ToneTracker.classifyText(text)
                ToneTracker.reset()
            }
            if (ArohiBus.emotion.value == Emotion.ANGRY && text.any { it.isLetter() }) {
                // rude words → Arohi shows hurt feelings, still usable
            }

            // 1) pending confirmation (yes/no)
            if (pendingAction != null || pendingCustom != null) {
                val t = text.lowercase()
                val yes = listOf("হ্যাঁ", "হা", "yes", "yeah", "ok", "ওকে", "ঠিক", "ঠিক আছে", "পাঠাও", "পাঠা", "করো", "করে দাও", "দে", "approved", "কনফার্ম", "হুম পাঠাও")
                val no = listOf("না", "no", "বাতিল", "নেই", "don't", "stop", "থাম", "পাঠিও না", "cancel")
                when {
                    yes.any { t.contains(it) } -> { answerConfirmation(true); return@launch }
                    no.any { t.contains(it) } -> { answerConfirmation(false); return@launch }
                }
            }

            // 2) fast local path (works offline, saves tokens)
            val local = IntentParser.match(text)
            if (local != null) {
                val result = ActionExecutor.execute(context, local.action, local.params)
                val speak = result.speak
                if (speak.isNotBlank()) reply(speak) else {
                    // action executed silently → gentle ack
                    reply("করে দিলাম শুভ্র।")
                }
                return@launch
            }

            // 1.5) pure emotional exchange (praise / apology / teasing) — reply
            //      locally so warmth survives even when Gemini is unreachable
            if (text.length < 60 && !NetworkMonitor.isOnline) {
                val e = ArohiBus.emotion.value
                val line = when {
                    e == Emotion.ANGRY -> EmotionEngine.angryReplies().random()
                    e == Emotion.HAPPY -> EmotionEngine.happyReplies().random()
                    else -> null
                }
                if (line != null) { reply(line); return@launch }
            }

            // 3) offline fallback → honest message
            if (!NetworkMonitor.isOnline) {
                ArohiBus.setState(ConvState.ERROR)
                reply(context.getString(com.arohi.ai.R.string.err_internet_check) +
                    " অফলাইনে আমি ডিভাইস কমান্ড আর স্মৃতি চালাতে পারি।")
                return@launch
            }

            // 4) AI brain — v6: Gemini বা OpenAI, যেটা কনফিগার্ড + অটো-ফলব্যাক
            if (!com.arohi.ai.brain.ArohiBrain.anyConfigured(context)) {
                ArohiBus.setState(ConvState.ERROR)
                reply("ব্রেইন API কী এখনো সেট করা হয়নি — API CENTER-এ Gemini বা OpenAI কী দিলে আমার পুরো বুদ্ধি জেগে উঠবে।")
                return@launch
            }

            // v5 long-chat: ২৪ টার্নের কনটেক্সট + দীর্ঘমেয়াদি স্মৃতি — ঘণ্টার গল্পও ধারাবাহিক থাকে
            val history = try { mem.recentChat(24).map { it.role to it.text } }
                catch (_: Exception) { emptyList() }

            val system = SystemPromptBuilder.build(context, mode = "command")
            val response = ArohiBrain.chat(context, system, history, text)
            response.fold(
                onSuccess = { (provider, raw) ->
                    val json = GeminiClient.extractJson(raw)
                    if (json != null && json.has("say")) {
                        var say = json.optString("say", "").trim()
                        // v6 BENGALI-ONLY GUARD: মডেল ইংরেজিতে উত্তর দিলে একবার বাংলায় পুনঃরিকোয়েস্ট
                        if (say.isNotBlank() && BengaliGuard.isMostlyEnglish(say)) {
                            val retry = ArohiBrain.chat(context, system, history,
                                "$text\n\n${BengaliGuard.FORCE_BENGALI_SUFFIX}")
                            retry.fold(onSuccess = { (_, raw2) ->
                                val j2 = GeminiClient.extractJson(raw2)
                                val s2 = j2?.optString("say", "")?.trim() ?: raw2.trim()
                                if (s2.isNotBlank() && !BengaliGuard.isMostlyEnglish(s2)) say = s2
                            }, onFailure = {})
                        }
                        val action = json.optString("action", "NONE").uppercase()
                        val confirm = json.optBoolean("confirm", false)
                        val paramsJson = json.optJSONObject("params")
                        val params = mutableMapOf<String, String>()
                        if (paramsJson != null) {
                            val keys = paramsJson.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                val v = paramsJson.opt(k)
                                params[k] = when (v) {
                                    is org.json.JSONArray -> {
                                        val sb = StringBuilder()
                                        for (i in 0 until v.length()) {
                                            if (i > 0) sb.append("|")
                                            sb.append(v.optString(i))
                                        }
                                        sb.toString()
                                    }
                                    null -> ""
                                    else -> v.toString()
                                }
                            }
                        }
                        setMood(json.optString("mood", ""))

                        if (say.isBlank() && action == "NONE") {
                            reply("হুম, একটু ভেবে বলি…")
                        } else if (confirm) {
                            setPendingConfirmation(
                                "$say — অনুমোদন দিলে 'হ্যাঁ' বলুন, নাহলে 'না'।", action, params)
                            reply(say)
                        } else {
                            val exec = ActionExecutor.execute(context, action, params)
                            val finalSay = buildString {
                                append(say)
                                if (exec.speak.isNotBlank() && action != "NONE" && !exec.speak.startsWith("এই অ্যাকশন"))
                                    append(if (say.isNotBlank()) " " else "").append(exec.speak)
                            }
                            reply(finalSay.ifBlank { exec.speak.ifBlank { "করে দিলাম শুভ্র।" } })
                        }
                    } else {
                        // model answered naturally without JSON → speak it directly
                        var natural = raw.take(600)
                        // v6 BENGALI-ONLY GUARD (raw path): ইংরেজি raw উত্তর বাংলায় ফেরত আনা
                        if (BengaliGuard.isMostlyEnglish(natural)) {
                            val retry = ArohiBrain.chat(context, system, history,
                                "$text\n\n${BengaliGuard.FORCE_BENGALI_SUFFIX}")
                            retry.fold(onSuccess = { (_, raw2) ->
                                val j2 = GeminiClient.extractJson(raw2)
                                val s2 = (j2?.optString("say", "")?.trim()).takeIf { !it.isNullOrBlank() }
                                    ?: raw2.trim()
                                if (s2.isNotBlank() && !BengaliGuard.isMostlyEnglish(s2))
                                    natural = s2.take(600)
                            }, onFailure = {})
                        }
                        reply(natural)
                    }
                },
                onFailure = { err ->
                    ArohiBus.setState(ConvState.ERROR)
                    ArohiBus.emotion.value = Emotion.ERROR
                    val msg = when {
                        err.message == "offline" -> context.getString(com.arohi.ai.R.string.err_internet_check)
                        err.message == "no_key" -> "ব্রেইন API কী সেট করা হয়নি — API CENTER-এ Gemini বা OpenAI কী দিয়ে দিন"
                        else -> context.getString(com.arohi.ai.R.string.err_gemini_no_response)
                    }
                    // emotional failure: if Shubho just hurt her feelings, say the
                    // hurt line first, then the honest technical reason
                    val emotional = if (ArohiBus.emotion.value == Emotion.ANGRY)
                        EmotionEngine.angryReplies().random() + " " else ""
                    reply(emotional + msg)
                }
            )
        }
    }

    private fun setMood(mood: String) {
        val e = when (mood.lowercase()) {
            "happy" -> Emotion.HAPPY; "playful" -> Emotion.PLAYFUL
            "focused" -> Emotion.FOCUSED; "confused" -> Emotion.CONFUSED
            "angry" -> Emotion.ANGRY; "sad" -> Emotion.SAD
            else -> null
        }
        if (e != null) ArohiBus.emotion.value = e
    }

    /** Speak + persist + resume the voice loop if in a session. */
    private fun reply(text: String) {
        if (text.isBlank()) { ArohiBus.resetToIdle(); return }
        scope.launch {
            try { MemoryRepository.get(context).addChat("arohi", text, ArohiBus.emotion.value.name) }
            catch (_: Exception) {}
        }
        TtsManager.speak(text) {
            if (ArohiBus.sessionOpen.value) ConversationHolder.engine?.listenForUser()
        }
    }

    private fun String.containsAny(vararg keys: String): Boolean = keys.any { contains(it) }
}
