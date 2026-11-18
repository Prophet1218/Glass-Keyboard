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
 * A real "liquid glass" capsule that refracts what lies under it.
 *
 * The refraction model follows Kyant's AndroidLiquidGlass (Apache License 2.0,
 * https://github.com/Kyant0/AndroidLiquidGlass): the inside of the glass shows the content
 * unchanged (optionally slightly magnified), and only a band along the rim bends it inwards with a
 * circular profile, with a little colour dispersion towards the corners. On top come an ambient
 * rim highlight (bright on the lit side, faintly dark on the other) and a soft body tint.
 * Android 13+ uses an AGSL runtime shader; older systems get a simple tinted magnifier.
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
    fun setContent(bmp: Bitmap, left: Float, top: Float, scale: Float = 1f) {
        if (bmp !== content) {
            content = bmp
            contentShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
                if (Build.VERSION.SDK_INT >= 33) it.filterMode = BitmapShader.FILTER_MODE_LINEAR
            }
        }
        contentLeft = left
        contentTop = top
        contentScale = scale
        m.setScale(scale, scale)
        m.postTranslate(left, top)
        contentShader?.setLocalMatrix(m)
    }

    private var contentScale = 1f

    /**
     * Draws the glass over [r].
     * @param mag magnification of the content inside (1 = none)
     * @param height width of the refracting rim band, px
     * @param amount how far the rim bends the content, px
     * @param tint glass body colour (ARGB; alpha = how milky the glass is)
     */
    fun draw(c: Canvas, r: RectF, radius: Float, mag: Float, t: GlassTheme, tint: Int, alpha: Float = 1f, shadow: Boolean = true) {
        // The settings are tuned for big glass (like the browser's search bar); on these small
        // capsules the rim band and its bend are scaled down so text under the glass stays clean.
        val small = minOf(r.width(), r.height()) / 2f
        val band = minOf(t.lensHeight, small * 0.42f)
        val frost = t.lensFrosted
        val amount = if (frost) 0f else t.lensAmount * (band / maxOf(t.lensHeight, 1f)) * 0.45f
        // frosted capsule: milky body, no refraction, softened view
        val body = if (frost && !t.solid) (if (t.dark) 0x38FFFFFF else 0x8CFFFFFF.toInt()) else tint
        drawRaw(
            c, r, radius, if (frost) 1f else mag, band, amount, if (frost) 0f else t.lensDispersion, t.lensBlur,
            t.params.rim, t.params.convex, body, t.dark, alpha, shadow, t.solid,
        )
    }

    /** Lens with explicit parameters (also used by the liquid slider and toggle). */
    fun drawRaw(
        c: Canvas, r: RectF, radius: Float, mag: Float, height: Float, amount: Float,
        dispersion: Float, blur: Float, rimOn: Boolean, convex: Boolean,
        tint: Int, dark: Boolean, alpha: Float = 1f, shadow: Boolean = true, solid: Boolean = false,
    ) {
        if (alpha <= 0f) return
        val rad = minOf(radius, r.height() / 2f, r.width() / 2f)
        if (shadow) {
            c.save()
            path.reset()
            path.addRoundRect(r, rad, rad, Path.Direction.CW)
            c.clipOutPath(path)
            for (i in 1..3) {
                shadowPaint.color = Color.argb(((if (dark) 18 else 9) * alpha).toInt(), 0, 0, 0)
                val g = i * 1.3f * dp
                tmp.set(r.left - g * 0.4f, r.top + g * 0.3f, r.right + g * 0.4f, r.bottom + g)
                c.drawRoundRect(tmp, rad + g, rad + g, shadowPaint)
            }
            c.restore()
        }
        if (solid) {
            // glass switched off: a plain capsule
            fallback.shader = null
            fallback.color = tint
            fallback.alpha = (Color.alpha(tint) * alpha).toInt()
            c.drawRoundRect(r, rad, rad, fallback)
            fallback.alpha = 255
            return
        }
        val sh = shader
        val cs = contentShader
        if (sh != null && cs != null) try {
            sh.setInputShader("content", cs)
            sh.setFloatUniform("center", r.centerX(), r.centerY())
            sh.setFloatUniform("halfSize", r.width() / 2f, r.height() / 2f)
            sh.setFloatUniform("radius", rad)
            sh.setFloatUniform("mag", mag)
            sh.setFloatUniform("refrHeight", maxOf(0.5f, minOf(height, rad, r.width() / 2f)))
            sh.setFloatUniform("refrAmount", amount)
            sh.setFloatUniform("dispersion", dispersion)
            sh.setFloatUniform("blur", blur)
            sh.setFloatUniform("rimOn", if (rimOn) 1f else 0f)
            sh.setFloatUniform("convex", if (convex) 1f else 0f)
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
        // fallback: tinted body + magnified content + rim light
        c.save()
        path.reset()
        path.addRoundRect(r, rad, rad, Path.Direction.CW)
        c.clipPath(path)
        content?.let {
            c.save()
            c.scale(mag, mag, r.centerX(), r.centerY())
            c.translate(contentLeft, contentTop)
            c.scale(contentScale, contentScale)
            fallback.alpha = (255 * alpha).toInt()
            c.drawBitmap(it, 0f, 0f, fallback)
            c.restore()
        }
        fallback.color = tint
        fallback.alpha = (Color.alpha(tint) * alpha).toInt()
        c.drawRect(r, fallback)
        c.restore()
        if (!rimOn) return
        rimPaint.strokeWidth = 1.2f * dp
        rimPaint.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(0xE6FFFFFF.toInt(), 0x14FFFFFF, 0x66FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        rimPaint.alpha = (255 * alpha).toInt()
        tmp.set(r.left + 0.6f * dp, r.top + 0.6f * dp, r.right - 0.6f * dp, r.bottom - 0.6f * dp)
        c.drawRoundRect(tmp, rad, rad, rimPaint)
        rimPaint.shader = null
    }

    companion object {
        private const val AGSL = """
uniform shader content;
uniform float2 center;
uniform float2 halfSize;
uniform float radius;
uniform float mag;
uniform float refrHeight;
uniform float refrAmount;
uniform float dispersion;
uniform float4 tint;
uniform float light;
uniform float alpha;
uniform float blur;
uniform float rimOn;
uniform float convex;

// what is under the glass, optionally softened (frosted look): 13-tap disc blur, no ghost copies
float4 look(float2 c) {
    if (blur < 0.5) { return float4(content.eval(c)); }
    float4 s = float4(content.eval(c)) * 0.16;
    for (int i = 0; i < 6; i++) {
        float a = float(i) * 1.0472;
        float2 o = float2(cos(a), sin(a));
        s += float4(content.eval(c + o * blur * 0.5)) * 0.08;
        s += float4(content.eval(c + float2(o.y, -o.x) * blur)) * 0.06;
    }
    return s;
}

// rounded-rectangle SDF and its gradient: adapted from Kyant's AndroidLiquidGlass (Apache-2.0)
float sdRoundedRect(float2 coord, float2 hs, float r) {
    float2 cornerCoord = abs(coord) - (hs - float2(r, r));
    float outside = length(max(cornerCoord, 0.0)) - r;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 hs, float r) {
    float2 cornerCoord = abs(coord) - (hs - float2(r, r));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        float2 m = max(cornerCoord, 0.0);
        float l = length(m);
        return sign(coord) * (l > 0.0001 ? m / l : float2(0.0, 1.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}

float circleMap(float x) {
    return 1.0 - sqrt(max(1.0 - x * x, 0.0));
}

half4 main(float2 xy) {
    float2 p = xy - center;
    float sd = sdRoundedRect(p, halfSize, radius);
    float cover = 1.0 - smoothstep(-0.75, 0.75, sd);
    if (cover <= 0.0) { return half4(0.0); }

    float2 base = center + p / mag;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = gradSdRoundedRect(p, halfSize, gradRadius);
    float s = min(sd, 0.0);

    float4 col;
    if (-s >= refrHeight) {
        col = look(base);
    } else {
        // the rim band bends the view inwards with a circular profile
        float d = circleMap(1.0 - (-s) / refrHeight) * (-refrAmount);
        float2 rc = base + d * grad;
        float di = dispersion * (p.x * p.y) / (halfSize.x * halfSize.y);
        float2 dc = d * grad * di;
        float4 cr = look(rc + dc);
        float4 cg = look(rc);
        float4 cb = look(rc - dc);
        col = float4(cr.r, cg.g, cb.b, (cr.a + cg.a + cb.a) / 3.0);
    }

    // glass body behind the refracted view
    float4 res = col + tint * (1.0 - col.a);

    // ambient rim highlight: bright on the side facing the light, faintly dark opposite
    float rim = 1.0 - smoothstep(0.0, 1.6, -sd);
    float dl = dot(grad, normalize(float2(-0.35, -1.0)));
    float lit = pow(abs(dl), 1.4) * rim * rimOn;
    float3 rgb = res.rgb;
    float a = res.a;
    if (dl > 0.0) {
        float w = lit * mix(0.55, 0.9, light);
        rgb = rgb + float3(w, w, w) * (1.0 - rgb);
        a = a + w * (1.0 - a);
    } else {
        rgb = rgb * (1.0 - lit * 0.18);
        a = a + lit * 0.12 * (1.0 - a);
    }
    // domed (3D) glass: a bright glint near the top-left and a soft bevel along the rim
    if (convex > 0.5) {
        float band = 1.0 - smoothstep(0.0, refrHeight, -sd);
        float bevel = band * dl * 0.22;
        rgb = clamp(rgb + float3(bevel, bevel, bevel) * a, 0.0, 1.0);
        float2 gp = (p - float2(-halfSize.x * 0.45, -halfSize.y * 0.45)) / max(halfSize.y, 1.0);
        float glint = exp(-dot(gp, gp) * 6.0) * 0.35;
        rgb = rgb + float3(glint, glint, glint) * (1.0 - rgb);
        a = a + glint * (1.0 - a);
    }
    // a soft sheen inside the upper rim
    float sheen = (1.0 - smoothstep(0.0, refrHeight * 1.4, -sd)) * clamp(-grad.y, 0.0, 1.0) * 0.10 * mix(0.6, 1.0, light);
    rgb = rgb + float3(sheen, sheen, sheen) * (1.0 - rgb);
    a = a + sheen * (1.0 - a);

    float4 outc = clamp(float4(rgb, a), 0.0, 1.0);
    outc.rgb = min(outc.rgb, float3(outc.a, outc.a, outc.a));
    return half4(outc * (cover * alpha));
}
"""
    }
}
