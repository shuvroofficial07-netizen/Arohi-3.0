package com.arohi.ai.services

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.arohi.ai.core.ArohiBus
import com.arohi.ai.core.SettingsStore
import com.arohi.ai.automation.AutoReplyEngine
import com.arohi.ai.voice.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Reads every incoming notification (after the user enables it in system
 * settings), announces it in detail through Arohi's voice, and feeds the
 * auto-reply engine for messaging apps.
 *
 * v4 — the REAL reply capability:
 *  - each NotifItem now carries its notification's INLINE REPLY actions
 *    (Notification.Action + RemoteInput). Those are the OS-blessed way to
 *    reply from the shade WITHOUT opening the app — WhatsApp, Telegram,
 *    Messenger, Instagram DM, Messages… all support it.
 *  - AutoReplyEngine tries inline reply first, then falls back to opening
 *    the conversation and typing (SET_TEXT → clipboard paste).
 */
class ArohiNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile var instance: ArohiNotificationListener? = null
            private set

        /** One inline-reply action of a notification (OS-blessed reply path). */
        data class ReplyAction(val label: String, val remoteInputKey: String, val pendingIntent: PendingIntent)

        data class NotifItem(val appLabel: String, val pkg: String, val title: String,
                             val text: String, val category: String, val timestamp: Long,
                             val contentIntent: PendingIntent? = null,
                             val replyActions: List<ReplyAction> = emptyList())

        val recent = ArrayDeque<NotifItem>()

        val EMAIL_PACKAGES = setOf(
            "com.google.android.gm", "com.microsoft.office.outlook", "com.yahoo.mobile.client.android.mail",
            "com.zoho.mail", "com.android.email")
        val MESSAGING_PACKAGES = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "com.facebook.orca", "org.telegram.messenger",
            "com.google.android.apps.messaging", "com.android.mms", "com.discord",
            "com.viber.voip", "com.skype.raider", "jp.naver.line.android", "im.vector.app",
            "org.thoughtcrime.securesms", "com.snapchat.android")
        val SOCIAL_PACKAGES = setOf(
            "com.facebook.katana", "com.facebook.lite", "com.instagram.android",
            "com.zhiliaoapp.musically", "com.zhiliaoapp.musically.go", "com.twitter.android",
            "com.linkedin.android", "com.facebook.pages.app")

        fun categoryOf(pkg: String): String = when {
            pkg in EMAIL_PACKAGES -> "ইমেইল"
            pkg in MESSAGING_PACKAGES -> "মেসেজ"
            pkg in SOCIAL_PACKAGES -> "সোশ্যাল মিডিয়া"
            else -> "অ্যাপ"
        }

        /** NOTIF_SUMMARY command support — only REAL stored items are summarised. */
        fun summaryBn(): String {
            if (recent.isEmpty()) return "এই মুহূর্তে কোনো নোটিফিকেশন জমা নেই।"
            return "সাম্প্রতিক নোটিফিকেশন: " + recent.takeLast(5).map { "${it.appLabel} থেকে ${it.title.ifBlank { it.text.take(30) }}" }
                .joinToString("; ")
        }

        /** Latest messaging/social notification (voice REPLY_LAST target). */
        fun latestReplyable(): NotifItem? =
            recent.lastOrNull { it.pkg in MESSAGING_PACKAGES || it.pkg in SOCIAL_PACKAGES }

        // dedup window: same pkg+title+text within 45s is ignored
        private var lastKey: String = ""
        private var lastAt: Long = 0L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val settings = SettingsStore.get(applicationContext)
        if (n.packageName == packageName || n.packageName.startsWith("com.android.systemui")) return
        val notification = n.notification ?: return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())?.trim() ?: ""
        if (title.isBlank() && text.isBlank()) return

        val appLabel = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(n.packageName, 0)).toString()
        } catch (_: Exception) { n.packageName }

        // ---- v4: collect INLINE REPLY actions (RemoteInput) ----
        val replyActions = mutableListOf<ReplyAction>()
        try {
            for (act in notification.actions ?: emptyArray()) {
                val ri = act.remoteInputs?.firstOrNull() ?: continue
                if (act.actionIntent != null) {
                    replyActions.add(ReplyAction(act.title?.toString() ?: "Reply", ri.resultKey, act.actionIntent))
                }
            }
        } catch (_: Exception) {}

        val item = NotifItem(appLabel, n.packageName, title, text, categoryOf(n.packageName), n.postTime,
            contentIntent = notification.contentIntent, replyActions = replyActions)

        // ---- dedup (apps like Instagram re-post the same notification) ----
        val key = "${n.packageName}|$title|$text"
        val now = System.currentTimeMillis()
        synchronized(recent) {
            if (key == lastKey && now - lastAt < 45_000) return
            lastKey = key; lastAt = now
            recent.addLast(item)
            if (recent.size > 30) recent.removeFirst()
        }

        scope.launch {
            try {
                com.arohi.ai.memory.MemoryRepository.get(applicationContext)
                    .logActivity("NOTIFICATION", "$appLabel: ${title.take(60)} ${text.take(60)}")
            } catch (_: Exception) {}
        }

        // Voice announcement — skip only if Arohi is already mid-sentence
        if (settings.announceNotifications && !ArohiBus.speaking.value) {
            val announcement = buildAnnouncement(item)
            if (announcement.isNotBlank()) TtsManager.speak(announcement)
            ArohiBus.toast("নোটিফিকেশন: $appLabel — ${title.take(40)}")
        }

        // Auto-reply decision for messaging AND social apps
        val replyTargets = MESSAGING_PACKAGES + SOCIAL_PACKAGES
        if (n.packageName in replyTargets && settings.autoReplyMode != SettingsStore.AUTO_REPLY_OFF) {
            scope.launch { AutoReplyEngine.handle(applicationContext, item) }
        }
    }

    private fun buildAnnouncement(item: NotifItem): String {
        val body = if (SettingsStore.get(applicationContext).announceContent)
            item.text.take(120).ifBlank { "বিস্তারিত টেক্সট নেই" }
        else "বিষয়বস্তু প্রাইভেসি মোডে বলছি না — স্ক্রিনে দেখে নিন"
        val openers = listOf("শুভ্র, শোনো —", "শুভ্র, একটু খেয়াল করো —", "লুক করো না শুভ্র —", "দেখো দেখো —")
        val opener = openers[Random.nextInt(openers.size)]
        return when (item.category) {
            "ইমেইল" -> "$opener নতুন ইমেইল এসেছে ${item.appLabel} থেকে। ${item.title}: $body"
            "মেসেজ" -> "$opener ${item.appLabel}-এ ${item.title} মেসেজ দিয়েছে: $body"
            "সোশ্যাল মিডিয়া" -> "$opener ${item.appLabel}-এ নোটিফিকেশন — ${item.title}: $body"
            else -> "নোটিফিকেশন ${item.appLabel} থেকে। ${item.title}: $body"
        }
    }
}
