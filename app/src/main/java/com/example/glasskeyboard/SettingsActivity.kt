package com.example.glasskeyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Setup steps, a test field and all settings — built in code, in the same glass style. */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var content: LinearLayout
    private lateinit var status1: TextView
    private lateinit var status2: TextView
    private var dark = false
    private val dp get() = resources.displayMetrics.density

    private val textColor get() = if (dark) Color.WHITE else 0xFF111114.toInt()
    private val softColor get() = if (dark) 0xA6FFFFFF.toInt() else 0x99000000.toInt()
    private val accent = 0xFF0A84FF.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        window.decorView.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (dark) intArrayOf(0xFF0E1015.toInt(), 0xFF1A1730.toInt(), 0xFF101820.toInt())
            else intArrayOf(0xFFE6EEFB.toInt(), 0xFFF4EEFA.toInt(), 0xFFE9F6F4.toInt())
        )
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (!dark) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        build()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshStatus()
    }

    private fun build() {
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.fitsSystemWindows = true
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding((18 * dp).toInt(), (22 * dp).toInt(), (18 * dp).toInt(), (40 * dp).toInt())
        scroll.addView(content)
        setContentView(scroll)

        content.addView(text("Glass Keyboard", 32f, textColor, bold = true))
        content.addView(text("Folyékony üveg billentyűzet • magyar és angol • v${version()}", 14f, softColor).also {
            (it.layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = (14 * dp).toInt()
        })

        // ---- setup
        val setup = card("Beállítás")
        status1 = text("", 15f, textColor)
        status2 = text("", 15f, textColor)
        setup.addView(status1)
        setup.addView(button("1. Bekapcsolás a rendszerben") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        setup.addView(status2)
        setup.addView(button("2. Glass Keyboard kiválasztása") {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })
        val test = EditText(this)
        test.hint = "Próbáld ki itt…"
        test.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        test.setTextColor(textColor)
        test.setHintTextColor(softColor)
        test.background = rounded(if (dark) 0x22FFFFFF else 0x99FFFFFF.toInt(), 14f)
        test.setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
        test.minLines = 2
        setup.addView(test, lp(top = 10))

        // ---- languages
        val langs = card("Nyelvek")
        langs.addView(switchRow("Magyar", "QWERTZ kiosztás, magyar szótár és javítás", Prefs.K_HU, true) { on ->
            if (!on && !prefs.englishEnabled) {
                prefs.englishEnabled = true
                recreate()
            }
        })
        langs.addView(switchRow("English", "QWERTY layout, English dictionary", Prefs.K_EN, true) { on ->
            if (!on && !prefs.hungarianEnabled) {
                prefs.hungarianEnabled = true
                recreate()
            }
        })
        langs.addView(hint("Váltás: a földgömb gombbal a billentyűzet alatt. Hosszan nyomva: billentyűzetváltó."))

        // ---- typing
        val typing = card("Gépelés")
        typing.addView(switchRow("Automatikus javítás", "Elgépelések és hiányzó ékezetek javítása szóköznél (jo → jó, koszonom → köszönöm). Visszatörléssel visszavonható.", Prefs.K_AUTOCORRECT, true))
        typing.addView(switchRow("Eszköztár a javaslatsávban", "Amíg nem gépelsz: emoji, vágólap, nyelv, billentyűzet elrejtése, beállítások. Gépeléskor szójavaslatok; a › gombbal előhozható.", Prefs.K_TOOLBAR, true))
        typing.addView(switchRow("Javaslatsáv", "Szójavaslatok és következő szó a billentyűk fölött", Prefs.K_SUGGESTIONS, true))
        typing.addView(switchRow("Szavak tanulása", "Megjegyzi a saját szavaidat és szófordulataidat", Prefs.K_LEARN, true))
        typing.addView(switchRow("Számsor", "Külön számsor (1–0) a betűk fölött", Prefs.K_NUMBER_ROW, false))
        typing.addView(switchRow("Pont a szóköz mellett", "„.” gomb a szóköz jobb oldalán (hosszan nyomva: , ? ! … : ;)", Prefs.K_PERIOD_KEY, false))
        typing.addView(switchRow("Ékezetsor", "Külön sor az á é í ó ö ő ú ü ű betűknek (magyarnál)", Prefs.K_ACCENT_ROW, false))
        typing.addView(switchRow("Automatikus nagybetű", "Mondat elején nagybetűvel kezd", Prefs.K_AUTOCAPS, true))
        typing.addView(switchRow("Dupla szóköz = pont", "Két szóköz gyorsan egymás után: „. ”", Prefs.K_DOUBLE_SPACE, true))
        typing.addView(switchRow("Okos idézőjelek", "\" helyett „ és ” (angolnál “ ”)", Prefs.K_SMART_QUOTES, true))
        typing.addView(switchRow("Emoji javaslatok", "Pl. pizza → 🍕, szeretlek → ❤️", Prefs.K_EMOJI_SUGG, true))
        typing.addView(switchRow("Szóköz mint kurzor", "Szóközt hosszan nyomva vagy elhúzva mozgatható a kurzor", Prefs.K_SPACE_CURSOR, true))

        // ---- glass (same set as in Glass Browser)
        val glass = card("Üveghatás")
        glass.addView(choiceRow("Üveg típusa", arrayOf("Folyékony üveg (fénytörés)", "Matt üveg (elmosás, fénytörés nélkül)", "Kikapcsolva (tömör felületek, üveg nélkül)"), Prefs.K_GLASS, 0))
        glass.addView(choiceRow("Üveg árnyalata", arrayOf("Rendszer szerint (világos / sötét)", "Világos", "Sötét", "Átlátszó"), Prefs.K_THEME, 0))
        glass.addView(choiceRow("Eszköztár gombjainak háttere", arrayOf("Liquid glass – átlátszó, fénytörő", "Frosted glass – matt, elmosott", "Tömör"), Prefs.K_TOOL_GLASS, 0))
        glass.addView(choiceRow("Betűbuborék anyaga (gépeléskor)", arrayOf("Liquid glass – átlátszó, fénytörő", "Frosted glass – matt, elmosott", "Tömör"), Prefs.K_PREVIEW_GLASS, 2))
        glass.addView(choiceRow("Hosszú nyomás menü (ékezetválasztó) anyaga", arrayOf("Liquid glass – átlátszó, fénytörő", "Frosted glass – matt, elmosott", "Tömör"), Prefs.K_POPUP_GLASS, 0))
        glass.addView(choiceRow("Kijelölő kapszula (javaslat és ékezetválasztó)", arrayOf("Liquid glass (fénytörés)", "Frosted glass (matt, elmosott)"), Prefs.K_LENS_STYLE, 0))
        glass.addView(hueRow("Üveg színe", Prefs.K_HUE, 210))
        glass.addView(seekRow("Szín erőssége (0 = színtelen üveg)", Prefs.K_HUE_STRENGTH, 0, 70, 0, ""))
        glass.addView(seekRow("Elmosás", Prefs.K_BLUR, 0, 24, 2, ""))
        glass.addView(seekRow("Fénytörés magassága", Prefs.K_REFR_H, 12, 50, 30, ""))
        glass.addView(seekRow("Fénytörés eltolása", Prefs.K_REFR_OFF, 20, 120, 50, ""))
        glass.addView(seekRow("Színszóródás", Prefs.K_DISP, 0, 100, 0, ""))
        glass.addView(seekRow("Üveg sötétítése", Prefs.K_GLASS_DARK, 0, 80, 0, ""))
        glass.addView(seekRow("Gombok sötétítése", Prefs.K_KEY_DARK, 0, 80, 0, ""))
        glass.addView(switchRow("Körív az üvegen", "Vékony fényív az üvegfelületek (gombok, lap, kijelölő) szélén.", Prefs.K_RIM, true))
        glass.addView(switchRow("Domború (3D) üveg", "Az üvegfelületek domborúnak, vastagnak látszanak: fénycsillanás a peremen, erősebb fénytörés a széleken.", Prefs.K_CONVEX, false))
        glass.addView(actionRow("Alapértelmezett üvegbeállítások visszaállítása", "Az üveg típusa, árnyalata, színe, átlátszósága, az elmosás, a fénytörés és a színszóródás visszaáll az eredeti értékre.") {
            AlertDialog.Builder(this).setMessage("Visszaállítod az üvegbeállításokat?")
                .setPositiveButton("Visszaállítás") { _, _ ->
                    prefs.resetGlass()
                    recreate()
                }
                .setNegativeButton("Mégse", null).show()
        })

        // ---- look & feel
        val look = card("Megjelenés és visszajelzés")
        look.addView(seekRow("Háttér átlátszatlansága", Prefs.K_PANEL_OPACITY, 0, 100, 100, "%"))
        look.addView(seekRow("Gombok átlátszatlansága", Prefs.K_KEY_OPACITY, 15, 100, 100, "%"))
        look.addView(seekRow("Billentyűmagasság", Prefs.K_KEY_HEIGHT, 80, 130, 100, "%"))
        look.addView(seekRow("Alsó sáv magassága (földgömb, mikrofon)", Prefs.K_STRIP_HEIGHT, 28, 80, 46, " dp"))
        look.addView(switchRow("Animációk", "Felugró buborékok, rugalmas ékezetválasztó, áttűnések", Prefs.K_ANIM, true))
        look.addView(choiceRow("Javaslatok animációja", arrayOf("Áttűnés", "Felúszás", "Nagyítás", "Gördülés", "Nincs"), Prefs.K_SUGG_ANIM, 0))
        look.addView(choiceRow("Gombérintés effekt", arrayOf("Kiemelkedés", "Benyomódás", "Fénylés", "Nincs"), Prefs.K_PRESS_ANIM, 0))
        look.addView(choiceRow("Betűbuborék gépeléskor", arrayOf("Rugós felugrás", "Felúszás", "Áttűnés", "Azonnali"), Prefs.K_PREVIEW_ANIM, 0))
        look.addView(choiceRow("Hosszú nyomás (ékezetválasztó)", arrayOf("Rugós kinyílás", "Szétnyílás oldalra", "Áttűnés"), Prefs.K_POPUP_ANIM, 0))
        look.addView(switchRow("Rezgés javaslatra koppintáskor", "Egy rövid, határozott haptikus jelzés", Prefs.K_CHAOS, true))
        look.addView(seekRow("Animációk sebessége (nagyobb = gyorsabb)", Prefs.K_ANIM_SPEED, 50, 200, 100, "%"))
        look.addView(choiceRow("Megnyílási animáció", arrayOf("Felcsúszás, fokozatosan lassulva", "Rugós felcsúszás", "Áttűnés és nagyítás", "Egyenletes felcsúszás", "Nincs"), Prefs.K_OPEN_ANIM, 0))
        look.addView(seekRow("Megnyílás időtartama (kisebb = gyorsabb)", Prefs.K_OPEN_MS, 100, 900, 320, " ms"))
        look.addView(switchRow("Billentyű-előnézet", "Felnagyított betű buborék gépeléskor", Prefs.K_PREVIEW, true))
        look.addView(choiceRow("Rezgés", arrayOf("Ki", "Enyhe", "Erős"), Prefs.K_HAPTICS, 1))
        look.addView(switchRow("Hang", "Kattanó hang gépeléskor", Prefs.K_SOUND, false))
        look.addView(switchRow("Mikrofon gomb", "Hangalapú bevitel (pl. Google hangbevitel) a billentyűzet alatt", Prefs.K_MIC, true))

        // ---- GIF
        val gif = card("GIF")
        gif.addView(hint("A Tenor GIF szolgáltatást a Google 2026. június 30-án lezárta a külső alkalmazások elől, ezért a billentyűzet a KLIPY-t használja (ugyanolyan GIF-kereső). Ehhez egy ingyenes API kulcs kell: regisztrálj a partner.klipy.com oldalon, hozz létre egy platformot, és másold ide a kulcsot."))
        val keyField = EditText(this)
        keyField.hint = "KLIPY API kulcs"
        keyField.setText(prefs.klipyKey)
        keyField.isSingleLine = true
        keyField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        keyField.setTextColor(textColor)
        keyField.setHintTextColor(softColor)
        keyField.background = rounded(if (dark) 0x22FFFFFF else 0x99FFFFFF.toInt(), 14f)
        keyField.setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
        gif.addView(keyField, lp(top = 8))
        gif.addView(button("Kulcs mentése") {
            prefs.sp.edit().putString(Prefs.K_KLIPY, keyField.text.toString().trim()).apply()
            android.widget.Toast.makeText(this, "Elmentve", android.widget.Toast.LENGTH_SHORT).show()
        })

        // ---- learned words
        val words = card("Tanult szavak")
        words.addView(actionRow("Magyar tanult szavak", "Megtekintés, törlés") { showWords("hu") })
        words.addView(actionRow("English learned words", "View, delete") { showWords("en") })

        content.addView(hint("A szótárak a wordfreq és az OpenSubtitles (FrequencyWords) adataiból készültek (CC BY-SA 4.0). Minden a telefonon marad; a billentyűzet nem használ internetet."))
    }

    private fun refreshStatus() {
        if (!::status1.isInitialized) return
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val selected = current.startsWith("$packageName/")
        status1.text = if (enabled) "✅  Bekapcsolva" else "⚪  Még nincs bekapcsolva"
        status2.text = if (selected) "✅  Kiválasztva, használatban" else "⚪  Még nincs kiválasztva"
    }

    private fun version(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    // ------------------------------------------------------------------ learned words

    private fun showWords(lang: String) {
        val ud = Shared.userDict(this, lang)
        val all = ud.all().filter { it.value >= 1 }
        if (all.isEmpty()) {
            AlertDialog.Builder(this).setTitle(if (lang == "hu") "Magyar tanult szavak" else "English learned words")
                .setMessage("Még nincs tanult szó.").setPositiveButton("OK", null).show()
            return
        }
        val labels = all.map { "${it.key}   ×${it.value}" }
        AlertDialog.Builder(this)
            .setTitle(if (lang == "hu") "Magyar tanult szavak (${all.size})" else "English learned words (${all.size})")
            .setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)) { _, which ->
                val w = all[which].key
                AlertDialog.Builder(this).setMessage("Törlöd ezt: „$w”?")
                    .setPositiveButton("Törlés") { _, _ ->
                        ud.remove(w)
                        Thread { ud.save() }.start()
                    }
                    .setNegativeButton("Mégse", null).show()
            }
            .setNeutralButton("Összes törlése") { _, _ ->
                AlertDialog.Builder(this).setMessage("Minden tanult szót törölsz ebben a nyelvben?")
                    .setPositiveButton("Törlés") { _, _ ->
                        ud.clear()
                        Thread { ud.save() }.start()
                    }
                    .setNegativeButton("Mégse", null).show()
            }
            .setPositiveButton("Bezárás", null)
            .show()
    }

    // ------------------------------------------------------------------ UI helpers

    private fun lp(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = (top * dp).toInt()
        bottomMargin = (bottom * dp).toInt()
    }

    private fun rounded(color: Int, radius: Float, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius * dp
        if (stroke != 0) setStroke((1 * dp).toInt().coerceAtLeast(1), stroke)
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        layoutParams = lp()
        setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt())
    }

    private fun hint(s: String) = text(s, 13f, softColor).apply {
        (layoutParams as LinearLayout.LayoutParams).topMargin = (6 * dp).toInt()
    }

    private fun card(title: String): LinearLayout {
        content.addView(text(title.uppercase(), 13f, softColor, bold = true).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = (18 * dp).toInt()
            setPadding((6 * dp).toInt(), 0, 0, (6 * dp).toInt())
        })
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = rounded(if (dark) 0x1FFFFFFF else 0xB3FFFFFF.toInt(), 22f, if (dark) 0x26FFFFFF else 0xE6FFFFFF.toInt())
        c.setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
        c.elevation = 0f
        content.addView(c, lp())
        return c
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        background = rounded(accent, 14f)
        stateListAnimator = null
        setOnClickListener { onClick() }
        layoutParams = lp(top = 4, bottom = 8)
        minHeight = (46 * dp).toInt()
    }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, (9 * dp).toInt(), 0, (9 * dp).toInt())
        layoutParams = lp()
    }

    private fun labels(title: String, sub: String?): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.addView(text(title, 16f, textColor))
        if (!sub.isNullOrEmpty()) l.addView(text(sub, 13f, softColor))
        l.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        return l
    }

    private fun switchRow(title: String, sub: String?, key: String, def: Boolean, onChange: ((Boolean) -> Unit)? = null): View {
        val r = row()
        r.addView(labels(title, sub))
        val sw = LiquidToggle(this, prefs.sp.getBoolean(key, def), dark) { on ->
            prefs.sp.edit().putBoolean(key, on).apply()
            onChange?.invoke(on)
        }
        r.addView(sw)
        r.setOnClickListener { sw.toggle(); sw.postInvalidateOnAnimation() }
        return r
    }

    private fun choiceRow(title: String, options: Array<String>, key: String, def: Int): View {
        val r = row()
        val l = labels(title, options.getOrElse(prefs.sp.getInt(key, def)) { options[0] })
        r.addView(l)
        r.setOnClickListener {
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(options, prefs.sp.getInt(key, def)) { d, which ->
                    prefs.sp.edit().putInt(key, which).apply()
                    (l.getChildAt(1) as? TextView)?.text = options[which]
                    d.dismiss()
                }.show()
        }
        return r
    }

    private fun seekRow(title: String, key: String, min: Int, max: Int, def: Int, suffix: String): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        val label = text("$title: ${prefs.sp.getInt(key, def)}$suffix", 16f, textColor)
        box.addView(label)
        val sb = LiquidSlider(this, min, max, prefs.sp.getInt(key, def), dark) { v ->
            label.text = "$title: $v$suffix"
            prefs.sp.edit().putInt(key, v).apply()
        }
        box.addView(sb, lp(top = 2))
        return box
    }

    /** Colour (hue) slider with a rainbow track. */
    private fun hueRow(title: String, key: String, def: Int): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        val label = text(title, 16f, textColor)
        box.addView(label)
        val swatch = View(this)
        fun col(h: Int) = Color.HSVToColor(floatArrayOf(h.toFloat(), 0.6f, 1f))
        swatch.background = rounded(col(prefs.sp.getInt(key, def)), 8f)
        val sb = LiquidSlider(this, 0, 360, prefs.sp.getInt(key, def), dark, IntArray(7) { col(it * 60) }) { v ->
            (swatch.background as? GradientDrawable)?.setColor(col(v))
            prefs.sp.edit().putInt(key, v).apply()
        }
        val line = LinearLayout(this)
        line.orientation = LinearLayout.HORIZONTAL
        line.gravity = Gravity.CENTER_VERTICAL
        line.addView(sb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(swatch, LinearLayout.LayoutParams((26 * dp).toInt(), (26 * dp).toInt()))
        box.addView(line, lp(top = 6))
        return box
    }

    private fun actionRow(title: String, sub: String, onClick: () -> Unit): View {
        val r = row()
        r.addView(labels(title, sub))
        r.addView(text("›", 22f, softColor).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) })
        r.setOnClickListener { onClick() }
        return r
    }
}
