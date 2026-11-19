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

        // ---- 1. setup (open by default until the keyboard is set up)
        val imm0 = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val ready = imm0.enabledInputMethodList.any { it.packageName == packageName } &&
            (Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: "").startsWith("$packageName/")
        val setup = section("setup", T("Bekapcsolás és kipróbálás"), T("A billentyűzet bekapcsolása a rendszerben, kiválasztása és egy próbamező"), !ready)
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

        // ---- 2. languages
        val langs = section("langs", T("Nyelvek"), T("Az alkalmazás nyelve és a billentyűzet nyelvei"))
        langs.addView(choiceRowThen(T("Az alkalmazás nyelve"), arrayOf(T("A telefon nyelve"), "Magyar", "English"), Prefs.K_UI_LANG, 0) {
            Ui.load(this)
            recreate()
        })
        langs.addView(switchRow(T("Magyar billentyűzet"), T("QWERTZ kiosztás, magyar szótár és javítás"), Prefs.K_HU, true) { on ->
            if (!on && !prefs.englishEnabled) {
                prefs.englishEnabled = true
                recreate()
            }
        })
        langs.addView(switchRow(T("Angol billentyűzet"), T("QWERTY kiosztás, angol szótár és javítás"), Prefs.K_EN, true) { on ->
            if (!on && !prefs.hungarianEnabled) {
                prefs.hungarianEnabled = true
                recreate()
            }
        })
        langs.addView(hint(T("Nyelvváltás gépelés közben: tartsd lenyomva az emoji gombot.")))

        // ---- 3. typing & correction
        val typing = section("typing", T("Gépelés és javítás"), T("Automatikus javítás, javaslatok, nagybetű, írásjelek"))
        typing.addView(switchRow(T("Automatikus javítás"), T("Szóköznél kijavítja az elgépelést és a hiányzó ékezetet (jo → jó, koszonom → köszönöm). Visszatörléssel visszavonható."), Prefs.K_AUTOCORRECT, true))
        typing.addView(switchRow(T("Szójavaslatok"), T("Három javaslat a billentyűk fölött: a beírt szó, a javítás és egy másik lehetőség, utána a következő szó"), Prefs.K_SUGGESTIONS, true))
        typing.addView(switchRow(T("Emoji javaslatok"), T("Illő emoji a javaslatok között (pizza → 🍕, szeretlek → ❤️)"), Prefs.K_EMOJI_SUGG, true))
        typing.addView(switchRow(T("Szavak tanulása"), T("Megjegyzi a saját szavaidat és szópárjaidat, így jobban javít és jósol"), Prefs.K_LEARN, true))
        typing.addView(switchRow(T("Automatikus nagybetű"), T("Mondat elején nagybetűvel kezd"), Prefs.K_AUTOCAPS, true))
        typing.addView(switchRow(T("Dupla szóköz = pont"), T("Két gyors szóköz pontot és szóközt tesz"), Prefs.K_DOUBLE_SPACE, true))
        typing.addView(switchRow(T("Okos idézőjelek"), T("\" helyett „ és ” (angolnál “ ”)"), Prefs.K_SMART_QUOTES, true))
        typing.addView(switchRow(T("Szóköz mint kurzor"), T("A szóközt hosszan nyomva vagy elhúzva a kurzor mozgatható"), Prefs.K_SPACE_CURSOR, true))
        typing.addView(actionRow(T("Magyar tanult szavak"), T("Megtekintés, törlés")) { showWords("hu") })
        typing.addView(actionRow(T("Angol tanult szavak"), T("Megtekintés, törlés")) { showWords("en") })

        // ---- 4. keyboard layout
        val layoutSec = section("layout", T("Billentyűzet elrendezése"), T("Számsor, pont gomb, ékezetsor, eszköztár, gombméret"))
        layoutSec.addView(switchRow(T("Eszköztár"), T("Amíg nem gépelsz, a javaslatsáv helyén gombok: billentyűzet elrejtése, emoji, GIF, vágólap, beállítások. Gépelés közben a › gombbal hozható elő."), Prefs.K_TOOLBAR, true))
        layoutSec.addView(switchRow(T("Számsor"), T("Külön sor a számoknak (1–0) a betűk fölött"), Prefs.K_NUMBER_ROW, false))
        layoutSec.addView(switchRow(T("Pont gomb a szóköz mellett"), T("„.” gomb a szóköz jobb oldalán; hosszan nyomva: , ? ! … : ;"), Prefs.K_PERIOD_KEY, false))
        layoutSec.addView(switchRow(T("Ékezetsor"), T("Külön sor az á é í ó ö ő ú ü ű betűknek (csak magyarnál)"), Prefs.K_ACCENT_ROW, false))
        layoutSec.addView(seekRow(T("Gombok magassága"), Prefs.K_KEY_HEIGHT, 80, 130, 100, "%"))

        // ---- 5. background & colours
        val colors = section("colors", T("Háttér és színek"), T("Háttér, világos/sötét, átlátszóság, színezés"))
        colors.addView(backgroundRow())
        colors.addView(choiceRow(T("Világos vagy sötét"), arrayOf(T("Rendszer szerint (világos / sötét)"), T("Világos"), T("Sötét"), T("Átlátszó")), Prefs.K_THEME, 0))
        colors.addView(seekRow(T("Háttér átlátszatlansága (üveg háttérnél)"), Prefs.K_PANEL_OPACITY, 0, 100, 100, "%"))
        colors.addView(seekRow(T("Gombok átlátszatlansága"), Prefs.K_KEY_OPACITY, 15, 100, 100, "%"))
        colors.addView(choiceRow(T("A billentyűzet alja (navigációs sáv)"), arrayOf(T("Üveg – teljesen átlátszó"), T("Üveg, az alja fokozatosan tömör (eltakarja az alkalmazás sötét sávját)"), T("A billentyűzet színe – tömör")), Prefs.K_NAV_STYLE, 0))
        colors.addView(hueRow(T("Üveg színe"), Prefs.K_HUE, 210))
        colors.addView(seekRow(T("Szín erőssége (0 = színtelen üveg)"), Prefs.K_HUE_STRENGTH, 0, 70, 0, ""))
        colors.addView(seekRow(T("Üveg sötétítése"), Prefs.K_GLASS_DARK, 0, 80, 0, ""))
        colors.addView(seekRow(T("Gombok sötétítése"), Prefs.K_KEY_DARK, 0, 80, 0, ""))

        // ---- 6. glass effect
        val glass = section("glass", T("Üveghatás"), T("Az üveg anyaga, elmosása, fénytörése és pereme"))
        glass.addView(choiceRow(T("Üveg típusa"), arrayOf(T("Folyékony üveg (fénytörés)"), T("Matt üveg (elmosás, fénytörés nélkül)"), T("Kikapcsolva (tömör felületek, üveg nélkül)")), Prefs.K_GLASS, 0))
        glass.addView(seekRow(T("Elmosás"), Prefs.K_BLUR, 0, 24, 2, ""))
        glass.addView(seekRow(T("Fénytörés magassága"), Prefs.K_REFR_H, 12, 50, 30, ""))
        glass.addView(seekRow(T("Fénytörés erőssége"), Prefs.K_REFR_OFF, 20, 120, 50, ""))
        glass.addView(seekRow(T("Színszóródás (szivárványos perem)"), Prefs.K_DISP, 0, 100, 0, ""))
        glass.addView(switchRow(T("Fényív a pereme mentén"), T("Vékony fényív az üvegfelületek (gombok, lap, kijelölő) szélén"), Prefs.K_RIM, true))
        glass.addView(switchRow(T("Domború (3D) üveg"), T("Vastag, domború hatás: csillanás a peremen, erősebb fénytörés a széleken"), Prefs.K_CONVEX, false))
        glass.addView(actionRow(T("Alapértelmezett üvegbeállítások visszaállítása"), T("Az üveg típusa, árnyalata, színe, átlátszósága, az elmosás, a fénytörés és a színszóródás visszaáll az eredeti értékre.")) {
            dialog().setMessage(T("Visszaállítod az üvegbeállításokat?"))
                .setPositiveButton(T("Visszaállítás")) { _, _ ->
                    prefs.resetGlass()
                    recreate()
                }
                .setNegativeButton(T("Mégse"), null).frosted()
        })

        // ---- 7. pop-ups
        val pops = section("popups", T("Buborékok és kijelölés"), T("Betűbuborék, ékezetválasztó, kijelölő kapszula, eszköztár gombjai"))
        pops.addView(switchRow(T("Betűbuborék gépeléskor"), T("A lenyomott betű felnagyítva megjelenik az ujjad fölött"), Prefs.K_PREVIEW, true))
        pops.addView(choiceRow(T("Betűbuborék anyaga"), arrayOf(T("Liquid glass – átlátszó, fénytörő"), T("Frosted glass – matt, elmosott"), T("Tömör")), Prefs.K_PREVIEW_GLASS, 2))
        pops.addView(choiceRow(T("Ékezetválasztó anyaga (hosszú nyomás)"), arrayOf(T("Liquid glass – átlátszó, fénytörő"), T("Frosted glass – matt, elmosott"), T("Tömör")), Prefs.K_POPUP_GLASS, 0))
        pops.addView(choiceRow(T("Kijelölő kapszula"), arrayOf(T("Liquid glass (fénytörés)"), T("Frosted glass (matt, elmosott)")), Prefs.K_LENS_STYLE, 0))
        pops.addView(choiceRow(T("Eszköztár gombjainak háttere"), arrayOf(T("Liquid glass – átlátszó, fénytörő"), T("Frosted glass – matt, elmosott"), T("Tömör")), Prefs.K_TOOL_GLASS, 0))
        pops.addView(seekRow(T("Buborékok üvegének elmosása"), Prefs.K_POPUP_BLUR, 0, 100, 45, ""))

        // ---- 8. animations
        val anim = section("anim", T("Animációk"), T("Megnyílás, gombnyomás, javaslatok, buborékok, sebesség"))
        anim.addView(switchRow(T("Animációk"), T("Felugró buborékok, rugalmas ékezetválasztó, áttűnések"), Prefs.K_ANIM, true))
        anim.addView(seekRow(T("Animációk sebessége (nagyobb = gyorsabb)"), Prefs.K_ANIM_SPEED, 50, 200, 100, "%"))
        anim.addView(choiceRow(T("Megnyílás"), arrayOf(T("Felcsúszás, fokozatosan lassulva"), T("Rugós felcsúszás"), T("Áttűnés és nagyítás"), T("Egyenletes felcsúszás"), T("Nincs")), Prefs.K_OPEN_ANIM, 0))
        anim.addView(seekRow(T("Megnyílás időtartama (kisebb = gyorsabb)"), Prefs.K_OPEN_MS, 100, 900, 320, " ms"))
        anim.addView(choiceRow(T("Gombnyomás"), arrayOf(T("Kiemelkedés"), T("Benyomódás"), T("Fénylés"), T("Nincs")), Prefs.K_PRESS_ANIM, 0))
        anim.addView(choiceRow(T("Javaslatok váltása"), arrayOf(T("Áttűnés"), T("Felúszás"), T("Nagyítás"), T("Gördülés"), T("Nincs")), Prefs.K_SUGG_ANIM, 0))
        anim.addView(choiceRow(T("Betűbuborék megjelenése"), arrayOf(T("Rugós felugrás"), T("Felúszás"), T("Áttűnés"), T("Azonnali")), Prefs.K_PREVIEW_ANIM, 0))
        anim.addView(choiceRow(T("Ékezetválasztó kinyílása"), arrayOf(T("Rugós kinyílás"), T("Szétnyílás oldalra"), T("Áttűnés")), Prefs.K_POPUP_ANIM, 0))

        // ---- 9. feedback
        val feel = section("feel", T("Rezgés és hang"), T("Visszajelzés gombnyomásra és javaslatra"))
        feel.addView(choiceRow(T("Rezgés gombnyomásra"), arrayOf(T("Ki"), T("Enyhe"), T("Erős")), Prefs.K_HAPTICS, 1))
        feel.addView(switchRow(T("Rezgés javaslatra koppintáskor"), T("Egy rövid, határozott haptikus jelzés"), Prefs.K_CHAOS, true))
        feel.addView(switchRow(T("Hang"), T("Kattanó hang gépeléskor"), Prefs.K_SOUND, false))

        // ---- 10. GIF
        val gif = section("gif", "GIF", T("GIF-kereső kulcsa (KLIPY)"))
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

        // ---- 11. diagnostics
        val diag = section("diag", T("Hibakeresés"), T("Napló arról, mit csinált a billentyűzet"))
        diag.addView(hint(T("Ha valami nem jól működik, itt látszik, mi történt. Készíts róla képernyőképet, vagy másold ki.")))
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

        content.addView(hint(T("A szótárak a wordfreq és az OpenSubtitles (FrequencyWords) adataiból készültek (CC BY-SA 4.0). A gépelés a telefonon marad; internetet csak a GIF-keresés használ.")), lp(top = 16))
    }

    // ------------------------------------------------------------------ collapsible sections

    /**
     * A category: a card with its title, a one-line description and a chevron; tapping the header
     * opens or closes it (remembered). Returns the body to put the rows in.
     */
    private fun section(id: String, title: String, subtitle: String, openByDefault: Boolean = false): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = rounded(if (dark) 0x1FFFFFFF else 0xB3FFFFFF.toInt(), 22f, if (dark) 0x26FFFFFF else 0xE6FFFFFF.toInt())
        c.setPadding((16 * dp).toInt(), (4 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt())
        c.layoutTransition = android.animation.LayoutTransition().apply { enableTransitionType(android.animation.LayoutTransition.CHANGING) }
        content.addView(c, lp(top = 12))
        val head = row()
        head.setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.addView(text(title, 18f, textColor, bold = true))
        l.addView(text(subtitle, 13f, softColor))
        head.addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val chev = text("›", 26f, softColor)
        chev.gravity = Gravity.CENTER
        // wrap the chevron only: with the default full width it squeezed the title to nothing
        head.addView(chev, LinearLayout.LayoutParams((28 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        c.addView(head)
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(0, 0, 0, (8 * dp).toInt())
        c.addView(body, lp())
        val key = "sec_$id"
        fun show(open: Boolean, animate: Boolean) {
            body.visibility = if (open) View.VISIBLE else View.GONE
            if (animate) chev.animate().rotation(if (open) 90f else 0f).setDuration(200).start() else chev.rotation = if (open) 90f else 0f
        }
        show(prefs.sp.getBoolean(key, openByDefault), false)
        head.setOnClickListener {
            val open = body.visibility != View.VISIBLE
            prefs.sp.edit().putBoolean(key, open).apply()
            show(open, true)
        }
        return body
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
            }
        }
        d.show()
        // the dialog's own frosted backdrop: only once its window exists (it crashed before show)
        if (android.os.Build.VERSION.SDK_INT >= 31) try {
            d.window?.setBackgroundBlurRadius((36 * dp).toInt())
        } catch (_: Exception) {
        }
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

    // ------------------------------------------------------------------ background picker

    private fun bgName(i: Int): String = GlassTheme.BACKGROUNDS.getOrNull(i)?.let { if (Ui.hu) it.hu else it.en } ?: ""

    /** "Background" row: a small picture of the current choice; tap → all choices with pictures. */
    private fun backgroundRow(): View {
        val r = row()
        val sel = prefs.background
        r.addView(labels(T("Háttér"), bgName(sel)))
        r.addView(BgPreview(this, sel), LinearLayout.LayoutParams((72 * dp).toInt(), (48 * dp).toInt()))
        r.setOnClickListener { pickBackground() }
        return r
    }

    private fun pickBackground() {
        val grid = android.widget.GridLayout(this)
        grid.columnCount = 2
        grid.setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
        var dlg: AlertDialog? = null
        val cur = prefs.background
        GlassTheme.BACKGROUNDS.indices.forEach { i ->
            val cell = LinearLayout(this)
            cell.orientation = LinearLayout.VERTICAL
            cell.gravity = Gravity.CENTER_HORIZONTAL
            cell.setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt())
            if (i == cur) cell.background = rounded(0x330A84FF, 16f, 0xFF0A84FF.toInt())
            cell.addView(BgPreview(this, i), LinearLayout.LayoutParams((128 * dp).toInt(), (84 * dp).toInt()))
            cell.addView(text(bgName(i), 13f, textColor).apply { gravity = Gravity.CENTER })
            cell.setOnClickListener {
                prefs.sp.edit().putInt(Prefs.K_BG, i).commit()
                dlg?.dismiss()
                recreate()
            }
            grid.addView(cell, android.widget.GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
            })
        }
        val sv = ScrollView(this)
        sv.addView(grid)
        dlg = dialog().setTitle(T("Háttér")).setView(sv).setNegativeButton(T("Mégse"), null).frosted()
    }

    /** A small picture of the keyboard with one background choice, drawn with the real theme. */
    @android.annotation.SuppressLint("ViewConstructor")
    inner class BgPreview(ctx: android.content.Context, private val preset: Int) : View(ctx) {
        private val wall = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val clip = android.graphics.Path()
        private val rr = android.graphics.RectF()

        private val fillP = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val txt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER }

        private fun key(c: android.graphics.Canvas, l: Float, t: Float, r: Float, b: Float, color: Int, th: GlassTheme, label: String? = null) {
            rr.set(l, t, r, b)
            val rad = (b - t) * 0.2f
            // soft contact shadow, the key, and a thin light top edge
            fillP.color = if (th.dark) 0x40000000 else 0x26000000
            rr.offset(0f, (b - t) * 0.05f)
            c.drawRoundRect(rr, rad, rad, fillP)
            rr.offset(0f, -(b - t) * 0.05f)
            fillP.color = color
            c.drawRoundRect(rr, rad, rad, fillP)
            if (label != null) {
                txt.color = th.text
                txt.textSize = (b - t) * 0.52f
                c.drawText(label, rr.centerX(), rr.centerY() + txt.textSize * 0.36f, txt)
            }
        }

        override fun onDraw(c: android.graphics.Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val t = GlassTheme(context, prefs.theme, prefs.glassStyle, prefs.panelOpacity, prefs.keyOpacity, prefs.glass(), preset)
            val corner = 12 * dp
            clip.reset()
            rr.set(0f, 0f, w, h)
            clip.addRoundRect(rr, corner, corner, android.graphics.Path.Direction.CW)
            c.save()
            c.clipPath(clip)
            if (!t.solidBg) {
                // a colourful "app" behind: the glass shows that it is see-through
                wall.shader = android.graphics.LinearGradient(0f, 0f, w, h, intArrayOf(0xFFFF7A59.toInt(), 0xFFB04BFF.toInt(), 0xFF2F80FF.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
                c.drawRect(rr, wall)
                wall.shader = null
                wall.color = 0x55FFFFFF
                c.drawCircle(w * 0.22f, h * 0.3f, h * 0.26f, wall)
                c.drawCircle(w * 0.8f, h * 0.85f, h * 0.2f, wall)
            }
            // the keyboard sheet over the whole picture
            wall.shader = android.graphics.LinearGradient(0f, 0f, 0f, h, t.panelTop, t.panelBottom, android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(rr, wall)
            wall.shader = null
            // rows like the real keyboard: 10 / 9 / shift 7 delete / 123 emoji space return
            val side = w * 0.03f
            val gap = w * 0.014f
            val kw = (w - side * 2 - gap * 9) / 10f
            val top = h * 0.07f
            val rowGap = h * 0.045f
            val kh = (h - top * 2 - rowGap * 3) / 4f
            val r1 = "QWERTZUIOP"
            for (i in 0 until 10) {
                val x = side + i * (kw + gap)
                key(c, x, top, x + kw, top + kh, t.key, t, r1[i].toString())
            }
            val r2 = "ASDFGHJKL"
            val off2 = (kw + gap) / 2f
            for (i in 0 until 9) {
                val x = side + off2 + i * (kw + gap)
                val y = top + kh + rowGap
                key(c, x, y, x + kw, y + kh, t.key, t, r2[i].toString())
            }
            val y3 = top + 2 * (kh + rowGap)
            val wide = kw * 1.45f
            key(c, side, y3, side + wide, y3 + kh, t.keySpecial, t)
            key(c, w - side - wide, y3, w - side, y3 + kh, t.keySpecial, t)
            val r3 = "YXCVBNM"
            val inner0 = side + wide + gap * 1.6f
            val innerW = (w - side - wide - gap * 1.6f) - inner0
            val kw3 = (innerW - gap * 6) / 7f
            for (i in 0 until 7) {
                val x = inner0 + i * (kw3 + gap)
                key(c, x, y3, x + kw3, y3 + kh, t.key, t, r3[i].toString())
            }
            val y4 = top + 3 * (kh + rowGap)
            val unit = (w - side * 2 - gap * 3) / 10f
            var x = side
            key(c, x, y4, x + unit * 1.25f, y4 + kh, t.keySpecial, t); x += unit * 1.25f + gap
            key(c, x, y4, x + unit * 1.25f, y4 + kh, t.keySpecial, t); x += unit * 1.25f + gap
            key(c, x, y4, x + unit * 5.5f, y4 + kh, t.key, t); x += unit * 5.5f + gap
            key(c, x, y4, w - side, y4 + kh, t.keySpecial, t)
            c.restore()
            // thin outline so light pictures stand out on light dialogs
            fillP.style = android.graphics.Paint.Style.STROKE
            fillP.strokeWidth = 1 * dp
            fillP.color = if (dark) 0x33FFFFFF else 0x22000000
            rr.set(0.5f * dp, 0.5f * dp, w - 0.5f * dp, h - 0.5f * dp)
            c.drawRoundRect(rr, corner, corner, fillP)
            fillP.style = android.graphics.Paint.Style.FILL
        }
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
