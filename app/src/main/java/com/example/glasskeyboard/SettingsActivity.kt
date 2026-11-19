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
    companion object {
        private const val K_SCROLL = "settings_scroll"
    }


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
        DiagLog.installCrashLogger(this)
        Ui.load(this)
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

    private var scroll: ScrollView? = null
    private var testOut: TextView? = null
    private var testDict: Dictation? = null

    /** Runs dictation right here (in the foreground), to see whether the phone's recognizers work. */
    private fun testDictation() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 7)
            return
        }
        testDict?.let { if (it.active) { it.stop(); return } }
        val d = Dictation(this, android.os.Handler(mainLooper), object : Dictation.Callbacks {
            override fun status(text: String) { testOut?.text = text }
            override fun partial(text: String) { testOut?.text = "🎤 $text" }
            override fun result(text: String) { testOut?.text = "✅ $text" }
            override fun end(message: String?) { if (message != null) testOut?.text = "❌ $message" }
        })
        testDict = d
        try {
            d.start(Ui.hu)
        } catch (e: Throwable) {
            DiagLog.add(this, "mic", "test crashed: $e")
            testOut?.text = "❌ $e"
        }
    }

    override fun onDestroy() {
        testDict?.destroy()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        scroll?.let { prefs.sp.edit().putInt(K_SCROLL, it.scrollY).apply() }
    }

    private fun build() {
        val scroll = ScrollView(this)
        this.scroll = scroll
        scroll.isFillViewport = true
        scroll.fitsSystemWindows = true
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding((18 * dp).toInt(), (22 * dp).toInt(), (18 * dp).toInt(), (40 * dp).toInt())
        scroll.addView(content)
        setContentView(scroll)
        // the page itself holds the focus, so the test field does not pull the view to the top
        content.isFocusableInTouchMode = true
        content.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        // back where the page was left last time
        val y = prefs.sp.getInt(K_SCROLL, 0)
        scroll.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (content.height <= 0) return
                scroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                content.requestFocus()
                scroll.scrollTo(0, y)
            }
        })

        content.addView(text("Glass Keyboard", 32f, textColor, bold = true))
        content.addView(text(T("Folyékony üveg billentyűzet • magyar és angol") + " • v${version()}", 14f, softColor).also {
            (it.layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = (14 * dp).toInt()
        })

        // ---- setup
        val setup = card(T("Beállítás"))
        status1 = text("", 15f, textColor)
        status2 = text("", 15f, textColor)
        setup.addView(status1)
        setup.addView(button(T("1. Bekapcsolás a rendszerben")) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        setup.addView(status2)
        setup.addView(button(T("2. Glass Keyboard kiválasztása")) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })
        val test = EditText(this)
        test.hint = T("Próbáld ki itt…")
        test.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        test.setTextColor(textColor)
        test.setHintTextColor(softColor)
        test.background = rounded(if (dark) 0x22FFFFFF else 0x99FFFFFF.toInt(), 14f)
        test.setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
        test.minLines = 2
        setup.addView(test, lp(top = 10))

        // ---- languages
        val langs = card(T("Nyelvek"))
        langs.addView(choiceRowThen(T("Az alkalmazás nyelve"), arrayOf(T("A telefon nyelve"), "Magyar", "English"), Prefs.K_UI_LANG, 0) {
            Ui.load(this)
            recreate()
        })
        langs.addView(switchRow(T("Magyar"), T("QWERTZ kiosztás, magyar szótár és javítás"), Prefs.K_HU, true) { on ->
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
        langs.addView(hint(T("Váltás: a földgömb gombbal a billentyűzet alatt. Hosszan nyomva: billentyűzetváltó.")))

        // ---- typing
        val typing = card(T("Gépelés"))
        typing.addView(switchRow(T("Automatikus javítás"), T("Elgépelések és hiányzó ékezetek javítása szóköznél (jo → jó, koszonom → köszönöm). Visszatörléssel visszavonható."), Prefs.K_AUTOCORRECT, true))
        typing.addView(switchRow(T("Eszköztár a javaslatsávban"), T("Amíg nem gépelsz: emoji, vágólap, nyelv, billentyűzet elrejtése, beállítások. Gépeléskor szójavaslatok; a › gombbal előhozható."), Prefs.K_TOOLBAR, true))
        typing.addView(switchRow(T("Javaslatsáv"), T("Szójavaslatok és következő szó a billentyűk fölött"), Prefs.K_SUGGESTIONS, true))
        typing.addView(switchRow(T("Szavak tanulása"), T("Megjegyzi a saját szavaidat és szófordulataidat"), Prefs.K_LEARN, true))
        typing.addView(switchRow(T("Számsor"), T("Külön számsor (1–0) a betűk fölött"), Prefs.K_NUMBER_ROW, false))
        typing.addView(switchRow(T("Pont a szóköz mellett"), T("„.” gomb a szóköz jobb oldalán (hosszan nyomva: , ? ! … : ;)"), Prefs.K_PERIOD_KEY, false))
        typing.addView(switchRow(T("Ékezetsor"), T("Külön sor az á é í ó ö ő ú ü ű betűknek (magyarnál)"), Prefs.K_ACCENT_ROW, false))
        typing.addView(switchRow(T("Automatikus nagybetű"), T("Mondat elején nagybetűvel kezd"), Prefs.K_AUTOCAPS, true))
        typing.addView(switchRow(T("Dupla szóköz = pont"), T("Két szóköz gyorsan egymás után: „. ”"), Prefs.K_DOUBLE_SPACE, true))
        typing.addView(switchRow(T("Okos idézőjelek"), T("\" helyett „ és ” (angolnál “ ”)"), Prefs.K_SMART_QUOTES, true))
        typing.addView(switchRow(T("Emoji javaslatok"), T("Pl. pizza → 🍕, szeretlek → ❤️"), Prefs.K_EMOJI_SUGG, true))
        typing.addView(switchRow(T("Szóköz mint kurzor"), T("Szóközt hosszan nyomva vagy elhúzva mozgatható a kurzor"), Prefs.K_SPACE_CURSOR, true))

        // ---- glass (same set as in Glass Browser)
        val glass = card(T("Üveghatás"))
        glass.addView(choiceRow(T("Üveg típusa"), arrayOf(T("Folyékony üveg (fénytörés)"), T("Matt üveg (elmosás, fénytörés nélkül)"), T("Kikapcsolva (tömör felületek, üveg nélkül)")), Prefs.K_GLASS, 0))
        glass.addView(choiceRow(T("Üveg árnyalata"), arrayOf(T("Rendszer szerint (világos / sötét)"), T("Világos"), T("Sötét"), T("Átlátszó")), Prefs.K_THEME, 0))
        glass.addView(choiceRow(T("Eszköztár gombjainak háttere"), arrayOf(T("Liquid glass – átlátszó, fénytörő"), T("Frosted glass – matt, elmosott"), T("Tömör")), Prefs.K_TOOL_GLASS, 0))
        glass.addView(choiceRow(T("Betűbuborék anyaga (gépeléskor)"), arrayOf(T("Liquid glass – átlátszó, fénytörő"), T("Frosted glass – matt, elmosott"), T("Tömör")), Prefs.K_PREVIEW_GLASS, 2))
        glass.addView(choiceRow(T("Hosszú nyomás menü (ékezetválasztó) anyaga"), arrayOf(T("Liquid glass – átlátszó, fénytörő"), T("Frosted glass – matt, elmosott"), T("Tömör")), Prefs.K_POPUP_GLASS, 0))
        glass.addView(seekRow(T("Üveg elmosása (ékezetválasztó, betűbuborék, eszköztár)"), Prefs.K_POPUP_BLUR, 0, 100, 45, ""))
        glass.addView(choiceRow(T("Kijelölő kapszula (javaslat és ékezetválasztó)"), arrayOf(T("Liquid glass (fénytörés)"), T("Frosted glass (matt, elmosott)")), Prefs.K_LENS_STYLE, 0))
        glass.addView(hueRow(T("Üveg színe"), Prefs.K_HUE, 210))
        glass.addView(seekRow(T("Szín erőssége (0 = színtelen üveg)"), Prefs.K_HUE_STRENGTH, 0, 70, 0, ""))
        glass.addView(seekRow(T("Elmosás"), Prefs.K_BLUR, 0, 24, 2, ""))
        glass.addView(seekRow(T("Fénytörés magassága"), Prefs.K_REFR_H, 12, 50, 30, ""))
        glass.addView(seekRow(T("Fénytörés eltolása"), Prefs.K_REFR_OFF, 20, 120, 50, ""))
        glass.addView(seekRow(T("Színszóródás"), Prefs.K_DISP, 0, 100, 0, ""))
        glass.addView(seekRow(T("Üveg sötétítése"), Prefs.K_GLASS_DARK, 0, 80, 0, ""))
        glass.addView(seekRow(T("Gombok sötétítése"), Prefs.K_KEY_DARK, 0, 80, 0, ""))
        glass.addView(switchRow(T("Körív az üvegen"), T("Vékony fényív az üvegfelületek (gombok, lap, kijelölő) szélén."), Prefs.K_RIM, true))
        glass.addView(switchRow(T("Domború (3D) üveg"), T("Az üvegfelületek domborúnak, vastagnak látszanak: fénycsillanás a peremen, erősebb fénytörés a széleken."), Prefs.K_CONVEX, false))
        glass.addView(actionRow(T("Alapértelmezett üvegbeállítások visszaállítása"), T("Az üveg típusa, árnyalata, színe, átlátszósága, az elmosás, a fénytörés és a színszóródás visszaáll az eredeti értékre.")) {
            dialog().setMessage(T("Visszaállítod az üvegbeállításokat?"))
                .setPositiveButton(T("Visszaállítás")) { _, _ ->
                    prefs.resetGlass()
                    recreate()
                }
                .setNegativeButton(T("Mégse"), null).frosted()
        })

        // ---- look & feel
        val look = card(T("Megjelenés és visszajelzés"))
        look.addView(seekRow(T("Háttér átlátszatlansága"), Prefs.K_PANEL_OPACITY, 0, 100, 100, "%"))
        look.addView(choiceRow(T("Navigációs sáv (a billentyűzet alatt)"), arrayOf(T("Üveg – teljesen átlátszó"), T("Üveg, az alja fokozatosan tömör (eltakarja az alkalmazás sötét sávját)"), T("A billentyűzet színe – tömör")), Prefs.K_NAV_STYLE, 1))
        look.addView(seekRow(T("Gombok átlátszatlansága"), Prefs.K_KEY_OPACITY, 15, 100, 100, "%"))
        look.addView(seekRow(T("Billentyűmagasság"), Prefs.K_KEY_HEIGHT, 80, 130, 100, "%"))
        look.addView(seekRow(T("Alsó sáv magassága (földgömb, mikrofon)"), Prefs.K_STRIP_HEIGHT, 28, 80, 46, " dp"))
        look.addView(switchRow(T("Animációk"), T("Felugró buborékok, rugalmas ékezetválasztó, áttűnések"), Prefs.K_ANIM, true))
        look.addView(choiceRow(T("Javaslatok animációja"), arrayOf(T("Áttűnés"), T("Felúszás"), T("Nagyítás"), T("Gördülés"), T("Nincs")), Prefs.K_SUGG_ANIM, 0))
        look.addView(choiceRow(T("Gombérintés effekt"), arrayOf(T("Kiemelkedés"), T("Benyomódás"), T("Fénylés"), T("Nincs")), Prefs.K_PRESS_ANIM, 0))
        look.addView(choiceRow(T("Betűbuborék gépeléskor"), arrayOf(T("Rugós felugrás"), T("Felúszás"), T("Áttűnés"), T("Azonnali")), Prefs.K_PREVIEW_ANIM, 0))
        look.addView(choiceRow(T("Hosszú nyomás (ékezetválasztó)"), arrayOf(T("Rugós kinyílás"), T("Szétnyílás oldalra"), T("Áttűnés")), Prefs.K_POPUP_ANIM, 0))
        look.addView(switchRow(T("Rezgés javaslatra koppintáskor"), T("Egy rövid, határozott haptikus jelzés"), Prefs.K_CHAOS, true))
        look.addView(seekRow(T("Animációk sebessége (nagyobb = gyorsabb)"), Prefs.K_ANIM_SPEED, 50, 200, 100, "%"))
        look.addView(choiceRow(T("Megnyílási animáció"), arrayOf(T("Felcsúszás, fokozatosan lassulva"), T("Rugós felcsúszás"), T("Áttűnés és nagyítás"), T("Egyenletes felcsúszás"), T("Nincs")), Prefs.K_OPEN_ANIM, 0))
        look.addView(seekRow(T("Megnyílás időtartama (kisebb = gyorsabb)"), Prefs.K_OPEN_MS, 100, 900, 320, " ms"))
        look.addView(switchRow(T("Billentyű-előnézet"), T("Felnagyított betű buborék gépeléskor"), Prefs.K_PREVIEW, true))
        look.addView(choiceRow(T("Rezgés"), arrayOf(T("Ki"), T("Enyhe"), T("Erős")), Prefs.K_HAPTICS, 1))
        look.addView(switchRow(T("Hang"), T("Kattanó hang gépeléskor"), Prefs.K_SOUND, false))
        look.addView(switchRow(T("Mikrofon gomb"), T("Hangalapú bevitel (pl. Google hangbevitel) a billentyűzet alatt"), Prefs.K_MIC, true))

        // ---- GIF
        val gif = card("GIF")
        gif.addView(hint(T("A Tenor GIF szolgáltatást a Google 2026. június 30-án lezárta a külső alkalmazások elől, ezért a billentyűzet a KLIPY-t használja (ugyanolyan GIF-kereső). Ehhez egy ingyenes API kulcs kell: regisztrálj a partner.klipy.com oldalon, hozz létre egy platformot, és másold ide a kulcsot.")))
        val keyField = EditText(this)
        keyField.hint = T("KLIPY API kulcs")
        keyField.setText(prefs.klipyKey)
        keyField.isSingleLine = true
        keyField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        keyField.setTextColor(textColor)
        keyField.setHintTextColor(softColor)
        keyField.background = rounded(if (dark) 0x22FFFFFF else 0x99FFFFFF.toInt(), 14f)
        keyField.setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
        gif.addView(keyField, lp(top = 8))
        gif.addView(button(T("Kulcs mentése")) {
            prefs.sp.edit().putString(Prefs.K_KLIPY, keyField.text.toString().trim()).apply()
            android.widget.Toast.makeText(this, T("Elmentve"), android.widget.Toast.LENGTH_SHORT).show()
        })

        // ---- learned words
        val words = card(T("Tanult szavak"))
        words.addView(actionRow(T("Magyar tanult szavak"), T("Megtekintés, törlés")) { showWords("hu") })
        words.addView(actionRow("English learned words", "View, delete") { showWords("en") })

        // ---- diagnostics
        val diag = card(T("Hibakeresés"))
        diag.addView(hint(T("Ha a diktálás vagy a navigációs sáv nem jól működik, itt látszik, mi történt. Készíts róla képernyőképet, vagy másold ki.")))
        diag.addView(button(T("Diktálás próbája itt")) { testDictation() })
        testOut = text("", 14f, textColor)
        diag.addView(testOut)
        val log = text(DiagLog.text(this).ifEmpty { T("(üres)") }, 11f, softColor)
        log.typeface = Typeface.MONOSPACE
        log.setTextIsSelectable(true)
        diag.addView(log, lp(top = 8))
        diag.addView(button(T("Napló frissítése")) { log.text = DiagLog.text(this).ifEmpty { T("(üres)") } })
        diag.addView(button(T("Napló másolása")) {
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("Glass Keyboard log", DiagLog.text(this)))
            android.widget.Toast.makeText(this, T("Kimásolva"), android.widget.Toast.LENGTH_SHORT).show()
        })
        diag.addView(button(T("Napló törlése")) {
            DiagLog.clear(this)
            log.text = T("(üres)")
        })

        content.addView(hint(T("A szótárak a wordfreq és az OpenSubtitles (FrequencyWords) adataiból készültek (CC BY-SA 4.0). Minden a telefonon marad; a billentyűzet nem használ internetet.")))
    }

    private fun refreshStatus() {
        if (!::status1.isInitialized) return
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val selected = current.startsWith("$packageName/")
        status1.text = if (enabled) T("✅  Bekapcsolva") else T("⚪  Még nincs bekapcsolva")
        status2.text = if (selected) T("✅  Kiválasztva, használatban") else T("⚪  Még nincs kiválasztva")
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
            dialog().setTitle(if (lang == "hu") T("Magyar tanult szavak") else "English learned words")
                .setMessage(T("Még nincs tanult szó.")).setPositiveButton("OK", null).frosted()
            return
        }
        val labels = all.map { "${it.key}   ×${it.value}" }
        dialog()
            .setTitle(if (lang == "hu") T("Magyar tanult szavak") + " (${all.size})" else "English learned words (${all.size})")
            .setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)) { _, which ->
                val w = all[which].key
                dialog().setMessage((if (Ui.hu) "Törlöd ezt: „$w”?" else "Delete “$w”?"))
                    .setPositiveButton(T("Törlés")) { _, _ ->
                        ud.remove(w)
                        Thread { ud.save() }.start()
                    }
                    .setNegativeButton(T("Mégse"), null).frosted()
            }
            .setNeutralButton(T("Összes törlése")) { _, _ ->
                dialog().setMessage(T("Minden tanult szót törölsz ebben a nyelvben?"))
                    .setPositiveButton(T("Törlés")) { _, _ ->
                        ud.clear()
                        Thread { ud.save() }.start()
                    }
                    .setNegativeButton(T("Mégse"), null).frosted()
            }
            .setPositiveButton(T("Bezárás"), null)
            .frosted()
    }


    // ------------------------------------------------------------------ frosted dialogs

    private fun dialog() = AlertDialog.Builder(
        this,
        if (dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert,
    )

    /** Shows the dialog as a frosted glass sheet: milky, rounded, the page blurred behind it. */
    private fun AlertDialog.Builder.frosted(): AlertDialog {
        val d = create()
        d.window?.let { w ->
            w.setBackgroundDrawable(GradientDrawable().apply {
                setColor(if (dark) 0xB32A2C33.toInt() else 0xC7F6F7FA.toInt())
                cornerRadius = 28 * dp
                setStroke((1 * dp).toInt().coerceAtLeast(1), if (dark) 0x33FFFFFF else 0x99FFFFFF.toInt())
            })
            w.setDimAmount(if (dark) 0.25f else 0.12f)
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                w.attributes = w.attributes.also { it.blurBehindRadius = (18 * dp).toInt() }
                w.setBackgroundBlurRadius((36 * dp).toInt())
            }
        }
        d.show()
        return d
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

    private fun choiceRowThen(title: String, options: Array<String>, key: String, def: Int, then: () -> Unit): View {
        val r = row()
        val l = labels(title, options.getOrElse(prefs.sp.getInt(key, def)) { options[0] })
        r.addView(l)
        r.setOnClickListener {
            dialog().setTitle(title)
                .setSingleChoiceItems(options, prefs.sp.getInt(key, def)) { d, which ->
                    prefs.sp.edit().putInt(key, which).commit()
                    d.dismiss()
                    then()
                }.frosted()
        }
        return r
    }

    private fun choiceRow(title: String, options: Array<String>, key: String, def: Int): View {
        val r = row()
        val l = labels(title, options.getOrElse(prefs.sp.getInt(key, def)) { options[0] })
        r.addView(l)
        r.setOnClickListener {
            dialog().setTitle(title)
                .setSingleChoiceItems(options, prefs.sp.getInt(key, def)) { d, which ->
                    prefs.sp.edit().putInt(key, which).apply()
                    (l.getChildAt(1) as? TextView)?.text = options[which]
                    d.dismiss()
                }.frosted()
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
