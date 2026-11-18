package com.example.glasskeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.Typeface
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
 */
@SuppressLint("ViewConstructor")
class CandidateBar(context: Context, private val onPick: (Candidate) -> Unit) : View(context) {
    var theme: GlassTheme = GlassTheme(context, 0)
        set(v) {
            field = v
            invalidate()
        }

    private var items: List<Candidate?> = listOf(null, null, null)
    private var pressed = -1
    private val dp = resources.displayMetrics.density
    private val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER }
    private val tmp = RectF()

    fun set(list: List<Candidate?>) {
        items = List(3) { list.getOrNull(it) }
        invalidate()
    }

    fun clear() = set(emptyList())

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (44 * dp).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        val w = width / 3f
        val h = height.toFloat()
        for (i in 0 until 3) {
            val c = items[i] ?: continue
            val l = i * w
            if (c.highlight || i == pressed) {
                t.fill.shader = null
                t.fill.color = t.candidateHighlight
                tmp.set(l + 5 * dp, 6 * dp, l + w - 5 * dp, h - 4 * dp)
                canvas.drawRoundRect(tmp, 10 * dp, 10 * dp, t.fill)
            }
            paint.color = t.text
            paint.textSize = 17f * t.sp
            paint.typeface = if (c.highlight) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val s = TextUtils.ellipsize(c.shown, paint, w - 14 * dp, TextUtils.TruncateAt.END).toString()
            val fm = paint.fontMetrics
            canvas.drawText(s, l + w / 2, h / 2 + 1 * dp - (fm.ascent + fm.descent) / 2, paint)
        }
        // thin separators between filled, non-highlighted slots
        t.fill.color = t.divider
        for (i in 1 until 3) {
            val a = items[i - 1]
            val b = items[i]
            if (a == null || b == null || a.highlight || b.highlight) continue
            canvas.drawRect(i * w - 0.5f * dp, 13 * dp, i * w + 0.5f * dp, h - 11 * dp, t.fill)
        }
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
                if (p == i) items[p]?.let(onPick)
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = -1
                invalidate()
            }
        }
        return true
    }
}
