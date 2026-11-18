package com.example.glasskeyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Key icons drawn with paths, so they stay sharp at every size and follow the theme colour. */
object Icons {
    private val p = Path()
    private val r = RectF()

    /** iOS-style shift arrow; [filled] when shift is on, [lock] adds the caps-lock bar. */
    fun shift(c: Canvas, cx: Float, cy: Float, s: Float, stroke: Paint, fill: Paint, filled: Boolean, lock: Boolean) {
        val top = cy - s * 0.48f - (if (lock) s * 0.08f else 0f)
        val mid = top + s * 0.48f
        val bottom = mid + s * 0.36f
        p.reset()
        p.moveTo(cx, top)
        p.lineTo(cx + s * 0.48f, mid)
        p.lineTo(cx + s * 0.22f, mid)
        p.lineTo(cx + s * 0.22f, bottom)
        p.lineTo(cx - s * 0.22f, bottom)
        p.lineTo(cx - s * 0.22f, mid)
        p.lineTo(cx - s * 0.48f, mid)
        p.close()
        c.drawPath(p, if (filled) fill else stroke)
        if (filled) c.drawPath(p, stroke)
        if (lock) {
            r.set(cx - s * 0.22f, bottom + s * 0.1f, cx + s * 0.22f, bottom + s * 0.2f)
            c.drawRect(r, fill)
        }
    }

    fun backspace(c: Canvas, cx: Float, cy: Float, s: Float, stroke: Paint) {
        val h = s * 0.36f
        val l = cx - s * 0.55f
        val k = cx - s * 0.22f
        val rt = cx + s * 0.5f
        p.reset()
        p.moveTo(l, cy)
        p.lineTo(k, cy - h)
        p.lineTo(rt - s * 0.06f, cy - h)
        p.quadTo(rt, cy - h, rt, cy - h + s * 0.06f)
        p.lineTo(rt, cy + h - s * 0.06f)
        p.quadTo(rt, cy + h, rt - s * 0.06f, cy + h)
        p.lineTo(k, cy + h)
        p.close()
        c.drawPath(p, stroke)
        val xc = cx + s * 0.13f
        val xs = s * 0.13f
        c.drawLine(xc - xs, cy - xs, xc + xs, cy + xs, stroke)
        c.drawLine(xc - xs, cy + xs, xc + xs, cy - xs, stroke)
    }

    fun globe(c: Canvas, cx: Float, cy: Float, s: Float, stroke: Paint) {
        val rad = s * 0.46f
        c.drawCircle(cx, cy, rad, stroke)
        r.set(cx - rad * 0.45f, cy - rad, cx + rad * 0.45f, cy + rad)
        c.drawOval(r, stroke)
        c.drawLine(cx - rad, cy, cx + rad, cy, stroke)
        c.drawLine(cx, cy - rad, cx, cy + rad, stroke)
        val y1 = cy - rad * 0.5f
        val y2 = cy + rad * 0.5f
        val w = rad * 0.866f
        c.drawLine(cx - w, y1, cx + w, y1, stroke)
        c.drawLine(cx - w, y2, cx + w, y2, stroke)
    }

    fun mic(c: Canvas, cx: Float, cy: Float, s: Float, stroke: Paint) {
        r.set(cx - s * 0.15f, cy - s * 0.48f, cx + s * 0.15f, cy + s * 0.1f)
        c.drawRoundRect(r, s * 0.15f, s * 0.15f, stroke)
        r.set(cx - s * 0.3f, cy - s * 0.3f, cx + s * 0.3f, cy + s * 0.28f)
        c.drawArc(r, 0f, 180f, false, stroke)
        c.drawLine(cx, cy + s * 0.28f, cx, cy + s * 0.46f, stroke)
        c.drawLine(cx - s * 0.16f, cy + s * 0.46f, cx + s * 0.16f, cy + s * 0.46f, stroke)
    }

    fun emoji(c: Canvas, cx: Float, cy: Float, s: Float, stroke: Paint, fill: Paint) {
        val rad = s * 0.46f
        c.drawCircle(cx, cy, rad, stroke)
        c.drawCircle(cx - rad * 0.36f, cy - rad * 0.22f, s * 0.055f, fill)
        c.drawCircle(cx + rad * 0.36f, cy - rad * 0.22f, s * 0.055f, fill)
        r.set(cx - rad * 0.52f, cy - rad * 0.2f, cx + rad * 0.52f, cy + rad * 0.55f)
        c.drawArc(r, 20f, 140f, false, stroke)
    }

    fun enter(c: Canvas, cx: Float, cy: Float, s: Float, stroke: Paint) {
        val x2 = cx + s * 0.4f
        val y1 = cy - s * 0.32f
        val y2 = cy + s * 0.12f
        val x1 = cx - s * 0.4f
        p.reset()
        p.moveTo(x2, y1)
        p.lineTo(x2, y2)
        p.lineTo(x1, y2)
        c.drawPath(p, stroke)
        p.reset()
        p.moveTo(x1 + s * 0.2f, y2 - s * 0.2f)
        p.lineTo(x1, y2)
        p.lineTo(x1 + s * 0.2f, y2 + s * 0.2f)
        c.drawPath(p, stroke)
    }

    fun chevronDown(c: Canvas, cx: Float, cy: Float, s: Float, stroke: Paint) {
        p.reset()
        p.moveTo(cx - s * 0.35f, cy - s * 0.15f)
        p.lineTo(cx, cy + s * 0.18f)
        p.lineTo(cx + s * 0.35f, cy - s * 0.15f)
        c.drawPath(p, stroke)
    }

    fun chevronRight(c: Canvas, cx: Float, cy: Float, s: Float, stroke: Paint) {
        p.reset()
        p.moveTo(cx - s * 0.18f, cy - s * 0.38f)
        p.lineTo(cx + s * 0.2f, cy)
        p.lineTo(cx - s * 0.18f, cy + s * 0.38f)
        c.drawPath(p, stroke)
    }

    fun clipboard(c: Canvas, cx: Float, cy: Float, s: Float, stroke: Paint) {
        r.set(cx - s * 0.34f, cy - s * 0.36f, cx + s * 0.34f, cy + s * 0.46f)
        c.drawRoundRect(r, s * 0.08f, s * 0.08f, stroke)
        r.set(cx - s * 0.16f, cy - s * 0.48f, cx + s * 0.16f, cy - s * 0.28f)
        c.drawRoundRect(r, s * 0.05f, s * 0.05f, stroke)
        c.drawLine(cx - s * 0.18f, cy - s * 0.02f, cx + s * 0.18f, cy - s * 0.02f, stroke)
        c.drawLine(cx - s * 0.18f, cy + s * 0.18f, cx + s * 0.1f, cy + s * 0.18f, stroke)
    }

    fun gear(c: Canvas, cx: Float, cy: Float, s: Float, stroke: Paint) {
        c.drawCircle(cx, cy, s * 0.15f, stroke)
        c.drawCircle(cx, cy, s * 0.31f, stroke)
        for (i in 0 until 8) {
            val a = Math.PI * i / 4
            val ca = Math.cos(a).toFloat()
            val sa = Math.sin(a).toFloat()
            c.drawLine(cx + ca * s * 0.31f, cy + sa * s * 0.31f, cx + ca * s * 0.46f, cy + sa * s * 0.46f, stroke)
        }
    }

    /** keyboard with a down chevron (hide keyboard) */
    fun hideKeyboard(c: Canvas, cx: Float, cy: Float, s: Float, stroke: Paint) {
        r.set(cx - s * 0.46f, cy - s * 0.42f, cx + s * 0.46f, cy + s * 0.14f)
        c.drawRoundRect(r, s * 0.08f, s * 0.08f, stroke)
        for (row in 0..1) for (k in 0..3) {
            val x = cx - s * 0.27f + k * s * 0.18f
            val y = cy - s * 0.26f + row * s * 0.15f
            c.drawPoint(x, y, stroke)
        }
        c.drawLine(cx - s * 0.18f, cy - s * 0.02f, cx + s * 0.18f, cy - s * 0.02f, stroke)
        p.reset()
        p.moveTo(cx - s * 0.14f, cy + s * 0.28f)
        p.lineTo(cx, cy + s * 0.42f)
        p.lineTo(cx + s * 0.14f, cy + s * 0.28f)
        c.drawPath(p, stroke)
    }

    /** "GIF" in a rounded frame */
    fun gif(c: Canvas, cx: Float, cy: Float, s: Float, stroke: Paint, fill: Paint) {
        r.set(cx - s * 0.5f, cy - s * 0.34f, cx + s * 0.5f, cy + s * 0.34f)
        c.drawRoundRect(r, s * 0.12f, s * 0.12f, stroke)
        val old = fill.textAlign
        val oldSize = fill.textSize
        val oldTf = fill.typeface
        fill.textAlign = Paint.Align.CENTER
        fill.textSize = s * 0.4f
        fill.typeface = android.graphics.Typeface.DEFAULT_BOLD
        val fm = fill.fontMetrics
        c.drawText("GIF", cx, cy - (fm.ascent + fm.descent) / 2, fill)
        fill.textAlign = old
        fill.textSize = oldSize
        fill.typeface = oldTf
    }

    /** Magnifying glass. */
    fun search(c: Canvas, cx: Float, cy: Float, s: Float, stroke: Paint) {
        val rr = s * 0.3f
        val ox = cx - s * 0.08f
        val oy = cy - s * 0.08f
        c.drawCircle(ox, oy, rr, stroke)
        val d = rr * 0.7071f
        c.drawLine(ox + d, oy + d, cx + s * 0.42f, cy + s * 0.42f, stroke)
    }

    /** Arrow pointing left (back). */
    fun back(c: Canvas, cx: Float, cy: Float, s: Float, stroke: Paint) {
        p.reset()
        p.moveTo(cx + s * 0.1f, cy - s * 0.36f)
        p.lineTo(cx - s * 0.26f, cy)
        p.lineTo(cx + s * 0.1f, cy + s * 0.36f)
        c.drawPath(p, stroke)
    }

    /** × */
    fun close(c: Canvas, cx: Float, cy: Float, s: Float, stroke: Paint) {
        val h = s * 0.28f
        c.drawLine(cx - h, cy - h, cx + h, cy + h, stroke)
        c.drawLine(cx - h, cy + h, cx + h, cy - h, stroke)
    }
}
