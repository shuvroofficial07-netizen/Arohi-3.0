package com.arohi.ai.ui

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.arohi.ai.core.ArohiBus
import com.arohi.ai.core.ConvState
import com.arohi.ai.core.Emotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin

/**
 * Arohi's holographic female avatar — fully drawn in code (no external assets).
 * Face, flowing hair, eyes with blink, lips that move with voice energy,
 * glowing rim light; colours shift with her emotion and conversation state.
 */
class AvatarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setMaskFilter(BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL))
    }

    private var t = 0f
    private var amp = 0f
    private var color = 0xFF00E5FF.toInt()
    private var state: ConvState = ConvState.IDLE
    private var emotion: Emotion = Emotion.NEUTRAL
    private var blink = 1f          // 1 = eyes open, 0 = closed
    private var speaking = false

    private val runnable = object : Runnable {
        override fun run() {
            t += 0.05f
            amp = amp * 0.75f + ArohiBus.amplitude.value * 0.25f
            speaking = ArohiBus.speaking.value
            invalidate()
            postOnAnimation(this)
        }
    }

    init {
        postOnAnimation(runnable)
        scheduleBlink()
        uiScope.launch {
            ArohiBus.emotion.collect { e ->
                emotion = e
                color = android.graphics.Color.parseColor(e.colorHex)
            }
        }
        uiScope.launch { ArohiBus.state.collect { state = it } }
    }

    private fun scheduleBlink() {
        postDelayed({
            blink = 0f
            animateBlinkBack()
            scheduleBlink()
        }, 2200 + (Math.random() * 2600).toLong())
    }

    private fun animateBlinkBack() {
        postDelayed({ blink = 1f }, 130)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(runnable)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height * 0.42f
        val s = minOf(width, height) / 340f   // design scale (base design 340px)

        // background aura
        paint.shader = RadialGradient(cx, cy, 170f * s,
            intArrayOf(withAlpha(color, 55), withAlpha(color, 12), Color.TRANSPARENT),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, 170f * s, paint)
        paint.shader = null

        val mouthOpen = if (speaking) abs(sin(t * 6.5f)) * (0.35f + amp) else 0f

        // ---- hair (flowing silhouette) ----
        val hair = Path()
        hair.moveTo(cx - 120f * s, cy + 130f * s)
        hair.cubicTo(cx - 150f * s, cy + 10f * s, cx - 110f * s, cy - 150f * s, cx, cy - 150f * s)
        hair.cubicTo(cx + 110f * s, cy - 150f * s, cx + 150f * s, cy + 10f * s, cx + 120f * s, cy + 130f * s)
        hair.cubicTo(cx + 130f * s, cy + 170f * s, cx + 80f * s, cy + 190f * s, cx + 40f * s, cy + 185f * s)
        hair.cubicTo(cx, cy + 210f * s, cx - 40f * s, cy + 205f * s, cx - 80f * s, cy + 185f * s)
        hair.cubicTo(cx - 120f * s, cy + 185f * s, cx - 130f * s, cy + 160f * s, cx - 120f * s, cy + 130f * s)
        hair.close()
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(cx, cy - 160f * s, cx, cy + 200f * s,
            withAlpha(color, 120), withAlpha(color, 25), Shader.TileMode.CLAMP)
        canvas.drawPath(hair, paint)
        glow.shader = paint.shader
        canvas.drawPath(hair, glow)
        paint.shader = null

        // ---- face ----
        val face = Path()
        face.addOval(android.graphics.RectF(cx - 78f * s, cy - 92f * s, cx + 78f * s, cy + 92f * s),
            Path.Direction.CW)
        paint.shader = RadialGradient(cx, cy - 10f * s, 110f * s,
            intArrayOf(withAlpha(Color.WHITE, 40), withAlpha(color, 80), withAlpha(color, 22)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(face, paint)
        paint.shader = null

        // rim light
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.4f * s
        paint.color = withAlpha(color, 220)
        canvas.drawPath(face, paint)

        // ---- eyes ----
        val eyeY = cy - 6f * s
        val eyeDX = 32f * s
        val eyeOpen = blink.coerceIn(0.06f, 1f)
        for (dir in listOf(-1f, 1f)) {
            val ex = cx + dir * eyeDX
            // upper lid curve depends on emotion (angry → slanted)
            val tilt = when (emotion) {
                Emotion.ANGRY -> 8f * s
                Emotion.CONFUSED -> 4f * s
                else -> 0f
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.6f * s
            paint.color = withAlpha(color, 240)
            val eye = Path()
            eye.moveTo(ex - 14f * s, eyeY - 6f * s * eyeOpen)
            eye.quadTo(ex, eyeY - 13f * s * eyeOpen + tilt, ex + 14f * s, eyeY - 6f * s * eyeOpen)
            eye.quadTo(ex, eyeY + 9f * s * eyeOpen, ex - 14f * s, eyeY - 6f * s * eyeOpen)
            canvas.drawPath(eye, paint)
            if (blink > 0.5f) {
                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                canvas.drawCircle(ex, eyeY, 3.2f * s, paint)
                paint.color = withAlpha(color, 255)
                canvas.drawCircle(ex, eyeY, 1.6f * s, paint)
            }
        }

        // brows (emotion)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.2f * s
        paint.color = withAlpha(color, 200)
        val browDrop = when (emotion) {
            Emotion.ANGRY -> 10f; Emotion.SAD -> 6f; else -> 0f
        }
        for (dir in listOf(-1f, 1f)) {
            val bx = cx + dir * eyeDX
            val by = eyeY - 24f * s + browDrop * s
            canvas.drawLine(bx - 13f * s, by + (if (dir > 0) -2f else 4f) * s,
                bx + 13f * s, by + (if (dir > 0) 4f else -2f) * s, paint)
        }

        // ---- lips ----
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(color, 235)
        val lipY = cy + 44f * s
        val lip = Path()
        lip.moveTo(cx - 20f * s, lipY)
        lip.quadTo(cx, lipY + 4f * s, cx + 20f * s, lipY)
        lip.quadTo(cx + 10f * s, lipY + (10f + mouthOpen * 26f) * s, cx, lipY + (12f + mouthOpen * 30f) * s)
        lip.quadTo(cx - 10f * s, lipY + (10f + mouthOpen * 26f) * s, cx - 20f * s, lipY)
        lip.close()
        canvas.drawPath(lip, paint)

        // ---- shoulders / body ----
        val body = Path()
        body.moveTo(cx - 60f * s, height.toFloat())
        body.cubicTo(cx - 75f * s, cy + 150f * s, cx - 105f * s, cy + 140f * s, cx - 55f * s, cy + 110f * s)
        body.lineTo(cx + 55f * s, cy + 110f * s)
        body.cubicTo(cx + 105f * s, cy + 140f * s, cx + 75f * s, cy + 150f * s, cx + 60f * s, height.toFloat())
        body.close()
        paint.shader = LinearGradient(cx, cy + 110f * s, cx, height.toFloat(),
            withAlpha(color, 60), withAlpha(color, 12), Shader.TileMode.CLAMP)
        canvas.drawPath(body, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f * s
        paint.color = withAlpha(color, 120)
        canvas.drawPath(body, paint)

        // heart glow on chest reacting to voice
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(cx, cy + 150f * s, (26f + amp * 30f) * s,
            intArrayOf(withAlpha(color, 220), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy + 150f * s, (26f + amp * 30f) * s, paint)
        paint.shader = null
        paint.style = Paint.Style.FILL
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(alpha.coerceIn(0, 255),
        Color.red(color), Color.green(color), Color.blue(color))
}
