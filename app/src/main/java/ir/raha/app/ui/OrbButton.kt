package ir.raha.app.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import kotlin.math.min

/**
 * The connect button: soft halo, gradient ring, dark glass core and a power glyph.
 * The ring spins only while connecting (it is the progress indicator), and the
 * whole orb tints by state.
 */
class OrbButton(context: Context) : View(context) {
    enum class Mode { IDLE, BUSY, ON, FAIL }

    private val dp = resources.displayMetrics.density
    private val ease = PathInterpolator(0.23f, 1f, 0.32f, 1f)

    private var accentA = IDLE_A
    private var accentB = IDLE_B
    private var colorAnim: ValueAnimator? = null
    private var spin = 0f
    private var spinAnim: ValueAnimator? = null
    private var mode = Mode.IDLE

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#14FFFFFF")
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#22FFFFFF")
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val oval = RectF()
    private val matrix = Matrix()

    init {
        isClickable = true
        isFocusable = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setMode(next: Mode) {
        if (next == mode) return
        mode = next
        val (a, b) = when (next) {
            Mode.IDLE -> IDLE_A to IDLE_B
            Mode.BUSY -> BUSY_A to BUSY_B
            Mode.ON -> ON_A to ON_B
            Mode.FAIL -> FAIL_A to FAIL_B
        }
        val fromA = accentA
        val fromB = accentB
        colorAnim?.cancel()
        colorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = ease
            val ev = ArgbEvaluator()
            addUpdateListener {
                val f = it.animatedFraction
                accentA = ev.evaluate(f, fromA, a) as Int
                accentB = ev.evaluate(f, fromB, b) as Int
                invalidate()
            }
            start()
        }
        if (next == Mode.BUSY) startSpin() else stopSpin()
    }

    private fun startSpin() {
        if (spinAnim?.isRunning == true) return
        spinAnim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { spin = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopSpin() {
        spinAnim?.cancel()
        spinAnim = null
        spin = 0f
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                animate().scaleX(0.96f).scaleY(0.96f).setDuration(120).setInterpolator(ease).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(ease).start()
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        spinAnim?.cancel()
        colorAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(width, height) / 2f

        // Halo: soft accent glow fading to nothing.
        haloPaint.shader = RadialGradient(
            cx, cy, outer,
            intArrayOf(withAlpha(accentA, 0x55), withAlpha(accentB, 0x22), Color.TRANSPARENT),
            floatArrayOf(0.45f, 0.72f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, outer, haloPaint)

        // Ring: full gradient when on/idle, a sweeping arc while connecting.
        val ringR = outer * 0.68f
        val stroke = 5f * dp
        trackPaint.strokeWidth = stroke
        ringPaint.strokeWidth = stroke
        oval.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
        canvas.drawCircle(cx, cy, ringR, trackPaint)

        val sweep = SweepGradient(cx, cy, intArrayOf(accentA, accentB, accentA), null)
        matrix.setRotate(spin - 90f, cx, cy)
        sweep.setLocalMatrix(matrix)
        ringPaint.shader = sweep
        if (mode == Mode.BUSY) {
            canvas.drawArc(oval, spin - 90f, 110f, false, ringPaint)
        } else {
            canvas.drawCircle(cx, cy, ringR, ringPaint)
        }

        // Core: dark glass with a faint top highlight.
        val coreR = ringR - 12f * dp
        corePaint.shader = LinearGradient(
            cx, cy - coreR, cx, cy + coreR,
            intArrayOf(Color.parseColor("#1A2436"), Color.parseColor("#0A0F1A")),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, coreR, corePaint)
        rimPaint.strokeWidth = 1f * dp
        canvas.drawCircle(cx, cy, coreR, rimPaint)

        // Power glyph, tinted toward the accent.
        val g = coreR * 0.34f
        glyphPaint.strokeWidth = 4.5f * dp
        glyphPaint.color = if (mode == Mode.IDLE) Color.WHITE else blend(Color.WHITE, accentA, 0.35f)
        oval.set(cx - g, cy - g, cx + g, cy + g)
        canvas.drawArc(oval, -60f, 300f, false, glyphPaint)
        canvas.drawLine(cx, cy - g * 1.25f, cx, cy - g * 0.15f, glyphPaint)
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    private fun blend(a: Int, b: Int, t: Float) = ArgbEvaluator().evaluate(t, a, b) as Int

    private companion object {
        val IDLE_A = Color.parseColor("#2E7DFF")
        val IDLE_B = Color.parseColor("#22D3A6")
        val BUSY_A = Color.parseColor("#4FA8FF")
        val BUSY_B = Color.parseColor("#7C5CFF")
        val ON_A = Color.parseColor("#20E3A0")
        val ON_B = Color.parseColor("#1BA8FF")
        val FAIL_A = Color.parseColor("#FF5A6A")
        val FAIL_B = Color.parseColor("#FF9A5A")
    }
}
