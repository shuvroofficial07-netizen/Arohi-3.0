package com.arohi.ai.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.arohi.ai.core.ArohiBus
import com.arohi.ai.core.PermissionHelper
import com.arohi.ai.core.SettingsStore

/**
 * Android SpeechRecognizer wrapper (STT). One live session at a time.
 * v3 fixes:
 *  - every listening attempt has a HARD TIMEOUT (no more silent hangs that
 *    killed the wake-word loop in v2)
 *  - start() is idempotent and destroys the previous recognizer first
 *  - Bengali (bn-BD) + Banglish friendly extras
 * v5 MIC-MODE fix (user complaint: "মাইক অটোমেটিকলি অন হয়ে যাচ্ছে"):
 *  - Mode.WAKE (wake-word standby) NEVER flips the UI to LISTENING — the app
 *    honestly shows DORMANT ("'আরোহী' বললে শুনবো") until the wake command
 *    actually arrives; only then Mode.CONVERSATION opens the real listener.
 */
object SttManager {

    enum class Mode { CONVERSATION, WAKE }

    private var recognizer: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())
    private var activeCb: Callbacks? = null
    private var timeoutRunnable: Runnable? = null
    private var currentMode: Mode = Mode.CONVERSATION

    /** Hard cap for one listening attempt (ms) — SpeechRecognizer sometimes never returns. */
    private const val LISTEN_TIMEOUT_MS = 12_000L

    interface Callbacks {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(code: Int)
    }

    fun isAvailable(context: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(context: Context, cb: Callbacks, mode: Mode = Mode.CONVERSATION) {
        currentMode = mode
        main.post {
            if (!PermissionHelper.hasMicrophone(context)) {
                ArohiBus.listening.value = false
                cb.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
                return@post
            }
            stopInternal()
            activeCb = cb
            val rec = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            recognizer = rec
            val s = SettingsStore.get(context)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, s.sttLanguage)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, s.sttLanguage)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                // accept Bengali + English in one utterance on engines that support it
                putExtra("android.speech.extra.LANGUAGES", arrayListOf("bn-BD", "bn-IN", "en-US"))
            }
            rec.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (currentMode == Mode.CONVERSATION) {
                        ArohiBus.listening.value = true
                        ArohiBus.setState(com.arohi.ai.core.ConvState.LISTENING)
                    } else {
                        // WAKE mode: passive ear — honest DORMANT, no fake "listening"
                        ArohiBus.listening.value = false
                        ArohiBus.setState(com.arohi.ai.core.ConvState.DORMANT)
                    }
                    armTimeout()
                }
                override fun onBeginningOfSpeech() {
                    ArohiBus.micActive.value = true
                    armTimeout()
                }
                override fun onRmsChanged(rmsdB: Float) {
                    // 0..~12dB → 0..1 energy for the live waveform + tone tracking
                    val norm = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                    ArohiBus.amplitude.value = norm
                    com.arohi.ai.voice.ToneTracker.sample(norm)
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    ArohiBus.micActive.value = false
                    armTimeout()
                }
                override fun onError(error: Int) {
                    if (currentMode == Mode.CONVERSATION) ArohiBus.listening.value = false
                    cancelTimeout()
                    val cb2 = activeCb
                    activeCb = null
                    cb2?.onError(error)
                }
                override fun onResults(results: Bundle?) {
                    if (currentMode == Mode.CONVERSATION) {
                        ArohiBus.listening.value = false
                        ArohiBus.amplitude.value = 0f
                    }
                    cancelTimeout()
                    val cb2 = activeCb
                    activeCb = null
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    if (text.isNotEmpty()) cb2?.onFinal(text) else cb2?.onError(SpeechRecognizer.ERROR_NO_MATCH)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val p = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    if (p.isNotEmpty()) activeCb?.onPartial(p)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            rec.startListening(intent)
        }
    }

    /** If the engine never fires any callback, force a NO_MATCH error. */
    private fun armTimeout() {
        cancelTimeout()
        // WAKE mode gets the loop's own 20s window; conversation keeps 12s.
        val ms = if (currentMode == Mode.WAKE) 22_000L else LISTEN_TIMEOUT_MS
        timeoutRunnable = Runnable {
            val cb = activeCb
            stopInternal()
            if (currentMode == Mode.CONVERSATION) ArohiBus.listening.value = false
            ArohiBus.amplitude.value = 0f
            activeCb = null
            cb?.onError(SpeechRecognizer.ERROR_NO_MATCH)
        }.also { main.postDelayed(it, ms) }
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { main.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun stopInternal() {
        cancelTimeout()
        try { recognizer?.stopListening() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    fun stop() { main.post {
        stopInternal()
        val cb = activeCb; activeCb = null
        ArohiBus.listening.value = false
        ArohiBus.resetToDormant()
        // do NOT fire onError here — this is an intentional user/loop stop
    } }

    fun destroy() { main.post { stopInternal() } }
}
