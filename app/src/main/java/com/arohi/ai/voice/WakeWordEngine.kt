package com.arohi.ai.voice

import android.content.Context
import android.speech.SpeechRecognizer
import com.arohi.ai.core.ArohiBus
import com.arohi.ai.core.SettingsStore
import com.arohi.ai.util.Extensions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Hey Arohi" wake word engine running inside the foreground service.
 *
 * v4 fixes (background listening must survive everything):
 *  - wakeWordEnabled is re-read EVERY loop iteration → toggling the switch in
 *    Settings takes effect live, no service restart needed
 *  - ERROR_RECOGNIZER_BUSY / mic-held-by-another-app: exponential wait instead
 *    of a tight retry storm
 *  - every recognition attempt runs inside withTimeoutOrNull → the loop can
 *    NEVER hang on a dead SpeechRecognizer
 *  - pause()/resume() while a conversation session is live
 *  - ERROR_INSUFFICIENT_PERMISSIONS waits politely for the permission instead
 *    of burning attempts
 * v6 MIC-CALM: শুধুমাত্র ব্যবহারকারীর স্পষ্ট টগলে চলে (SettingsStore.wakeWordEnabled
 * ডিফল্ট FALSE) — অ্যাপ খুললেই মাইক আর নিজে নিজে জ্বলে না; প্রতিটি শোনার সাইকেলের
 * পর ন্যূনতম ২.৫ সে বিরতি — দ্রুত অন/অফ চক্র নেই।
 */
class WakeWordEngine(
    private val context: Context,
    private val onWake: (remainingUtterance: String) -> Unit
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var running = false
    private var paused = false
    private var loopJob: Job? = null
    private var attempts = 0L

    fun start() {
        if (running) return
        running = true
        paused = false
        loopJob = scope.launch { listenLoop() }
    }

    fun stop() {
        running = false
        paused = false
        scope.launch {
            try { loopJob?.cancelAndJoin() } catch (_: Exception) {}
            loopJob = null
            SttManager.stop()
        }
    }

    /** Called when a conversation session opens — free the mic for it. */
    fun pause() {
        paused = true
        SttManager.stop()
    }

    fun resume() {
        paused = false
    }

    private data class Trigger(val rest: String, val matched: Boolean, val errCode: Int = 0)

    private suspend fun listenLoop() {
        while (loopJob?.isActive == true && running) {
            // v4/v5: LIVE settings — wake on/off, wake words, wake strictness apply instantly
            val s = SettingsStore.get(context)
            if (!s.wakeWordEnabled) { delay(1500); continue }
            if (paused || ArohiBus.speaking.value || ArohiBus.sessionOpen.value) {
                delay(400) // don't chase wake words while Arohi talks or session live
                continue
            }
            // v7 CALL SAFETY: Android's SpeechRecognizer cannot reliably run while
            // a phone call owns the audio path (and it would transcribe the call).
            // Instead of burning CPU on guaranteed failures, wait for the call to end.
            if (CallControl.isCallActive(context)) { delay(3000); continue }
            // v7: never re-arm the ear inside the speaker tail window — that is
            // exactly when her own voice is loudest in the microphone.
            EchoGuard.awaitTail()
            if (!com.arohi.ai.core.PermissionHelper.hasMicrophone(context)) { delay(4000); continue }

            // HARD timeout: either a result arrives within the window, or we
            // re-arm cleanly. This is the fix for the v2 hang.
            val triggered: Trigger = withTimeoutOrNull(20_000L) {
                kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                    try {
                        // v5: Mode.WAKE — passive ear; UI stays DORMANT (no fake "listening")
                        SttManager.start(context, object : SttManager.Callbacks {
                            override fun onPartial(text: String) {
                                if (ArohiBus.speaking.value) return // ignore own voice echo
                                if (Extensions.matchesWakeWord(text, s.wakeWords)) {
                                    val rest = Extensions.stripWakeWords(text, s.wakeWords)
                                    if (cont.isActive) cont.resumeWith(Result.success(Trigger(rest, true)))
                                }
                            }
                            override fun onFinal(text: String) {
                                val matched = Extensions.matchesWakeWord(text, s.wakeWords)
                                val rest = if (matched) Extensions.stripWakeWords(text, s.wakeWords) else ""
                                if (cont.isActive) cont.resumeWith(Result.success(Trigger(rest, matched)))
                            }
                            override fun onError(code: Int) {
                                if (cont.isActive) cont.resumeWith(Result.success(Trigger("", false, code)))
                            }
                        }, SttManager.Mode.WAKE)
                    } catch (_: Exception) {
                        if (cont.isActive) cont.resumeWith(Result.success(Trigger("", false)))
                    }
                }
            } ?: Trigger("", false) // timeout → treat as "no wake word heard"

            if (triggered.matched) {
                // v7 ECHO GUARD: reject own-voice echo (this is the fix for the
                // "she wakes herself / interrupts herself" class of bug).
                val heard = if (triggered.rest.isNotBlank()) triggered.rest else ""
                if (!EchoGuard.acceptWake(heard)) {
                    attempts++
                    delay(900)
                    continue
                }
                attempts = 0
                onWake(triggered.rest)
                continue
            }

            attempts++
            when (triggered.errCode) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> delay(3000) // another app holds the mic
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> delay(5000)
                SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_NETWORK -> delay(2500)
                else -> {
                    // v6 MIC-CALM FIX: নিঃশব্দ সাইকেলে ফাঁক এখন ২.৫ সেকেন্ড —
                    // আগে ০.৮ সেকেন্ডে মাইক বারবার অন/অফ হতো (ব্যবহারকারীর "মাইক
                    // অটোমেটিক অন হয় আবার বন্ধ হয়" অভিযোগের মূল কারণ)। এখন
                    // প্রতিটি সেশন ~১০ সেকেন্ড শুনে তারপর শান্ত বিরতি নেয় —
                    // মাইক ইন্ডিকেটর স্থির থাকে, ব্যাটারিও কম লাগে।
                    delay(2500L)
                }
            }
        }
    }
}
