package com.example.glasskeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Transparent layer above the keyboard that draws the key preview "balloon" and the long-press
 * accent picker. It may draw above the keyboard sheet (into the transparent top margin of the
 * input view), like the iOS balloons do. It never takes touches.
 */
class PopupOverlay(context: Context) : View(context) {
    var theme: GlassTheme? = null

    private val shape = Path()
    private val tmpPath = Path()
    private val shadowPath = Path()
    private val key = RectF()
    private val bubble = RectF()
    private val tmp = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private var previewText: String? = null
    private var popupItems: List<String> = emptyList()
    private var popupSelected = 0
    private var itemW = 0f
    private var itemsLeft = 0f

    init {
        isClickable = false
        isFocusable = false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?) = false

    private fun offsetOf(v: View): PointF {
        var x = 0f
        var y = 0f
        var cur: View? = v
        val stop = parent
        while (cur != null && cur !== stop) {
            x += cur.left + cur.translationX
            y += cur.top + cur.translationY
            cur = cur.parent as? View
        }
        return PointF(x, y)
    }

    fun showPreview(src: View, k: RectF, text: String) {
        val t = theme ?: return
        val o = offsetOf(src)
        key.set(k.left + o.x, k.top + o.y, k.right + o.x, k.bottom + o.y)
        val extra = 9f * t.dp
        var l = key.left - extra
        var r = key.right + extra
        if (l < 2 * t.dp) {
            r += 2 * t.dp - l; l = 2 * t.dp
        }
        if (r > width - 2 * t.dp) {
            l -= r - (width - 2 * t.dp); r = width - 2 * t.dp
        }
        val h = key.height() * 1.15f
        bubble.set(l, key.top - h - key.height() * 0.12f, r, key.top - key.height() * 0.12f)
        buildShape(t)
        previewText = text
        popupItems = emptyList()
        invalidate()
    }

    fun hidePreview() {
        if (previewText != null) {
            previewText = null
            invalidate()
        }
    }

    /** Shows the accent picker; returns the left edge of the first item and the item width, in [src] coordinates. */
    fun showPopup(src: View, k: RectF, items: List<String>): FloatArray {
        val t = theme ?: return floatArrayOf(k.left, k.width())
        val o = offsetOf(src)
        key.set(k.left + o.x, k.top + o.y, k.right + o.x, k.bottom + o.y)
        itemW = maxOf(key.width(), 30f * t.dp)
        val pad = 5f * t.dp
        val w = items.size * itemW + 2 * pad
        var l = key.centerX() - itemW / 2 - pad
        if (l + w > width - 3 * t.dp) l = width - 3 * t.dp - w
        if (l < 3 * t.dp) l = 3 * t.dp
        val h = key.height() * 1.12f
        bubble.set(l, key.top - h - key.height() * 0.12f, l + w, key.top - key.height() * 0.12f)
        itemsLeft = l + pad
        buildShape(t)
        popupItems = items
        popupSelected = 0
        previewText = null
        invalidate()
        return floatArrayOf(itemsLeft - o.x, itemW)
    }

    fun selectPopup(i: Int) {
        if (i != popupSelected) {
            popupSelected = i
            invalidate()
        }
    }

    fun popupItem(): String? = popupItems.getOrNull(popupSelected)

    fun hidePopup() {
        if (popupItems.isNotEmpty()) {
            popupItems = emptyList()
            invalidate()
        }
    }

    fun hideAll() {
        previewText = null
        popupItems = emptyList()
        invalidate()
    }

    /** Bubble + neck + key, merged into one outline. */
    private fun buildShape(t: GlassTheme) {
        shape.reset()
        val br = 11f * t.dp
        shape.addRoundRect(bubble, br, br, Path.Direction.CW)
        tmpPath.reset()
        tmpPath.addRoundRect(key, t.keyRadius, t.keyRadius, Path.Direction.CW)
        shape.op(tmpPath, Path.Op.UNION)
        tmpPath.reset()
        val nl = maxOf(bubble.left, key.left)
        val nr = minOf(bubble.right, key.right)
        tmpPath.moveTo(bubble.left, bubble.bottom - br)
        tmpPath.cubicTo(bubble.left, bubble.bottom + (key.top - bubble.bottom) * 0.4f, nl, key.top - (key.top - bubble.bottom) * 0.4f, nl, key.top + t.keyRadius)
        tmpPath.lineTo(nr, key.top + t.keyRadius)
        tmpPath.cubicTo(nr, key.top - (key.top - bubble.bottom) * 0.4f, bubble.right, bubble.bottom + (key.top - bubble.bottom) * 0.4f, bubble.right, bubble.bottom - br)
        tmpPath.close()
        shape.op(tmpPath, Path.Op.UNION)
        shadowPath.set(shape)
        shadowPath.offset(0f, 2f * t.dp)
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme ?: return
        val text = previewText
        if (text == null && popupItems.isEmpty()) return
        paint.color = t.popupShadow
        canvas.drawPath(shadowPath, paint)
        paint.color = t.popup
        canvas.drawPath(shape, paint)
        textPaint.color = t.text
        if (text != null) {
            textPaint.textSize = if (text.length > 2) 22f * t.sp else 34f * t.sp
            val fm = textPaint.fontMetrics
            canvas.drawText(text, bubble.centerX(), bubble.centerY() - (fm.ascent + fm.descent) / 2, textPaint)
            return
        }
        for ((i, s) in popupItems.withIndex()) {
            val l = itemsLeft + i * itemW
            if (i == popupSelected) {
                paint.color = t.accent
                tmp.set(l + 1.5f * t.dp, bubble.top + 5f * t.dp, l + itemW - 1.5f * t.dp, bubble.bottom - 5f * t.dp)
                canvas.drawRoundRect(tmp, 8f * t.dp, 8f * t.dp, paint)
            }
            textPaint.color = if (i == popupSelected) 0xFFFFFFFF.toInt() else t.text
            textPaint.textSize = if (s.length > 2) 15f * t.sp else 26f * t.sp
            val fm = textPaint.fontMetrics
            canvas.drawText(s, l + itemW / 2, bubble.centerY() - (fm.ascent + fm.descent) / 2, textPaint)
        }
    }
}
