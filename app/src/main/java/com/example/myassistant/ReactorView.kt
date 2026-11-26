package com.example.myassistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Spinning arc-reactor. Bright and animated when Jarvis is running, dim when it is off. */
class ReactorView(context: Context) : View(context) {

    var active: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val cyan = Color.parseColor("#00E5FF")
    private val off = Color.parseColor("#3A5560")
    private val dark = Color.parseColor("#070B10")

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val box = RectF()

    private var angle = 0f

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 7000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            angle = it.animatedValue as Float
            invalidate()
        }
    }

    private fun dp(n: Int) = n * resources.displayMetrics.density

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - dp(6)
        val color = if (active) cyan else off
        val spin = if (active) angle else 0f

        // soft glow
        fill.color = color
        fill.alpha = if (active) 38 else 14
        canvas.drawCircle(cx, cy, r, fill)

        // outer thin ring
        ring.color = color
        ring.alpha = 170
        ring.strokeWidth = dp(2)
        canvas.drawCircle(cx, cy, r, ring)

        // tick marks
        ring.strokeWidth = dp(2)
        for (i in 0 until 48) {
            val rad = Math.toRadians((i * 7.5).toDouble())
            val inner = r * (if (i % 4 == 0) 0.88f else 0.92f)
            canvas.drawLine(
                cx + cos(rad).toFloat() * inner, cy + sin(rad).toFloat() * inner,
                cx + cos(rad).toFloat() * r * 0.97f, cy + sin(rad).toFloat() * r * 0.97f,
                ring
            )
        }

        // rotating outer arcs
        ring.alpha = 255
        ring.strokeWidth = dp(6)
        box.set(cx - r * 0.78f, cy - r * 0.78f, cx + r * 0.78f, cy + r * 0.78f)
        for (k in 0 until 3) {
            canvas.drawArc(box, spin + k * 120f, 70f, false, ring)
        }

        // counter-rotating inner arcs
        ring.strokeWidth = dp(4)
        ring.alpha = 200
        box.set(cx - r * 0.58f, cy - r * 0.58f, cx + r * 0.58f, cy + r * 0.58f)
        canvas.drawArc(box, -spin * 1.6f, 110f, false, ring)
        canvas.drawArc(box, -spin * 1.6f + 180f, 110f, false, ring)

        // core
        fill.color = color
        fill.alpha = if (active) 255 else 70
        canvas.drawCircle(cx, cy, r * 0.34f, fill)
        ring.alpha = 255
        ring.strokeWidth = dp(3)
        canvas.drawCircle(cx, cy, r * 0.40f, ring)

        // the J
        letter.color = if (active) dark else color
        letter.textSize = r * 0.42f
        canvas.drawText("J", cx, cy + r * 0.15f, letter)
    }
}
