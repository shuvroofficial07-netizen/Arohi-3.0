package com.arohi.ai.voice

import android.content.Context
import com.arohi.ai.core.ArohiBus
import kotlinx.coroutines.delay

/**
 * v7 — ECHO GUARD (self-voice false wake protection)
 *
 * A real problem in every wake-word assistant: while Arohi herself is talking,
 * the phone speaker's sound reaches the microphone. If her own sentence
 * happened to contain a wake phrase (or a notification announcement said
 * "আরোহী বলেছেন…"), the passive ear would "wake" on the app's own audio and
 * interrupt or re-trigger itself in a loop.
 *
 * Current guards were only partial: WakeWordEngine checks `ArohiBus.speaking`
 * at loop level, but a wake trigger arriving in the same instant speech starts
 * can still slip through, and the Service's wake handler calls
 * `TtsManager.stop()` + dispatches a command on any utterance.
 *
 * This gate is the single, testable answer: a wake trigger is accepted only if
 *   1) Arohi is not speaking, AND
 *   2) at least [TAIL_MS] has passed since her last playback ended, AND
 *   3) the recognised text is not a verbatim echo of what she just said.
 *
 * It never blocks a real user: the user's phrase is different text and arrives
 * after the tail window, so normal conversation is unaffected.
 */
object EchoGuard {

    /** Silence required after Arohi's own voice before the mic is trusted again. */
    const val TAIL_MS = 750L

    @Volatile
    private var lastSpeechEndedAt: Long = 0L

    @Volatile
    var lastSpoken: String = ""
        private set

    /** Called by TtsManager when playback starts. */
    fun onSpeechStarted(text: String) {
        lastSpoken = text.trim()
    }

    /** Called by TtsManager when playback finishes, is stopped or fails. */
    fun onSpeechEnded() {
        lastSpeechEndedAt = System.currentTimeMillis()
    }

    /** True while the speaker tail is still ringing into the mic. */
    fun inTailWindow(): Boolean =
        System.currentTimeMillis() - lastSpeechEndedAt < TAIL_MS

    /**
     * Decide whether this recognised utterance should be allowed to wake her.
     * Returns true = genuine user wake; false = own-voice echo / too soon.
     */
    fun acceptWake(utterance: String): Boolean {
        if (ArohiBus.speaking.value) return false
        if (inTailWindow()) return false
        return !looksLikeEcho(utterance)
    }

    /**
     * v7 — text-level echo test, independent of playback state.
     *
     * Used by the barge-in monitor (which necessarily listens WHILE she talks,
     * so `speaking` is true and cannot be used as a filter) and by the wake
     * loop. It answers one question: "is this phrase a slice of what she is
     * saying right now?" — if yes it is the speaker, not the user.
     */
    fun looksLikeEcho(utterance: String): Boolean {
        val heard = normalise(utterance)
        if (heard.length < 8) return false
        val spoke = normalise(lastSpoken)
        if (spoke.isEmpty()) return false
        if (spoke.contains(heard)) return true
        if (heard.length >= 12 && spoke.contains(heard.take(16))) return true
        // token overlap: most of the heard words appear in her sentence
        val hw = heard.split(" ").filter { it.length > 2 }
        if (hw.size >= 3) {
            val hit = hw.count { spoke.contains(it) }
            if (hit >= (hw.size * 2) / 3) return true
        }
        return false
    }

    private val notWord = Regex("[^\\p{L}\\p{N} ]")
    private val spaces = Regex("\\s+")

    private fun normalise(s: String) =
        s.lowercase().replace(notWord, " ").replace(spaces, " ").trim()

    /** Waits out the tail window — used before re-arming the passive ear. */
    suspend fun awaitTail() {
        val wait = TAIL_MS - (System.currentTimeMillis() - lastSpeechEndedAt)
        if (wait > 0) delay(wait)
    }

    fun reset() {
        lastSpeechEndedAt = 0L
        lastSpoken = ""
    }

    /** Convenience for call sites that only have a Context for logging. */
    fun isSafeToListen(@Suppress("UNUSED_PARAMETER") context: Context): Boolean =
        !ArohiBus.speaking.value && !inTailWindow()
}
