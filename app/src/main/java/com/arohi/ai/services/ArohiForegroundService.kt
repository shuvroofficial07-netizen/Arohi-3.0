package com.arohi.ai.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.arohi.ai.MainActivity
import com.arohi.ai.R
import com.arohi.ai.automation.AutoReplyEngine
import com.arohi.ai.automation.TaskEngine
import com.arohi.ai.core.ArohiBus
import com.arohi.ai.core.PermissionHelper
import com.arohi.ai.core.SettingsStore
import com.arohi.ai.proactive.ProactiveEngine
import com.arohi.ai.voice.ConversationEngine
import com.arohi.ai.voice.TtsManager
import com.arohi.ai.voice.WakeWordCoordinator
import com.arohi.ai.voice.WakeWordEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Arohi's 24/7 heart: foreground service hosting wake-word listening,
 * the proactive companion engine and the conversation loop.
 * v3 fixes:
 *  - re-starting the service no longer spawns duplicate wake/proactive
 *    engines (v2 doubled them on every start → mic chaos)
 *  - a watchdog restarts the wake engine if the mic loop ever dies
 */
class ArohiForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeEngine: WakeWordEngine? = null
    private var conversation: ConversationEngine? = null
    private var proactive: ProactiveEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var watchdogJob: kotlinx.coroutines.Job? = null

    companion object {
        const val CHANNEL_SERVICE = "arohi_service"
        const val CHANNEL_TASKS = "arohi_tasks"
        const val CHANNEL_ALERTS = "arohi_alerts"
        const val NOTIF_ID = 42
        const val ACTION_CHAT = "ACTION_CHAT"
        const val KEY_REPLY = "arohi_notif_reply"

        var isRunning = false
            private set

        fun start(context: Context) {
            val i = Intent(context, ArohiForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ArohiForegroundService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        TtsManager.init(this)
        TaskEngine.init(this)
        AutoReplyEngine.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()

        // v5 — ব্যাকগ্রাউন্ড টেক্সট চ্যাট: নোটিফিকেশনের রিপ্লাই বাক্স থেকে লেখা
        // এলে সরাসরি ব্রেইনে যায় — অ্যাপ খুলতে হয় না। উত্তর কথায় বলে +
        // নোটিফিকেশনে দেখায়।
        if (intent?.action == ACTION_CHAT) {
            val bundle = RemoteInput.getResultsFromIntent(intent)
            val text = bundle?.getCharSequence(KEY_REPLY)?.toString()?.trim()
            if (!text.isNullOrBlank()) {
                if (SettingsStore.get(this).notifChatEnabled) handleNotificationChat(text)
                else updateNotificationText("নোটিফিকেশন চ্যাট সেটিংসে বন্ধ আছে")
            }
            return START_STICKY
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ArohiAI::service").apply {
            acquire(12 * 60 * 60 * 1000L) // 12h safety cap, renewable on restart
        }

        // ---- engines: tear down any previous instance first (v3 anti-duplicate) ----
        wakeEngine?.stop(); wakeEngine = null
        proactive?.stop(); proactive = null
        conversation?.destroy(); conversation = null

        conversation = ConversationEngine(this)
        ConversationHolder.engine = conversation

        val settings = SettingsStore.get(this)
        if (settings.wakeWordEnabled) {
            wakeEngine = makeWakeEngine(this)
            WakeWordCoordinator.wakeEngine = wakeEngine
            wakeEngine?.start()
        }

        proactive = ProactiveEngine(this)
        proactive?.start()

        startWatchdog()

        isRunning = true
        ArohiBus.serviceRunning.value = true
        return START_STICKY
    }

    /**
     * Watchdog: keeps the wake engine alive no matter what.
     * v4: if the user enables Wake Word AFTER the service started, the engine
     * is created live; if she disables it, the engine stops live — no restart.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(30_000)
                if (!isRunning) continue
                val s = SettingsStore.get(applicationContext)
                val hasMic = PermissionHelper.hasMicrophone(applicationContext)
                if (!s.wakeWordEnabled) {
                    // live-disable: tear down if running
                    if (wakeEngine != null) {
                        wakeEngine?.stop(); wakeEngine = null
                        WakeWordCoordinator.wakeEngine = null
                    }
                    continue
                }
                if (wakeEngine == null && hasMic) {
                    // engine missing (enabled later / killed) → create
                    wakeEngine = makeWakeEngine(applicationContext)
                    WakeWordCoordinator.wakeEngine = wakeEngine
                    wakeEngine?.start()
                }
            }
        }
    }

    /** Single factory so the main path and watchdog share identical behaviour. */
    private fun makeWakeEngine(ctx: Context): WakeWordEngine =
        WakeWordEngine(ctx) { utterance ->
            scope.launch(Dispatchers.Main) {
                if (utterance.isNotBlank()) {
                    TtsManager.stop()
                    com.arohi.ai.memory.MemoryRepository.get(applicationContext)
                        .addChat("user", utterance)
                    com.arohi.ai.commands.CommandRouter.get(applicationContext)
                        .handle(utterance, source = "wake")
                } else {
                    ConversationHolder.engine?.openSession(greet = true)
                }
            }
        }

    /**
     * v5 — NOTIFICATION CHAT (user spec: "ব্যাকগ্রাউন্ডে বসে বসে গল্প করব,
     * এর ভিতরে না থেকেই")। Notification RemoteInput → CommandRouter →
     * spoken reply + chat history + notification text update.
     */
    private fun handleNotificationChat(text: String) {
        scope.launch(Dispatchers.Main) {
            try {
                ConversationHolder.engine?.openSession(greet = false)
            } catch (_: Exception) {}
            com.arohi.ai.memory.MemoryRepository.get(applicationContext)
                .addChat("user", text)
            com.arohi.ai.commands.CommandRouter.get(applicationContext)
                .handle(text, source = "text")
        }
        startReplyMirror()
    }

    /** ONE persistent watcher that mirrors Arohi's latest answer into the notification. */
    private fun startReplyMirror() {
        if (notifWatcherActive) return
        notifWatcherActive = true
        scope.launch {
            try {
                ArohiBus.chatChanged.collect {
                    val last = try {
                        com.arohi.ai.memory.MemoryRepository.get(applicationContext)
                            .recentChat(1).lastOrNull()
                    } catch (_: Exception) { null }
                    if (last != null && last.role == "arohi" && last.text.isNotBlank()) {
                        updateNotificationText(last.text.take(160))
                    }
                }
            } finally { notifWatcherActive = false }
        }
    }

    private var notifWatcherActive = false

    /** Re-post the persistent notification with the latest answer. */
    private fun updateNotificationText(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = nm.activeNotifications.firstOrNull { it.id == NOTIF_ID } ?: return
            val updated = NotificationCompat.Builder(this, CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(getString(R.string.notif_service_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setSilent(true)
                .addAction(0, getString(R.string.notif_chat_action), chatPendingIntent())
                .addAction(0, getString(R.string.notif_stop), stopPendingIntent())
                .setContentIntent(openPendingIntent())
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
            nm.notify(NOTIF_ID, updated)
        } catch (_: Exception) {}
    }

    // ---------- v5 notification chat plumbing ----------
    private fun chatPendingIntent(): PendingIntent {
        val i = Intent(this, ArohiForegroundService::class.java).setAction(ACTION_CHAT)
        return PendingIntent.getService(this, 3, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun stopPendingIntent(): PendingIntent {
        val i = Intent(this, ArohiForegroundService::class.java).setAction("ACTION_STOP")
        return PendingIntent.getService(this, 1, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openPendingIntent(): PendingIntent =
        PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun startAsForeground() {
        // v5: inline text-chat action — অ্যাপের ভেতরে না ঢুকেই আরোহীর সাথে গল্প
        val remoteInput = RemoteInput.Builder(KEY_REPLY)
            .setLabel(getString(R.string.notif_chat_hint))
            .build()
        val chatAction = NotificationCompat.Action.Builder(0,
            getString(R.string.notif_chat_action), chatPendingIntent())
            .addRemoteInput(remoteInput)
            .build()

        val notif = NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(getString(R.string.notif_service_title))
            .setContentText(getString(R.string.notif_service_text))
            .setOngoing(true)
            .setSilent(true)
            .addAction(chatAction)
            .addAction(0, getString(R.string.notif_stop), stopPendingIntent())
            .setContentIntent(openPendingIntent())
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        // v4.1 crash fix: Android 14+ throws SecurityException when the
        // MICROPHONE FGS type is requested without while-in-use RECORD_AUDIO
        // permission. Degrade honestly (DATA_SYNC-only → plain) instead of
        // crashing — the wake engine checks permission itself and waits.
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } catch (e: Exception) {
                try {
                    startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } catch (e2: Exception) {
                    startForeground(NOTIF_ID, notif)
                }
            }
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        isRunning = false
        ArohiBus.serviceRunning.value = false
        watchdogJob?.cancel(); watchdogJob = null
        wakeEngine?.stop(); wakeEngine = null
        WakeWordCoordinator.wakeEngine = null
        proactive?.stop(); proactive = null
        conversation?.destroy(); conversation = null
        ConversationHolder.engine = null
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE,
                getString(R.string.notif_channel_service), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_TASKS,
                getString(R.string.notif_channel_tasks), NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS,
                getString(R.string.notif_channel_alerts), NotificationManager.IMPORTANCE_HIGH))
        }
    }
}

/** Bridges the engine instance between service, fragments and the router. */
object ConversationHolder {
    @Volatile var engine: ConversationEngine? = null
}
