package com.arohi.ai.ui

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.arohi.ai.core.ArohiBus
import com.arohi.ai.core.ConvState
import com.arohi.ai.core.Emotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * Arohi's living core: rotating holographic arcs, orbiting particles,
 * emotion-coloured pulsing heart reacting to live voice energy.
 */
class ArohiCoreView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blur = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setMaskFilter(BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL))
    }

    private var t = 0f                     // global animation time
    private var smoothAmp = 0f
    private var emotionColor = 0xFF00E5FF.toInt()
    private var lastEmotion: Emotion = Emotion.NEUTRAL
    private var lastState: ConvState = ConvState.IDLE

    private val particles = List(36) {
        Particle(
            radiusFrac = 0.42f + (it % 5) * 0.08f,
            angle = it * 10f,
            speed = 0.15f + (it % 4) * 0.05f,
            size = 2.2f + (it % 3) * 1.6f
        )
    }

    private class Particle(val radiusFrac: Float, var angle: Float, val speed: Float, val size: Float)

    private val runnable = object : Runnable {
        override fun run() {
            t += 0.035f
            smoothAmp = smoothAmp * 0.82f + ArohiBus.amplitude.value * 0.18f
            invalidate()
            postOnAnimation(this)
        }
    }

    init {
        postOnAnimation(runnable)
        uiScope.launch {
            ArohiBus.emotion.collect { e ->
                if (e != lastEmotion) {
                    lastEmotion = e
                    emotionColor = android.graphics.Color.parseColor(e.colorHex)
                }
            }
        }
        uiScope.launch { ArohiBus.state.collect { lastState = it } }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(runnable)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val base = minOf(cx, cy)
        val ampPulse = 1f + smoothAmp * 0.14f

        // outer aura
        val auraRadius = base * 0.95f * ampPulse
        paint.shader = RadialGradient(cx, cy, auraRadius, intArrayOf(
            withAlpha(emotionColor, 60), withAlpha(emotionColor, 18), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        paint.setShadowLayer(0f, 0f, 0f, 0)
        canvas.drawCircle(cx, cy, auraRadius, paint)
        paint.shader = null

        // three rotating arcs
        val arcs = listOf(
            Triple(0.78f, t * 40f, 110f),
            Triple(0.66f, -t * 55f + 140f, 80f),
            Triple(0.55f, t * 80f + 60f, 150f)
        )
        for ((rf, start, sweep) in arcs) {
            val r = base * rf * ampPulse
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (base * 0.02f)
            blur.strokeWidth = (base * 0.02f)
            paint.color = emotionColor
            paint.setShadowLayer(0f, 0f, 0f, 0)
            paint.shader = SweepGradient(cx, cy, intArrayOf(
                withAlpha(emotionColor, 40), withAlpha(emotionColor, 230), withAlpha(emotionColor, 40)),
                null)
            blur.shader = paint.shader
            canvas.save()
            canvas.rotate(start, cx, cy)
            val rect = RectF(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(rect, 0f, sweep, false, blur)
            canvas.drawArc(rect, 0f, sweep, false, paint)
            canvas.restore()
        }
        paint.shader = null

        // orbiting particles
        paint.style = Paint.Style.FILL
        for (p in particles) {
            p.angle += p.speed * 2.4f
            val r = base * p.radiusFrac * ampPulse
            val px = cx + r * cos(Math.toRadians(p.angle.toDouble())).toFloat()
            val py = cy + r * sin(Math.toRadians(p.angle.toDouble())).toFloat() * 0.9f
            paint.color = withAlpha(emotionColor, 180)
            canvas.drawCircle(px, py, p.size, paint)
        }

        // heart core
        val coreR = base * (0.24f + smoothAmp * 0.10f)
        paint.shader = RadialGradient(cx, cy, coreR * 1.4f,
            intArrayOf(
                Color.WHITE, withAlpha(emotionColor, 235), withAlpha(emotionColor, 90)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, coreR * 1.4f, paint)
        paint.shader = null

        // state ring
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = base * 0.012f
        paint.color = when (lastState) {
            ConvState.LISTENING -> 0xFF2979FF.toInt()
            ConvState.THINKING -> 0xFF7C4DFF.toInt()
            ConvState.SPEAKING -> emotionColor
            ConvState.EXECUTING -> 0xFF00E676.toInt()
            ConvState.ERROR -> 0xFFFF5252.toInt()
            else -> withAlpha(emotionColor, 90)
        }
        val ringR = base * 0.9f
        canvas.drawArc(RectF(cx - ringR, cy - ringR, cx + ringR, cy + ringR), 0f, 360f, false, paint)
        paint.style = Paint.Style.FILL
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(alpha.coerceIn(0, 255),
        Color.red(color), Color.green(color), Color.blue(color))
}
