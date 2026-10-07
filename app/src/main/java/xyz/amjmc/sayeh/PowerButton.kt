package xyz.amjmc.sayeh

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator

/** The one big round button. Colour follows the connection phase. */
class PowerButton(ctx: Context) : View(ctx) {

    private val easeOut = PathInterpolator(0.23f, 1f, 0.32f, 1f)
    private val d = resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * d; strokeCap = Paint.Cap.ROUND
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6 * d; strokeCap = Paint.Cap.ROUND
    }
    private val box = RectF()

    private var fillColor = OFF_FILL
    private var accent = OFF_ACCENT
    private var colorAnim: ValueAnimator? = null

    private var spinning = false
    private var angle = 0f
    private val spin = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1100
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { angle = it.animatedValue as Float; invalidate() }
    }

    init {
        isClickable = true
        contentDescription = "اتصال"
    }

    fun setPhase(p: Status.Phase) {
        val (f, a) = when (p) {
            Status.Phase.ON -> ON_FILL to ON_ACCENT
            Status.Phase.STARTING, Status.Phase.SEARCHING, Status.Phase.STOPPING -> WAIT_FILL to WAIT_ACCENT
            Status.Phase.ERROR -> ERR_FILL to ERR_ACCENT
            Status.Phase.OFF -> OFF_FILL to OFF_ACCENT
        }
        animateColors(f, a)
        val wantSpin = p == Status.Phase.STARTING || p == Status.Phase.SEARCHING || p == Status.Phase.STOPPING
        if (wantSpin && !spinning) { spinning = true; spin.start() }
        if (!wantSpin && spinning) { spinning = false; spin.cancel(); invalidate() }
    }

    private fun animateColors(toFill: Int, toAccent: Int) {
        if (toFill == fillColor && toAccent == accent) return
        colorAnim?.cancel()
        val f0 = fillColor; val a0 = accent
        val ev = ArgbEvaluator()
        colorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = easeOut
            addUpdateListener {
                val t = it.animatedFraction
                fillColor = ev.evaluate(t, f0, toFill) as Int
                accent = ev.evaluate(t, a0, toAccent) as Int
                invalidate()
            }
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                animate().scaleX(0.97f).scaleY(0.97f).setDuration(160).setInterpolator(easeOut).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(easeOut).start()
        }
        return super.onTouchEvent(e)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(width, height) / 2f - 8 * d

        fill.color = fillColor
        c.drawCircle(cx, cy, r, fill)

        ring.color = withAlpha(accent, 90)
        c.drawCircle(cx, cy, r, ring)

        if (spinning) {
            arc.color = accent
            box.set(cx - r, cy - r, cx + r, cy + r)
            c.drawArc(box, angle - 90f, 70f, false, arc)
        }

        // power glyph
        glyph.color = accent
        val g = r * 0.34f
        box.set(cx - g, cy - g, cx + g, cy + g)
        c.drawArc(box, -55f, 290f, false, glyph)
        c.drawLine(cx, cy - g * 1.25f, cx, cy - g * 0.25f, glyph)
    }

    override fun onDetachedFromWindow() {
        spin.cancel(); colorAnim?.cancel()
        super.onDetachedFromWindow()
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    companion object {
        val OFF_FILL = Color.parseColor("#1B2027")
        val OFF_ACCENT = Color.parseColor("#7D8894")
        val WAIT_FILL = Color.parseColor("#2A2418")
        val WAIT_ACCENT = Color.parseColor("#F2B84B")
        val ON_FILL = Color.parseColor("#123327")
        val ON_ACCENT = Color.parseColor("#3DDC97")
        val ERR_FILL = Color.parseColor("#33191A")
        val ERR_ACCENT = Color.parseColor("#EF6F64")
    }
}
