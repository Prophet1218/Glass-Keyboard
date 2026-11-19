package com.example.glasskeyboard

import android.content.Context
import android.content.SharedPreferences

/** Glass effect parameters (ranges as in Glass Browser). */
data class GlassParams(
    val hue: Int = 210,
    /** 0..70 % */
    val hueStrength: Int = 0,
    /** 0..24 */
    val blur: Int = 2,
    /** 12..50 */
    val refrHeight: Int = 30,
    /** 20..120 */
    val refrOffset: Int = 50,
    /** 0..100 */
    val dispersion: Int = 0,
    /** 0..80 % */
    val darken: Int = 0,
    /** extra darkening of the keys only, 0..80 % */
    val keyDarken: Int = 0,
    val rim: Boolean = true,
    val convex: Boolean = false,
    /** selection capsules (suggestion pill, accent picker): 0 liquid glass, 1 frosted glass */
    val lensStyle: Int = 0,
)

/** All settings of the keyboard in one place. */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("glass_keyboard", Context.MODE_PRIVATE)

    private fun b(key: String, def: Boolean) = sp.getBoolean(key, def)

    var hungarianEnabled: Boolean
        get() = b(K_HU, true)
        set(v) = sp.edit().putBoolean(K_HU, v).apply()
    var englishEnabled: Boolean
        get() = b(K_EN, true)
        set(v) = sp.edit().putBoolean(K_EN, v).apply()
    /** "hu" or "en": the language used last. */
    var language: String
        get() = sp.getString(K_LANG, null) ?: if (Ui.hu) "hu" else "en"
        set(v) = sp.edit().putString(K_LANG, v).apply()

    val accentRow get() = b(K_ACCENT_ROW, false)
    val autocorrect get() = b(K_AUTOCORRECT, true)
    val suggestions get() = b(K_SUGGESTIONS, true)
    val learnWords get() = b(K_LEARN, true)
    val autoCaps get() = b(K_AUTOCAPS, true)
    val doubleSpacePeriod get() = b(K_DOUBLE_SPACE, true)
    val smartQuotes get() = b(K_SMART_QUOTES, true)
    val emojiSuggestions get() = b(K_EMOJI_SUGG, true)
    val keyPreview get() = b(K_PREVIEW, true)
    val spaceCursor get() = b(K_SPACE_CURSOR, true)
    val sound get() = b(K_SOUND, false)
    val showMic get() = false
    /** background preset (GlassTheme.BACKGROUNDS): 0 glass, others solid colours */
    val background get() = sp.getInt(K_BG, 0)
    /** strip under the keys (gesture handle): 0 glass, 1 glass fading to solid, 2 solid */
    val navStyle get() = sp.getInt(K_NAV_STYLE, 0)
    val numberRow get() = b(K_NUMBER_ROW, false)
    val periodKey get() = b(K_PERIOD_KEY, false)
    val animations get() = b(K_ANIM, true)
    /** 0 = liquid glass, 1 = frosted glass, 2 = off (solid) */
    val glassStyle get() = sp.getInt(K_GLASS, 0)
    /** height of the strip under the keys (globe, mic) in dp */
    val stripHeight get() = sp.getInt(K_STRIP_HEIGHT, 46)
    /** opacity of the keyboard background and of the keys, percent */
    val panelOpacity get() = sp.getInt(K_PANEL_OPACITY, 100)
    val keyOpacity get() = sp.getInt(K_KEY_OPACITY, 100)
    /** opening animation: 0 = slide, slowing down; 1 = springy slide; 2 = fade & zoom; 3 = even slide; 4 = none */
    val openAnim get() = sp.getInt(K_OPEN_ANIM, 0)
    val openMs get() = sp.getInt(K_OPEN_MS, 320)
    /** suggestion change: 0 fade, 1 float up, 2 zoom, 3 roll, 4 none */
    val suggAnim get() = sp.getInt(K_SUGG_ANIM, 0)
    /** key touch: 0 lift (grow), 1 press in (shrink), 2 glow, 3 none */
    val pressAnim get() = sp.getInt(K_PRESS_ANIM, 0)
    /** typing balloon: 0 spring, 1 float up, 2 fade, 3 none */
    val previewAnim get() = sp.getInt(K_PREVIEW_ANIM, 0)
    /** long-press picker: 0 spring, 1 unfold sideways, 2 fade */
    val popupAnim get() = sp.getInt(K_POPUP_ANIM, 0)
    val chaos get() = b(K_CHAOS, true)
    /** Glass effect settings (same set as in Glass Browser). */
    fun glass() = GlassParams(
        hue = sp.getInt(K_HUE, 210),
        hueStrength = sp.getInt(K_HUE_STRENGTH, 0),
        blur = sp.getInt(K_BLUR, 2),
        refrHeight = sp.getInt(K_REFR_H, 30),
        refrOffset = sp.getInt(K_REFR_OFF, 50),
        dispersion = sp.getInt(K_DISP, 0),
        darken = sp.getInt(K_GLASS_DARK, 0),
        keyDarken = sp.getInt(K_KEY_DARK, 0),
        rim = b(K_RIM, true),
        convex = b(K_CONVEX, false),
        lensStyle = sp.getInt(K_LENS_STYLE, 0),
    )

    fun resetGlass() {
        sp.edit().remove(K_GLASS).remove(K_THEME).remove(K_HUE).remove(K_HUE_STRENGTH).remove(K_BLUR)
            .remove(K_REFR_H).remove(K_REFR_OFF).remove(K_DISP).remove(K_GLASS_DARK).remove(K_KEY_DARK).remove(K_RIM).remove(K_CONVEX).remove(K_LENS_STYLE).remove(K_POPUP_GLASS).remove(K_POPUP_BLUR).remove(K_PREVIEW_GLASS).remove(K_TOOL_GLASS)
            .remove(K_PANEL_OPACITY).remove(K_KEY_OPACITY).apply()
    }

    /** typing balloon material: 0 liquid glass, 1 frosted glass, 2 solid */
    val previewGlass get() = sp.getInt(K_PREVIEW_GLASS, 2)
    /** toolbar button background: 0 liquid glass, 1 frosted glass, 2 solid */
    val toolGlass get() = sp.getInt(K_TOOL_GLASS, 0)
    /** Klipy GIF API key (free from partner.klipy.com) */
    val klipyKey get() = sp.getString(K_KLIPY, "")?.trim() ?: ""

    /** toolbar in the suggestion bar while not typing */
    val toolbar get() = b(K_TOOLBAR, true)

    /** long-press picker material: 0 liquid glass, 1 frosted glass, 2 solid */
    val popupGlass get() = sp.getInt(K_POPUP_GLASS, 0)
    /** Long-press menu glass: 0 = clear glass … 100 = strongly blurred / milky. */
    val popupBlur get() = sp.getInt(K_POPUP_BLUR, 45)

    /** animation speed in percent (100 = normal) */
    val animSpeed get() = sp.getInt(K_ANIM_SPEED, 100)

    /** 0 = off, 1 = light, 2 = strong */
    val haptics get() = sp.getInt(K_HAPTICS, 1)
    /** 0 = follow system, 1 = light, 2 = dark, 3 = clear */
    val theme get() = sp.getInt(K_THEME, 0)
    /** key height in percent of the default */
    val keyHeight get() = sp.getInt(K_KEY_HEIGHT, 100)

    var recentEmoji: String
        get() = sp.getString(K_RECENT_EMOJI, "") ?: ""
        set(v) = sp.edit().putString(K_RECENT_EMOJI, v).apply()

    companion object {
        const val K_HU = "lang_hu"
        const val K_EN = "lang_en"
        const val K_LANG = "language"
        const val K_ACCENT_ROW = "accent_row"
        const val K_AUTOCORRECT = "autocorrect"
        const val K_SUGGESTIONS = "suggestions"
        const val K_LEARN = "learn"
        const val K_AUTOCAPS = "autocaps"
        const val K_DOUBLE_SPACE = "double_space"
        const val K_SMART_QUOTES = "smart_quotes"
        const val K_EMOJI_SUGG = "emoji_suggestions"
        const val K_PREVIEW = "key_preview"
        const val K_SPACE_CURSOR = "space_cursor"
        const val K_SOUND = "sound"
        const val K_MIC = "mic"
        const val K_NAV_STYLE = "nav_style3"
        const val K_UI_LANG = "ui_lang"
        const val K_BG = "background"
        const val K_HAPTICS = "haptics"
        const val K_THEME = "theme"
        const val K_KEY_HEIGHT = "key_height"
        const val K_RECENT_EMOJI = "recent_emoji"
        const val K_NUMBER_ROW = "number_row"
        const val K_PERIOD_KEY = "period_key"
        const val K_ANIM = "animations"
        const val K_GLASS = "glass_style"
        const val K_STRIP_HEIGHT = "strip_height"
        const val K_PANEL_OPACITY = "panel_opacity"
        const val K_KEY_OPACITY = "key_opacity"
        const val K_OPEN_ANIM = "open_anim"
        const val K_OPEN_MS = "open_ms"
        const val K_SUGG_ANIM = "sugg_anim"
        const val K_PRESS_ANIM = "press_anim"
        const val K_PREVIEW_ANIM = "preview_anim"
        const val K_POPUP_ANIM = "popup_anim"
        const val K_CHAOS = "chaos"
        const val K_ANIM_SPEED = "anim_speed"
        const val K_HUE = "glass_hue"
        const val K_HUE_STRENGTH = "glass_hue_strength"
        const val K_BLUR = "glass_blur"
        const val K_REFR_H = "glass_refraction_height"
        const val K_REFR_OFF = "glass_refraction_offset"
        const val K_DISP = "glass_dispersion"
        const val K_GLASS_DARK = "glass_dark"
        const val K_KEY_DARK = "key_dark"
        const val K_RIM = "glass_rim"
        const val K_CONVEX = "glass_convex"
        const val K_LENS_STYLE = "lens_style"
        const val K_POPUP_GLASS = "popup_glass"
        const val K_POPUP_BLUR = "popup_blur"
        const val K_PREVIEW_GLASS = "preview_glass"
        const val K_TOOLBAR = "toolbar"
        const val K_TOOL_GLASS = "tool_glass"
        const val K_KLIPY = "klipy_key"
    }
}
