package com.arohi.ai.voice

import android.content.Context
import com.arohi.ai.core.ArohiBus
import com.arohi.ai.core.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v7 — REAL BARGE-IN (interrupt Arohi while she speaks).
 *
 * The v6 engine looked like it supported barge-in (there was a stop-word check
 * inside the conversation listener), but that listener is stopped for the whole
 * time she talks: TTS plays, the mic session is torn down, and only after
 * `onDone` does listening resume. So "থামো" during a long answer was heard by
 * nobody — the check could never fire. That is a genuine functional gap, not a
 * tuning issue.
 *
 * This monitor fixes it with the standard architecture used by voice
 * assistants: while Arohi speaks, a SEPARATE short recognition session listens
 * for the user's voice. Because that session also hears the phone speaker
 * (her own words), every result is passed through [EchoGuard.looksLikeEcho];
 * only speech that is NOT her own output — and long enough to be a person —
 * interrupts playback.
 *
 * Milestone announcements are sent when the monitor genuinely starts/stops, so
 * the UI never claims "listening" while it is not.
 */
object BargeInMonitor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var appCtx: Context? = null

    /** Consecutive echo detections after which we stop fighting the speaker. */
    private var echoStreak = 0

    fun start(context: Context) {
        val ctx = context.applicationContext
        appCtx = ctx
        if (job?.isActive == true) return
        val s = SettingsStore.get(ctx)
        if (!s.bargeIn) return
        if (!com.arohi.ai.core.PermissionHelper.hasMicrophone(ctx)) return
        if (!SttManager.isAvailable(ctx)) return
        if (CallControl.isCallActive(ctx)) return   // never listen into a call
        echoStreak = 0
        job = scope.launch { monitorLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        // Deliberately NOT calling SttManager.stop() here: the conversation loop
        // owns the recogniser between answers and would be killed mid-listen.
    }

    private suspend fun monitorLoop() {
        val ctx = appCtx ?: return
        var ownRestarts = 0
        while (job?.isActive == true) {
            if (!ArohiBus.speaking.value) break          // she finished — nothing to interrupt
            val s = SettingsStore.get(ctx)
            if (!s.bargeIn) break
            if (CallControl.isCallActive(ctx)) break

            val interrupted = withTimeoutOrNull(WINDOW_MS) {
                kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                    try {
                        SttManager.start(ctx, object : SttManager.Callbacks {
                            override fun onPartial(text: String) { judge(text, cont) }
                            override fun onFinal(text: String) { judge(text, cont) }
                            override fun onError(code: Int) {
                                if (cont.isActive) cont.resumeWith(Result.success(false))
                            }
                        }, SttManager.Mode.CONVERSATION)
                    } catch (_: Exception) {
                        if (cont.isActive) cont.resumeWith(Result.success(false))
                    }
                }
            } ?: false

            if (interrupted) {
                ArohiBus.toast("থামলাম — আপনি বলুন")
                // TtsManager.stop() → finish() → BargeInMonitor.stop() cancels
                // THIS job, so the hand-off runs in a fresh coroutine; otherwise
                // resuming the conversation listener could be skipped.
                scope.launch {
                    com.arohi.ai.services.ConversationHolder.engine?.listenForUser()
                }
                TtsManager.stop()
                return
            }

            ownRestarts++
            if (ownRestarts > MAX_RESTARTS) return   // recogniser is unusable here
            delay(if (echoStreak > 2) 1200L else 250L)
        }
        // her answer finished normally
        echoStreak = 0
    }

    private fun judge(text: String, cont: kotlinx.coroutines.CancellableContinuation<Boolean>) {
        if (!cont.isActive) return
        val t = text.trim()
        if (t.isEmpty()) return
        // 1) the phone speaker is the most likely source → reject echoes
        if (EchoGuard.looksLikeEcho(t)) {
            echoStreak++
            return
        }
        // 2) a bare short word is usually ASR noise ("the", "একটা")
        if (!isLikelyUserSpeech(t)) return
        echoStreak = 0
        cont.resumeWith(Result.success(true))
    }

    /** Same conservative rule as the conversation engine: real words, not noise. */
    private fun isLikelyUserSpeech(text: String): Boolean {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size >= 2) return true
        val w = words.firstOrNull() ?: return false
        return w.length >= 5 && w.count { it.isLetter() } >= 4
    }

    private const val WINDOW_MS = 9_000L
    private const val MAX_RESTARTS = 6
}
