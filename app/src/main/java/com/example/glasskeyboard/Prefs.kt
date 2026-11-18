package com.example.glasskeyboard

import android.content.Context
import android.content.SharedPreferences

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
        get() = sp.getString(K_LANG, "hu") ?: "hu"
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
    val showMic get() = b(K_MIC, true)
    val numberRow get() = b(K_NUMBER_ROW, false)
    val periodKey get() = b(K_PERIOD_KEY, false)
    val animations get() = b(K_ANIM, true)
    /** 0 = liquid glass, 1 = frosted glass */
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
    /** animation speed in percent (100 = normal) */
    val animSpeed get() = sp.getInt(K_ANIM_SPEED, 100)

    /** 0 = off, 1 = light, 2 = strong */
    val haptics get() = sp.getInt(K_HAPTICS, 1)
    /** 0 = follow system, 1 = light, 2 = dark */
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
    }
}
