package com.example.glasskeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * Transparent layer over the keyboard that draws the key preview balloon, the long-press accent
 * picker with its draggable liquid-glass lens, and the "chaos" particle burst. It never takes
 * touches. Everything stays inside the keyboard window (no invisible margin above it, so apps
 * never get pushed by an empty band).
 */
class PopupOverlay(context: Context) : View(context) {
    var theme: GlassTheme? = null
    var animations = true
    /** 0 spring, 1 float up, 2 fade, 3 none */
    var previewStyle = 0
    /** 0 spring, 1 unfold sideways, 2 fade */
    var popupStyle = 0
    /** long-press picker material: 0 liquid glass, 1 frosted glass, 2 solid */
    var popupGlass = 0
    /** long-press menu glass: 0 clear … 1 strongly blurred / milky */
    var popupBlur = 0.45f
    /** typing balloon material: 0 liquid glass, 1 frosted glass, 2 solid */
    var previewGlass = 2
    private var backdropDirty = true

    /** The keyboard changed (page, shift, theme): take a new snapshot next time glass needs it. */
    fun invalidateBackdrop() {
        backdropDirty = true
    }

    private fun ensureBackdrop() {
        if (!backdropDirty && backSharp != null) return
        backdropDirty = false
        captureBackdrop()
    }
    /** renders the keyboard as it looks right now, at the given scale (what the picker's glass shows) */
    var backdrop: ((Float) -> Bitmap?)? = null
    private var backSharp: Bitmap? = null
    private var backSoft: Bitmap? = null
    private val boxLens = LiquidLens(resources.displayMetrics.density)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val backMatrix = android.graphics.Matrix()

    private val dp = resources.displayMetrics.density

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
    private val lensRect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val pop = OvershootInterpolator(1.6f)
    private val popSoft = OvershootInterpolator(1.1f)
    private val ease = DecelerateInterpolator()
    private val ease2 = DecelerateInterpolator(2f)
    private val lens = LiquidLens(dp)

    private var previewText: String? = null
    private var popupItems: List<String> = emptyList()
    private var popupSelected = 0
    private var lensX = 0f
    private var lensTarget = 0f
    private var lensV = 0f
    private var lensPress = 0f
    private var lensFrame = 0L
    private var itemW = 0f
    private var itemsLeft = 0f
    private var lastPreviewEnd = 0L
    private var reversed = false
    private var popupContent: Bitmap? = null

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

    /** Keeps a bubble inside the view: if there is no room above, it slides down over the key. */
    private fun clampTop(b: Bubble) {
        val minTop = 2 * dp
        if (b.box.top < minTop) b.box.offset(0f, minTop - b.box.top)
    }

    // ------------------------------------------------------------------ preview balloon

    fun showPreview(src: View, k: RectF, text: String) {
        val t = theme ?: return
        val o = offsetOf(src)
        val b = preview
        b.key.set(k.left + o.x, k.top + o.y, k.right + o.x, k.bottom + o.y)
        val extra = 9f * dp
        var l = b.key.left - extra
        var r = b.key.right + extra
        if (l < 2 * dp) {
            r += 2 * dp - l; l = 2 * dp
        }
        if (r > width - 2 * dp) {
            l -= r - (width - 2 * dp); r = width - 2 * dp
        }
        val h = b.key.height() * 1.15f
        b.box.set(l, b.key.top - h - b.key.height() * 0.12f, r, b.key.top - b.key.height() * 0.12f)
        clampTop(b)
        buildShape(b, t)
        val n = now()
        // in quick succession (fast typing) the next balloon starts half-grown: snappy, not jumpy
        val visible = previewText != null && preview.hideAt == 0L
        b.shownAt = if (visible || n - lastPreviewEnd < 120) n - Anim.ms(55f).toLong() else n
        if (previewGlass != 2) ensureBackdrop()
        b.hideAt = 0
        previewText = text
        postInvalidateOnAnimation()
    }

    fun hidePreview() {
        if (previewText == null || preview.hideAt != 0L) return
        lastPreviewEnd = now()
        if (animations && previewStyle != 3) preview.hideAt = now() else previewText = null
        postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------------ long-press picker

    /** Shows the accent picker; returns the left edge of the first slot, the slot width and 1 if reversed (in [src] coordinates). */
    fun showPopup(src: View, k: RectF, items: List<String>): FloatArray {
        val t = theme ?: return floatArrayOf(k.left, k.width(), 0f)
        val o = offsetOf(src)
        val b = popup
        b.key.set(k.left + o.x, k.top + o.y, k.right + o.x, k.bottom + o.y)
        itemW = maxOf(b.key.width(), 30f * dp)
        val pad = 9f * dp
        val w = items.size * itemW + 2 * pad
        var l = b.key.centerX() - itemW / 2 - pad
        // Near the right edge the picker opens to the left, in reverse order, so the default
        // choice stays right above the finger (like on iOS).
        reversed = l + w > width - 3 * dp
        if (reversed) l = b.key.centerX() + itemW / 2 + pad - w
        if (l + w > width - 3 * dp) l = width - 3 * dp - w
        if (l < 3 * dp) l = 3 * dp
        val h = b.key.height() * 1.12f
        b.box.set(l, b.key.top - h - b.key.height() * 0.12f, l + w, b.key.top - b.key.height() * 0.12f)
        clampTop(b)
        itemsLeft = b.box.left + pad
        buildShape(b, t)
        b.shownAt = now()
        b.hideAt = 0
        popupItems = items
        popupSelected = 0
        lensTarget = slotX(0)
        lensX = lensTarget
        lensV = 0f
        lensPress = 0f
        lensFrame = now()
        if (popupGlass != 2) ensureBackdrop()
        renderPopupContent(t)
        postInvalidateOnAnimation()
        return floatArrayOf(itemsLeft - o.x, itemW, if (reversed) 1f else 0f)
    }

    /** Left edge of the slot that shows item [i]. */
    private fun slotX(i: Int) = itemsLeft + (if (reversed) popupItems.size - 1 - i else i) * itemW

    /** The finger moved to [x] (in [src] coordinates): the glass lens follows it; returns the selected item. */
    fun dragPopup(src: View, x: Float): Int {
        if (popupItems.isEmpty()) return 0
        val o = offsetOf(src)
        val n = popupItems.size
        lensTarget = (x + o.x - itemW / 2).coerceIn(itemsLeft, itemsLeft + (n - 1) * itemW)
        val vis = ((lensTarget + itemW / 2 - itemsLeft) / itemW).toInt().coerceIn(0, n - 1)
        popupSelected = if (reversed) n - 1 - vis else vis
        if (!animations) lensX = lensTarget
        postInvalidateOnAnimation()
        return popupSelected
    }

    fun popupItem(): String? = popupItems.getOrNull(popupSelected)

    fun hidePopup() {
        if (popupItems.isEmpty() || popup.hideAt != 0L) return
        // on release the lens settles onto the chosen letter while fading
        lensTarget = slotX(popupSelected)
        if (animations) popup.hideAt = now() else popupItems = emptyList()
        postInvalidateOnAnimation()
    }

    fun hideAll() {
        previewText = null
        popupItems = emptyList()
        invalidate()
    }

    /** Snapshot of the keys under the picker: sharp (for liquid glass) and very soft (frosted). */
    private fun captureBackdrop() {
        backSharp = null
        backSoft = null
        val sharp = try {
            backdrop?.invoke(0.5f)
        } catch (_: Exception) {
            null
        } ?: return
        backSharp = sharp
        // downscale twice with filtering: a cheap, smooth blur
        val w1 = maxOf(1, sharp.width / 6)
        val h1 = maxOf(1, sharp.height / 6)
        val small = Bitmap.createScaledBitmap(sharp, w1, h1, true)
        backSoft = Bitmap.createScaledBitmap(small, maxOf(1, w1 / 2), maxOf(1, h1 / 2), true)
    }

    private fun liquidVeil(t: GlassTheme): Int {
        val base = if (t.dark) 0x8C3A3B40.toInt() else 0x99FFFFFF.toInt()
        val a = (Color.alpha(base) * (0.25f + 1.45f * popupBlur)).toInt().coerceIn(0, 240)
        return (base and 0x00FFFFFF) or (a shl 24)
    }

    private fun popupTint(t: GlassTheme, mat: Int = popupGlass): Int = when (mat) {
        0 -> if (t.dark) 0x99595A60.toInt() else 0xB3FFFFFF.toInt()
        1 -> if (t.dark) 0xA6505156.toInt() else 0xB8F5F6F9.toInt()
        else -> t.popup
    }

    /** The picker body: frosted (blurred keys + milky tint) or the glass base of liquid. */
    private fun drawPopupBody(c: Canvas, t: GlassTheme, shape: Path, alpha: Float, mat: Int = popupGlass, menu: Boolean = true) {
        val soft = backSoft
        c.save()
        c.clipPath(shape)
        // the menu's blur slider: from clear glass (sharp keys, thin tint) to milky frosted glass
        val v = popupBlur
        if (soft != null && mat != 2) {
            val sc = width.toFloat() / soft.width
            backMatrix.setScale(sc, sc)
            bmpPaint.alpha = (255 * alpha).toInt()
            c.drawBitmap(soft, backMatrix, bmpPaint)
            val sharp = backSharp
            if (mat == 1 && sharp != null && v < 0.6f) {
                backMatrix.setScale(2f, 2f)
                bmpPaint.alpha = (255 * alpha * (1f - v / 0.6f)).toInt()
                c.drawBitmap(sharp, backMatrix, bmpPaint)
            }
        }
        val tint = popupTint(t, mat)
        paint.color = tint
        val ta = if (mat != 2) (0.35f + 1.2f * v).coerceAtMost(1.45f) else 1f
        paint.alpha = (Color.alpha(tint) * ta * alpha).toInt().coerceIn(0, 255)
        c.drawPaint(paint)
        c.restore()
    }

    /** The picker (bubble + letters) as a bitmap: this is what the glass lens refracts. */
    private fun renderPopupContent(t: GlassTheme) {
        val w = (popup.box.width() + 2).toInt().coerceAtLeast(1)
        val h = (popup.box.height() + 2).toInt().coerceAtLeast(1)
        var bmp = popupContent
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            popupContent = bmp
        }
        bmp.eraseColor(0)
        val c = Canvas(bmp)
        c.translate(-popup.box.left, -popup.box.top)
        val br = popup.box.height() / 2f
        if (popupGlass == 2 || backSoft == null) {
            paint.color = t.popup
            paint.alpha = 255
            c.drawRoundRect(popup.box, br, br, paint)
        } else {
            tmpPath.reset()
            tmpPath.addRoundRect(popup.box, br, br, Path.Direction.CW)
            val sharp = backSharp
            if (popupGlass == 0 && sharp != null) {
                c.save()
                c.clipPath(tmpPath)
                backMatrix.setScale(2f, 2f)
                bmpPaint.alpha = 255
                c.drawBitmap(sharp, backMatrix, bmpPaint)
                paint.color = liquidVeil(t)
                c.drawPaint(paint)
                c.restore()
            } else drawPopupBody(c, t, tmpPath, 1f)
        }
        drawLetters(c, t, 1f)
        lens.setContent(bmp, popup.box.left, popup.box.top)
    }

    private fun drawLetters(c: Canvas, t: GlassTheme, alpha: Float) {
        for ((i, s) in popupItems.withIndex()) {
            textPaint.color = t.text
            textPaint.alpha = (255 * alpha).toInt()
            textPaint.textSize = (if (s.length > 2) 15f else 26f) * t.sp
            val fm = textPaint.fontMetrics
            c.drawText(s, slotX(i) + itemW / 2, popup.box.centerY() - (fm.ascent + fm.descent) / 2, textPaint)
        }
    }

    // ------------------------------------------------------------------ chaos burst

    private class Particle(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        val size: Float, val color: Int, var rot: Float, val vr: Float, val kind: Int,
    )

    private val particles = ArrayList<Particle>()
    private var burstAt = 0L
    private var lastFrame = 0L
    private val rnd = Random()
    private val palette = intArrayOf(
        0xFF0A84FF.toInt(), 0xFFFF375F.toInt(), 0xFFFFD60A.toInt(), 0xFF30D158.toInt(),
        0xFF64D2FF.toInt(), 0xFFBF5AF2.toInt(), 0xFFFF9F0A.toInt(), 0xFFFFFFFF.toInt(),
    )

    /** A wild little explosion of glassy confetti from ([x], [y]) in [src] coordinates. */
    fun burst(src: View, x: Float, y: Float) {
        val o = offsetOf(src)
        val cx = x + o.x
        val cy = y + o.y
        repeat(34) {
            val a = rnd.nextFloat() * Math.PI.toFloat() * 2f
            val sp = (180 + rnd.nextFloat() * 900) * dp / 3f
            particles += Particle(
                cx + (rnd.nextFloat() - 0.5f) * 30 * dp, cy + (rnd.nextFloat() - 0.5f) * 10 * dp,
                cos(a) * sp, sin(a) * sp - 260 * dp / 3f,
                (3 + rnd.nextFloat() * 6) * dp, palette[rnd.nextInt(palette.size)],
                rnd.nextFloat() * 360f, (rnd.nextFloat() - 0.5f) * 900f, rnd.nextInt(3),
            )
        }
        burstAt = now()
        lastFrame = burstAt
        postInvalidateOnAnimation()
    }

    private fun drawParticles(c: Canvas): Boolean {
        if (particles.isEmpty()) return false
        val n = now()
        val dt = ((n - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = n
        val life = (n - burstAt) / 750f
        if (life >= 1f) {
            particles.clear()
            return false
        }
        val g = 1500 * dp / 3f
        val a = (1f - life * life)
        for (p in particles) {
            p.vy += g * dt
            p.vx *= 0.985f
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.rot += p.vr * dt
            paint.color = p.color
            paint.alpha = (255 * a).toInt()
            c.save()
            c.rotate(p.rot, p.x, p.y)
            when (p.kind) {
                0 -> c.drawCircle(p.x, p.y, p.size / 2, paint)
                1 -> {
                    tmp.set(p.x - p.size / 2, p.y - p.size / 4, p.x + p.size / 2, p.y + p.size / 4)
                    c.drawRoundRect(tmp, p.size / 6, p.size / 6, paint)
                }
                else -> {
                    // four-point sparkle
                    tmpPath.reset()
                    val s = p.size * 0.8f
                    tmpPath.moveTo(p.x, p.y - s)
                    tmpPath.quadTo(p.x, p.y, p.x + s, p.y)
                    tmpPath.quadTo(p.x, p.y, p.x, p.y + s)
                    tmpPath.quadTo(p.x, p.y, p.x - s, p.y)
                    tmpPath.quadTo(p.x, p.y, p.x, p.y - s)
                    c.drawPath(tmpPath, paint)
                }
            }
            // a tiny glass glint on each piece
            paint.color = Color.WHITE
            paint.alpha = (110 * a).toInt()
            c.drawCircle(p.x - p.size * 0.15f, p.y - p.size * 0.15f, p.size * 0.14f, paint)
            c.restore()
        }
        return true
    }

    // ------------------------------------------------------------------ shapes & drawing

    /** Bubble + neck + key, merged into one outline. */
    private fun buildShape(b: Bubble, t: GlassTheme) {
        val shape = b.shape
        val bubble = b.box
        val key = b.key
        shape.reset()
        // the picker is a capsule, concentric with the round glass lens inside it
        val br = if (b === popup) bubble.height() / 2f else 12f * dp
        shape.addRoundRect(bubble, br, br, Path.Direction.CW)
        tmpPath.reset()
        tmpPath.addRoundRect(key, t.keyRadius, t.keyRadius, Path.Direction.CW)
        shape.op(tmpPath, Path.Op.UNION)
        val gap = key.top - bubble.bottom
        if (gap > 0) {
            tmpPath.reset()
            val nl = maxOf(bubble.left, key.left)
            val nr = minOf(bubble.right, key.right)
            tmpPath.moveTo(bubble.left, bubble.bottom - br)
            tmpPath.cubicTo(bubble.left, bubble.bottom + gap * 0.4f, nl, key.top - gap * 0.4f, nl, key.top + t.keyRadius)
            tmpPath.lineTo(nr, key.top + t.keyRadius)
            tmpPath.cubicTo(nr, key.top - gap * 0.4f, bubble.right, bubble.bottom + gap * 0.4f, bubble.right, bubble.bottom - br)
            tmpPath.close()
            shape.op(tmpPath, Path.Op.UNION)
        }
        b.shadow.set(shape)
        b.shadow.offset(0f, 2.5f * dp)
        b.bounds.set(bubble.left, bubble.top, bubble.right, maxOf(key.bottom, bubble.bottom))
    }

    /** Scale x, scale y, translation y and alpha of a bubble now, or null when it finished hiding. */
    private fun state(b: Bubble, style: Int, appearMs: Float): FloatArray? {
        val n = now()
        if (!animations || style == 3) return floatArrayOf(1f, 1f, 0f, 1f)
        val a = ((n - b.shownAt) / Anim.ms(appearMs)).coerceIn(0f, 1f)
        var sx: Float
        var sy: Float
        var ty = 0f
        var alpha: Float
        when (style) {
            0 -> { // spring
                val v = 0.5f + 0.5f * (if (b === popup) popSoft else pop).getInterpolation(a)
                sx = v; sy = v
                alpha = (a * 2.2f).coerceAtMost(1f)
            }
            1 -> if (b === popup) { // unfold sideways
                val e = ease2.getInterpolation(a)
                val k = (b.key.width() / b.box.width()).coerceIn(0.05f, 1f)
                sx = k + (1f - k) * e
                sy = 1f
                alpha = (a * 3f).coerceAtMost(1f)
            } else { // float up
                val e = ease2.getInterpolation(a)
                sx = 1f; sy = 1f
                ty = (1f - e) * b.key.height() * 0.6f
                alpha = (a * 2f).coerceAtMost(1f)
            }
            else -> { // fade
                sx = 1f; sy = 1f
                alpha = ease.getInterpolation(a)
            }
        }
        if (b.hideAt != 0L) {
            val h = ((n - b.hideAt) / Anim.ms(130f)).coerceIn(0f, 1f)
            if (h >= 1f) return null
            val e = ease.getInterpolation(h)
            alpha *= 1f - e
            if (style == 0) {
                sx *= 1f - 0.08f * e; sy *= 1f - 0.08f * e
            }
        }
        return floatArrayOf(sx, sy, ty, alpha)
    }

    private fun begin(canvas: Canvas, b: Bubble, t: GlassTheme, st: FloatArray, pivotX: Float) {
        canvas.save()
        canvas.translate(0f, st[2])
        canvas.scale(st[0], st[1], pivotX, b.box.bottom)
        canvas.save()
        canvas.clipOutPath(b.shape)
        paint.color = t.popupShadow
        paint.alpha = (Color.alpha(t.popupShadow) * st[3]).toInt()
        canvas.drawPath(b.shadow, paint)
        canvas.restore()
        val mat = if (b === popup) popupGlass else previewGlass
        if (mat != 2 && backSoft != null) {
            drawPopupBody(canvas, t, b.shape, st[3], mat, b === popup)
            if (mat == 0) {
                // liquid: the bubble itself is a lens over the keys, bending them at its rim
                backSharp?.let { boxLens.setContent(it, 0f, 0f, 2f) }
                val rad = if (b === popup) b.box.height() / 2f else 12f * dp
                // the small typing balloon bends less, so the letter stays clean
                val bend = if (b === popup) 0.8f else 0.35f
                boxLens.drawRaw(
                    canvas, b.box, rad, 1f, minOf(t.lensHeight, rad * 0.6f), t.lensAmount * bend,
                    t.lensDispersion, popupBlur * (if (b === popup) 7f else 4f) * dp, t.params.rim, t.params.convex,
                    popupTint(t, mat), t.dark, st[3], shadow = false,
                )
                // a light veil over the refracted keys keeps the letters readable
                val veil = if (b === popup) liquidVeil(t) else {
                    // the letter balloon stays more opaque (the letter must be readable) but follows the slider too
                    val base = if (t.dark) 0xCC3A3B40.toInt() else 0xD9FFFFFF.toInt()
                    val a = (Color.alpha(base) * (0.55f + 0.9f * popupBlur)).toInt().coerceIn(0, 245)
                    (base and 0x00FFFFFF) or (a shl 24)
                }
                paint.color = veil
                paint.alpha = (Color.alpha(veil) * st[3]).toInt()
                canvas.drawRoundRect(b.box, rad, rad, paint)
            }
            t.drawRim(canvas, b.shape, b.bounds, (255 * st[3]).toInt())
        } else {
            t.drawShape(canvas, b.shape, b.bounds, t.popup, (255 * st[3]).toInt())
        }
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme ?: return
        var again = false

        val text = previewText
        if (text != null) {
            val st = state(preview, previewStyle, if (previewStyle == 0) 120f else 110f)
            if (st == null) {
                previewText = null
            } else {
                begin(canvas, preview, t, st, preview.key.centerX())
                textPaint.color = t.text
                textPaint.alpha = (255 * st[3]).toInt()
                textPaint.textSize = if (text.length > 2) 22f * t.sp else 34f * t.sp
                val fm = textPaint.fontMetrics
                canvas.drawText(text, preview.box.centerX(), preview.box.centerY() - (fm.ascent + fm.descent) / 2, textPaint)
                canvas.restore()
                if (animations && (now() - preview.shownAt < 140 || preview.hideAt != 0L)) again = true
            }
        }

        if (popupItems.isNotEmpty()) {
            val st = state(popup, popupStyle, 210f)
            if (st == null) {
                popupItems = emptyList()
            } else {
                begin(canvas, popup, t, st, popup.key.centerX())
                drawLetters(canvas, t, st[3])
                // spring-follow of the finger (damped, like a liquid slider thumb)
                val n = now()
                val dt = ((n - lensFrame) / 1000f).coerceIn(0f, 0.034f) * Anim.speed
                lensFrame = n
                if (animations) {
                    val k = 700f
                    val damp = 2f * kotlin.math.sqrt(k) * 0.78f
                    lensV += ((lensTarget - lensX) * k - lensV * damp) * dt
                    lensX += lensV * dt
                    if (kotlin.math.abs(lensTarget - lensX) > 0.3f || kotlin.math.abs(lensV) > 5f) again = true
                    else {
                        lensX = lensTarget; lensV = 0f
                    }
                    val pressTarget = if (popup.hideAt == 0L) 1f else 0f
                    lensPress += (pressTarget - lensPress) * (1f - kotlin.math.exp(-dt * 18f))
                    if (kotlin.math.abs(pressTarget - lensPress) > 0.01f) again = true
                } else {
                    lensX = lensTarget; lensV = 0f; lensPress = 1f
                }
                // the glass grows a little while held and stretches with its speed
                val vel = (lensV / (2600f * dp / 3f)).coerceIn(-1f, 1f)
                val grow = 1f + 0.08f * lensPress
                val sx = grow / (1f - (kotlin.math.abs(vel) * 0.22f).coerceAtMost(0.2f))
                val sy = grow * (1f - (kotlin.math.abs(vel) * 0.12f).coerceAtMost(0.15f))
                val cx = lensX + itemW / 2f
                val cy = popup.box.centerY()
                val hw = (itemW / 2f + 5f * dp) * sx
                val hh = (popup.box.height() / 2f - 5f * dp) * sy
                lensRect.set(cx - hw, cy - hh, cx + hw, cy + hh)
                lens.draw(
                    canvas, lensRect, hh, 1.08f, t,
                    if (t.solid) t.accent else if (t.dark) 0x1AFFFFFF else 0x2EFFFFFF, st[3],
                )
                if (t.solid) popupItem()?.let { sel ->
                    textPaint.color = 0xFFFFFFFF.toInt()
                    textPaint.alpha = (255 * st[3]).toInt()
                    textPaint.textSize = (if (sel.length > 2) 15f else 26f) * t.sp
                    val fm = textPaint.fontMetrics
                    canvas.drawText(sel, cx, cy - (fm.ascent + fm.descent) / 2, textPaint)
                }
                canvas.restore()
                if (animations && (now() - popup.shownAt < 230 || popup.hideAt != 0L)) again = true
            }
        }
        if (drawParticles(canvas)) again = true
        if (again) postInvalidateOnAnimation()
    }
}
