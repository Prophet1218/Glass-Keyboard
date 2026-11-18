package com.example.glasskeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

interface KeyboardActions {
    fun onText(text: String)
    fun onDelete(repeat: Int)
    fun onDeleteUp()
    fun onSpace()
    fun onEnter()
    fun onShiftTap()
    fun onShiftChordEnd()
    fun onLayout(kind: LayoutKind)
    fun onEmoji()
    fun onGlobe()
    fun onGlobeLong()
    fun onMic()
    fun onCursor(dx: Int, dy: Int)
    fun onCursorModeEnd()
    fun feedback(key: Key)
}

/**
 * Draws the keys and turns touches into key actions: multi-touch roll-over typing, key preview
 * balloons, long-press accents, repeating delete, space-bar trackpad, and the iOS trick of
 * sliding from 123 to a symbol and releasing.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val actions: KeyboardActions, private val overlay: PopupOverlay) : View(context) {

    var theme: GlassTheme = GlassTheme(context, 0)
        set(v) {
            field = v
            invalidate()
        }

    var layout: KbLayout? = null
        set(v) {
            if (field != null && field !== v) {
                layoutChangedAt = SystemClock.uptimeMillis()
                if (animations) captureSnapshot()
            }
            field = v
            requestLayout()
            layoutKeys()
            invalidate()
        }

    /** 0 = off, 1 = on (next letter), 2 = caps lock */
    var shift = 0
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }
    var locale: Locale = Locale("hu", "HU")
    var spaceLabel = ""
        set(v) {
            field = v
            invalidate()
        }
    var enterLabel = ""
        set(v) {
            field = v
            invalidate()
        }
    var enterAccent = false
        set(v) {
            field = v
            invalidate()
        }
    var showGlobe = true
    var showMic = true
    var previews = true
    var spaceCursor = true
    var heightScale = 1f
    var animations = true
    /** height of the strip under the keys (globe, mic), dp */
    var stripDp = 46f
    private var layoutChangedAt = 0L
    private val pressStart = HashMap<Key, Long>()
    private val releaseAt = HashMap<Key, Long>()
    private val globeIcon = context.getDrawable(R.drawable.ic_globe)!!.mutate()
    private val micIcon = context.getDrawable(R.drawable.ic_mic)!!.mutate()
    /** Rows of the letter layout: other layouts stretch their rows to the same total height. */
    var minRows = 4

    private val dp = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val globeKey = Key(KeyType.GLOBE, "")
    private val micKey = Key(KeyType.MIC, "")
    private val rowTops = ArrayList<Float>()
    private var cursorMode = false

    private val rowHeight get() = 54f * dp * heightScale
    private val topPad get() = 4f * dp
    private val stripHeight get() = if (showGlobe || showMic) stripDp * dp else 8f * dp
    private val sidePad get() = 3f * dp
    private val gapH get() = 6f * dp
    private val gapV get() = 11f * dp * heightScale

    private class Pointer(var key: Key?, val downX: Float, val downY: Float) {
        var consumed = false
        var popupOpen = false
        var popupLeft = 0f
        var popupItemW = 1f
        var popupReversed = false
        var longPressed = false
        var switchedFrom: LayoutKind? = null
        var cursorAccX = 0f
        var cursorAccY = 0f
        var lastX = downX
        var lastY = downY
        var repeat = 0
        var timer: Runnable? = null
    }

    private val pointers = SparseArray<Pointer>()
    private var shiftHeld = false
    private var typedDuringShift = false

    private fun rowCount() = layout?.rows?.size ?: 4

    /** Height of one row (the same on every layout: keys never grow on the 123 pages). */
    private fun rh(): Float = rowHeight

    /** Layouts with fewer rows than the letters keep the bottom aligned; the gap goes on top. */
    private fun rowsTop(): Float = topPad + maxOf(0, minRows - rowCount()) * rowHeight

    fun desiredHeight(): Int = (topPad + maxOf(rowCount(), minRows) * rowHeight + stripHeight).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), desiredHeight())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutKeys()
    }

    private fun layoutKeys() {
        val l = layout ?: return
        val w = width.toFloat()
        if (w <= 0) return
        val unit = (w - 2 * sidePad) / 10f
        val h = rh()
        rowTops.clear()
        var y = rowsTop()
        for (row in l.rows) {
            rowTops += y
            var x = sidePad + row.pad * unit
            for (k in row.keys) {
                val kw = k.width * unit
                k.rect.set(x + gapH / 2, y + gapV / 2, x + kw - gapH / 2, y + h - gapV / 2)
                x += kw
            }
            y += h
        }
        val stripTop = y
        val s = 48f * dp
        val sh = stripHeight
        // icons sit in the upper part of the strip, like on iOS (the lower part is thumb room)
        val iconBand = minOf(sh, 46f * dp)
        globeKey.rect.set(sidePad + 8 * dp, stripTop, sidePad + 8 * dp + s, stripTop + iconBand)
        micKey.rect.set(w - sidePad - 8 * dp - s, stripTop, w - sidePad - 8 * dp, stripTop + iconBand)
    }

    private fun keyAt(x: Float, y: Float): Key? {
        val l = layout ?: return null
        if (l.rows.isEmpty()) return null
        val bottom = rowsTop() + l.rows.size * rh()
        if (y >= bottom) {
            if (showGlobe && x < width * 0.25f) return globeKey
            if (showMic && x > width * 0.75f) return micKey
            if (y < bottom + 10 * dp) return nearestInRow(l.rows.last(), x)
            return null
        }
        if (y < rowsTop() - 6 * dp) return null
        var ri = ((y - rowsTop()) / rh()).toInt()
        ri = ri.coerceIn(0, l.rows.size - 1)
        return nearestInRow(l.rows[ri], x)
    }

    private fun nearestInRow(row: KbRow, x: Float): Key? {
        var best: Key? = null
        var bd = Float.MAX_VALUE
        for (k in row.keys) {
            if (x >= k.rect.left - gapH / 2 && x <= k.rect.right + gapH / 2) return k
            val d = minOf(abs(x - k.rect.left), abs(x - k.rect.right))
            if (d < bd) {
                bd = d; best = k
            }
        }
        return best
    }

    // ------------------------------------------------------------------ drawing

    /** 0..1: how "pressed" a key looks right now (animated in and out). */
    private fun pressProgress(k: Key, pressed: Boolean, now: Long): Float {
        if (pressed) {
            releaseAt.remove(k)
            val st = pressStart.getOrPut(k) { now }
            return if (!animations) 1f else ((now - st) / 60f).coerceIn(0f, 1f)
        }
        if (pressStart.remove(k) != null && animations) releaseAt[k] = now
        val u = releaseAt[k] ?: return 0f
        val q = 1f - (now - u) / 180f
        if (q <= 0f) {
            releaseAt.remove(k)
            return 0f
        }
        return q * q
    }

    private var labelAlpha = 255
    private var animating = false
    private var snapshot: android.graphics.Bitmap? = null
    private var snapshotOn = false
    private val snapPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val switchMs = 210f
    private val decel = android.view.animation.DecelerateInterpolator(1.6f)

    /** Freezes the current keys into a bitmap, faded out over the new layout (123 ⇄ ABC). */
    private fun captureSnapshot() {
        if (width <= 0 || height <= 0 || layout == null) return
        var b = snapshot
        if (b == null || b.width != width || b.height != height) {
            b = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            snapshot = b
        }
        b.eraseColor(0)
        val c = Canvas(b)
        labelAlpha = 255
        for (row in layout!!.rows) for (k in row.keys) drawKey(c, k, 0f, theme)
        snapshotOn = true
    }

    override fun onDraw(canvas: Canvas) {
        val l = layout ?: return
        val t = theme
        val now = SystemClock.uptimeMillis()
        animating = false
        val pressed = HashSet<Key>()
        for (i in 0 until pointers.size()) pointers.valueAt(i).key?.let { if (!pointers.valueAt(i).consumed || it.type != KeyType.CHAR) pressed += it }
        val since = now - layoutChangedAt
        val sw = if (!animations || since >= switchMs) 1f else decel.getInterpolation(since / switchMs)
        labelAlpha = (255 * sw).toInt()
        if (sw < 1f) animating = true
        if (sw < 1f && snapshotOn) {
            // the new keys rise slightly into place while the old page fades away
            canvas.save()
            canvas.translate(0f, (1f - sw) * 6 * dp)
        }
        for (row in l.rows) for (k in row.keys) {
            val p = pressProgress(k, k in pressed, now)
            if (p > 0f && p < 1f) animating = true
            if (k in pressed && p < 1f) animating = true
            drawKey(canvas, k, p, t)
        }
        if (sw < 1f && snapshotOn) canvas.restore()
        val snap = snapshot
        if (snapshotOn && snap != null) {
            if (sw >= 1f) snapshotOn = false
            else {
                snapPaint.alpha = (255 * (1f - sw)).toInt()
                canvas.drawBitmap(snap, 0f, 0f, snapPaint)
            }
        }
        if (showGlobe) drawStripIcon(canvas, globeKey, globeIcon, pressProgress(globeKey, globeKey in pressed, now), t)
        if (showMic) drawStripIcon(canvas, micKey, micIcon, pressProgress(micKey, micKey in pressed, now), t)
        t.icon.color = t.text
        if (animating || releaseAt.isNotEmpty()) postInvalidateOnAnimation()
    }

    private fun drawStripIcon(c: Canvas, k: Key, d: android.graphics.drawable.Drawable, p: Float, t: GlassTheme) {
        val cx = k.rect.centerX()
        val cy = k.rect.centerY()
        if (p > 0f) {
            t.fill.shader = null
            t.fill.color = t.keySpecial
            t.fill.alpha = (android.graphics.Color.alpha(t.keySpecial) * p).toInt()
            c.drawCircle(cx, cy, (16 + 4 * p) * dp, t.fill)
            t.fill.alpha = 255
        }
        val size = (minOf(26f * dp, k.rect.height() * 0.62f) * (1f - 0.08f * p)).toInt()
        d.setTint(t.stripIcon)
        d.setBounds((cx - size / 2f).toInt(), (cy - size / 2f).toInt(), (cx + size / 2f).toInt(), (cy + size / 2f).toInt())
        d.draw(c)
    }

    private fun drawKey(c: Canvas, k: Key, p: Float, t: GlassTheme) {
        val r = k.rect
        val color = when {
            k.type == KeyType.ENTER && enterAccent -> GlassTheme.lerpColor(t.accent, t.accentPressed, p)
            k.type == KeyType.SHIFT && shift > 0 -> t.keyActive
            k.special -> GlassTheme.lerpColor(t.keySpecial, t.keySpecialPressed, p)
            else -> GlassTheme.lerpColor(t.key, t.keyPressed, p)
        }
        val scale = if (animations) 1f + 0.04f * p else 1f
        c.save()
        if (scale != 1f) c.scale(scale, scale, r.centerX(), r.centerY())
        t.drawKey(c, r, color)
        if (!cursorMode) drawLabel(c, k, t)
        c.restore()
    }

    private fun drawLabel(c: Canvas, k: Key, t: GlassTheme) {
        val r = k.rect
        val cx = r.centerX()
        val cy = r.centerY()
        val ink = if (k.type == KeyType.ENTER && enterAccent) 0xFFFFFFFF.toInt()
        else if (k.type == KeyType.SHIFT && shift > 0) t.activeIcon else t.text
        t.icon.color = ink
        t.iconFill.color = ink
        t.label.color = ink
        t.icon.alpha = labelAlpha
        t.iconFill.alpha = labelAlpha
        t.label.alpha = labelAlpha
        val iconSize = minOf(r.height() * 0.55f, 24f * dp)
        when (k.type) {
            KeyType.SHIFT -> Icons.shift(c, cx, cy, iconSize, t.icon, t.iconFill, shift > 0, shift == 2)
            KeyType.DELETE -> Icons.backspace(c, cx, cy, iconSize, t.icon)
            KeyType.EMOJI -> Icons.emoji(c, cx, cy, iconSize, t.icon, t.iconFill)
            KeyType.ENTER -> if (enterLabel.isEmpty()) Icons.enter(c, cx, cy, iconSize, t.icon) else {
                // the return key is narrower now: shrink long labels ("Keresés") to fit
                t.label.textSize = 16f * t.sp
                val fit = (r.width() - 10 * dp) / maxOf(1f, t.label.measureText(enterLabel))
                text(c, enterLabel, cx, cy, 16f * minOf(1f, fit), t)
            }
            KeyType.SPACE -> if (spaceLabel.isNotEmpty()) {
                // only shown briefly after switching language
                t.label.color = t.textSoft
                t.label.alpha = labelAlpha * 255 / 255
                text(c, spaceLabel, cx, cy, 15f, t)
            }
            KeyType.TO_SYMBOLS, KeyType.TO_LETTERS, KeyType.TO_MORE -> text(c, k.label, cx, cy, 16f, t)
            else -> {
                var s = k.label
                if (k.type == KeyType.CHAR && s == " ") s = "␣"
                val letter = s.length == 1 && s[0].isLetter()
                if (letter && shift > 0) s = s.uppercase(locale)
                val size = when {
                    s.length > 2 -> 16f
                    l2(s) -> 20f
                    letter -> 24f
                    else -> 23f
                }
                // lowercase letters sit a little lower, like on iOS
                text(c, s, cx, cy + (if (letter && shift == 0) -1.5f * dp else 0f), size, t)
            }
        }
    }

    private fun l2(s: String) = s.length == 2

    private fun text(c: Canvas, s: String, cx: Float, cy: Float, sizeSp: Float, t: GlassTheme) {
        t.label.textSize = sizeSp * t.sp
        val fm = t.label.fontMetrics
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2, t.label)
    }

    // ------------------------------------------------------------------ touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                up(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_CANCEL -> cancelAll()
        }
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        val key = keyAt(x, y) ?: return
        // roll-over typing: a new finger commits the keys still held by the others
        for (i in 0 until pointers.size()) {
            val o = pointers.valueAt(i)
            val ok = o.key
            if (ok != null && !o.consumed && !o.popupOpen && ok.type == KeyType.CHAR) {
                commitChar(ok.output)
                o.consumed = true
                cancelTimer(o)
            }
        }
        val p = Pointer(key, x, y)
        pointers.put(id, p)
        actions.feedback(key)
        when (key.type) {
            KeyType.CHAR -> {
                if (previews && layout?.kind != LayoutKind.NUMBER && layout?.kind != LayoutKind.PHONE) {
                    overlay.showPreview(this, key.rect, displayText(key.label))
                }
                if (key.popup.isNotEmpty()) schedule(p, 380) { openPopup(p) }
            }
            KeyType.DELETE -> {
                actions.onDelete(0)
                schedule(p, 400) { repeatDelete(p) }
            }
            KeyType.SHIFT -> {
                actions.onShiftTap()
                shiftHeld = true
                typedDuringShift = false
            }
            KeyType.TO_SYMBOLS, KeyType.TO_LETTERS, KeyType.TO_MORE -> {
                val from = layout?.kind
                actions.onLayout(
                    when (key.type) {
                        KeyType.TO_SYMBOLS -> LayoutKind.SYMBOLS
                        KeyType.TO_MORE -> LayoutKind.MORE
                        else -> LayoutKind.LETTERS
                    }
                )
                p.switchedFrom = from
                p.key = keyAt(x, y)
            }
            KeyType.SPACE -> if (spaceCursor) schedule(p, 450) { startCursor(p) }
            KeyType.GLOBE -> schedule(p, 500) {
                p.longPressed = true
                actions.onGlobeLong()
            }
            else -> {}
        }
        invalidate()
    }

    private fun displayText(s: String): String {
        val letter = s.length == 1 && s[0].isLetter()
        return if (letter && shift > 0) s.uppercase(locale) else s
    }

    private fun move(id: Int, x: Float, y: Float) {
        val p = pointers.get(id) ?: return
        val key = p.key
        if (p.popupOpen) {
            val n = key?.popup?.size ?: 1
            val vis = ((x - p.popupLeft) / p.popupItemW).toInt().coerceIn(0, n - 1)
            overlay.selectPopup(if (p.popupReversed) n - 1 - vis else vis)
            return
        }
        if (cursorMode) {
            p.cursorAccX += x - p.lastX
            p.cursorAccY += y - p.lastY
            p.lastX = x
            p.lastY = y
            val stepX = 9f * dp
            val stepY = 26f * dp
            val dx = (p.cursorAccX / stepX).toInt()
            val dy = (p.cursorAccY / stepY).toInt()
            if (dx != 0 || dy != 0) {
                p.cursorAccX -= dx * stepX
                p.cursorAccY -= dy * stepY
                actions.onCursor(dx, dy)
            }
            return
        }
        p.lastX = x
        p.lastY = y
        if (key?.type == KeyType.SPACE && spaceCursor && abs(x - p.downX) > 14 * dp) {
            startCursor(p)
            return
        }
        if (p.consumed) return
        if (key?.type == KeyType.CHAR || p.switchedFrom != null) {
            // the pressed key follows the finger (fixes near-misses before release)
            val k = keyAt(x, y)
            if (k != null && k !== key && (k.type == KeyType.CHAR || p.switchedFrom != null)) {
                if (hypot(x - p.downX, y - p.downY) > 6 * dp) {
                    cancelTimer(p)
                    p.key = k
                    if (k.type == KeyType.CHAR) {
                        if (previews && layout?.kind != LayoutKind.NUMBER && layout?.kind != LayoutKind.PHONE) overlay.showPreview(this, k.rect, displayText(k.label))
                        if (k.popup.isNotEmpty()) schedule(p, 380) { openPopup(p) }
                    } else overlay.hidePreview()
                    invalidate()
                }
            }
        }
    }

    private fun up(id: Int, x: Float, y: Float) {
        val p = pointers.get(id) ?: return
        pointers.remove(id)
        cancelTimer(p)
        val key = p.key
        if (p.popupOpen) {
            overlay.popupItem()?.let { commitChar(it) }
            overlay.hidePopup()
            invalidate()
            return
        }
        if (cursorMode && key?.type == KeyType.SPACE) {
            cursorMode = false
            actions.onCursorModeEnd()
            invalidate()
            return
        }
        overlay.hidePreview()
        if (key == null) {
            invalidate(); return
        }
        if (p.switchedFrom != null) {
            // slid from 123/ABC onto a key: type it and go back
            if (key.type == KeyType.CHAR) {
                commitChar(key.output)
                actions.onLayout(p.switchedFrom!!)
            } else if (key.type != KeyType.TO_SYMBOLS && key.type != KeyType.TO_LETTERS && key.type != KeyType.TO_MORE) {
                fire(key)
            }
            invalidate()
            return
        }
        when (key.type) {
            KeyType.CHAR, KeyType.TEXT -> if (!p.consumed) commitChar(key.output)
            KeyType.DELETE -> actions.onDeleteUp()
            KeyType.SHIFT -> {
                shiftHeld = false
                if (typedDuringShift) actions.onShiftChordEnd()
            }
            KeyType.GLOBE -> if (!p.longPressed) actions.onGlobe()
            KeyType.TO_SYMBOLS, KeyType.TO_LETTERS, KeyType.TO_MORE -> {}
            else -> fire(key)
        }
        invalidate()
    }

    private fun fire(key: Key) {
        when (key.type) {
            KeyType.SPACE -> actions.onSpace()
            KeyType.ENTER -> actions.onEnter()
            KeyType.EMOJI -> actions.onEmoji()
            KeyType.MIC -> actions.onMic()
            KeyType.GLOBE -> actions.onGlobe()
            KeyType.CHAR, KeyType.TEXT -> commitChar(key.output)
            else -> {}
        }
    }

    private fun commitChar(s: String) {
        val letter = s.isNotEmpty() && s[0].isLetter()
        actions.onText(if (letter && shift > 0) s.uppercase(locale) else s)
        if (shiftHeld) typedDuringShift = true
    }

    fun cancelAll() {
        for (i in 0 until pointers.size()) cancelTimer(pointers.valueAt(i))
        pointers.clear()
        shiftHeld = false
        if (cursorMode) {
            cursorMode = false
            actions.onCursorModeEnd()
        }
        overlay.hideAll()
        invalidate()
    }

    private fun schedule(p: Pointer, delay: Long, block: () -> Unit) {
        cancelTimer(p)
        val r = Runnable { block() }
        p.timer = r
        handler.postDelayed(r, delay)
    }

    private fun cancelTimer(p: Pointer) {
        p.timer?.let { handler.removeCallbacks(it) }
        p.timer = null
    }

    private fun openPopup(p: Pointer) {
        val key = p.key ?: return
        if (p.consumed || key.popup.isEmpty()) return
        overlay.hidePreview()
        val items = key.popup.map { displayText(it) }
        val g = overlay.showPopup(this, key.rect, items)
        p.popupLeft = g[0]
        p.popupItemW = g[1]
        p.popupReversed = g.size > 2 && g[2] > 0.5f
        p.popupOpen = true
        actions.feedback(key)
    }

    private fun repeatDelete(p: Pointer) {
        p.repeat++
        actions.onDelete(p.repeat)
        val next = if (p.repeat > 18) 110L else 55L // word-wise after a while: slower pace
        schedule(p, next) { repeatDelete(p) }
    }

    private fun startCursor(p: Pointer) {
        if (cursorMode) return
        cancelTimer(p)
        cursorMode = true
        p.lastX = p.lastX
        p.cursorAccX = 0f
        p.cursorAccY = 0f
        actions.feedback(p.key ?: return)
        invalidate()
    }

    @Suppress("unused")
    private fun now() = SystemClock.uptimeMillis()
}
