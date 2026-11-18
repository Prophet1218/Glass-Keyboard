package com.example.glasskeyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.util.Random

/**
 * Colours and drawing helpers of the glass look (modelled on the iOS 26/27 keyboard).
 *
 * Two materials:
 *  - liquid (0): clear, translucent keys with a bright specular rim, a top sheen and a soft
 *    refraction glow along the bottom edge — the "Liquid Glass" look;
 *  - frosted (1): milky, matte keys and sheet with a fine grain, gentle highlights.
 */
class GlassTheme(
    context: Context, prefTheme: Int, val style: Int = 0, panelOpacity: Int = 100, keyOpacity: Int = 100,
    val params: GlassParams = GlassParams(),
) {
    /** "clear" tone: very see-through sheet and keys */
    val clear = prefTheme == 3
    /** 0..1 opacity of the keyboard sheet and of the keys (settings). */
    val panelAlpha = (panelOpacity / 100f).coerceIn(0f, 1f) * (if (clear) 0.22f else 1f)
    val keyAlpha = (keyOpacity / 100f).coerceIn(0f, 1f) * (if (clear) 0.6f else 1f)
    /** glass switched off: plain solid surfaces */
    val solid = style == 2
    val frosted = style == 1

    // lens (refraction) parameters, in px
    val lensHeight get() = params.refrHeight / 3f * dp
    val lensAmount get() = params.refrOffset / 4f * dp * (if (params.convex) 1.5f else 1f)
    val lensDispersion get() = params.dispersion / 50f
    /** Selection capsules made of frosted (matt) glass instead of liquid glass. */
    val lensFrosted get() = params.lensStyle == 1

    /** Only frosted capsules blur what is under them; liquid glass keeps text sharp. */
    val lensBlur get() = if (lensFrosted) (1.6f + params.blur * 0.2f) * dp else 0f
    val dp = context.resources.displayMetrics.density
    val sp = dp * context.resources.configuration.fontScale

    val dark: Boolean = when (prefTheme) {
        1 -> false
        2 -> true
        else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
    val liquid = style == 0

    var panelTop = 0; private set
    var panelBottom = 0; private set
    var key = 0; private set
    var keySpecial = 0; private set
    var keyPressed = 0; private set
    var keySpecialPressed = 0; private set
    var popup = 0; private set

    init {
        if (liquid) {
            if (dark) {
                panelTop = 0xFF2B2D35.toInt(); panelBottom = 0xFF15161A.toInt()
                key = 0x948C8F99.toInt(); keySpecial = 0x6650535C
                keyPressed = 0x6650535C; keySpecialPressed = 0x948C8F99.toInt()
                popup = 0xFF7C7F88.toInt()
            } else {
                panelTop = 0xFFF3F6FC.toInt(); panelBottom = 0xFFD0D7E5.toInt()
                key = 0xDEFFFFFF.toInt(); keySpecial = 0x8CA5AEC0.toInt()
                keyPressed = 0xB3B9C1CF.toInt(); keySpecialPressed = 0xF2FFFFFF.toInt()
                popup = 0xFAFFFFFF.toInt()
            }
        } else {
            if (dark) {
                panelTop = 0xFF2D2E32.toInt(); panelBottom = 0xFF1F2023.toInt()
                key = 0xFF606166.toInt(); keySpecial = 0xFF3F4045.toInt()
                keyPressed = 0xFF3F4045.toInt(); keySpecialPressed = 0xFF606166.toInt()
                popup = 0xFF6A6B70.toInt()
            } else {
                panelTop = 0xFFEEF0F4.toInt(); panelBottom = 0xFFDDE0E6.toInt()
                key = 0xFAFCFCFD.toInt(); keySpecial = 0xFFCBCFD7.toInt()
                keyPressed = 0xFFCBCFD7.toInt(); keySpecialPressed = 0xFFFFFFFF.toInt()
                popup = 0xFFFFFFFF.toInt()
            }
        }
        panelTop = fx(panelTop); panelBottom = fx(panelBottom)
        key = kfx(fx(key)); keySpecial = kfx(fx(keySpecial)); keyPressed = kfx(fx(keyPressed)); keySpecialPressed = kfx(fx(keySpecialPressed))
        popup = fx(popup)
        panelTop = withAlpha(panelTop, panelAlpha)
        panelBottom = withAlpha(panelBottom, panelAlpha)
        key = withAlpha(key, keyAlpha)
        keySpecial = withAlpha(keySpecial, keyAlpha)
        keyPressed = withAlpha(keyPressed, keyAlpha)
        keySpecialPressed = withAlpha(keySpecialPressed, keyAlpha)
    }

    /** Extra darkening of the keys only. */
    private fun kfx(c: Int): Int = if (params.keyDarken > 0) mixRgb(c, Color.BLACK, params.keyDarken / 100f) else c

    /** Colour tint (hue + strength) and darkening of the glass, alpha kept. */
    private fun fx(c: Int): Int {
        var out = c
        if (params.hueStrength > 0) {
            val tintCol = Color.HSVToColor(floatArrayOf(params.hue.toFloat(), if (dark) 0.55f else 0.45f, if (dark) 0.55f else 1f))
            out = mixRgb(out, tintCol, params.hueStrength / 100f)
        }
        if (params.darken > 0) out = mixRgb(out, Color.BLACK, params.darken / 100f)
        return out
    }

    val panelRim = withAlpha(if (dark) 0x40FFFFFF else 0xE6FFFFFF.toInt(), panelAlpha)
    val keyActive = if (dark) 0xFFF2F2F7.toInt() else 0xFFFFFFFF.toInt()
    val text = if (dark) Color.WHITE else Color.BLACK
    val textSoft = if (dark) 0xB3FFFFFF.toInt() else 0x99000000.toInt()
    val stripIcon = if (dark) 0xE6FFFFFF.toInt() else 0xCC1C1C22.toInt()
    val activeIcon = Color.BLACK
    val accent = 0xFF0A84FF.toInt()
    val accentPressed = 0xFF0066D6.toInt()
    val divider = if (dark) 0x33FFFFFF else 0x26000000
    val popupShadow = if (dark) 0x80000000.toInt() else 0x33000000
    val candidateHighlight = if (dark) 0x40FFFFFF else 0xCCFFFFFF.toInt()

    val keyRadius = (if (liquid) 9.5f else 8.5f) * dp

    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = (if (liquid) 1.2f else 1f) * dp
    }
    private val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = BitmapShader(grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        alpha = if (dark) 40 else 70
    }
    private val grainBase = if (dark) 40 else 70
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        color = text
    }
    val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.7f * dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = text
    }
    val iconFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = text
    }

    // Unit-height gradients, stretched onto each key with a local matrix (no allocation per frame).
    private val sheen = LinearGradient(
        0f, 0f, 0f, 1f,
        intArrayOf(if (liquid) (if (dark) 0x33FFFFFF else 0x73FFFFFF) else (if (dark) 0x12FFFFFF else 0x24FFFFFF), 0x00FFFFFF, 0x00FFFFFF),
        floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
    )
    private val glow = LinearGradient(
        0f, 0f, 0f, 1f,
        intArrayOf(0x00FFFFFF, 0x00FFFFFF, if (dark) 0x26FFFFFF else 0x59FFFFFF),
        floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP
    )
    private val rimShader = LinearGradient(
        0f, 0f, 0f, 1f,
        if (liquid) intArrayOf(if (dark) 0x8CFFFFFF.toInt() else 0xFFFFFFFF.toInt(), if (dark) 0x0FFFFFFF else 0x1AFFFFFF, if (dark) 0x40FFFFFF else 0x99FFFFFF.toInt())
        else intArrayOf(if (dark) 0x40FFFFFF else 0x99FFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF),
        floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
    )
    private val convexShade = LinearGradient(
        0f, 0f, 0f, 1f,
        intArrayOf(0x00000000, 0x00000000, if (dark) 0x33000000 else 0x1A000000),
        floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
    )
    private val panelSheen = LinearGradient(
        0f, 0f, 1f, 1f,
        intArrayOf(if (dark) 0x14FFFFFF else 0x4DFFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP
    )
    private val m = Matrix()
    private val tmp = RectF()
    private val path = Path()
    private val keyPath = Path()

    private fun stretch(s: Shader, r: RectF): Shader {
        m.setScale(1f, r.height())
        m.postTranslate(0f, r.top)
        s.setLocalMatrix(m)
        return s
    }

    /** One glass key: shadow, body, sheen, refraction glow and the bright rim. */
    fun drawKey(c: Canvas, r: RectF, color: Int, radius: Float = keyRadius) {
        // shadow: a soft wide one and a tight contact one, kept outside the (translucent) body
        c.save()
        keyPath.reset()
        keyPath.addRoundRect(r, radius, radius, Path.Direction.CW)
        c.clipOutPath(keyPath)
        shadowPaint.color = withAlpha(if (dark) 0x40000000 else if (liquid) 0x14000000 else 0x1A000000, keyAlpha)
        tmp.set(r.left - 0.5f * dp, r.top + 1.5f * dp, r.right + 0.5f * dp, r.bottom + 2.2f * dp)
        c.drawRoundRect(tmp, radius + dp, radius + dp, shadowPaint)
        shadowPaint.color = withAlpha(if (dark) 0x59000000 else if (liquid) 0x26000000 else 0x30000000, keyAlpha)
        tmp.set(r.left, r.top + 0.8f * dp, r.right, r.bottom + 0.9f * dp)
        c.drawRoundRect(tmp, radius, radius, shadowPaint)
        c.restore()

        fill.shader = null
        fill.color = color
        c.drawRoundRect(r, radius, radius, fill)

        if (solid) return
        overlayPaint.alpha = (255 * keyAlpha).toInt()
        grainPaint.alpha = (grainBase * keyAlpha).toInt()
        overlayPaint.shader = stretch(sheen, r)
        c.drawRoundRect(r, radius, radius, overlayPaint)
        if (params.convex) {
            // domed glass: a second, tighter sheen on top and a shade in the lower part
            overlayPaint.shader = stretch(sheen, r)
            c.drawRoundRect(r, radius, radius, overlayPaint)
            overlayPaint.shader = stretch(convexShade, r)
            c.drawRoundRect(r, radius, radius, overlayPaint)
        }
        if (liquid) {
            overlayPaint.shader = stretch(glow, r)
            c.drawRoundRect(r, radius, radius, overlayPaint)
        } else {
            c.drawRoundRect(r, radius, radius, grainPaint)
        }
        overlayPaint.shader = null
        overlayPaint.alpha = 255
        if (!params.rim) return

        rimPaint.shader = stretch(rimShader, r)
        rimPaint.alpha = (255 * keyAlpha).toInt()
        tmp.set(r.left + 0.6f * dp, r.top + 0.6f * dp, r.right - 0.6f * dp, r.bottom - 0.6f * dp)
        c.drawRoundRect(tmp, radius - 0.6f * dp, radius - 0.6f * dp, rimPaint)
        rimPaint.shader = null
        rimPaint.alpha = 255
    }

    /** Glass surface for popups (any outline). */
    fun drawShape(c: Canvas, shape: Path, bounds: RectF, color: Int, alpha: Int = 255) {
        fill.shader = null
        fill.color = color
        fill.alpha = (Color.alpha(color) * alpha) / 255
        c.drawPath(shape, fill)
        fill.alpha = 255
        if (solid) return
        overlayPaint.shader = stretch(sheen, bounds)
        overlayPaint.alpha = alpha
        c.drawPath(shape, overlayPaint)
        overlayPaint.shader = null
        overlayPaint.alpha = 255
        if (!params.rim) return
        rimPaint.shader = stretch(rimShader, bounds)
        rimPaint.alpha = alpha
        c.drawPath(shape, rimPaint)
        rimPaint.shader = null
        rimPaint.alpha = 255
    }

    /** The keyboard sheet from [top] to the bottom of the view. */
    fun drawPanel(c: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val r = 24f * dp
        fill.shader = LinearGradient(0f, top, 0f, bottom, panelTop, panelBottom, Shader.TileMode.CLAMP)
        path.reset()
        tmp.set(left, top, right, bottom + r)
        path.addRoundRect(tmp, floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW)
        c.drawPath(path, fill)
        fill.shader = null
        if (solid) return
        if (liquid) {
            // light falling diagonally across the glass
            m.setScale((right - left) * 0.6f, (bottom - top) * 0.6f)
            m.postTranslate(left, top)
            panelSheen.setLocalMatrix(m)
            overlayPaint.shader = panelSheen
            overlayPaint.alpha = (255 * panelAlpha).toInt()
            c.drawPath(path, overlayPaint)
            overlayPaint.shader = null
            overlayPaint.alpha = 255
        } else {
            grainPaint.alpha = (grainBase * panelAlpha).toInt()
            c.drawPath(path, grainPaint)
        }
        if (!params.rim) return
        c.save()
        c.clipRect(left, top, right, top + r)
        tmp.set(left + 0.5f * dp, top + 0.5f * dp, right - 0.5f * dp, bottom + r)
        rimPaint.color = panelRim
        rimPaint.strokeWidth = 1f * dp
        c.drawRoundRect(tmp, r, r, rimPaint)
        rimPaint.strokeWidth = (if (liquid) 1.2f else 1f) * dp
        c.restore()
    }

    companion object {
        /** Fine random grain for the frosted material (generated once). */
        val grain: Bitmap by lazy {
            val n = 96
            val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            val rnd = Random(7)
            val px = IntArray(n * n)
            for (i in px.indices) {
                val a = rnd.nextInt(26)
                px[i] = if (rnd.nextBoolean()) Color.argb(a, 255, 255, 255) else Color.argb(a / 2, 0, 0, 0)
            }
            bmp.setPixels(px, 0, n, 0, 0, n, n)
            bmp
        }

        fun mixRgb(a: Int, b: Int, t: Float): Int = Color.argb(
            Color.alpha(a),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
        )

        fun withAlpha(c: Int, f: Float): Int = (c and 0x00FFFFFF) or ((Color.alpha(c) * f).toInt().coerceIn(0, 255) shl 24)

        fun lerpColor(a: Int, b: Int, t: Float): Int {
            val u = t.coerceIn(0f, 1f)
            return Color.argb(
                (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * u).toInt(),
                (Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt(),
            )
        }
    }
}
