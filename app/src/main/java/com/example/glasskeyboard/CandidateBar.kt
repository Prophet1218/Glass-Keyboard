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
    /** Toolbar (emoji, clipboard, language, hide, settings) while nothing is being typed. */
    var toolbarEnabled = true
        set(v) {
            field = v
            invalidate()
        }
    var onTool: ((Int) -> Unit)? = null
    /** the toolbar was opened with the › button while suggestions are showing */
    private var forced = false
    private var toolLevel = 1f
    private var toolFrame = 0L
    private var toolPressed = -1
    /** press state of each toolbar button: springs to 1 while held, back (with a small bounce) after */
    private val toolSprings = Array(TOOLS) { Spring(0f, 600f, 0.55f) }
    private var toolFrameAt = 0L
    private val chevW get() = 40f * dp

    /** background of the toolbar buttons: 0 liquid glass, 1 frosted glass, 2 solid */
    var toolGlass = 0
    /** glass milkiness of the toolbar buttons (the shared "glass blur" slider): 0 clear … 1 milky */
    var glassBlur = 0.45f

    private fun scaled(c: Int): Int {
        val a = (Color.alpha(c) * (0.3f + 1.55f * glassBlur)).toInt().coerceIn(0, 235)
        return (c and 0x00FFFFFF) or (a shl 24)
    }
    /** the tool whose panel is open (emoji, GIF, clipboard), highlighted; -1 = none */
    var activeTool = -1
        set(v) {
            field = v
            invalidate()
        }
    private val toolLens = LiquidLens(resources.displayMetrics.density)
    private var iconLayer: android.graphics.Bitmap? = null
    private val toolRect = RectF()

    companion object {
        const val TOOL_HIDE = 0
        const val TOOL_EMOJI = 1
        const val TOOL_GIF = 2
        const val TOOL_CLIPBOARD = 3
        const val TOOL_SETTINGS = 4
        private const val TOOLS = 5
    }

    private fun hasItems() = items.any { it != null }
    private fun toolbarWanted() = toolbarEnabled && (forced || !hasItems())
    private fun sugLeft() = if (toolbarEnabled) chevW else 0f
    private val lens = LiquidLens(resources.displayMetrics.density)
    private var layer: android.graphics.Bitmap? = null
    private val lensRect = RectF()
    private val clipPath = android.graphics.Path()
    private val paintBmp = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)

    private fun pillRect(i: Int, left: Float, w: Float, h: Float) {
        val l = left + i * w
        lensRect.set(l + 6 * dp, 6 * dp, l + w - 6 * dp, h - 5 * dp)
    }

    private var items: List<Candidate?> = listOf(null, null, null)
    private var old: List<Candidate?> = listOf(null, null, null)
    private val changedAt = LongArray(3)
    /** animated text size of each slot's word (0 = not drawn yet) */
    private val slotSize = FloatArray(3)
    private val slotSizeAt = LongArray(3)
    private var sizeAnimating = false
    private val pillAt = LongArray(3)
    private val pillFrom = FloatArray(3)
    private var pressed = -1
    private val dp = resources.displayMetrics.density
    private val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER }
    private val tmp = RectF()

    private val fadeMs get() = Anim.ms(150f)

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
        forced = false
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
        val now = SystemClock.uptimeMillis()
        val dt = if (toolFrame == 0L) 0.016f else ((now - toolFrame) / 1000f).coerceIn(0f, 0.05f)
        toolFrame = now
        val target = if (toolbarWanted()) 1f else 0f
        if (!animations) toolLevel = target
        else toolLevel += (target - toolLevel) * (1f - kotlin.math.exp(-dt * 16f * Anim.speed))
        if (kotlin.math.abs(target - toolLevel) < 0.01f) toolLevel = target
        var again = toolLevel != target
        if (toolLevel < 1f) {
            val save = if (toolLevel > 0f) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (255 * (1f - toolLevel)).toInt()) else canvas.save()
            if (drawSuggestions(canvas)) again = true
            canvas.restoreToCount(save)
        }
        if (toolLevel > 0f) drawToolbar(canvas, toolLevel)
        if (again) postInvalidateOnAnimation() else toolFrame = 0L
    }

    private fun toolIcon(c: Canvas, id: Int, cx: Float, cy: Float, sz: Float, t: GlassTheme) {
        when (id) {
            TOOL_HIDE -> Icons.hideKeyboard(c, cx, cy, sz, t.icon)
            TOOL_EMOJI -> Icons.emoji(c, cx, cy, sz, t.icon, t.iconFill)
            TOOL_GIF -> Icons.gif(c, cx, cy, sz, t.icon, t.iconFill)
            TOOL_CLIPBOARD -> Icons.clipboard(c, cx, cy, sz, t.icon)
            else -> Icons.gear(c, cx, cy, sz, t.icon)
        }
    }

    private fun drawIcons(c: Canvas, alpha: Float, t: GlassTheme) {
        val w = width / TOOLS.toFloat()
        val cy = toolCy()
        for (i in 0 until TOOLS) {
            val col = if (i == activeTool) t.accent else t.stripIcon
            t.icon.color = col
            t.iconFill.color = col
            t.icon.alpha = (Color.alpha(col) * alpha).toInt()
            t.iconFill.alpha = t.icon.alpha
            val sc = toolScale(i)
            val cx = w * i + w / 2
            c.save()
            c.scale(sc, sc, cx, cy)
            toolIcon(c, i, cx, cy, 22 * dp, t)
            c.restore()
        }
        t.icon.alpha = 255
        t.iconFill.alpha = 255
    }

    /** Size of button [i] now: grows while pressed, the icon with it (iOS liquid glass style). */
    private fun toolScale(i: Int): Float = 1f + 0.16f * toolSprings[i].value

    /** Advances the press springs; true while any of them still moves. */
    private fun stepToolSprings(): Boolean {
        val now = SystemClock.uptimeMillis()
        val dt = if (toolFrameAt == 0L) 0.016f else ((now - toolFrameAt) / 1000f).coerceIn(0f, 0.05f)
        toolFrameAt = now
        var moving = false
        for (i in 0 until TOOLS) {
            val target = if (i == toolPressed) 1f else 0f
            if (!animations) {
                toolSprings[i].value = target
                toolSprings[i].velocity = 0f
            } else if (toolSprings[i].step(target, dt * Anim.speed)) moving = true
        }
        if (!moving) toolFrameAt = 0L
        return moving
    }

    /** Icons sit in the middle of the visible bar (a touch lower than the view's centre). */
    private fun toolCy() = height / 2f + 2.5f * dp

    private fun drawToolbar(c: Canvas, alpha: Float) {
        val t = theme
        val w = width / TOOLS.toFloat()
        val cy = toolCy()
        val bw = minOf(w - 10 * dp, 52 * dp)
        val bh = 34 * dp
        val moving = stepToolSprings()
        // icons into a layer: the liquid glass buttons refract them
        var layer = iconLayer
        if (toolGlass == 0 && !t.solid && width > 0 && height > 0) {
            if (layer == null || layer.width != width || layer.height != height) {
                layer = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                iconLayer = layer
            }
            layer!!.eraseColor(0)
            drawIcons(Canvas(layer), 1f, t)
            toolLens.setContent(layer, 0f, 0f)
        }
        for (i in 0 until TOOLS) {
            val cx = w * i + w / 2
            val press = toolSprings[i].value.coerceAtLeast(0f)
            val grow = toolScale(i)
            toolRect.set(cx - bw / 2 * grow, cy - bh / 2 * grow, cx + bw / 2 * grow, cy + bh / 2 * grow)
            val r = toolRect.height() / 2f
            when {
                toolGlass == 2 || t.solid -> {
                    t.fill.shader = null
                    t.fill.color = GlassTheme.mixRgb(t.keySpecial, t.keySpecialPressed, press.coerceAtMost(1f)) or (t.keySpecial and 0xFF000000.toInt())
                    t.fill.alpha = (Color.alpha(t.fill.color) * alpha).toInt()
                    c.drawRoundRect(toolRect, r, r, t.fill)
                    t.fill.alpha = 255
                }
                toolGlass == 1 -> toolLens.drawRaw(
                    c, toolRect, r, 1f, 0.5f, 0f, 0f, 0f, t.params.rim, t.params.convex,
                    brighten(scaled(if (t.dark) 0x33FFFFFF else 0x99FFFFFF.toInt()), press), t.dark, alpha,
                )
                // pressed liquid glass bends a little more, like a drop being pushed
                else -> toolLens.drawRaw(
                    c, toolRect, r, 1f, minOf(t.lensHeight, r * 0.6f), t.lensAmount * (0.4f + 0.35f * press), t.lensDispersion, 0f,
                    t.params.rim, t.params.convex, brighten(scaled(if (t.dark) 0x1FFFFFFF else 0x4DFFFFFF), press), t.dark, alpha,
                )
            }
        }
        // the liquid buttons already show the icons (refracted); the others get them on top
        if (!(toolGlass == 0 && !t.solid)) drawIcons(c, alpha, t)
        else {
            // icons between the buttons are not covered by glass: draw any part outside
            c.save()
            for (i in 0 until TOOLS) {
                val cx = w * i + w / 2
                val g = toolScale(i)
                toolRect.set(cx - bw / 2 * g, cy - bh / 2 * g, cx + bw / 2 * g, cy + bh / 2 * g)
                c.clipOutRect(toolRect)
            }
            drawIcons(c, alpha, t)
            c.restore()
        }
        if (moving) postInvalidateOnAnimation()
    }

    /** The glass tint, a little lighter while pressed. */
    private fun brighten(c: Int, press: Float): Int {
        if (press <= 0f) return c
        val a = (Color.alpha(c) + (0x30 * press)).toInt().coerceAtMost(255)
        return (c and 0x00FFFFFF) or (a shl 24)
    }

    /** Draws the three suggestion slots; returns true while something is animating. */
    private fun drawSuggestions(canvas: Canvas): Boolean {
        val t = theme
        val now = SystemClock.uptimeMillis()
        val left = sugLeft()
        val w = (width - left) / 3f
        val h = height.toFloat()
        var again = false
        if (toolbarEnabled && hasItems()) {
            // › opens the toolbar
            t.icon.color = t.stripIcon
            Icons.chevronRight(canvas, chevW / 2f + 2 * dp, h / 2f, 16 * dp, t.icon)
        }
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
        sizeAnimating = false
        for (i in 0 until 3) {
            val l = left + i * w
            val a = if (!animations || style == 4) 1f else ((now - changedAt[i]) / fadeMs).coerceIn(0f, 1f)
            if (a < 1f) {
                again = true
                old[i]?.let { drawWord(target, it, i, l, w, h, 1f - a, false, now, t) }
            }
            items[i]?.let { drawWord(target, it, i, l, w, h, a, true, now, t) }
        }
        if (bmp != null && t.solid) {
            // glass off: plain pill under the words
            for (i in 0 until 3) {
                if (pills[i] <= 0f) continue
                pillRect(i, left, w, h)
                t.fill.shader = null
                t.fill.color = t.candidateHighlight
                t.fill.alpha = (Color.alpha(t.candidateHighlight) * pills[i]).toInt()
                canvas.drawRoundRect(lensRect, lensRect.height() / 2f, lensRect.height() / 2f, t.fill)
                t.fill.alpha = 255
            }
            canvas.drawBitmap(bmp, 0f, 0f, null)
        } else if (bmp != null) {
            // the plain words everywhere except under the glass (no double image)…
            canvas.save()
            clipPath.reset()
            for (i in 0 until 3) {
                if (pills[i] <= 0f) continue
                pillRect(i, left, w, h)
                clipPath.addRoundRect(lensRect, lensRect.height() / 2f, lensRect.height() / 2f, android.graphics.Path.Direction.CW)
            }
            canvas.clipOutPath(clipPath)
            canvas.drawBitmap(bmp, 0f, 0f, null)
            canvas.restore()
            // …and under it, the refracted ones
            lens.setContent(bmp, 0f, 0f)
            for (i in 0 until 3) {
                if (pills[i] <= 0f) continue
                pillRect(i, left, w, h)
                // fading pills show the plain word through their transparent part
                if (pills[i] < 1f) {
                    canvas.save()
                    canvas.clipRect(lensRect)
                    paintBmp.alpha = (255 * (1f - pills[i])).toInt()
                    canvas.drawBitmap(bmp, 0f, 0f, paintBmp)
                    canvas.restore()
                }
                lens.draw(
                    canvas, lensRect, lensRect.height() / 2f, 1f, t,
                    if (t.solid) t.candidateHighlight else if (t.dark) 0x24FFFFFF else 0x59FFFFFF, pills[i],
                )
            }
        }
        if (sizeAnimating) again = true
        // thin separators between filled, non-highlighted slots
        t.fill.shader = null
        t.fill.color = t.divider
        for (i in 1 until 3) {
            val a = items[i - 1]
            val b = items[i]
            if (a == null || b == null || a.highlight || b.highlight) continue
            canvas.drawRect(left + i * w - 0.5f * dp, 13 * dp, left + i * w + 0.5f * dp, h - 11 * dp, t.fill)
        }
        return again
    }

    private fun drawWord(canvas: Canvas, c: Candidate, slot: Int, l: Float, w: Float, h: Float, alpha: Float, incoming: Boolean, now: Long, t: GlassTheme) {
        if (alpha <= 0f) return
        paint.color = t.text
        paint.alpha = (255 * alpha).toInt()
        paint.textSize = 17f * t.sp
        paint.typeface = if (c.highlight) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        // a long word shrinks to stay inside the glass capsule, clear of its bending rim
        // (otherwise its ends sit under the rim and look smeared)
        val avail = w - (if (c.highlight) 40f else 18f) * dp
        val min = 12f * t.sp
        while (paint.measureText(c.shown) > avail && paint.textSize > min) paint.textSize = maxOf(min, paint.textSize * 0.93f)
        if (incoming) {
            // the word shrinks / grows smoothly as it gets longer / shorter while typing
            val target = paint.textSize
            val cur = slotSize[slot]
            if (cur <= 0f || !animations) slotSize[slot] = target
            else {
                val dt = ((now - slotSizeAt[slot]) / 1000f).coerceIn(0f, 0.05f) * Anim.speed
                val next = cur + (target - cur) * (1f - kotlin.math.exp(-dt * 16f))
                slotSize[slot] = if (kotlin.math.abs(target - next) < 0.15f) target else next
                if (slotSize[slot] != target) sizeAnimating = true
            }
            slotSizeAt[slot] = now
            paint.textSize = slotSize[slot]
        }
        // while the size is still settling the word may be a little wider: no "…" flash
        val settling = incoming && sizeAnimating
        val s = TextUtils.ellipsize(c.shown, paint, if (settling) avail * 1.35f else avail, TextUtils.TruncateAt.END).toString()
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
        canvas.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2, paint)
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (toolbarWanted()) {
            val i = (e.x / (width / TOOLS.toFloat())).toInt().coerceIn(0, TOOLS - 1)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    toolPressed = i
                    // a light tick when a toolbar button is touched
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP, android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
                }
                MotionEvent.ACTION_MOVE -> if (i != toolPressed) toolPressed = -1
                MotionEvent.ACTION_UP -> {
                    val p = toolPressed
                    toolPressed = -1
                    if (p == i) {
                        forced = false
                        onTool?.invoke(p)
                    }
                }
                MotionEvent.ACTION_CANCEL -> toolPressed = -1
            }
            invalidate()
            return true
        }
        val left = sugLeft()
        if (e.actionMasked == MotionEvent.ACTION_DOWN && toolbarEnabled && e.x < left) {
            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP, android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
            forced = true
            toolPressed = -1
            invalidate()
            return false
        }
        val i = ((e.x - left) / ((width - left) / 3f)).toInt().coerceIn(0, 2)
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
                    val w = (width - left) / 3f
                    onTapAt?.invoke(left + p * w + w / 2, height / 2f)
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
