package com.example.glasskeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Damped spring (value, velocity) — the motion model of the liquid controls, after
 * DampedDragAnimation in Kyant's AndroidLiquidGlass (Apache-2.0).
 */
class Spring(var value: Float, private val stiffness: Float, dampingRatio: Float) {
    var velocity = 0f
    private val damping = 2f * sqrt(stiffness) * dampingRatio

    /** Advances by [dt] seconds towards [target]; returns true while still moving. */
    fun step(target: Float, dt: Float, eps: Float = 0.001f): Boolean {
        var t = dt
        while (t > 0f) {
            val h = minOf(t, 1f / 240f)
            velocity += ((target - value) * stiffness - velocity * damping) * h
            value += velocity * h
            t -= h
        }
        if (abs(target - value) < eps && abs(velocity) < eps * 10f) {
            value = target
            velocity = 0f
            return false
        }
        return true
    }
}

/** Shared drawing of the liquid thumb: white capsule at rest, refracting glass while held. */
private class LiquidThumb(private val dp: Float) {
    val lens = LiquidLens(dp)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    fun draw(
        c: Canvas, cx: Float, cy: Float, w: Float, h: Float, scaleX: Float, scaleY: Float,
        press: Float, content: Bitmap, refrHeight: Float, refrAmount: Float, dark: Boolean,
    ) {
        val hw = w / 2f * scaleX
        val hh = h / 2f * scaleY
        r.set(cx - hw, cy - hh, cx + hw, cy + hh)
        // soft shadow
        paint.shader = null
        for (i in 1..3) {
            paint.color = Color.argb(12, 0, 0, 0)
            val g = i * 1.2f * dp
            c.drawRoundRect(r.left - g * 0.3f, r.top + g * 0.2f, r.right + g * 0.3f, r.bottom + g, hh + g, hh + g, paint)
        }
        if (press > 0.01f) {
            lens.setContent(content, 0f, 0f)
            lens.drawRaw(
                c, r, hh, 1f, refrHeight * press, refrAmount * press, 1f, 0f, true, false,
                if (dark) 0x14FFFFFF else 0x1FFFFFFF, dark, 1f, shadow = false,
            )
        }
        // the white surface fades away as the thumb turns into glass
        val a = (1f - press).coerceIn(0f, 1f)
        if (a > 0f) {
            paint.color = Color.WHITE
            paint.alpha = (255 * a).toInt()
            c.drawRoundRect(r, hh, hh, paint)
        }
    }
}

/**
 * Liquid glass slider (after Kyant's LiquidSlider): capsule track with an accent fill and a white
 * thumb that, while held, grows, turns into refracting glass over the track and stretches with
 * its speed.
 */
@SuppressLint("ViewConstructor")
class LiquidSlider(
    context: Context,
    private val min: Int,
    private val max: Int,
    value: Int,
    private val dark: Boolean,
    /** null = accent fill; otherwise the track shows this gradient (e.g. a rainbow) */
    private val trackColors: IntArray? = null,
    private val onChange: (Int) -> Unit,
) : View(context) {
    private val dp = resources.displayMetrics.density
    private val accent = if (dark) 0xFF0091FF.toInt() else 0xFF0088FF.toInt()
    private val trackColor = if (dark) 0x5C787880 else 0x33787878
    private val thumbW = 40f * dp
    private val thumbH = 24f * dp
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = LiquidThumb(dp)

    private var target = ((value - min).toFloat() / (max - min)).coerceIn(0f, 1f)
    private val pos = Spring(target, 1000f, 1f)
    private val press = Spring(0f, 1000f, 1f)
    private val sx = Spring(1f, 250f, 0.6f)
    private val sy = Spring(1f, 250f, 0.7f)
    private var pressed = false
    private var last = 0L
    private var downX = 0f
    private var startTarget = 0f
    private var layer: Bitmap? = null

    var value: Int = value
        private set

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (44 * dp).toInt())
    }

    private val trackLeft get() = thumbW / 2f
    private val trackW get() = width - thumbW

    private fun drawTrack(c: Canvas, progress: Float) {
        val cy = height / 2f
        val th = 6f * dp
        paint.shader = null
        if (trackColors != null) {
            paint.shader = LinearGradient(trackLeft, 0f, trackLeft + trackW, 0f, trackColors, null, Shader.TileMode.CLAMP)
            c.drawRoundRect(trackLeft, cy - th / 2, trackLeft + trackW, cy + th / 2, th / 2, th / 2, paint)
            paint.shader = null
            return
        }
        paint.color = trackColor
        c.drawRoundRect(trackLeft, cy - th / 2, trackLeft + trackW, cy + th / 2, th / 2, th / 2, paint)
        paint.color = accent
        val fx = trackLeft + trackW * progress
        if (fx > trackLeft + 1) c.drawRoundRect(trackLeft, cy - th / 2, fx, cy + th / 2, th / 2, th / 2, paint)
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0) return
        val n = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 0.016f else ((n - last) / 1000f).coerceIn(0f, 0.05f)
        last = n
        var moving = pos.step(target, dt)
        moving = press.step(if (pressed) 1f else 0f, dt) or moving
        moving = sx.step(if (pressed) 1.5f else 1f, dt) or moving
        moving = sy.step(if (pressed) 1.5f else 1f, dt) or moving

        var b = layer
        if (b == null || b.width != width || b.height != height) {
            b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            layer = b
        }
        b!!.eraseColor(0)
        drawTrack(Canvas(b), pos.value)
        canvas.drawBitmap(b, 0f, 0f, null)

        val v = (pos.velocity / 10f).coerceIn(-1f, 1f)
        val scX = sx.value / (1f - (abs(v) * 0.75f).coerceIn(0f, 0.2f))
        val scY = sy.value * (1f - (abs(v) * 0.25f).coerceIn(0f, 0.2f))
        val cx = (trackLeft + trackW * pos.value).coerceIn(thumbW / 4f, width - thumbW / 4f)
        thumb.draw(canvas, cx, height / 2f, thumbW, thumbH, scX, scY, press.value, b, 10f * dp, 14f * dp, dark)
        if (moving) postInvalidateOnAnimation() else last = 0L
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                pressed = true
                downX = e.x
                val thumbX = trackLeft + trackW * target
                // touching the track away from the thumb jumps there; touching the thumb drags it
                if (abs(e.x - thumbX) > thumbW) target = ((e.x - trackLeft) / trackW).coerceIn(0f, 1f)
                startTarget = target
                update()
            }
            MotionEvent.ACTION_MOVE -> {
                target = (startTarget + (e.x - downX) / trackW).coerceIn(0f, 1f)
                update()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        postInvalidateOnAnimation()
        return true
    }

    private fun update() {
        val v = min + Math.round(target * (max - min))
        if (v != value) {
            value = v
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            onChange(v)
        }
    }
}

/**
 * Liquid glass switch (after Kyant's LiquidToggle): the track turns green, the white thumb grows
 * into refracting glass while touched and can be dragged across.
 */
@SuppressLint("ViewConstructor")
class LiquidToggle(context: Context, checked: Boolean, private val dark: Boolean, private val onChange: (Boolean) -> Unit) : View(context) {
    private val dp = resources.displayMetrics.density
    private val on = if (dark) 0xFF30D158.toInt() else 0xFF34C759.toInt()
    private val off = if (dark) 0x5C787880 else 0x33787878
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = LiquidThumb(dp)
    private val trackW = 64f * dp
    private val trackH = 28f * dp
    private val dragW = 20f * dp

    var checked = checked
        private set
    private var fraction = if (checked) 1f else 0f
    private val pos = Spring(fraction, 1000f, 1f)
    private val press = Spring(0f, 1000f, 1f)
    private val sx = Spring(1f, 250f, 0.6f)
    private val sy = Spring(1f, 250f, 0.7f)
    private var pressed = false
    private var dragged = false
    private var downX = 0f
    private var startFraction = 0f
    private var last = 0L
    private var layer: Bitmap? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((trackW + 16 * dp).toInt(), (trackH + 18 * dp).toInt())
    }

    fun toggle() = set(!checked)

    fun set(v: Boolean) {
        checked = v
        fraction = if (v) 1f else 0f
        onChange(v)
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val n = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 0.016f else ((n - last) / 1000f).coerceIn(0f, 0.05f)
        last = n
        var moving = pos.step(fraction, dt)
        moving = press.step(if (pressed) 1f else 0f, dt) or moving
        moving = sx.step(if (pressed) 1.5f else 1f, dt) or moving
        moving = sy.step(if (pressed) 1.5f else 1f, dt) or moving

        val left = (width - trackW) / 2f
        val top = (height - trackH) / 2f
        var b = layer
        if (b == null || b.width != width || b.height != height) {
            b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            layer = b
        }
        b!!.eraseColor(0)
        val lc = Canvas(b)
        paint.color = GlassTheme.lerpColor(off, on, pos.value.coerceIn(0f, 1f))
        lc.drawRoundRect(left, top, left + trackW, top + trackH, trackH / 2, trackH / 2, paint)
        canvas.drawBitmap(b, 0f, 0f, null)

        val v = (pos.velocity / 50f).coerceIn(-1f, 1f)
        val scX = sx.value / (1f - (abs(v) * 0.75f).coerceIn(0f, 0.2f))
        val scY = sy.value * (1f - (abs(v) * 0.25f).coerceIn(0f, 0.2f))
        val pad = 2f * dp
        val cx = left + pad + 20f * dp + pos.value * dragW
        // at rest the thumb is 40×24 dp; while held it grows 1.5× and turns to glass
        thumb.draw(canvas, cx, top + trackH / 2f, 40f * dp, 24f * dp, scX, scY, press.value, b, 5f * dp, 10f * dp, dark)
        if (moving) postInvalidateOnAnimation() else last = 0L
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                pressed = true
                dragged = false
                downX = e.x
                startFraction = fraction
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) > 3 * dp) dragged = true
                if (dragged) fraction = (startFraction + (e.x - downX) / dragW).coerceIn(0f, 1f)
            }
            MotionEvent.ACTION_UP -> {
                pressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                val now = if (dragged) fraction >= 0.5f else !checked
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                set(now)
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = false
                fraction = if (checked) 1f else 0f
            }
        }
        postInvalidateOnAnimation()
        return true
    }
}
