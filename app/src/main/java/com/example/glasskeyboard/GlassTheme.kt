package com.example.glasskeyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/**
 * Colours and drawing helpers of the liquid-glass look (modelled on the iOS 26/27 keyboard):
 * a frosted, slightly tinted sheet with rounded top corners, translucent rounded keys with a
 * bright rim at the top edge and a soft shadow, darker glass for function keys.
 */
class GlassTheme(context: Context, prefTheme: Int) {
    val dp = context.resources.displayMetrics.density
    val sp = context.resources.displayMetrics.scaledDensity

    val dark: Boolean = when (prefTheme) {
        1 -> false
        2 -> true
        else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    val panelTop = if (dark) 0xFF2B2C30.toInt() else 0xFFF3F4F8.toInt()
    val panelBottom = if (dark) 0xFF1B1C1F.toInt() else 0xFFD5D8E0.toInt()
    val panelRim = if (dark) 0x40FFFFFF else 0xCCFFFFFF.toInt()
    val key = if (dark) 0xFF6A6B70.toInt() else 0xFFFFFFFF.toInt()
    val keySpecial = if (dark) 0xFF404146.toInt() else 0xFFC6CAD4.toInt()
    val keyPressed = if (dark) 0xFF404146.toInt() else 0xFFC6CAD4.toInt()
    val keySpecialPressed = if (dark) 0xFF6A6B70.toInt() else 0xFFFFFFFF.toInt()
    val keyActive = if (dark) 0xFFF2F2F7.toInt() else 0xFFFFFFFF.toInt() // shift on
    val text = if (dark) Color.WHITE else Color.BLACK
    val textSoft = if (dark) 0xB3FFFFFF.toInt() else 0x99000000.toInt()
    val activeIcon = Color.BLACK
    val accent = 0xFF0A84FF.toInt()
    val accentPressed = 0xFF0066D6.toInt()
    val shadow = if (dark) 0x66000000 else 0x38000000
    val rim = if (dark) 0x33FFFFFF else 0xB3FFFFFF.toInt()
    val divider = if (dark) 0x33FFFFFF else 0x26000000
    val popup = if (dark) 0xFF707176.toInt() else 0xFFFFFFFF.toInt()
    val popupShadow = if (dark) 0x80000000.toInt() else 0x40000000
    val candidateHighlight = if (dark) 0x40FFFFFF else 0xCCFFFFFF.toInt()

    val keyRadius = 8.5f * dp

    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = shadow }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * dp
        color = rim
    }
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        color = text
    }
    val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = text
    }
    val iconFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = text
    }

    private val tmp = RectF()
    private val path = Path()

    /** One glass key: shadow, body, and the bright rim along the top edge. */
    fun drawKey(c: Canvas, r: RectF, color: Int, radius: Float = keyRadius) {
        tmp.set(r.left, r.top + 1.1f * dp, r.right, r.bottom + 1.1f * dp)
        c.drawRoundRect(tmp, radius, radius, shadowPaint)
        fill.color = color
        fill.shader = null
        c.drawRoundRect(r, radius, radius, fill)
        // a soft vertical sheen makes the key read as glass rather than a flat tile
        fill.shader = LinearGradient(0f, r.top, 0f, r.bottom, if (dark) 0x14FFFFFF else 0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, fill)
        fill.shader = null
        c.save()
        c.clipRect(r.left - dp, r.top - dp, r.right + dp, r.top + r.height() * 0.45f)
        tmp.set(r.left + 0.5f * dp, r.top + 0.5f * dp, r.right - 0.5f * dp, r.bottom - 0.5f * dp)
        c.drawRoundRect(tmp, radius, radius, rimPaint)
        c.restore()
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
        // light catching the upper edge of the glass
        c.save()
        c.clipRect(left, top, right, top + r)
        tmp.set(left + 0.5f * dp, top + 0.5f * dp, right - 0.5f * dp, bottom + r)
        rimPaint.color = panelRim
        c.drawRoundRect(tmp, r, r, rimPaint)
        rimPaint.color = rim
        c.restore()
    }
}
