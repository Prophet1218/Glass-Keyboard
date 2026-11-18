package com.example.glasskeyboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.icu.text.BreakIterator
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.util.concurrent.Executors

/** Process-wide learned-word stores (shared by the keyboard and the settings screen). */
object Shared {
    private val dicts = HashMap<String, UserDict>()

    @Synchronized
    fun userDict(ctx: Context, lang: String): UserDict =
        dicts.getOrPut(lang) { UserDict(java.io.File(ctx.filesDir, "user_$lang.tsv")) }
}

/** Window background: transparent, but fills everything below the keyboard sheet (gesture bar area). */
class BelowSheetFill(private val root: View, color: Int) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint().apply { this.color = color }

    override fun draw(canvas: Canvas) {
        val loc = IntArray(2)
        root.getLocationInWindow(loc)
        val top = (loc[1] + root.height).toFloat()
        val b = bounds
        if (root.height > 0 && top < b.bottom) canvas.drawRect(b.left.toFloat(), top, b.right.toFloat(), b.bottom.toFloat(), paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** Root of the input view: transparent margin on top (for balloons), then the glass sheet. */
class KeyboardRoot(context: Context, val panel: View, val overlay: View) : ViewGroup(context) {
    var theme: GlassTheme? = null
        set(v) {
            field = v
            invalidate()
        }
    /** No transparent margin above the sheet: an invisible band would push the app's content. */
    val extraTop = 0
    /** Height of the navigation bar below the sheet when the keyboard draws behind it. */
    var bottomInset = 0
        set(v) {
            if (field != v) {
                field = v
                requestLayout()
            }
        }

    init {
        setWillNotDraw(false)
        addView(panel)
        addView(overlay)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        panel.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val total = panel.measuredHeight + extraTop + bottomInset
        overlay.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(total, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, total)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val h = b - t
        panel.layout(0, h - bottomInset - panel.measuredHeight, r - l, h - bottomInset)
        overlay.layout(0, 0, r - l, h)
    }

    override fun onDraw(canvas: Canvas) {
        theme?.drawPanel(canvas, 0f, panel.top.toFloat(), width.toFloat(), height.toFloat())
    }

    private var openAnim: ValueAnimator? = null

    private fun parts(): List<View> {
        val g = panel as? ViewGroup ?: return listOf(panel)
        return List(g.childCount) { g.getChildAt(it) }
    }

    /**
     * Opening animation. The system already slides the keyboard window in together with the app,
     * so the glass sheet itself stays in place; the keys and the suggestion bar animate inside it
     * (clipped to the sheet) — no empty band in front of the app.
     */
    fun playOpen(type: Int, ms: Int) {
        openAnim?.cancel()
        resetParts()
        if (type == 4 || ms <= 0) return
        val h = (if (panel.height > 0) panel.height else panel.measuredHeight).toFloat()
        if (h <= 0f) return
        val views = parts()
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = ms.toLong()
        anim.interpolator = when (type) {
            1 -> OvershootInterpolator(1.15f)
            2 -> DecelerateInterpolator(2f)
            3 -> AccelerateDecelerateInterpolator()
            else -> DecelerateInterpolator(2.6f) // fast start, slowing down gradually
        }
        fun apply(f: Float) {
            for (v in views) {
                if (type == 2) {
                    v.alpha = f.coerceIn(0f, 1f)
                    val s = 0.94f + 0.06f * f
                    v.pivotX = v.width / 2f
                    v.pivotY = v.height.toFloat()
                    v.scaleX = s
                    v.scaleY = s
                } else {
                    v.translationY = (1f - f) * h * 0.55f
                    v.alpha = (0.25f + f).coerceIn(0f, 1f)
                }
            }
        }
        anim.addUpdateListener { apply(it.animatedValue as Float) }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                resetParts()
            }
        })
        openAnim = anim
        apply(0f)
        anim.start()
    }

    private fun resetParts() {
        for (v in parts()) {
            v.translationY = 0f
            v.alpha = 1f
            v.scaleX = 1f
            v.scaleY = 1f
        }
    }
}

class GlassKeyboardService : InputMethodService(), KeyboardActions, EmojiActions {

    private class Correction(val original: String, val corrected: String, val sep: String)
    private class Analysis(val word: String, val prev: String?, val allow: Boolean, val result: Engine.Result)

    private lateinit var prefs: Prefs
    private var theme: GlassTheme? = null
    private var root: KeyboardRoot? = null
    private lateinit var kb: KeyboardView
    private lateinit var bar: CandidateBar
    private lateinit var emoji: EmojiView
    private lateinit var overlay: PopupOverlay

    private val engines = HashMap<String, Engine>()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var lang = "hu"
    private var shift = 0
    private var lastShiftTap = 0L
    private var layoutKind = LayoutKind.LETTERS
    private var field = FieldKind.TEXT
    private var numericLayout: LayoutKind? = null
    private var suggestionsActive = true
    private var correctionActive = true
    private var learning = true
    private var capsAllowed = true
    private var enterAction = EditorInfo.IME_ACTION_NONE

    private var lastCorrection: Correction? = null
    private var lastSpaceAt = 0L
    private var autoSpace = false
    private var analyzeSeq = 0
    private var cached: Analysis? = null
    private var vibrator: Vibrator? = null
    private var audio: AudioManager? = null

    private val refreshRunnable = Runnable {
        updateShift()
        updateSuggestions()
    }
    private val saveRunnable = Runnable { worker.execute { saveDicts() } }
    private val restoreSpaceLabel = Runnable { if (::kb.isInitialized) kb.spaceLabel = "" }

    private val navGuard = NavBarGuard()
    private lateinit var clips: ClipStore
    private lateinit var clipPanel: ClipboardPanel
    private lateinit var gifPanel: GifPanel
    private lateinit var gifBar: GifSearchBar
    /** Non-null while a GIF search is being typed: the keys type into it instead of the app. */
    private var gifQuery: StringBuilder? = null
    private var clipListener: android.content.ClipboardManager.OnPrimaryClipChangedListener? = null

    override fun onCreate() {
        // own window theme: draws its own system-bar backgrounds, no translucent/black nav scrim
        setTheme(R.style.ImeTheme)
        super.onCreate()
        // edge to edge on every version: the sheet itself runs under the navigation bar
        if (Build.VERSION.SDK_INT >= 30) window?.window?.setDecorFitsSystemWindows(false)
        prefs = Prefs(this)
        lang = prefs.language
        @Suppress("DEPRECATION")
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        audio = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        clips = ClipStore(prefs)
        VoiceActivity.listener = { main.postDelayed({ takeVoiceResult() }, 400) }
        // remember what gets copied (the active keyboard is allowed to read the clipboard)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        if (cm != null) {
            val l = android.content.ClipboardManager.OnPrimaryClipChangedListener { captureClip(cm) }
            cm.addPrimaryClipChangedListener(l)
            clipListener = l
        }
    }

    private fun captureClip(cm: android.content.ClipboardManager? = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager) {
        try {
            val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            if (!text.isNullOrBlank()) clips.add(text)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        clipListener?.let { (getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)?.removePrimaryClipChangedListener(it) }
        recognizer?.destroy()
        saveDicts()
        worker.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ engines

    private fun engine(l: String = lang): Engine = engines.getOrPut(l) {
        val afd = assets.openFd("$l.dict")
        val buf = FileInputStream(afd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        val proper = try {
            assets.open("proper_$l.txt").bufferedReader(Charsets.UTF_8).readLines()
        } catch (_: Exception) {
            emptyList()
        }
        val e = Engine(l == "hu", Dict(buf), Shared.userDict(this, l), proper)
        // 0.0.13: drop typos that older versions learned by mistake (once per language)
        val flag = "pruned_typos_v13_$l"
        if (!prefs.sp.getBoolean(flag, false)) {
            prefs.sp.edit().putBoolean(flag, true).apply()
            worker.execute {
                try {
                    if (e.pruneLearnedTypos() > 0) Shared.userDict(this, l).save()
                } catch (_: Exception) {
                }
            }
        }
        e
    }

    private fun saveDicts() {
        Shared.userDict(this, "hu").save()
        Shared.userDict(this, "en").save()
    }

    private fun enabledLangs(): List<String> {
        val l = ArrayList<String>()
        if (prefs.hungarianEnabled) l += "hu"
        if (prefs.englishEnabled) l += "en"
        if (l.isEmpty()) l += "hu"
        return l
    }

    private val hu get() = lang == "hu"

    // ------------------------------------------------------------------ views

    override fun onCreateInputView(): View {
        overlay = PopupOverlay(this)
        kb = KeyboardView(this, this, overlay)
        bar = CandidateBar(this) { pickCandidate(it) }
        bar.onTapAt = { x, y ->
            if (prefs.chaos) suggestionTap()
        }
        emoji = EmojiView(this, this, prefs)
        emoji.visibility = View.GONE
        val area = FrameLayout(this)
        area.addView(kb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        area.addView(emoji, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        clipPanel = ClipboardPanel(this, clips, { text ->
            currentInputConnection?.commitText(text, 1)
            showKeys()
            afterInput()
        }, { showKeys() })
        clipPanel.visibility = View.GONE
        area.addView(clipPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        gifPanel = GifPanel(this, prefs, { sendGif(it) }, { showKeys() })
        gifPanel.visibility = View.GONE
        area.addView(gifPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.onTool = { onTool(it) }
        gifPanel.onSearchTap = { startGifSearch(it) }
        gifBar = GifSearchBar(this)
        gifBar.visibility = View.GONE
        gifBar.onBack = { endGifSearch(); showGifs(gifPanel.query) }
        gifBar.onClear = {
            gifQuery?.setLength(0)
            gifBar.text = ""
        }
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(gifBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(area, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val r = KeyboardRoot(this, panel, overlay)
        root = r
        overlay.backdrop = { scale -> renderBackdrop(scale) }
        // the strip below the sheet follows the sheet's size
        r.addOnLayoutChangeListener { _, l, t, rr, b, ol, ot, orr, ob ->
            window?.window?.decorView?.invalidate()
            if (l != ol || t != ot || rr != orr || b != ob) scheduleReassert()
        }
        // draw the glass sheet behind the navigation bar too (no black band under the keys)
        if (Build.VERSION.SDK_INT >= 30) {
            window?.window?.let { w ->
                // without this flag the system paints a black "legacy" bar under the keyboard
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                w.isNavigationBarContrastEnforced = false
                w.setDecorFitsSystemWindows(false)
            }
            r.setOnApplyWindowInsetsListener { _, insets ->
                // the system's IME navigation bar reports itself as a caption bar
                val types = android.view.WindowInsets.Type.navigationBars() or android.view.WindowInsets.Type.captionBar()
                r.bottomInset = insets.getInsets(types).bottom
                scheduleReassert()
                insets
            }
        }
        applyTheme()
        return r
    }

    private fun applyTheme() {
        val t = GlassTheme(this, prefs.theme, prefs.glassStyle, prefs.panelOpacity, prefs.keyOpacity, prefs.glass())
        theme = t
        if (root == null) return
        root?.theme = t
        kb.theme = t
        bar.theme = t
        emoji.theme = t
        clipPanel.theme = t
        gifPanel.theme = t
        gifBar.theme = t
        overlay.theme = t
        styleNavBar(t)
    }

    /**
     * Colour of the strip under the keys (gesture handle): always the sheet's bottom colour, opaque.
     * A see-through strip let the system's own black bar show through now and then (flicker).
     */
    private fun navColor(t: GlassTheme): Int = t.panelBottom or 0xFF000000.toInt()

    private fun styleNavBar(t: GlassTheme) {
        val w = window?.window ?: return
        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        @Suppress("DEPRECATION")
        w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        if (Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
        // transparent window: the rounded sheet and the app behind it decide what is seen
        val bg = w.decorView.background
        if (!(bg is android.graphics.drawable.ColorDrawable && bg.color == android.graphics.Color.TRANSPARENT)) {
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
        // window nav colour + the system's keyboard bar view (black by default), kept every frame
        navGuard.attach(w, navColor(t))
        // Android 15+: hide the system-drawn IME navigation bar (its black background); the
        // keyboard paints that area itself.
        if (Build.VERSION.SDK_INT >= 35) try {
            w.insetsController?.hide(android.view.WindowInsets.Type.captionBar())
        } catch (_: Exception) {
        }
        applyWindowBlur(t, force = false)
        if (Build.VERSION.SDK_INT >= 30) {
            w.insetsController?.setSystemBarsAppearance(
                if (t.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            val flags = w.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = if (t.dark) flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            else flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    /**
     * See-through sheet: blur whatever the app shows behind the keyboard (Android 12+), the same
     * with the glass effect switched off. The system can drop the blur when the window is laid
     * out again (it did on the 123 key), so [force] sets it anew instead of trusting the old value.
     */
    private fun applyWindowBlur(t: GlassTheme, force: Boolean) {
        if (Build.VERSION.SDK_INT < 31) return
        val w = window?.window ?: return
        try {
            val blur = if (t.panelAlpha < 0.98f) (t.params.blur * 3f * t.dp + (if (t.frosted) 24f * t.dp else 0f)).toInt() else 0
            if (force && blur > 0) w.setBackgroundBlurRadius(0)
            w.setBackgroundBlurRadius(blur)
        } catch (_: Exception) {
        }
    }

    private val reassertRunnable = Runnable { reassertWindow() }

    /** After anything that lays the keyboard window out again: same nav colour, same blur. */
    private fun scheduleReassert() {
        main.removeCallbacks(reassertRunnable)
        main.post(reassertRunnable)
        main.postDelayed(reassertRunnable, 180)
        main.postDelayed(reassertRunnable, 650)
    }

    private fun reassertWindow() {
        val t = theme ?: return
        if (root == null || !isInputViewShown) return
        window?.window?.let { navGuard.attach(it, navColor(t)) }
        applyWindowBlur(t, force = false)
    }

    /** The keyboard as it looks now (sheet + suggestions + keys) at [scale]: the picker's glass shows it. */
    private fun renderBackdrop(scale: Float): android.graphics.Bitmap? {
        val r = root ?: return null
        val t = theme ?: return null
        if (r.width == 0 || r.height == 0) return null
        val bmp = android.graphics.Bitmap.createBitmap(
            maxOf(1, (r.width * scale).toInt()), maxOf(1, (r.height * scale).toInt()), android.graphics.Bitmap.Config.ARGB_8888
        )
        val c = Canvas(bmp)
        c.scale(scale, scale)
        t.drawPanel(c, 0f, r.panel.top.toFloat(), r.width.toFloat(), r.height.toFloat())
        c.save()
        c.translate(r.panel.left.toFloat(), r.panel.top.toFloat())
        r.panel.draw(c)
        c.restore()
        return bmp
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onWindowShown() {
        super.onWindowShown()
        // re-apply every time the keyboard appears (the system may reset it in between)
        theme?.let { styleNavBar(it) }
        scheduleReassert()
        val r = root ?: return
        r.post { r.playOpen(prefs.openAnim, prefs.openMs) }
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val r = root ?: return
        if (!isInputViewShown || r.height == 0) return
        val top = r.panel.top
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, top, r.width, r.height)
    }

    // ------------------------------------------------------------------ editor setup

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val langs = enabledLangs()
        if (lang !in langs) lang = langs[0]
        applyTheme()
        if (VoiceActivity.pending != null) main.postDelayed({ takeVoiceResult() }, 250)

        val type = info.inputType
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        numericLayout = when (cls) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> LayoutKind.NUMBER
            InputType.TYPE_CLASS_PHONE -> LayoutKind.PHONE
            else -> null
        }
        field = if (cls == InputType.TYPE_CLASS_TEXT) when (variation) {
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldKind.EMAIL
            InputType.TYPE_TEXT_VARIATION_URI -> FieldKind.URL
            else -> FieldKind.TEXT
        } else FieldKind.TEXT
        val password = (cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
                (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val noSuggestions = type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
        val textClass = cls == InputType.TYPE_CLASS_TEXT
        suggestionsActive = prefs.suggestions && textClass && !password && field != FieldKind.EMAIL &&
                !(noSuggestions && variation != InputType.TYPE_TEXT_VARIATION_NORMAL && variation != InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE && variation != InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE)
        correctionActive = prefs.autocorrect && suggestionsActive && field == FieldKind.TEXT &&
                variation != InputType.TYPE_TEXT_VARIATION_FILTER && !noSuggestions
        learning = prefs.learnWords && !password && (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0
        capsAllowed = prefs.autoCaps && textClass && !password && field == FieldKind.TEXT &&
                variation != InputType.TYPE_TEXT_VARIATION_FILTER

        configureEnter(info)
        kb.showGlobe = true
        kb.showMic = prefs.showMic
        kb.previews = prefs.keyPreview
        kb.spaceCursor = prefs.spaceCursor
        kb.heightScale = prefs.keyHeight / 100f
        kb.stripDp = prefs.stripHeight.toFloat()
        kb.animations = prefs.animations
        overlay.animations = prefs.animations
        overlay.previewStyle = prefs.previewAnim
        overlay.popupStyle = prefs.popupAnim
        overlay.popupGlass = prefs.popupGlass
        overlay.popupBlur = prefs.popupBlur / 100f
        overlay.previewGlass = prefs.previewGlass
        overlay.invalidateBackdrop()
        bar.animations = prefs.animations
        bar.style = prefs.suggAnim
        bar.toolbarEnabled = prefs.toolbar
        bar.toolGlass = prefs.toolGlass
        kb.pressStyle = prefs.pressAnim
        Anim.speed = prefs.animSpeed / 100f
        kb.locale = engine().locale
        kb.minRows = letterRows()
        kb.spaceLabel = ""
        emoji.hungarian = hu
        showKeys()
        shift = 0
        setLayout(numericLayout ?: LayoutKind.LETTERS)
        lastCorrection = null
        autoSpace = false
        cached = null
        bar.clear()
        main.removeCallbacks(refreshRunnable)
        main.post(refreshRunnable)
    }

    private fun configureEnter(info: EditorInfo) {
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val noAction = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        enterAction = if (noAction) EditorInfo.IME_ACTION_NONE else action
        val label = when (enterAction) {
            EditorInfo.IME_ACTION_GO -> if (hu) "Ugrás" else "Go"
            EditorInfo.IME_ACTION_SEARCH -> if (hu) "Keresés" else "Search"
            EditorInfo.IME_ACTION_SEND -> if (hu) "Küldés" else "Send"
            EditorInfo.IME_ACTION_NEXT -> if (hu) "Tovább" else "Next"
            EditorInfo.IME_ACTION_DONE -> if (hu) "Kész" else "Done"
            EditorInfo.IME_ACTION_PREVIOUS -> if (hu) "Vissza" else "Back"
            else -> ""
        }
        if (label.isEmpty()) enterAction = EditorInfo.IME_ACTION_NONE
        kb.enterLabel = label
        kb.enterAccent = enterAction == EditorInfo.IME_ACTION_GO || enterAction == EditorInfo.IME_ACTION_SEARCH ||
                enterAction == EditorInfo.IME_ACTION_SEND || enterAction == EditorInfo.IME_ACTION_DONE
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (::gifBar.isInitialized) endGifSearch()
        if (::kb.isInitialized) kb.cancelAll()
        main.removeCallbacks(saveRunnable)
        worker.execute { saveDicts() }
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        scheduleRefresh()
    }

    private fun scheduleRefresh() {
        main.removeCallbacks(refreshRunnable)
        main.post(refreshRunnable)
    }

    private fun setLayout(kind: LayoutKind) {
        layoutKind = kind
        val h = hu
        kb.layout = when (kind) {
            LayoutKind.LETTERS -> Layouts.letters(h, prefs.accentRow, field, prefs.numberRow, prefs.periodKey)
            LayoutKind.SYMBOLS -> Layouts.symbols(h, field, prefs.periodKey, letterRows() - 4, suggestedEmoji(), prefs.numberRow)
            LayoutKind.MORE -> Layouts.more(h, field, prefs.periodKey, letterRows() - 4, suggestedEmoji(), prefs.numberRow)
            LayoutKind.NUMBER -> {
                val t = currentInputEditorInfo?.inputType ?: 0
                Layouts.number(h, t and InputType.TYPE_NUMBER_FLAG_SIGNED != 0, t and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0)
            }
            LayoutKind.PHONE -> Layouts.phone()
        }
        kb.shift = if (kind == LayoutKind.LETTERS) shift else 0
        overlay.invalidateBackdrop()
        scheduleReassert()
        if (kind == LayoutKind.LETTERS) updateShift()
    }

    /** Recently used emoji first, then popular ones (for the spare rows of the symbol pages). */
    private fun suggestedEmoji(): List<String> {
        val out = LinkedHashSet<String>()
        prefs.recentEmoji.split(" ").filter { it.isNotEmpty() }.forEach { out += it }
        "😂 ❤️ 😍 🥰 😊 👍 🙏 😭 🔥 😘 🤣 😅 😉 🥲 😎 🤔 🙌 👏 💪 🎉 ✨ 💯 😢 😡 🤗 😴 🙈 👌 💕 ✅".split(" ").forEach { out += it }
        return out.toList()
    }

    private fun letterRows() = Layouts.letters(hu, prefs.accentRow, field, prefs.numberRow, prefs.periodKey).rows.size

    private fun onTool(id: Int) {
        endGifSearch()
        // a tool whose panel is open switches back to the keys (like Gboard)
        if (id == bar.activeTool) {
            showKeys()
            return
        }
        when (id) {
            CandidateBar.TOOL_EMOJI -> onEmoji()
            CandidateBar.TOOL_GIF -> showGifs()
            CandidateBar.TOOL_CLIPBOARD -> showClipboard()
            CandidateBar.TOOL_HIDE -> requestHideSelf(0)
            else -> startActivity(android.content.Intent(this, SettingsActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun hidePanels() {
        emoji.visibility = View.GONE
        clipPanel.visibility = View.GONE
        if (gifPanel.visibility == View.VISIBLE) gifPanel.close()
        gifPanel.visibility = View.GONE
    }

    private fun fadeIn(v: View) {
        if (!prefs.animations) return
        v.alpha = 0f
        v.translationY = 14 * resources.displayMetrics.density
        v.animate().alpha(1f).translationY(0f).setDuration(Anim.ms(240f).toLong()).start()
    }

    private fun showGifs(query: String? = null) {
        val word = query ?: extractWord(textBefore())
        gifPanel.fixedHeight = if (kb.visibility == View.VISIBLE) kb.height else maxOf(kb.height, emoji.height, clipPanel.height)
        gifPanel.hungarian = hu
        hidePanels()
        kb.visibility = View.GONE
        gifPanel.visibility = View.VISIBLE
        gifPanel.open(word)
        gifPanel.requestLayout()
        bar.activeTool = CandidateBar.TOOL_GIF
        fadeIn(gifPanel)
    }

    /** Sends a GIF as a real image where the app accepts one, otherwise as a link. */
    private fun sendGif(item: GifItem) {
        val ic = currentInputConnection ?: return
        val mimes = if (Build.VERSION.SDK_INT >= 25) currentInputEditorInfo?.contentMimeTypes else null
        val accepts = mimes?.any { android.content.ClipDescription.compareMimeTypes("image/gif", it) } == true
        if (!accepts || Build.VERSION.SDK_INT < 25) {
            ic.commitText(item.full + " ", 1)
            return
        }
        Toast.makeText(this, if (hu) "GIF küldése…" else "Sending GIF…", Toast.LENGTH_SHORT).show()
        worker.execute {
            val name = "gif_" + item.id.filter { it.isLetterOrDigit() }.take(40).ifEmpty { "x" } + ".gif"
            val ok = try {
                val bytes = GifApi.download(item.full)
                java.io.File(GifProvider.dir(this), name).writeBytes(bytes)
                true
            } catch (_: Exception) {
                false
            }
            main.post {
                val conn = currentInputConnection ?: return@post
                if (!ok) {
                    conn.commitText(item.full + " ", 1)
                    return@post
                }
                val info = android.view.inputmethod.InputContentInfo(
                    GifProvider.uriFor(name),
                    android.content.ClipDescription(item.title.ifEmpty { "GIF" }, arrayOf("image/gif")),
                    android.net.Uri.parse(item.page),
                )
                val sent = try {
                    conn.commitContent(info, android.view.inputmethod.InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
                } catch (_: Exception) {
                    false
                }
                if (!sent) conn.commitText(item.full + " ", 1)
            }
        }
    }

    private fun showClipboard() {
        captureClip()
        clipPanel.fixedHeight = kb.height
        clipPanel.hungarian = hu
        clipPanel.refresh()
        hidePanels()
        kb.visibility = View.GONE
        clipPanel.visibility = View.VISIBLE
        clipPanel.requestLayout()
        bar.activeTool = CandidateBar.TOOL_CLIPBOARD
        fadeIn(clipPanel)
    }

    // ------------------------------------------------------------------ GIF search

    /** Search field tapped: the letter keys type into the search bar above them. */
    private fun startGifSearch(current: String) {
        gifQuery = StringBuilder(current)
        hidePanels()
        bar.activeTool = -1
        bar.visibility = View.GONE
        gifBar.hungarian = hu
        gifBar.text = current
        gifBar.visibility = View.VISIBLE
        if (layoutKind != LayoutKind.LETTERS) setLayout(LayoutKind.LETTERS)
        shift = 0
        kb.shift = 0
        kb.visibility = View.VISIBLE
        fadeIn(kb)
    }

    private fun gifType(text: String) {
        val q = gifQuery ?: return
        if (q.length >= 60) return
        q.append(text)
        gifBar.text = q.toString()
        if (layoutKind != LayoutKind.LETTERS && (text == " " || text == "'")) setLayout(LayoutKind.LETTERS)
        if (shift == 1 && layoutKind == LayoutKind.LETTERS) {
            shift = 0
            kb.shift = 0
        }
    }

    private fun runGifSearch() {
        val q = gifQuery?.toString()?.trim() ?: return
        endGifSearch()
        showGifs(q)
    }

    private fun endGifSearch() {
        if (gifQuery == null) return
        gifQuery = null
        gifBar.visibility = View.GONE
        bar.visibility = View.VISIBLE
        if (layoutKind == LayoutKind.LETTERS) updateShift()
    }

    private fun showKeys() {
        endGifSearch()
        val wasEmoji = emoji.visibility == View.VISIBLE || clipPanel.visibility == View.VISIBLE || gifPanel.visibility == View.VISIBLE
        hidePanels()
        bar.activeTool = -1
        kb.visibility = View.VISIBLE
        if (wasEmoji && prefs.animations) {
            kb.alpha = 0f
            kb.translationY = 10 * resources.displayMetrics.density
            kb.animate().alpha(1f).translationY(0f).setDuration(Anim.ms(220f).toLong()).start()
        }
    }

    // ------------------------------------------------------------------ text helpers

    private fun textBefore(n: Int = 64): String =
        currentInputConnection?.getTextBeforeCursor(n, 0)?.toString() ?: ""

    private fun isWordChar(c: Char) = c.isLetterOrDigit()

    /** The word right before the cursor (letters/digits, with inner ' and - ). */
    private fun extractWord(before: String): String {
        var i = before.length
        while (i > 0) {
            val c = before[i - 1]
            if (isWordChar(c)) i--
            else if ((c == '\'' || c == '’' || c == '-') && i < before.length && i >= 2 && isWordChar(before[i - 2])) i--
            else break
        }
        return before.substring(i)
    }

    private fun sentenceStart(beforeWord: String): Boolean {
        val t = beforeWord.trimEnd()
        if (t.isEmpty()) return true
        val u = t.trimEnd('"', '”', '“', '\'', '’', ')', '»')
        if (u.isEmpty()) return true
        return u.last() in ".!?…" || beforeWord.endsWith("\n") || t.endsWith("\n")
    }

    /** Previous word in lower case, or null at the start of a sentence. */
    private fun prevWord(beforeWord: String): String? {
        if (sentenceStart(beforeWord)) return null
        val t = beforeWord.trimEnd()
        if (t.isEmpty() || !isWordChar(t.last())) return null
        return extractWord(t).lowercase(engine().locale).ifEmpty { null }
    }

    private fun correctionAllowed(word: String, beforeWord: String): Boolean {
        if (!correctionActive) return false
        if (word.length > 1 && word.all { !it.isLetter() || it.isUpperCase() }) return false // acronyms
        if (word[0].isUpperCase() && !sentenceStart(beforeWord) && shift != 2) {
            // a capitalised word inside a sentence is probably a name: fix only known words
            return engine().freq(word.lowercase(engine().locale)) > 0
        }
        return true
    }

    private fun graphemeLength(s: String): Int {
        if (s.isEmpty()) return 0
        val bi = BreakIterator.getCharacterInstance()
        bi.setText(s)
        val start = bi.preceding(s.length)
        return if (start == BreakIterator.DONE) 1 else s.length - start
    }

    // ------------------------------------------------------------------ suggestions

    private fun updateSuggestions() {
        if (!::bar.isInitialized) return
        if (!suggestionsActive || layoutKind == LayoutKind.NUMBER || layoutKind == LayoutKind.PHONE) {
            bar.clear(); return
        }
        val ic = currentInputConnection ?: return
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) {
            bar.clear(); return
        }
        val before = textBefore()
        val word = extractWord(before)
        val beforeWord = before.substring(0, before.length - word.length)
        val prev = prevWord(beforeWord)
        val eng = engine()
        val seq = ++analyzeSeq
        if (word.isEmpty()) {
            cached = null
            if (before.isNotEmpty() && !before.last().isWhitespace() && !sentenceStart(before)) {
                bar.clear(); return
            }
            val start = sentenceStart(beforeWord)
            worker.execute {
                // only what was learned from you: otherwise the toolbar stays
                val p = eng.predict(prev, start, 3, false)
                main.post {
                    if (seq == analyzeSeq) bar.set(p.map { Candidate(it) })
                }
            }
            return
        }
        val allow = correctionAllowed(word, beforeWord)
        worker.execute {
            val r = try {
                eng.analyze(word, prev, allow)
            } catch (_: Exception) {
                null
            }
            main.post {
                if (seq == analyzeSeq && r != null) {
                    cached = Analysis(word, prev, allow, r)
                    showResult(word, r)
                }
            }
        }
    }

    private fun showResult(word: String, r: Engine.Result) {
        val quoted = if (hu) "„$word”" else "“$word”"
        val others = r.suggestions.filter { it != word && it != r.correction }
        val slots = ArrayList<Candidate?>()
        slots += Candidate(word, quoted, literal = true)
        if (r.correction != null) {
            slots += Candidate(r.correction!!, highlight = true)
            slots += others.firstOrNull()?.let { Candidate(it) }
        } else {
            slots += others.getOrNull(0)?.let { Candidate(it) }
            slots += others.getOrNull(1)?.let { Candidate(it) }
        }
        if (prefs.emojiSuggestions) {
            val e = EmojiHints.forWord(r.correction ?: word, hu) ?: slots[1]?.let { EmojiHints.forWord(it.text, hu) }
            if (e != null) slots[2] = Candidate(e)
        }
        bar.set(slots)
    }

    private fun pickCandidate(c: Candidate) {
        if (listening) {
            recognizer?.stopListening()
            return
        }
        val ic = currentInputConnection ?: return
        val before = textBefore()
        val word = extractWord(before)
        val beforeWord = before.substring(0, before.length - word.length)
        val prev = prevWord(beforeWord)
        val after = ic.getTextAfterCursor(1, 0)
        val needSpace = after.isNullOrEmpty() || !after[0].isWhitespace()
        ic.beginBatchEdit()
        if (word.isNotEmpty()) ic.deleteSurroundingText(word.length, 0)
        ic.commitText(c.text + if (needSpace) " " else "", 1)
        ic.endBatchEdit()
        if (learning) {
            val eng = engine()
            val loc = eng.locale
            worker.execute {
                if (c.literal) eng.learnRejected(c.text.lowercase(loc))
                val last = c.text.substringAfterLast(' ')
                if (last.any { it.isLetter() }) eng.learn(prev, last.lowercase(loc), false)
            }
            scheduleSave()
        }
        lastCorrection = null
        autoSpace = needSpace
        lastSpaceAt = 0
        cached = null
        afterInput()
    }

    private fun scheduleSave() {
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, 8000)
    }

    // ------------------------------------------------------------------ typing

    private fun afterInput() {
        scheduleRefresh()
    }

    /** Applies autocorrect to the word before the cursor (if needed) and types [sep] after it. */
    private fun commitWithCorrection(sep: String) {
        val ic = currentInputConnection ?: return
        val before = textBefore()
        val word = extractWord(before)
        val beforeWord = before.substring(0, before.length - word.length)
        val prev = prevWord(beforeWord)
        var corrected: String? = null
        if (word.isNotEmpty() && correctionActive) {
            val allow = correctionAllowed(word, beforeWord)
            val c = cached
            val r = if (c != null && c.word == word && c.prev == prev && c.allow == allow) c.result
            else try {
                engine().analyze(word, prev, allow)
            } catch (_: Exception) {
                null
            }
            val cor = r?.correction
            if (allow && cor != null && cor != word) corrected = cor
        }
        ic.beginBatchEdit()
        if (corrected != null) {
            ic.deleteSurroundingText(word.length, 0)
            ic.commitText(corrected + sep, 1)
            lastCorrection = Correction(word, corrected, sep)
        } else {
            if (sep.isNotEmpty()) ic.commitText(sep, 1)
            lastCorrection = null
        }
        ic.endBatchEdit()
        if (word.isNotEmpty() && learning && suggestionsActive) {
            val eng = engine()
            val loc = eng.locale
            val final = corrected ?: word
            worker.execute {
                val parts = final.split(' ')
                var p = prev
                for (part in parts) {
                    val lw = part.lowercase(loc)
                    eng.learn(p, lw, corrected != null)
                    p = lw
                }
            }
            scheduleSave()
        }
        cached = null
    }

    private fun isSeparator(c: Char) = c in " .,!?;:)]}\"”»…\n"

    private fun smartQuote(before: String): String {
        val open = before.isEmpty() || before.last().isWhitespace() || before.last() in "([{„“«"
        return if (hu) (if (open) "„" else "”") else (if (open) "“" else "”")
    }

    override fun onText(text: String) {
        if (gifQuery != null) {
            gifType(text)
            return
        }
        val ic = currentInputConnection ?: return
        if (text.length == 1 && isSeparator(text[0])) {
            var t = text
            if (t == "\"" && prefs.smartQuotes && field == FieldKind.TEXT && suggestionsActive) t = smartQuote(textBefore(1))
            if (autoSpace && t[0] in ".,!?;:)”»…") {
                // "word |" + "." → "word." (the space came from a suggestion tap)
                if (textBefore(1) == " ") ic.deleteSurroundingText(1, 0)
            }
            autoSpace = false
            commitWithCorrection(t)
        } else {
            autoSpace = false
            ic.commitText(text, 1)
            lastCorrection = null
            if (text.isNotEmpty() && text.codePointAt(0) >= 0x2600 && Character.getType(text.codePointAt(0)) == Character.OTHER_SYMBOL.toInt()) {
                emoji.addRecent(text)
            }
        }
        if (layoutKind == LayoutKind.SYMBOLS || layoutKind == LayoutKind.MORE) {
            if (text == "'" || text == "’") setLayout(LayoutKind.LETTERS)
        }
        if (shift == 1 && layoutKind == LayoutKind.LETTERS) {
            shift = 0
            kb.shift = 0
        }
        afterInput()
    }

    override fun onSpace() {
        if (gifQuery != null) {
            gifType(" ")
            return
        }
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        val before = textBefore(2)
        if (prefs.doubleSpacePeriod && field == FieldKind.TEXT && now - lastSpaceAt < 650 && before.length == 2 &&
            before[1] == ' ' && (before[0].isLetterOrDigit() || before[0] in ")\"”’'»")
        ) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            ic.endBatchEdit()
            lastSpaceAt = 0
            lastCorrection = null
        } else {
            autoSpace = false
            commitWithCorrection(" ")
            lastSpaceAt = now
        }
        if (layoutKind == LayoutKind.SYMBOLS || layoutKind == LayoutKind.MORE) setLayout(LayoutKind.LETTERS)
        afterInput()
    }

    override fun onEnter() {
        if (gifQuery != null) {
            runGifSearch()
            return
        }
        val ic = currentInputConnection ?: return
        autoSpace = false
        if (enterAction != EditorInfo.IME_ACTION_NONE) {
            commitWithCorrection("")
            ic.performEditorAction(enterAction)
        } else {
            commitWithCorrection("")
            sendKeyChar('\n')
        }
        afterInput()
    }

    override fun onDelete(repeat: Int) {
        val q = gifQuery
        if (q != null) {
            if (q.isNotEmpty()) {
                if (repeat > 18) q.setLength(0) else q.setLength(q.length - 1)
                gifBar.text = q.toString()
            }
            return
        }
        val ic = currentInputConnection ?: return
        cached = null
        autoSpace = false
        val lc = lastCorrection
        if (repeat == 0 && lc != null) {
            val tail = lc.corrected + lc.sep
            if (textBefore(tail.length) == tail) {
                // undo the autocorrection and trust the typed word from now on
                ic.beginBatchEdit()
                ic.deleteSurroundingText(tail.length, 0)
                ic.commitText(lc.original, 1)
                ic.endBatchEdit()
                lastCorrection = null
                if (learning) {
                    val eng = engine()
                    val w = lc.original.lowercase(eng.locale)
                    worker.execute { eng.learnRejected(w) }
                    scheduleSave()
                }
                afterInput()
                return
            }
        }
        lastCorrection = null
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) {
            ic.commitText("", 1)
            afterInput()
            return
        }
        if (repeat > 18) {
            // held long: delete whole words
            val before = textBefore(64)
            if (before.isEmpty()) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            } else {
                var n = before.length
                while (n > 0 && before[n - 1].isWhitespace()) n--
                val w = extractWord(before.substring(0, n))
                n -= if (w.isNotEmpty()) w.length else 1
                ic.deleteSurroundingText(maxOf(1, before.length - maxOf(0, n)), 0)
            }
        } else {
            val before = textBefore(16)
            if (before.isEmpty()) sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            else ic.deleteSurroundingText(graphemeLength(before), 0)
        }
        afterInput()
    }

    override fun onDeleteUp() {}

    // ------------------------------------------------------------------ shift & layouts

    private fun updateShift() {
        if (!::kb.isInitialized || layoutKind != LayoutKind.LETTERS || shift == 2 || gifQuery != null) return
        shift = if (shouldCap()) 1 else 0
        kb.shift = shift
    }

    private fun shouldCap(): Boolean {
        val ei = currentInputEditorInfo ?: return false
        val t = ei.inputType
        if (t and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        if (t and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0) return true
        val before = textBefore(32)
        if (t and InputType.TYPE_TEXT_FLAG_CAP_WORDS != 0) return before.isEmpty() || before.last().isWhitespace()
        if (!capsAllowed) return false
        if (before.isNotEmpty() && !before.last().isWhitespace()) return false
        return sentenceStart(before)
    }

    override fun onShiftTap() {
        val now = SystemClock.uptimeMillis()
        shift = when {
            now - lastShiftTap < 330 -> 2
            shift == 0 -> 1
            else -> 0
        }
        lastShiftTap = now
        kb.shift = shift
    }

    override fun onShiftChordEnd() {
        if (shift != 2) {
            shift = 0
            kb.shift = 0
        }
    }

    override fun onLayout(kind: LayoutKind) {
        setLayout(kind)
    }

    override fun onEmoji() {
        endGifSearch()
        if (kb.height > 0) emoji.fixedHeight = kb.height
        hidePanels()
        bar.activeTool = CandidateBar.TOOL_EMOJI
        emoji.hungarian = hu
        emoji.refresh()
        kb.visibility = View.GONE
        emoji.visibility = View.VISIBLE
        emoji.requestLayout()
        if (prefs.animations) {
            emoji.alpha = 0f
            emoji.translationY = 14 * resources.displayMetrics.density
            emoji.animate().alpha(1f).translationY(0f).setDuration(Anim.ms(240f).toLong()).start()
        }
    }

    override fun onGlobe() {
        val langs = enabledLangs()
        if (langs.size < 2) {
            onEmoji()
            return
        }
        lang = langs[(langs.indexOf(lang) + 1) % langs.size]
        prefs.language = lang
        kb.locale = engine().locale
        kb.minRows = letterRows()
        currentInputEditorInfo?.let { configureEnter(it) }
        emoji.hungarian = hu
        setLayout(LayoutKind.LETTERS)
        kb.spaceLabel = if (hu) "Magyar" else "English"
        main.removeCallbacks(restoreSpaceLabel)
        main.postDelayed(restoreSpaceLabel, 1300)
        cached = null
        scheduleRefresh()
    }

    override fun onGlobeLong() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    // ------------------------------------------------------------------ dictation

    private var recognizer: android.speech.SpeechRecognizer? = null
    private var listening = false

    /** A dictation attempt is running (possibly between two recognizers). */
    private var dictating = false

    override fun onMic() {
        endGifSearch()
        if (listening || dictating) {
            dictating = false
            recognizer?.stopListening()
            if (!listening) stopDictation()
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // a keyboard cannot ask for permissions itself: a tiny screen asks once
            startActivity(android.content.Intent(this, MicPermissionActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        dictationTry = 0
        dictationRetry = 0
        dictating = true
        startDictation()
    }

    /** Marker for the phone's on-device (offline) recognizer, Android 12+. */
    private val onDevice = android.content.ComponentName("ondevice", "ondevice")

    /**
     * Recognizers to try, best first: Google's speech service (best Hungarian), then the other
     * Google services, the system default, the on-device recognizer and the rest.
     */
    private fun recognizers(): List<android.content.ComponentName?> {
        val out = ArrayList<android.content.ComponentName?>()
        val services = try {
            packageManager.queryIntentServices(android.content.Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0)
        } catch (_: Exception) {
            emptyList()
        }
        val names = services.map { android.content.ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
        names.filter { it.packageName == "com.google.android.googlequicksearchbox" }.forEach { out += it }
        names.filter { it.packageName == "com.google.android.tts" && it !in out }.forEach { out += it }
        names.filter { it.packageName.startsWith("com.google") && it !in out }.forEach { out += it }
        out += null // the system default
        if (Build.VERSION.SDK_INT >= 31 && try {
                android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
            } catch (_: Exception) {
                false
            }
        ) out += onDevice
        names.filter { it !in out }.forEach { out += it }
        return out
    }

    private var dictationTry = 0
    private var dictationOptions: List<android.content.ComponentName?> = emptyList()
    private var dictationLang = true
    private var dictationStartedAt = 0L
    private var heardSpeech = false
    private val dictationErrors = ArrayList<String>()

    private fun startDictation() {
        if (dictationTry == 0 && dictationRetry == 0) {
            dictationOptions = recognizers()
            dictationErrors.clear()
            dictationLang = true
        }
        val options = dictationOptions
        if (dictationTry >= options.size) {
            dictationFailed()
            return
        }
        val comp = options[dictationTry]
        // keep the recognizer that worked last time (already connected to its service: no cold
        // start, which is what made the first press fail); a fresh one only after an error
        val reuse = recognizer != null && recognizerComp == comp && recognizerOk && dictationRetry == 0
        val rec = if (reuse) recognizer!! else {
            recognizer?.let {
                recognizer = null
                try {
                    it.destroy()
                } catch (_: Exception) {
                }
            }
            try {
                if (comp == onDevice && Build.VERSION.SDK_INT >= 31) android.speech.SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                else if (comp != null) android.speech.SpeechRecognizer.createSpeechRecognizer(this, comp)
                else android.speech.SpeechRecognizer.createSpeechRecognizer(this)
            } catch (e: Exception) {
                dictationErrors += "${recName(comp)}: ${e.javaClass.simpleName}"
                nextRecognizer(sameAgain = false)
                return
            }
        }
        recognizer = rec
        recognizerComp = comp
        recognizerOk = false
        heardSpeech = false
        rec.setRecognitionListener(object : android.speech.RecognitionListener {
            // callbacks of a recognizer that was replaced are ignored
            private fun current() = listening && recognizer === rec
            override fun onReadyForSpeech(params: android.os.Bundle?) {
                if (!current()) return
                recognizerOk = true
                dictationRetry = 0
                showDictation(if (hu) "Hallgatlak…" else "Listening…")
            }
            override fun onBeginningOfSpeech() {
                heardSpeech = true
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                if (current()) showDictation(if (hu) "Feldolgozás…" else "Processing…")
            }
            override fun onError(error: Int) {
                if (!current()) return
                val quick = SystemClock.uptimeMillis() - dictationStartedAt < 1500
                if ((error == android.speech.SpeechRecognizer.ERROR_NO_MATCH || error == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && (heardSpeech || !quick)) {
                    // it worked, only nothing (understandable) was said
                    stopDictation()
                    Toast.makeText(this@GlassKeyboardService, if (hu) "Nem hallottam semmit – próbáld újra." else "Didn't catch that.", Toast.LENGTH_SHORT).show()
                    return
                }
                // language not supported by a Google service: once more without asking for one
                if ((error == 12 || error == 13) && dictationLang && comp != onDevice && !hu) {
                    dictationLang = false
                    startDictation()
                    return
                }
                dictationErrors += "${recName(comp)}: ${errorText(error)}"
                // busy / not connected yet / dropped connection: the same recognizer once or
                // twice more (a cold Google service often fails the very first time)
                val transient = error == android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                    error == android.speech.SpeechRecognizer.ERROR_CLIENT || error == 11 ||
                    error == android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                    error == android.speech.SpeechRecognizer.ERROR_SERVER ||
                    error == android.speech.SpeechRecognizer.ERROR_NO_MATCH || error == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                nextRecognizer(sameAgain = transient)
            }

            override fun onResults(results: android.os.Bundle?) {
                if (!current()) return
                val text = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                stopDictation()
                if (!text.isNullOrBlank()) insertDictated(text)
            }

            override fun onPartialResults(partialResults: android.os.Bundle?) {
                if (!current()) return
                val text = partialResults?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) showDictation("🎤 $text")
            }

            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            if (dictationLang) {
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, if (hu) "hu-HU" else "en-US")
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, if (hu) "hu-HU" else "en-US")
            }
            putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        listening = true
        kb.micActive = true
        dictationStartedAt = SystemClock.uptimeMillis()
        showDictation(if (hu) "Hallgatlak…" else "Listening…")
        try {
            rec.startListening(intent)
        } catch (e: Exception) {
            dictationErrors += "${recName(comp)}: ${e.javaClass.simpleName}"
            nextRecognizer(sameAgain = true)
        }
    }

    private var dictationRetry = 0
    private var recognizerComp: android.content.ComponentName? = null
    /** the current recognizer reached "ready for speech" at least once */
    private var recognizerOk = false

    private fun nextRecognizer(sameAgain: Boolean) {
        dictationLang = true
        if (sameAgain && dictationRetry < 2) {
            dictationRetry++
        } else {
            dictationRetry = 0
            dictationTry++
        }
        // a short pause lets the previous attempt release the microphone / finish connecting
        main.postDelayed({ if (dictating) startDictation() }, if (dictationRetry > 0) 350L else 150L)
    }

    private fun recName(c: android.content.ComponentName?) = when {
        c == null -> "default"
        c == onDevice -> "offline"
        else -> c.packageName.substringAfterLast('.')
    }

    /** No recognizer on the phone worked: say why (the keyboard does not switch to another app). */
    private fun dictationFailed() {
        stopDictation()
        if (dictationErrors.isNotEmpty()) android.util.Log.w("GlassKeyboard", "dictation: " + dictationErrors.joinToString("; "))
        Toast.makeText(
            this,
            (if (hu) "Diktálás nem sikerült: " else "Dictation failed: ") + dictationErrors.distinct().take(3).joinToString("; ").ifEmpty { "?" },
            Toast.LENGTH_LONG
        ).show()
    }

    /** Text from the voice input screen: typed as soon as the field is back. */
    private fun takeVoiceResult() {
        if (currentInputConnection == null) return
        val text = VoiceActivity.take() ?: return
        insertDictated(text)
    }

    private fun errorText(e: Int): String = when (e) {
        android.speech.SpeechRecognizer.ERROR_NETWORK, android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> if (hu) "nincs internet" else "no network"
        android.speech.SpeechRecognizer.ERROR_AUDIO -> if (hu) "mikrofon hiba" else "audio error"
        android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> if (hu) "a beszédfelismerőnek nincs mikrofon engedélye (Google alkalmazás)" else "recognizer lacks mic permission"
        android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> if (hu) "a felismerő foglalt" else "recognizer busy"
        android.speech.SpeechRecognizer.ERROR_SERVER -> if (hu) "szerverhiba" else "server error"
        12, 13 -> if (hu) "a nyelv nem támogatott" else "language not supported"
        else -> (if (hu) "hibakód " else "error ") + e
    }

    private fun showDictation(text: String) {
        if (listening) bar.set(listOf(null, Candidate("", text), null))
    }

    private fun stopDictation() {
        listening = false
        dictating = false
        if (::kb.isInitialized) kb.micActive = false
        if (::bar.isInitialized) bar.clear()
        scheduleRefresh()
    }

    /** Puts the dictated text in with sensible spacing and a capital at sentence start. */
    private fun insertDictated(raw: String) {
        val ic = currentInputConnection ?: return
        val before = textBefore(32)
        var text = raw.trim()
        if (sentenceStart(before) && capsAllowed) text = text.substring(0, 1).uppercase(engine().locale) + text.substring(1)
        val space = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
        ic.commitText(space + text + " ", 1)
        lastCorrection = null
        afterInput()
    }

    override fun onFinishInput() {
        if (listening) {
            recognizer?.cancel()
            stopDictation()
        }
        super.onFinishInput()
    }

    private fun switchToVoiceIme(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        for (imi in imm.enabledInputMethodList) {
            if (imi.packageName == packageName) continue
            for (st in imm.getEnabledInputMethodSubtypeList(imi, true)) {
                if (st.mode == "voice") {
                    if (Build.VERSION.SDK_INT >= 28) switchInputMethod(imi.id, st)
                    else {
                        @Suppress("DEPRECATION")
                        window?.window?.attributes?.token?.let { imm.setInputMethodAndSubtype(it, imi.id, st) }
                    }
                    return true
                }
            }
        }
        return false
    }

    override fun onCursor(dx: Int, dy: Int) {
        val ic = currentInputConnection ?: return
        lastCorrection = null
        val kx = if (dx < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(dx)) { sendDownUpKeyEvents(kx) }
        val ky = if (dy < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
        repeat(kotlin.math.abs(dy)) { sendDownUpKeyEvents(ky) }
        if (dx != 0 || dy != 0) feedbackTick()
        ic.hashCode()
    }

    override fun onCursorModeEnd() {
        scheduleRefresh()
    }

    // ------------------------------------------------------------------ emoji panel

    override fun onEmojiPicked(e: String) {
        val ic = currentInputConnection ?: return
        autoSpace = false
        lastCorrection = null
        ic.commitText(e, 1)
        afterInput()
    }

    override fun onEmojiBack() = showKeys()

    override fun onEmojiDelete(repeat: Int) = onDelete(if (repeat > 0) 1 else 0)

    override fun feedbackLight() = feedbackTick()

    // ------------------------------------------------------------------ feedback

    /** A single crisp haptic tap when a suggestion is chosen. */
    private fun suggestionTap() {
        val v = vibrator ?: return
        if (prefs.haptics == 0) return
        try {
            if (Build.VERSION.SDK_INT >= 29) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            else v.vibrate(VibrationEffect.createOneShot(14, 140))
        } catch (_: Exception) {
        }
    }

    private fun feedbackTick() {
        val v = vibrator ?: return
        if (prefs.haptics == 0) return
        try {
            if (Build.VERSION.SDK_INT >= 29) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            else v.vibrate(VibrationEffect.createOneShot(8, 60))
        } catch (_: Exception) {
        }
    }

    override fun feedback(key: Key) {
        val v = vibrator
        val h = prefs.haptics
        if (v != null && h > 0) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    v.vibrate(VibrationEffect.createPredefined(if (h == 2) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK))
                } else {
                    v.vibrate(VibrationEffect.createOneShot(if (h == 2) 18 else 9, if (h == 2) 160 else 80))
                }
            } catch (_: Exception) {
            }
        }
        if (prefs.sound) {
            audio?.playSoundEffect(
                when (key.type) {
                    KeyType.DELETE -> AudioManager.FX_KEYPRESS_DELETE
                    KeyType.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                    KeyType.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                    else -> AudioManager.FX_KEYPRESS_STANDARD
                }, 0.35f
            )
        }
    }
}
