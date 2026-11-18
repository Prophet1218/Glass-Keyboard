package com.example.glasskeyboard

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build

/**
 * A real "liquid glass" capsule: it refracts what lies under it. The content below is magnified,
 * bent strongly near the rim (like the edge of a thick glass lens), split into colours a little
 * at the very edge (chromatic aberration), and lit with a specular highlight on top and a soft
 * glow at the bottom. Uses an AGSL runtime shader (Android 13+); older systems get a magnifier
 * with highlights.
 */
class LiquidLens(private val dp: Float) {

    private var shader: RuntimeShader? = if (Build.VERSION.SDK_INT >= 33) try {
        RuntimeShader(AGSL)
    } catch (_: Throwable) {
        null
    } else null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fallback = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private val path = Path()
    private val tmp = RectF()
    private var content: Bitmap? = null
    private var contentShader: BitmapShader? = null
    private var contentLeft = 0f
    private var contentTop = 0f

    /** What lies under the lens: a bitmap whose top-left corner is at ([left], [top]) in canvas coordinates. */
    fun setContent(bmp: Bitmap, left: Float, top: Float) {
        if (bmp !== content) {
            content = bmp
            contentShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        contentLeft = left
        contentTop = top
        m.setTranslate(left, top)
        contentShader?.setLocalMatrix(m)
    }

    /**
     * Draws the lens over [r].
     * @param mag magnification of the content (1 = none)
     * @param refraction how far the rim bends the content, in px
     * @param tint glass body colour (ARGB, alpha = how milky the glass is)
     */
    fun draw(c: Canvas, r: RectF, radius: Float, mag: Float, refraction: Float, tint: Int, dark: Boolean, alpha: Float = 1f, shadow: Boolean = true) {
        if (alpha <= 0f) return
        if (shadow) {
            c.save()
            path.reset()
            path.addRoundRect(r, radius, radius, Path.Direction.CW)
            c.clipOutPath(path)
            shadowPaint.color = Color.argb((if (dark) 90 else 46) * alpha.coerceIn(0f, 1f), 0f, 0f, 0f)
            tmp.set(r.left - 0.5f * dp, r.top + 1.5f * dp, r.right + 0.5f * dp, r.bottom + 3f * dp)
            c.drawRoundRect(tmp, radius + dp, radius + dp, shadowPaint)
            c.restore()
        }
        val sh = shader
        val cs = contentShader
        if (sh != null && cs != null) try {
            sh.setInputShader("content", cs)
            sh.setFloatUniform("center", r.centerX(), r.centerY())
            sh.setFloatUniform("halfSize", r.width() / 2f, r.height() / 2f)
            sh.setFloatUniform("radius", minOf(radius, r.height() / 2f, r.width() / 2f))
            sh.setFloatUniform("mag", mag)
            sh.setFloatUniform("refr", refraction)
            val a = Color.alpha(tint) / 255f
            sh.setFloatUniform("tint", Color.red(tint) / 255f * a, Color.green(tint) / 255f * a, Color.blue(tint) / 255f * a, a)
            sh.setFloatUniform("light", if (dark) 0f else 1f)
            sh.setFloatUniform("alpha", alpha.coerceIn(0f, 1f))
            paint.shader = sh
            tmp.set(r.left - 1f, r.top - 1f, r.right + 1f, r.bottom + 1f)
            c.drawRect(tmp, paint)
            paint.shader = null
            return
        } catch (_: Throwable) {
            // shader not usable on this device: fall back for good
            shader = null
            paint.shader = null
        }
        // fallback: magnified content + tinted body + rim light
        c.save()
        path.reset()
        path.addRoundRect(r, radius, radius, Path.Direction.CW)
        c.clipPath(path)
        fallback.color = tint
        fallback.alpha = (Color.alpha(tint) * alpha).toInt()
        c.drawRect(r, fallback)
        content?.let {
            c.save()
            c.scale(mag, mag, r.centerX(), r.centerY())
            fallback.alpha = (255 * alpha).toInt()
            c.drawBitmap(it, contentLeft, contentTop, fallback)
            c.restore()
        }
        c.restore()
        rimPaint.strokeWidth = 1.3f * dp
        rimPaint.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(0xF2FFFFFF.toInt(), 0x1AFFFFFF, 0x80FFFFFF.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        rimPaint.alpha = (255 * alpha).toInt()
        tmp.set(r.left + 0.6f * dp, r.top + 0.6f * dp, r.right - 0.6f * dp, r.bottom - 0.6f * dp)
        c.drawRoundRect(tmp, radius, radius, rimPaint)
        rimPaint.shader = null
    }

    companion object {
        private const val AGSL = """
uniform shader content;
uniform float2 center;
uniform float2 halfSize;
uniform float radius;
uniform float mag;
uniform float refr;
uniform float4 tint;
uniform float light;
uniform float alpha;

float sdRR(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r, r);
    return length(max(q, float2(0.0, 0.0))) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 xy) {
    float2 p = xy - center;
    float d = sdRR(p, halfSize, radius);
    float cover = 1.0 - smoothstep(-0.8, 0.8, d);
    if (cover <= 0.0) { return half4(0.0); }
    float h = min(halfSize.x, halfSize.y);
    float depth = clamp(-d / h, 0.0, 1.0);
    float bend = pow(1.0 - depth, 2.4);
    float2 n = float2(
        sdRR(p + float2(1.0, 0.0), halfSize, radius) - sdRR(p - float2(1.0, 0.0), halfSize, radius),
        sdRR(p + float2(0.0, 1.0), halfSize, radius) - sdRR(p - float2(0.0, 1.0), halfSize, radius));
    float nl = length(n);
    n = nl > 0.0001 ? n / nl : float2(0.0, -1.0);

    // refraction: magnify, and pull the content inwards near the rim
    float2 src = center + p / mag - n * bend * refr;
    float ca = bend * 2.2;
    float4 base = float4(content.eval(src));
    float4 cr = float4(content.eval(src - n * ca));
    float4 cb = float4(content.eval(src + n * ca));
    float4 col = float4(cr.r, base.g, cb.b, max(base.a, max(cr.a, cb.a)));

    // glass body behind the refracted content
    float4 res = col + tint * (1.0 - col.a);

    // light: specular along the top rim, soft glow along the bottom, thin bright edge
    float rim = pow(1.0 - depth, 5.0);
    float spec = rim * clamp(-n.y * 0.85 - n.x * 0.3, 0.0, 1.0);
    float glow = rim * clamp(n.y, 0.0, 1.0) * 0.5;
    float edge = 1.0 - smoothstep(0.0, 1.6, -d);
    float add = (spec * 0.85 + glow + edge * 0.35) * mix(0.6, 1.0, light);
    res = float4(res.rgb + float3(add, add, add), max(res.a, add));
    res = clamp(res, 0.0, 1.0);
    res.rgb = min(res.rgb, float3(res.a, res.a, res.a));
    return half4(res * (cover * alpha));
}
"""
    }
}
