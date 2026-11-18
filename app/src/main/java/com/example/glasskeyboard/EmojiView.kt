package com.example.glasskeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.ceil

interface EmojiActions {
    fun onEmojiPicked(e: String)
    fun onEmojiBack()
    fun onEmojiDelete(repeat: Int)
    fun feedbackLight()
}

/**
 * iOS-style emoji keyboard: one long, horizontally scrolling grid (5 rows) with every category
 * after each other, a section title that sticks to the left, and a category bar at the bottom.
 */
@SuppressLint("ViewConstructor")
class EmojiView(context: Context, private val actions: EmojiActions, private val prefs: Prefs) : View(context) {

    class Section(val id: String, val icon: String, var items: List<String>) {
        var startCol = 0
        var cols = 0
    }

    var theme: GlassTheme = GlassTheme(context, 0)
        set(v) {
            field = v
            invalidate()
        }
    var hungarian = true
        set(v) {
            field = v
            invalidate()
        }
    var fixedHeight = 0

    private val dp = resources.displayMetrics.density
    private val sections = ArrayList<Section>()
    private val recent = Section("recent", "🕘", emptyList())
    private val emojiPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())
    private var scrollPos = 0f
    private var totalCols = 0
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false
    private var downInBar = false
    private var pressedBar = -1
    private var deleteRepeat = 0
    private val deleteRunnable = object : Runnable {
        override fun run() {
            deleteRepeat++
            actions.onEmojiDelete(deleteRepeat)
            handler.postDelayed(this, 60)
        }
    }
    private val tmp = RectF()

    private val rows = 5
    private val barH get() = 46f * dp
    private val titleH get() = 24f * dp
    private val cellW get() = width / 8.4f
    private val cellH get() = (height - barH - titleH - 4 * dp) / rows

    init {
        loadSections()
    }

    private fun loadSections() {
        val icons = mapOf(
            "smileys" to "😀", "animals" to "🐻", "food" to "🍔", "activity" to "⚽",
            "travel" to "🚗", "objects" to "💡", "symbols" to "🔣", "flags" to "🏳️",
        )
        sections.clear()
        recent.items = prefs.recentEmoji.split(" ").filter { it.isNotEmpty() }
        sections += recent
        try {
            context.assets.open("emoji.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val id = line.substring(0, tab)
                    sections += Section(id, icons[id] ?: "•", line.substring(tab + 1).split(" ").filter { it.isNotEmpty() })
                }
            }
        } catch (_: Exception) {
        }
        computeColumns()
    }

    private fun computeColumns() {
        var col = 0
        for (s in sections) {
            s.startCol = col
            s.cols = if (s.items.isEmpty()) 0 else ceil(s.items.size / rows.toFloat()).toInt()
            col += s.cols + if (s.cols > 0) 1 else 0
        }
        totalCols = col
    }

    private fun title(id: String): String = if (hungarian) when (id) {
        "recent" -> "GYAKRAN HASZNÁLT"
        "smileys" -> "SMILEYK ÉS EMBEREK"
        "animals" -> "ÁLLATOK ÉS TERMÉSZET"
        "food" -> "ÉTELEK ÉS ITALOK"
        "activity" -> "TEVÉKENYSÉGEK"
        "travel" -> "UTAZÁS ÉS HELYEK"
        "objects" -> "TÁRGYAK"
        "symbols" -> "SZIMBÓLUMOK"
        else -> "ZÁSZLÓK"
    } else when (id) {
        "recent" -> "FREQUENTLY USED"
        "smileys" -> "SMILEYS & PEOPLE"
        "animals" -> "ANIMALS & NATURE"
        "food" -> "FOOD & DRINK"
        "activity" -> "ACTIVITY"
        "travel" -> "TRAVEL & PLACES"
        "objects" -> "OBJECTS"
        "symbols" -> "SYMBOLS"
        else -> "FLAGS"
    }

    fun addRecent(e: String) {
        val l = ArrayList(recent.items)
        l.remove(e)
        l.add(0, e)
        while (l.size > 30) l.removeAt(l.size - 1)
        prefs.recentEmoji = l.joinToString(" ")
    }

    /** Reload recents when the panel is opened (so the newest is first). */
    fun refresh() {
        recent.items = prefs.recentEmoji.split(" ").filter { it.isNotEmpty() }
        computeColumns()
        clampScroll()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (fixedHeight > 0) fixedHeight else (260 * dp).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    private fun maxScroll() = maxOf(0f, totalCols * cellW - width + 8 * dp)

    private fun clampScroll() {
        scrollPos = scrollPos.coerceIn(0f, maxScroll())
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollPos = scroller.currX.toFloat()
            clampScroll()
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        val cw = cellW
        val ch = cellH
        val left = 4 * dp
        emojiPaint.textSize = minOf(cw, ch) * 0.7f
        val fm = emojiPaint.fontMetrics
        val firstCol = ((scrollPos - left) / cw).toInt() - 1
        val lastCol = firstCol + (width / cw).toInt() + 3
        titlePaint.textSize = 11.5f * t.sp
        titlePaint.color = t.textSoft
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height - barH)
        for ((si, s) in sections.withIndex()) {
            if (s.cols == 0) continue
            if (s.startCol + s.cols < firstCol || s.startCol > lastCol) continue
            // sticky title
            val sx = left + s.startCol * cw - scrollPos
            val ex = left + (s.startCol + s.cols) * cw - scrollPos
            val tw = titlePaint.measureText(title(s.id))
            var tx = maxOf(sx + 6 * dp, 10 * dp)
            tx = minOf(tx, ex - tw - 4 * dp)
            canvas.drawText(title(s.id), maxOf(tx, sx + 6 * dp), titleH - 7 * dp, titlePaint)
            for ((i, e) in s.items.withIndex()) {
                val col = s.startCol + i / rows
                if (col < firstCol || col > lastCol) continue
                val row = i % rows
                val cx = left + col * cw + cw / 2 - scrollPos
                val cy = titleH + row * ch + ch / 2
                canvas.drawText(e, cx, cy - (fm.ascent + fm.descent) / 2, emojiPaint)
            }
            if (si < 0) break
        }
        canvas.restore()
        drawBar(canvas, t)
    }

    private fun barSlots() = sections.size + 2 // ABC + categories + delete

    private fun drawBar(c: Canvas, t: GlassTheme) {
        val top = height - barH
        val slot = width / barSlots().toFloat()
        val cur = currentSection()
        // ABC
        t.label.color = t.text
        t.label.textSize = 15f * t.sp
        var fm = t.label.fontMetrics
        if (pressedBar == 0) {
            t.fill.color = t.keySpecial
            tmp.set(4 * dp, top + 6 * dp, slot * 1f - 2 * dp, top + barH - 8 * dp)
            c.drawRoundRect(tmp, 9 * dp, 9 * dp, t.fill)
        }
        c.drawText("ABC", slot / 2 + 2 * dp, top + barH / 2 - (fm.ascent + fm.descent) / 2, t.label)
        emojiPaint.textSize = 17f * t.sp
        fm = emojiPaint.fontMetrics
        for ((i, s) in sections.withIndex()) {
            val cx = slot * (i + 1) + slot / 2
            if (i == cur || pressedBar == i + 1) {
                t.fill.color = t.keySpecial
                c.drawCircle(cx, top + barH / 2 - 1 * dp, minOf(slot / 2 - 1 * dp, 15 * dp), t.fill)
            }
            emojiPaint.alpha = if (i == cur) 255 else 170
            c.drawText(s.icon, cx, top + barH / 2 - 1 * dp - (fm.ascent + fm.descent) / 2, emojiPaint)
        }
        emojiPaint.alpha = 255
        val dx = slot * (barSlots() - 1) + slot / 2
        if (pressedBar == barSlots() - 1) {
            t.fill.color = t.keySpecial
            c.drawCircle(dx, top + barH / 2 - 1 * dp, minOf(slot / 2, 16 * dp), t.fill)
        }
        t.icon.color = t.text
        Icons.backspace(c, dx, top + barH / 2 - 1 * dp, 21 * dp, t.icon)
    }

    private fun currentSection(): Int {
        val col = ((scrollPos + width * 0.25f) / cellW).toInt()
        var cur = 0
        for ((i, s) in sections.withIndex()) if (s.cols > 0 && s.startCol <= col) cur = i
        return cur
    }

    private fun emojiAt(x: Float, y: Float): String? {
        if (y < titleH || y > height - barH) return null
        val col = ((x + scrollPos - 4 * dp) / cellW).toInt()
        val row = ((y - titleH) / cellH).toInt().coerceIn(0, rows - 1)
        for (s in sections) {
            if (col >= s.startCol && col < s.startCol + s.cols) {
                return s.items.getOrNull((col - s.startCol) * rows + row)
            }
        }
        return null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x
                dragging = false
                downInBar = e.y > height - barH
                if (downInBar) {
                    pressedBar = (e.x / (width / barSlots().toFloat())).toInt().coerceIn(0, barSlots() - 1)
                    actions.feedbackLight()
                    if (pressedBar == barSlots() - 1) {
                        deleteRepeat = 0
                        actions.onEmojiDelete(0)
                        handler.postDelayed(deleteRunnable, 400)
                    }
                    invalidate()
                } else {
                    scroller.forceFinished(true)
                    velocity?.recycle()
                    velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                }
            }
            MotionEvent.ACTION_MOVE -> if (!downInBar) {
                velocity?.addMovement(e)
                if (!dragging && abs(e.x - downX) > slop) dragging = true
                if (dragging) {
                    scrollPos -= e.x - lastX
                    clampScroll()
                    invalidate()
                }
                lastX = e.x
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(deleteRunnable)
                if (downInBar) {
                    val slot = (e.x / (width / barSlots().toFloat())).toInt().coerceIn(0, barSlots() - 1)
                    if (slot == pressedBar) {
                        when (slot) {
                            0 -> actions.onEmojiBack()
                            barSlots() - 1 -> {}
                            else -> jumpTo(slot - 1)
                        }
                    }
                    pressedBar = -1
                    invalidate()
                } else if (!dragging) {
                    emojiAt(e.x, e.y)?.let {
                        actions.feedbackLight()
                        actions.onEmojiPicked(it)
                        addRecent(it)
                    }
                } else {
                    velocity?.let { v ->
                        v.addMovement(e)
                        v.computeCurrentVelocity(1000)
                        scroller.fling(scrollPos.toInt(), 0, -v.xVelocity.toInt(), 0, 0, maxScroll().toInt(), 0, 0)
                        postInvalidateOnAnimation()
                    }
                }
                velocity?.recycle()
                velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(deleteRunnable)
                pressedBar = -1
                velocity?.recycle()
                velocity = null
                invalidate()
            }
        }
        return true
    }

    private fun jumpTo(i: Int) {
        val s = sections.getOrNull(i) ?: return
        if (s.cols == 0) return
        scroller.forceFinished(true)
        val target = (s.startCol * cellW).coerceIn(0f, maxScroll())
        scroller.startScroll(scrollPos.toInt(), 0, (target - scrollPos).toInt(), 0, 280)
        postInvalidateOnAnimation()
    }
}
