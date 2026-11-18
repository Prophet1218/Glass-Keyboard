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
    var spaceLabel = "szóköz"
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
    private val stripHeight get() = if (showGlobe || showMic) 46f * dp else 8f * dp
    private val sidePad get() = 3f * dp
    private val gapH get() = 6f * dp
    private val gapV get() = 11f * dp * heightScale

    private class Pointer(var key: Key?, val downX: Float, val downY: Float) {
        var consumed = false
        var popupOpen = false
        var popupLeft = 0f
        var popupItemW = 1f
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

    /** Height of one row of the current layout. */
    private fun rh(): Float = maxOf(rowCount(), minRows) * rowHeight / rowCount()

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
        var y = topPad
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
        val s = 44f * dp
        globeKey.rect.set(sidePad + 6 * dp, stripTop, sidePad + 6 * dp + s, stripTop + minOf(s, stripHeight))
        micKey.rect.set(w - sidePad - 6 * dp - s, stripTop, w - sidePad - 6 * dp, stripTop + minOf(s, stripHeight))
    }

    private fun keyAt(x: Float, y: Float): Key? {
        val l = layout ?: return null
        if (l.rows.isEmpty()) return null
        val bottom = topPad + l.rows.size * rh()
        if (y >= bottom) {
            if (showGlobe && x < width * 0.25f) return globeKey
            if (showMic && x > width * 0.75f) return micKey
            if (y < bottom + 10 * dp) return nearestInRow(l.rows.last(), x)
            return null
        }
        var ri = ((y - topPad) / rh()).toInt()
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

    override fun onDraw(canvas: Canvas) {
        val l = layout ?: return
        val t = theme
        val pressed = HashSet<Key>()
        for (i in 0 until pointers.size()) pointers.valueAt(i).key?.let { if (!pointers.valueAt(i).consumed || it.type != KeyType.CHAR) pressed += it }
        for (row in l.rows) for (k in row.keys) drawKey(canvas, k, k in pressed, t)
        val iconSize = 22f * dp
        if (showGlobe) {
            if (globeKey in pressed) {
                t.fill.color = t.keySpecial
                canvas.drawCircle(globeKey.rect.centerX(), globeKey.rect.centerY(), 19 * dp, t.fill)
            }
            t.icon.color = t.textSoft
            Icons.globe(canvas, globeKey.rect.centerX(), globeKey.rect.centerY(), iconSize, t.icon)
        }
        if (showMic) {
            if (micKey in pressed) {
                t.fill.color = t.keySpecial
                canvas.drawCircle(micKey.rect.centerX(), micKey.rect.centerY(), 19 * dp, t.fill)
            }
            t.icon.color = t.textSoft
            Icons.mic(canvas, micKey.rect.centerX(), micKey.rect.centerY(), iconSize, t.icon)
        }
        t.icon.color = t.text
    }

    private fun drawKey(c: Canvas, k: Key, pressed: Boolean, t: GlassTheme) {
        val r = k.rect
        val color = when {
            k.type == KeyType.ENTER && enterAccent -> if (pressed) t.accentPressed else t.accent
            k.type == KeyType.SHIFT && shift > 0 -> t.keyActive
            k.special -> if (pressed) t.keySpecialPressed else t.keySpecial
            else -> if (pressed) t.keyPressed else t.key
        }
        t.drawKey(c, r, color)
        if (cursorMode) return
        val cx = r.centerX()
        val cy = r.centerY()
        val ink = if (k.type == KeyType.ENTER && enterAccent) 0xFFFFFFFF.toInt()
        else if (k.type == KeyType.SHIFT && shift > 0) t.activeIcon else t.text
        t.icon.color = ink
        t.iconFill.color = ink
        t.label.color = ink
        val iconSize = minOf(r.height() * 0.55f, 24f * dp)
        when (k.type) {
            KeyType.SHIFT -> Icons.shift(c, cx, cy, iconSize, t.icon, t.iconFill, shift > 0, shift == 2)
            KeyType.DELETE -> Icons.backspace(c, cx, cy, iconSize, t.icon)
            KeyType.EMOJI -> Icons.emoji(c, cx, cy, iconSize, t.icon, t.iconFill)
            KeyType.ENTER -> if (enterLabel.isEmpty()) Icons.enter(c, cx, cy, iconSize, t.icon) else text(c, enterLabel, cx, cy, 16f, t)
            KeyType.SPACE -> {
                t.label.color = if (enterAccent) t.text else t.text
                text(c, spaceLabel, cx, cy, 16f, t)
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
            val idx = ((x - p.popupLeft) / p.popupItemW).toInt().coerceIn(0, (key?.popup?.size ?: 1) - 1)
            overlay.selectPopup(idx)
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
