package com.arohi.ai.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.arohi.ai.core.ArohiBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Voice Activity Detection: real RMS energy from AudioRecord feeding the
 * live waveform while listening. If the mic is busy (STT engine owns it),
 * waveform silently falls back to STT's rmsChanged values.
 */
object VadMeter {

    private var record: AudioRecord? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (job?.isActive == true) return
        job = scope.launch {
            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) return@launch
            try {
                val rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
                if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return@launch }
                record = rec
                rec.startRecording()
                val buf = ShortArray(2048)
                var smooth = 0f
                while (isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        var sum = 0.0
                        for (i in 0 until n) sum += buf[i].toDouble() * buf[i].toDouble()
                        val rms = kotlin.math.sqrt(sum / n)
                        val norm = (rms / 4000.0).toFloat().coerceIn(0f, 1f)
                        smooth = smooth * 0.6f + norm * 0.4f
                        ArohiBus.amplitude.value = smooth
                    }
                }
            } catch (_: Exception) {
                // mic busy → waveform stays driven by STT rmsChanged
            } finally {
                stopInternal()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        stopInternal()
        ArohiBus.amplitude.value = 0f
    }

    private fun stopInternal() {
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
    }
}
