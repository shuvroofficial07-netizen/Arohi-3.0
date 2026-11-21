package com.arohi.ai.voice

import android.content.Context
import android.speech.SpeechRecognizer
import com.arohi.ai.commands.CommandRouter
import com.arohi.ai.core.ArohiBus
import com.arohi.ai.core.ConvState
import com.arohi.ai.core.PermissionHelper
import com.arohi.ai.core.SettingsStore
import com.arohi.ai.util.Extensions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The continuous conversation loop:
 * LISTEN → (user text) → CommandRouter (THINK/EXECUTE) → SPEAK → LISTEN again…
 * Session auto-closes after [SESSION_TIMEOUT_MS] of silence or on "stop".
 * Barge-in: while speaking, a stop-word partial from the mic interrupts TTS.
 *
 * v3: opening/closing a session now pauses/resumes the wake-word engine
 * (via WakeWordCoordinator), so background listening survives every chat.
 */
class ConversationEngine(private val context: Context) {

    companion object {
        private const val TAG = "ConversationEngine"
        // v5: timeout is now user-configurable (SettingsStore.sessionTimeoutMinutes,
        // default 5 min) so Shubho can chat for a long time in the background.
        fun sessionTimeoutMs(context: Context): Long =
            SettingsStore.get(context).sessionTimeoutMinutes.coerceIn(1, 60) * 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sessionTimeoutJob: Job? = null
    private var listeningForUser = false

    fun openSession(greet: Boolean = true) {
        scope.launch {
            ArohiBus.sessionOpen.value = true
            WakeWordCoordinator.pauseAll()
            armSessionTimeout()
            if (greet && !ArohiBus.speaking.value) {
                speakAndThenListen("হ্যাঁ শুভ্র, আমি শুনছি।")
            } else {
                listenForUser()
            }
        }
    }

    fun closeSession() {
        ArohiBus.sessionOpen.value = false
        listeningForUser = false
        sessionTimeoutJob?.cancel()
        SttManager.stop()
        VadMeter.stop()
        ArohiBus.resetToIdle()
        WakeWordCoordinator.resumeAll()
    }

    fun destroy() { closeSession() }

    /** User pressed the mic button (or wake word hit). */
    fun onUserTrigger() {
        scope.launch {
            if (ArohiBus.speaking.value) TtsManager.stop()
            if (!ArohiBus.sessionOpen.value) openSession(greet = false) else listenForUser()
        }
    }

    private fun armSessionTimeout() {
        sessionTimeoutJob?.cancel()
        sessionTimeoutJob = scope.launch {
            delay(sessionTimeoutMs(context))
            if (!ArohiBus.sessionOpen.value) return@launch
            if (ArohiBus.speaking.value || ArohiBus.taskProgress.value != null) {
                // busy — re-arm and keep the session alive
                armSessionTimeout()
            } else if (ArohiBus.listening.value) {
                // user is mid-sentence (mic live) — keep waiting
                armSessionTimeout()
            } else {
                closeSession()
            }
        }
    }

    private fun speakAndThenListen(text: String) {
        TtsManager.speak(text) { scope.launch { if (ArohiBus.sessionOpen.value) listenForUser() } }
    }

    fun listenForUser() {
        if (listeningForUser || ArohiBus.speaking.value) return
        if (!ArohiBus.sessionOpen.value) ArohiBus.sessionOpen.value = true
        WakeWordCoordinator.pauseAll()
        if (!PermissionHelper.hasMicrophone(context)) {
            ArohiBus.setState(ConvState.ERROR)
            speakAndThenListen(context.getString(com.arohi.ai.R.string.err_mic_permission))
            return
        }
        if (!SttManager.isAvailable(context)) {
            ArohiBus.toast("এই ডিভাইসে ভয়েস রিকগনিশন সার্ভিস নেই — টেক্সট মোড ব্যবহার করুন")
            closeSession()
            return
        }
        listeningForUser = true
        VadMeter.start(context)
        SttManager.start(context, object : SttManager.Callbacks {
            override fun onPartial(text: String) {
                // Barge-in: user interrupts Arohi's speech
                val s = SettingsStore.get(context)
                if (s.bargeIn && ArohiBus.speaking.value && Extensions.isStopCommand(text)) {
                    TtsManager.stop()
                }
            }
            override fun onFinal(text: String) {
                listeningForUser = false
                VadMeter.stop()
                armSessionTimeout()
                val s = SettingsStore.get(context)
                val clean = Extensions.stripWakeWords(text, s.wakeWords)
                if (clean.isBlank()) { listenForUser(); return }
                if (Extensions.isStopCommand(clean)) {
                    closeSession()
                    return
                }
                scope.launch {
                    ArohiBus.addUserMessage(context, clean)
                    CommandRouter.get(context).handle(clean, source = "voice")
                }
            }
            override fun onError(code: Int) {
                listeningForUser = false
                VadMeter.stop()
                if (!ArohiBus.sessionOpen.value) return
                when (code) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        scope.launch { if (ArohiBus.sessionOpen.value) listenForUser() }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        ArohiBus.setState(ConvState.ERROR)
                        speakAndThenListen(context.getString(com.arohi.ai.R.string.err_mic_permission))
                    }
                    else -> scope.launch {
                        delay(800)
                        if (ArohiBus.sessionOpen.value) listenForUser() // safe reconnection
                    }
                }
            }
        })
    }

    /** Called by CommandRouter after a response is produced. */
    fun onArohiReply(text: String) {
        speakAndThenListen(text)
    }
}

/**
 * Lets the service-owned wake engine and any fragment-created conversation
 * engine coordinate: only one mic listener may run at a time.
 */
object WakeWordCoordinator {
    @Volatile var wakeEngine: WakeWordEngine? = null

    fun pauseAll() { wakeEngine?.pause() }
    fun resumeAll() {
        wakeEngine?.resume()
        if (ArohiBus.serviceRunning.value && !ArohiBus.sessionOpen.value && !ArohiBus.speaking.value) {
            wakeEngine?.resume()
        }
    }
}

private fun ArohiBus.addUserMessage(context: Context, text: String) {
    kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        com.arohi.ai.memory.MemoryRepository.get(context).addChat("user", text)
    }
}
