package ai.alfred.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class PetMood { IDLE, LISTENING, THINKING, ACTING, ASKING, ANSWERING, SUCCESS, ERROR }

class PetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var mood: PetMood = PetMood.IDLE
        set(value) {
            field = value
            contentDescription = "Alfred is ${value.name.lowercase()}"
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val startedAt = System.nanoTime()
    private val animator = object : Runnable {
        override fun run() {
            if (isAttachedToWindow) {
                invalidate()
                postDelayed(this, 16)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(animator)
        post(animator)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(animator)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = context.dp(270)
        setMeasuredDimension(resolveSize(desired, widthMeasureSpec), resolveSize(desired, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = (System.nanoTime() - startedAt) / 1_000_000_000.0
        val cx = width / 2f
        val cy = height / 2f + context.dp(7)
        val unit = minOf(width, height) / 270f
        val pulseSpeed = when (mood) {
            PetMood.LISTENING -> 6.0
            PetMood.ANSWERING -> 4.0
            PetMood.ASKING -> 3.2
            else -> 2.1
        }
        val pulse = (sin(t * pulseSpeed) * 0.5 + 0.5).toFloat()
        val accent = when (mood) {
            PetMood.ERROR -> Palette.Coral
            PetMood.ASKING -> Palette.Coral
            PetMood.SUCCESS -> Palette.Mint
            PetMood.THINKING -> Palette.Violet
            PetMood.ANSWERING -> Palette.Mint
            else -> Palette.Mint
        }

        paint.shader = RadialGradient(
            cx,
            cy,
            (92 + pulse * 18) * unit,
            intArrayOf(withAlpha(accent, 80), withAlpha(accent, 0)),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, (92 + pulse * 18) * unit, paint)
        paint.shader = null

        if (mood == PetMood.LISTENING || mood == PetMood.THINKING) {
            stroke.color = withAlpha(accent, 120)
            stroke.strokeWidth = 2.5f * unit
            repeat(3) { ring ->
                val phase = ((t * 0.75 + ring / 3.0) % 1.0).toFloat()
                stroke.alpha = ((1f - phase) * 140).toInt()
                canvas.drawCircle(cx, cy, (82 + phase * 52) * unit, stroke)
            }
            stroke.alpha = 255
        }

        val bobSpeed = if (mood == PetMood.ANSWERING) 3.4 else 2.2
        val bob = (sin(t * bobSpeed) * 4 * unit).toFloat()
        val shake = if (mood == PetMood.ERROR) (sin(t * 20) * 3 * unit).toFloat() else 0f
        canvas.save()
        canvas.translate(shake, bob)
        if (mood == PetMood.ASKING) {
            canvas.rotate((sin(t * 2.4) * 3.5).toFloat(), cx, cy)
        }

        paint.color = ColorValue.shadow
        canvas.drawOval(cx - 64 * unit, cy + 74 * unit, cx + 64 * unit, cy + 89 * unit, paint)

        paint.color = accent
        canvas.drawRoundRect(
            cx - 70 * unit,
            cy - 57 * unit,
            cx + 70 * unit,
            cy + 77 * unit,
            49 * unit,
            49 * unit,
            paint,
        )
        val ears = Path().apply {
            moveTo(cx - 64 * unit, cy - 36 * unit)
            lineTo(cx - 56 * unit, cy - 98 * unit)
            lineTo(cx - 17 * unit, cy - 58 * unit)
            close()
            moveTo(cx + 64 * unit, cy - 36 * unit)
            lineTo(cx + 56 * unit, cy - 98 * unit)
            lineTo(cx + 17 * unit, cy - 58 * unit)
            close()
        }
        canvas.drawPath(ears, paint)

        paint.color = Palette.Night
        val blink = (t % 4.6) > 4.42
        if (blink) {
            stroke.color = Palette.Night
            stroke.strokeWidth = 5 * unit
            canvas.drawLine(cx - 38 * unit, cy - 9 * unit, cx - 21 * unit, cy - 9 * unit, stroke)
            canvas.drawLine(cx + 21 * unit, cy - 9 * unit, cx + 38 * unit, cy - 9 * unit, stroke)
        } else {
            canvas.drawOval(cx - 42 * unit, cy - 20 * unit, cx - 20 * unit, cy + 9 * unit, paint)
            canvas.drawOval(cx + 20 * unit, cy - 20 * unit, cx + 42 * unit, cy + 9 * unit, paint)
            paint.color = Palette.Ink
            canvas.drawCircle(cx - 31 * unit, cy - 13 * unit, 4 * unit, paint)
            canvas.drawCircle(cx + 31 * unit, cy - 13 * unit, 4 * unit, paint)
        }

        stroke.color = Palette.Night
        stroke.strokeWidth = 4 * unit
        if (mood == PetMood.ANSWERING) {
            paint.color = Palette.Night
            val speaking = (sin(t * 11) * 0.5 + 0.5).toFloat()
            canvas.drawOval(
                cx - 14 * unit,
                cy + 29 * unit,
                cx + 14 * unit,
                cy + (39 + speaking * 10) * unit,
                paint,
            )
        } else {
            val mouth = Path().apply {
                when (mood) {
                    PetMood.SUCCESS -> {
                        moveTo(cx - 20 * unit, cy + 30 * unit)
                        quadTo(cx, cy + 49 * unit, cx + 20 * unit, cy + 30 * unit)
                    }
                    PetMood.ERROR -> {
                        moveTo(cx - 18 * unit, cy + 43 * unit)
                        quadTo(cx, cy + 28 * unit, cx + 18 * unit, cy + 43 * unit)
                    }
                    else -> {
                        moveTo(cx - 15 * unit, cy + 35 * unit)
                        quadTo(cx, cy + 42 * unit, cx + 15 * unit, cy + 35 * unit)
                    }
                }
            }
            canvas.drawPath(mouth, stroke)
        }

        if (mood == PetMood.LISTENING) drawWave(canvas, cx, cy + 105 * unit, t, unit, accent)
        if (mood == PetMood.THINKING) drawOrbit(canvas, cx, cy, t, unit)
        if (mood == PetMood.ACTING) drawSpeedLines(canvas, cx, cy, t, unit)
        if (mood == PetMood.ASKING) drawQuestions(canvas, cx, cy, t, unit)
        if (mood == PetMood.ANSWERING) drawSpeechMotes(canvas, cx, cy, t, unit)
        if (mood == PetMood.SUCCESS) drawSparkles(canvas, cx, cy, t, unit)
        canvas.restore()
    }

    private fun drawWave(canvas: Canvas, cx: Float, y: Float, t: Double, unit: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = 4 * unit
        repeat(7) { index ->
            val height = (8 + 15 * (sin(t * 7 + index * 0.8) * 0.5 + 0.5)).toFloat() * unit
            val x = cx + (index - 3) * 13 * unit
            canvas.drawLine(x, y - height / 2, x, y + height / 2, stroke)
        }
    }

    private fun drawOrbit(canvas: Canvas, cx: Float, cy: Float, t: Double, unit: Float) {
        paint.color = Palette.Coral
        repeat(3) { index ->
            val angle = t * 1.8 + index * (2 * PI / 3)
            canvas.drawCircle(
                cx + (cos(angle) * 91 * unit).toFloat(),
                cy + (sin(angle) * 55 * unit).toFloat(),
                6 * unit,
                paint,
            )
        }
    }

    private fun drawSparkles(canvas: Canvas, cx: Float, cy: Float, t: Double, unit: Float) {
        paint.color = Palette.Ink
        repeat(6) { index ->
            val angle = index * (2 * PI / 6) + t * 0.35
            val pulse = (sin(t * 4.5 + index) * 0.5 + 0.5).toFloat()
            val radius = (96 + pulse * 25) * unit
            val x = cx + (cos(angle) * radius).toFloat()
            val y = cy + (sin(angle) * radius * 0.62).toFloat()
            canvas.drawCircle(x, y, (2.5f + pulse * 3f) * unit, paint)
        }
    }

    private fun drawQuestions(canvas: Canvas, cx: Float, cy: Float, t: Double, unit: Float) {
        paint.color = Palette.Ink
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        repeat(3) { index ->
            val phase = (t * 0.7 + index * 0.33) % 1.0
            paint.alpha = ((1.0 - phase) * 220).toInt().coerceIn(0, 255)
            paint.textSize = (20 + index * 3) * unit
            val side = if (index % 2 == 0) -1 else 1
            canvas.drawText(
                "?",
                cx + side * (88 + index * 7) * unit,
                cy + (30 - phase * 95).toFloat() * unit,
                paint,
            )
        }
        paint.alpha = 255
        paint.typeface = Typeface.DEFAULT
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawSpeechMotes(canvas: Canvas, cx: Float, cy: Float, t: Double, unit: Float) {
        paint.color = Palette.Ink
        repeat(4) { index ->
            val phase = ((t * 0.9 + index * 0.24) % 1.0).toFloat()
            paint.alpha = ((1f - phase) * 190).toInt()
            val x = cx + (72 + index * 9) * unit
            val y = cy + (30 - phase * 100) * unit
            canvas.drawCircle(x, y, (3 + phase * 4) * unit, paint)
        }
        paint.alpha = 255
    }

    private fun drawSpeedLines(canvas: Canvas, cx: Float, cy: Float, t: Double, unit: Float) {
        stroke.color = withAlpha(Palette.Mint, 150)
        stroke.strokeWidth = 3 * unit
        repeat(4) { index ->
            val phase = ((t * 1.8 + index * 0.23) % 1.0).toFloat()
            val y = cy - 52 * unit + index * 34 * unit
            val start = cx - (90 + phase * 36) * unit
            canvas.drawLine(start, y, start - (22 + phase * 18) * unit, y, stroke)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00ffffff) or (alpha.coerceIn(0, 255) shl 24)

    private object ColorValue { val shadow = android.graphics.Color.argb(70, 0, 0, 0) }
}
