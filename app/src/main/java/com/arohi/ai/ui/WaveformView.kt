package com.arohi.ai.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.arohi.ai.core.ArohiBus
import kotlin.random.Random

/**
 * Real-time audio waveform. While LISTENING the bars follow the true mic RMS
 * (VAD/STT), while SPEAKING they follow the playback energy curve.
 */
class WaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val barCount = 40
    private val bars = FloatArray(barCount) { 0.1f }
    private val targets = FloatArray(barCount) { 0.1f }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shader: Shader? = null
    private var smoothAmp = 0f
    private var phase = 0f

    private val runnable = object : Runnable {
        override fun run() {
            phase += 0.14f
            smoothAmp = smoothAmp * 0.7f + ArohiBus.amplitude.value * 0.3f
            for (i in 0 until barCount) {
                val wave = (sin2(phase * (1f + i * 0.09f) + i * 0.55f) * 0.5f + 0.5f)
                val centreBoost = 1f - abs(i - barCount / 2f) / (barCount / 2f) * 0.7f
                targets[i] = (0.08f + smoothAmp * wave * centreBoost * 1.6f).coerceIn(0.05f, 1f)
                bars[i] += (targets[i] - bars[i]) * 0.32f
            }
            invalidate()
            postOnAnimation(this)
        }
    }

    private fun sin2(v: Float): Float = kotlin.math.sin(v.toDouble()).toFloat()

    private fun abs(v: Float): Float = kotlin.math.abs(v)

    init { postOnAnimation(runnable) }

    override fun onDetachedFromWindow() {
        removeCallbacks(runnable)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shader = LinearGradient(0f, h * 0.5f, w.toFloat(), h * 0.5f,
            0xFF2979FF.toInt(), 0xFF00E5FF.toInt(), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.shader = shader
        if (shader == null) paint.color = 0xFF00E5FF.toInt()
        val gap = width / (barCount * 2f)
        val barW = width / barCount - gap
        val mid = height / 2f
        for (i in 0 until barCount) {
            val x = i * (width / barCount.toFloat()) + gap / 2
            val h = bars[i] * height * 0.46f
            canvas.drawRoundRect(x, mid - h, x + barW, mid + h, barW / 2, barW / 2, paint)
        }
        paint.shader = null
        paint.color = Color.argb(70, 0, 229, 255)
        canvas.drawRoundRect(0f, mid - 1, width.toFloat(), mid + 1, 2f, 2f, paint)
    }
}
