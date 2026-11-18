package com.example.glasskeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View

/** One slot of the suggestion bar. */
data class Candidate(val text: String, val shown: String = text, val highlight: Boolean = false, val literal: Boolean = false)

/**
 * The predictive bar above the keys: three slots like on iOS. The left one shows the typed word
 * in quotes (tap it to keep exactly what you typed), the middle one the best word — highlighted
 * when it will replace the typed word on space — and the right one an alternative or an emoji.
 *
 * Nothing moves: a slot whose word changes cross-fades in place, the highlight pill only fades
 * in/out when it appears or disappears and otherwise stays put while its word changes.
 */
@SuppressLint("ViewConstructor")
class CandidateBar(context: Context, private val onPick: (Candidate) -> Unit) : View(context) {
    var theme: GlassTheme = GlassTheme(context, 0)
        set(v) {
            field = v
            invalidate()
        }
    var animations = true
    /** suggestion change: 0 fade, 1 float up, 2 zoom, 3 roll, 4 none */
    var style = 0
    /** called with the centre of a tapped suggestion (for the chaos burst) */
    var onTapAt: ((Float, Float) -> Unit)? = null
    private val lens = LiquidLens(resources.displayMetrics.density)
    private var layer: android.graphics.Bitmap? = null
    private val lensRect = RectF()
    private var pulseSlot = -1
    private var pulseAt = 0L

    private var items: List<Candidate?> = listOf(null, null, null)
    private var old: List<Candidate?> = listOf(null, null, null)
    private val changedAt = LongArray(3)
    private val pillAt = LongArray(3)
    private val pillFrom = FloatArray(3)
    private var pressed = -1
    private val dp = resources.displayMetrics.density
    private val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER }
    private val tmp = RectF()

    private val fadeMs = 130f

    fun set(list: List<Candidate?>) {
        val n = List(3) { list.getOrNull(it) }
        if (n == items) return
        val now = SystemClock.uptimeMillis()
        val prev = items
        val o = ArrayList<Candidate?>(3)
        for (i in 0 until 3) {
            val a = prev[i]
            val b = n[i]
            val level = pillLevel(i, now)
            if (a?.shown != b?.shown) {
                // the word in this slot changes: remember what was visible for the cross-fade
                o += a
                changedAt[i] = now
            } else {
                o += old[i]
            }
            if ((a?.highlight == true) != (b?.highlight == true)) {
                pillFrom[i] = level
                pillAt[i] = now
            }
        }
        old = o
        items = n
        invalidate()
    }

    fun clear() = set(emptyList())

    /** Current visibility (0..1) of the highlight pill in slot [i]. */
    private fun pillLevel(i: Int, now: Long): Float {
        val target = if (items[i]?.highlight == true) 1f else 0f
        if (!animations) return target
        val t = ((now - pillAt[i]) / fadeMs).coerceIn(0f, 1f)
        return pillFrom[i] + (target - pillFrom[i]) * t
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (44 * dp).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        val now = SystemClock.uptimeMillis()
        val w = width / 3f
        val h = height.toFloat()
        var again = false
        val pills = FloatArray(3)
        var anyPill = false
        for (i in 0 until 3) {
            pills[i] = maxOf(pillLevel(i, now), if (i == pressed) 0.75f else 0f)
            if (pills[i] > 0f) anyPill = true
            if (animations && now - pillAt[i] < fadeMs) again = true
        }
        // words go to a layer first when a glass pill has to refract them
        var target = canvas
        var bmp: android.graphics.Bitmap? = null
        if (anyPill && width > 0 && height > 0) {
            bmp = layer
            if (bmp == null || bmp.width != width || bmp.height != height) {
                bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                layer = bmp
            }
            bmp!!.eraseColor(0)
            target = Canvas(bmp)
        }
        for (i in 0 until 3) {
            val l = i * w
            val a = if (!animations || style == 4) 1f else ((now - changedAt[i]) / fadeMs).coerceIn(0f, 1f)
            if (a < 1f) {
                again = true
                old[i]?.let { drawWord(target, it, i, l, w, h, 1f - a, false, now, t) }
            }
            items[i]?.let { drawWord(target, it, i, l, w, h, a, true, now, t) }
        }
        if (now - pulseAt < 280) again = true
        if (bmp != null) {
            canvas.drawBitmap(bmp, 0f, 0f, null)
            lens.setContent(bmp, 0f, 0f)
            for (i in 0 until 3) {
                if (pills[i] <= 0f) continue
                val l = i * w
                lensRect.set(l + 5 * dp, 6 * dp, l + w - 5 * dp, h - 4 * dp)
                lens.draw(
                    canvas, lensRect, lensRect.height() / 2f, 1.06f, 7f * dp,
                    if (t.dark) 0x2EFFFFFF else 0x66FFFFFF, t.dark, pills[i],
                )
            }
        }
        // thin separators between filled, non-highlighted slots
        t.fill.shader = null
        t.fill.color = t.divider
        for (i in 1 until 3) {
            val a = items[i - 1]
            val b = items[i]
            if (a == null || b == null || a.highlight || b.highlight) continue
            canvas.drawRect(i * w - 0.5f * dp, 13 * dp, i * w + 0.5f * dp, h - 11 * dp, t.fill)
        }
        if (again) postInvalidateOnAnimation()
    }

    private fun drawWord(canvas: Canvas, c: Candidate, slot: Int, l: Float, w: Float, h: Float, alpha: Float, incoming: Boolean, now: Long, t: GlassTheme) {
        if (alpha <= 0f) return
        paint.color = t.text
        paint.alpha = (255 * alpha).toInt()
        paint.textSize = 17f * t.sp
        paint.typeface = if (c.highlight) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val s = TextUtils.ellipsize(c.shown, paint, w - 14 * dp, TextUtils.TruncateAt.END).toString()
        val fm = paint.fontMetrics
        val cx = l + w / 2
        val cy = h / 2 + 1 * dp
        canvas.save()
        canvas.clipRect(l, 0f, l + w, h)
        val k = 1f - alpha // 0 = settled
        when (if (animations) style else 4) {
            1 -> canvas.translate(0f, if (incoming) k * 8 * dp else -(1f - alpha) * 0f - (1f - alpha) * 8 * dp)
            2 -> {
                val sc = if (incoming) 0.82f + 0.18f * alpha else 1f + 0.12f * (1f - alpha)
                canvas.scale(sc, sc, cx, cy)
            }
            3 -> canvas.translate(0f, if (incoming) k * h * 0.6f else -(1f - alpha) * h * 0.6f)
        }
        if (incoming && slot == pulseSlot) {
            val pt = ((now - pulseAt) / 280f).coerceIn(0f, 1f)
            if (pt < 1f) {
                val sc = 1f + 0.22f * kotlin.math.sin(pt * Math.PI).toFloat()
                canvas.scale(sc, sc, cx, cy)
            }
        }
        canvas.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2, paint)
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = (e.x / (width / 3f)).toInt().coerceIn(0, 2)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (items[i] == null) return false
                pressed = i
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (i != pressed && pressed >= 0) {
                pressed = -1
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val p = pressed
                pressed = -1
                invalidate()
                if (p == i) items[p]?.let {
                    pulseSlot = p
                    pulseAt = SystemClock.uptimeMillis()
                    val w = width / 3f
                    onTapAt?.invoke(p * w + w / 2, height / 2f)
                    onPick(it)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = -1
                invalidate()
            }
        }
        return true
    }
}
