package com.example.glasskeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Transparent layer above the keyboard that draws the key preview "balloon" and the long-press
 * accent picker, with spring-like pop-in and soft fade-out animations. It may draw above the
 * keyboard sheet (into the transparent top margin of the input view). It never takes touches.
 */
class PopupOverlay(context: Context) : View(context) {
    var theme: GlassTheme? = null
    var animations = true

    /** One bubble (outline, shadow, the key it grows out of). */
    private class Bubble {
        val shape = Path()
        val shadow = Path()
        val key = RectF()
        val box = RectF()
        val bounds = RectF()
        var shownAt = 0L
        var hideAt = 0L // 0 = visible
    }

    private val preview = Bubble()
    private val popup = Bubble()
    private val tmpPath = Path()
    private val tmp = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val pop = OvershootInterpolator(1.6f)
    private val popSoft = OvershootInterpolator(1.1f)
    private val ease = DecelerateInterpolator()

    private var previewText: String? = null
    private var popupItems: List<String> = emptyList()
    private var popupSelected = 0
    private var selX = 0f
    private var itemW = 0f
    private var itemsLeft = 0f
    private var lastPreviewEnd = 0L

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

    private fun now() = SystemClock.uptimeMillis()

    fun showPreview(src: View, k: RectF, text: String) {
        val t = theme ?: return
        val o = offsetOf(src)
        val b = preview
        b.key.set(k.left + o.x, k.top + o.y, k.right + o.x, k.bottom + o.y)
        val extra = 9f * t.dp
        var l = b.key.left - extra
        var r = b.key.right + extra
        if (l < 2 * t.dp) {
            r += 2 * t.dp - l; l = 2 * t.dp
        }
        if (r > width - 2 * t.dp) {
            l -= r - (width - 2 * t.dp); r = width - 2 * t.dp
        }
        val h = b.key.height() * 1.15f
        b.box.set(l, b.key.top - h - b.key.height() * 0.12f, r, b.key.top - b.key.height() * 0.12f)
        buildShape(b, t)
        val n = now()
        // in quick succession (fast typing) the next balloon starts half-grown: snappy, not jumpy
        val visible = previewText != null && preview.hideAt == 0L
        b.shownAt = if (visible || n - lastPreviewEnd < 120) n - 55 else n
        b.hideAt = 0
        previewText = text
        postInvalidateOnAnimation()
    }

    fun hidePreview() {
        if (previewText == null || preview.hideAt != 0L) return
        lastPreviewEnd = now()
        if (animations) preview.hideAt = now() else previewText = null
        postInvalidateOnAnimation()
    }

    /** Shows the accent picker; returns the left edge of the first item and the item width, in [src] coordinates. */
    fun showPopup(src: View, k: RectF, items: List<String>): FloatArray {
        val t = theme ?: return floatArrayOf(k.left, k.width())
        val o = offsetOf(src)
        val b = popup
        b.key.set(k.left + o.x, k.top + o.y, k.right + o.x, k.bottom + o.y)
        itemW = maxOf(b.key.width(), 30f * t.dp)
        val pad = 5f * t.dp
        val w = items.size * itemW + 2 * pad
        var l = b.key.centerX() - itemW / 2 - pad
        if (l + w > width - 3 * t.dp) l = width - 3 * t.dp - w
        if (l < 3 * t.dp) l = 3 * t.dp
        val h = b.key.height() * 1.12f
        b.box.set(l, b.key.top - h - b.key.height() * 0.12f, l + w, b.key.top - b.key.height() * 0.12f)
        itemsLeft = l + pad
        buildShape(b, t)
        b.shownAt = now()
        b.hideAt = 0
        popupItems = items
        popupSelected = 0
        selX = itemsLeft
        postInvalidateOnAnimation()
        return floatArrayOf(itemsLeft - o.x, itemW)
    }

    fun selectPopup(i: Int) {
        if (i != popupSelected) {
            popupSelected = i
            if (!animations) selX = itemsLeft + i * itemW
            postInvalidateOnAnimation()
        }
    }

    fun popupItem(): String? = popupItems.getOrNull(popupSelected)

    fun hidePopup() {
        if (popupItems.isEmpty() || popup.hideAt != 0L) return
        if (animations) popup.hideAt = now() else popupItems = emptyList()
        postInvalidateOnAnimation()
    }

    fun hideAll() {
        previewText = null
        popupItems = emptyList()
        invalidate()
    }

    /** Bubble + neck + key, merged into one outline. */
    private fun buildShape(b: Bubble, t: GlassTheme) {
        val shape = b.shape
        val bubble = b.box
        val key = b.key
        shape.reset()
        val br = 12f * t.dp
        shape.addRoundRect(bubble, br, br, Path.Direction.CW)
        tmpPath.reset()
        tmpPath.addRoundRect(key, t.keyRadius, t.keyRadius, Path.Direction.CW)
        shape.op(tmpPath, Path.Op.UNION)
        tmpPath.reset()
        val nl = maxOf(bubble.left, key.left)
        val nr = minOf(bubble.right, key.right)
        val gap = key.top - bubble.bottom
        tmpPath.moveTo(bubble.left, bubble.bottom - br)
        tmpPath.cubicTo(bubble.left, bubble.bottom + gap * 0.4f, nl, key.top - gap * 0.4f, nl, key.top + t.keyRadius)
        tmpPath.lineTo(nr, key.top + t.keyRadius)
        tmpPath.cubicTo(nr, key.top - gap * 0.4f, bubble.right, bubble.bottom + gap * 0.4f, bubble.right, bubble.bottom - br)
        tmpPath.close()
        shape.op(tmpPath, Path.Op.UNION)
        b.shadow.set(shape)
        b.shadow.offset(0f, 2.5f * t.dp)
        b.bounds.set(bubble.left, bubble.top, bubble.right, key.bottom)
    }

    /** Returns scale and alpha of a bubble now, or null when it has finished hiding. */
    private fun state(b: Bubble, interp: OvershootInterpolator, appearMs: Float, from: Float): FloatArray? {
        val n = now()
        if (!animations) return floatArrayOf(1f, 1f)
        val a = ((n - b.shownAt) / appearMs).coerceIn(0f, 1f)
        var scale = from + (1f - from) * interp.getInterpolation(a)
        var alpha = (a * 2.2f).coerceAtMost(1f)
        if (b.hideAt != 0L) {
            val h = ((n - b.hideAt) / 120f).coerceIn(0f, 1f)
            if (h >= 1f) return null
            val e = ease.getInterpolation(h)
            alpha *= 1f - e
            scale *= 1f - 0.08f * e
        }
        return floatArrayOf(scale, alpha)
    }

    private fun drawBubble(canvas: Canvas, b: Bubble, t: GlassTheme, scale: Float, alpha: Float, pivotX: Float) {
        canvas.save()
        canvas.scale(scale, scale, pivotX, b.box.bottom)
        canvas.save()
        canvas.clipOutPath(b.shape)
        paint.color = t.popupShadow
        paint.alpha = (android.graphics.Color.alpha(t.popupShadow) * alpha).toInt()
        canvas.drawPath(b.shadow, paint)
        canvas.restore()
        t.drawShape(canvas, b.shape, b.bounds, t.popup, (255 * alpha).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme ?: return
        var again = false

        val text = previewText
        if (text != null) {
            val st = state(preview, pop, 120f, 0.5f)
            if (st == null) {
                previewText = null
            } else {
                drawBubble(canvas, preview, t, st[0], st[1], preview.key.centerX())
                textPaint.color = t.text
                textPaint.alpha = (255 * st[1]).toInt()
                textPaint.textSize = if (text.length > 2) 22f * t.sp else 34f * t.sp
                val fm = textPaint.fontMetrics
                canvas.drawText(text, preview.box.centerX(), preview.box.centerY() - (fm.ascent + fm.descent) / 2, textPaint)
                canvas.restore()
                if (animations && (now() - preview.shownAt < 140 || preview.hideAt != 0L)) again = true
            }
        }

        if (popupItems.isNotEmpty()) {
            val st = state(popup, popSoft, 200f, 0.6f)
            if (st == null) {
                popupItems = emptyList()
            } else {
                drawBubble(canvas, popup, t, st[0], st[1], popup.key.centerX())
                val target = itemsLeft + popupSelected * itemW
                if (animations) {
                    selX += (target - selX) * 0.42f
                    if (kotlin.math.abs(target - selX) > 0.5f) again = true else selX = target
                } else selX = target
                paint.color = t.accent
                paint.alpha = (255 * st[1]).toInt()
                tmp.set(selX + 1.5f * t.dp, popup.box.top + 5f * t.dp, selX + itemW - 1.5f * t.dp, popup.box.bottom - 5f * t.dp)
                canvas.drawRoundRect(tmp, 9f * t.dp, 9f * t.dp, paint)
                for ((i, s) in popupItems.withIndex()) {
                    val l = itemsLeft + i * itemW
                    val sel = i == popupSelected
                    textPaint.color = if (sel) 0xFFFFFFFF.toInt() else t.text
                    textPaint.alpha = (255 * st[1]).toInt()
                    textPaint.textSize = (if (s.length > 2) 15f else 26f) * t.sp
                    val fm = textPaint.fontMetrics
                    canvas.drawText(s, l + itemW / 2, popup.box.centerY() - (fm.ascent + fm.descent) / 2, textPaint)
                }
                canvas.restore()
                if (animations && (now() - popup.shownAt < 220 || popup.hideAt != 0L)) again = true
            }
        }
        if (again) postInvalidateOnAnimation()
    }
}
