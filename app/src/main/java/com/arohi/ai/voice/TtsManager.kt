package com.arohi.ai.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.arohi.ai.core.ArohiBus
import com.arohi.ai.core.ConvState
import com.arohi.ai.core.NetworkMonitor
import com.arohi.ai.core.SecureStore
import com.arohi.ai.core.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Arohi's voice. Two real engines:
 *  1) ElevenLabs (natural female multilingual voice, Bengali supported)
 *  2) Android device TTS (bn) fallback with speed/pitch control
 * v3 fixes:
 *  - speaks are QUEUED until the TTS engine finishes initializing (v2 said
 *    "voice unavailable" if you spoke in the first seconds after launch)
 *  - a PARTIAL_WAKE_LOCK is held during playback → notifications are still
 *    announced while the screen is off / phone is locked
 *  - MediaPlayer gets proper AudioAttributes (media usage)
 */
object TtsManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private var appCtx: Context? = null
    private var deviceTts: TextToSpeech? = null
    @Volatile private var deviceTtsReady = false
    private var player: MediaPlayer? = null
    private var synthJob: Job? = null
    private var pendingSpeak: Pair<String, (() -> Unit)?>? = null   // queued while engine initing
    private var doneCallback: (() -> Unit)? = null
    private var lastSpokenText: String = ""
    private var wakeLock: PowerManager.WakeLock? = null
    var currentEngine: String = "device"
        private set

    val isSpeaking get() = ArohiBus.speaking.value

    /** Real readiness check for the health dashboard (device TTS initialised). */
    fun engineReady(context: Context): Boolean {
        if (deviceTtsReady) return true
        init(context)
        return deviceTtsReady
    }

    fun init(context: Context) {
        if (appCtx != null) return
        appCtx = context.applicationContext
        ensureDeviceTts()
    }

    private fun ensureDeviceTts() {
        val ctx = appCtx ?: return
        if (deviceTts != null) return
        deviceTts = TextToSpeech(ctx) { status ->
            deviceTtsReady = status == TextToSpeech.SUCCESS
            if (deviceTtsReady) {
                val bn = trySetLanguage("bn-IN") || trySetLanguage("bn-BD") || trySetLanguage("bn")
                if (!bn) deviceTts?.language = Locale.getDefault()
            }
            // If something was queued while the engine was warming up, speak it now
            val queued = pendingSpeak
            pendingSpeak = null
            if (deviceTtsReady && queued != null) {
                val s = SettingsStore.get(ctx)
                currentEngine = "device"
                speakDevice(queued.first, s)
            } else if (!deviceTtsReady && queued != null) {
                val cb = doneCallback
                doneCallback = null
                ArohiBus.speaking.value = false
                cb?.invoke()
            }
        }
    }

    private fun trySetLanguage(tag: String): Boolean {
        val r = deviceTts?.setLanguage(Locale.forLanguageTag(tag)) ?: return false
        return r >= 0
    }

    /** Speak [text]; [onDone] fires when playback finishes or is interrupted. */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val ctx = appCtx
        if (ctx == null) {
            // v4.1 fix: never lose the callback (a session would hang waiting);
            // callers fall back gracefully instead of stalling mid-conversation.
            ArohiBus.speaking.value = false
            onDone?.invoke()
            return
        }
        val clean = text.trim()
        if (clean.isEmpty()) { onDone?.invoke(); return }
        lastSpokenText = clean
        doneCallback = onDone
        ArohiBus.speaking.value = true
        ArohiBus.setState(ConvState.SPEAKING)
        acquireWakeLock()

        val s = SettingsStore.get(ctx)
        if (!deviceTtsReady) ensureDeviceTts()

        val useEleven = s.ttsEngine == "elevenlabs" && SecureStore.get(ctx).elevenConfigured && NetworkMonitor.isOnline
        if (useEleven) {
            scope.launch {
                val ok = speakElevenLabs(clean, s)
                if (!ok && ArohiBus.speaking.value) {
                    currentEngine = "device (ElevenLabs fallback)"
                    speakDeviceOrQueue(clean, s)
                }
            }
        } else {
            currentEngine = "device"
            speakDeviceOrQueue(clean, s)
        }
    }

    /** Device TTS or, if it is still initializing, queue for when it's ready. */
    private fun speakDeviceOrQueue(text: String, s: SettingsStore) {
        val tts = deviceTts
        if (tts == null || !deviceTtsReady) {
            pendingSpeak = text to doneCallback
            // safety: if init never succeeds, release after 8s
            scope.launch {
                delay(8000)
                if (pendingSpeak?.first == text && !deviceTtsReady) {
                    pendingSpeak = null
                    ArohiBus.toast("ভয়েস সার্ভিস সাময়িকভাবে অনুপলব্ধ")
                    finish()
                }
            }
            return
        }
        speakDevice(text, s)
    }

    /** Stop immediately (interrupt / stop button). */
    fun stop() {
        try { player?.stop(); player?.release() } catch (_: Exception) {}
        player = null
        try { deviceTts?.stop() } catch (_: Exception) {}
        pendingSpeak = null
        synthJob?.cancel()
        finish()
    }

    private fun finish() {
        ArohiBus.speaking.value = false
        ArohiBus.amplitude.value = 0f
        releaseWakeLock()
        if (ArohiBus.state.value == ConvState.SPEAKING) ArohiBus.resetToIdle()
        val cb = doneCallback
        doneCallback = null
        cb?.invoke()
    }

    private fun acquireWakeLock() {
        try {
            val ctx = appCtx ?: return
            if (wakeLock == null) {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ArohiAI::speak")
            }
            if (wakeLock?.isHeld != true) wakeLock?.acquire(5 * 60 * 1000L) // max 5 min per utterance
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
    }

    // ---------- ElevenLabs ----------
    private suspend fun speakElevenLabs(text: String, s: SettingsStore): Boolean = withContext(Dispatchers.IO) {
        val ctx = appCtx ?: return@withContext false
        val store = SecureStore.get(ctx)
        try {
            val url = "https://api.elevenlabs.io/v1/text-to-speech/${store.elevenVoiceId}?output_format=mp3_44100_128"
            val body = org.json.JSONObject()
                .put("text", text)
                .put("model_id", "eleven_multilingual_v2")
                .put("voice_settings", org.json.JSONObject()
                    .put("stability", 0.45)
                    .put("similarity_boost", 0.78)
                    .put("style", 0.45)
                    .put("use_speaker_boost", true))
                .toString()
            val req = Request.Builder().url(url)
                .header("xi-api-key", store.elevenKey)
                .post(body.toRequestBody(jsonMedia))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val bytes = resp.body?.bytes() ?: return@withContext false
                val cache = File(ctx.cacheDir, "arohi_tts.mp3")
                cache.writeBytes(bytes)
                withContext(Dispatchers.Main) { playFile(cache.absolutePath) }
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun playFile(path: String) {
        try {
            player?.release()
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            mp.setDataSource(path)
            mp.prepare()
            mp.setOnCompletionListener { finish() }
            mp.setOnErrorListener { _, _, _ -> finish(); true }
            mp.start()
            startSyntheticAmplitude()
        } catch (_: Exception) {
            val s = SettingsStore.get(appCtx ?: return)
            currentEngine = "device (player fallback)"
            speakDeviceOrQueue(lastSpokenText, s)
        }
    }

    // ---------- Device TTS ----------
    private fun speakDevice(text: String, s: SettingsStore) {
        ensureDeviceTts()
        val tts = deviceTts
        if (tts == null || !deviceTtsReady) {
            speakDeviceOrQueue(text, s)
            return
        }
        tts.setSpeechRate(s.ttsSpeed.coerceIn(0.5f, 2.5f))
        tts.setPitch(s.ttsPitch.coerceIn(0.5f, 2.5f))
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { startSyntheticAmplitude() }
            override fun onDone(utteranceId: String?) { finish() }
            @Deprecated("deprecated in Java")
            override fun onError(utteranceId: String?) { finish() }
        })
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "arohi_utt")
    }

    /**
     * Live "speaking" waveform. TTS engines do not expose output amplitude,
     * so this drives a smooth energy curve synced to playback (visualiser only,
     * never presented as a microphone level).
     */
    private fun startSyntheticAmplitude() {
        synthJob?.cancel()
        synthJob = scope.launch {
            var v = 0.45f
            while (isActive && ArohiBus.speaking.value) {
                v += (Random.nextFloat() - 0.5f) * 0.22f
                v = v.coerceIn(0.15f, 0.95f)
                ArohiBus.amplitude.value = v
                delay(70)
            }
        }
    }
}
