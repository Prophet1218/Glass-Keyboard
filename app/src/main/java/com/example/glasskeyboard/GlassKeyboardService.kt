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

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        lang = prefs.language
        @Suppress("DEPRECATION")
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        audio = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    override fun onDestroy() {
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
        Engine(l == "hu", Dict(buf), Shared.userDict(this, l), proper)
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
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(area, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val r = KeyboardRoot(this, panel, overlay)
        root = r
        // draw the glass sheet behind the navigation bar too (no black band under the keys)
        if (Build.VERSION.SDK_INT >= 30) {
            window?.window?.let { w ->
                w.setDecorFitsSystemWindows(false)
                @Suppress("DEPRECATION")
                w.navigationBarColor = android.graphics.Color.TRANSPARENT
                w.isNavigationBarContrastEnforced = false
            }
            r.setOnApplyWindowInsetsListener { _, insets ->
                r.bottomInset = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
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
        overlay.theme = t
        styleNavBar(t)
    }

    private fun styleNavBar(t: GlassTheme) {
        val w = window?.window ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            @Suppress("DEPRECATION")
            w.navigationBarColor = android.graphics.Color.TRANSPARENT
            w.isNavigationBarContrastEnforced = false
        }
        // see-through sheet: blur whatever the app shows behind the keyboard (Android 12+)
        if (Build.VERSION.SDK_INT >= 31) try {
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            val blur = if (t.panelAlpha < 0.98f && !t.solid) (t.params.blur * 3f * t.dp + (if (t.frosted) 24f * t.dp else 0f)).toInt() else 0
            w.setBackgroundBlurRadius(blur)
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT < 30) {
            @Suppress("DEPRECATION")
            w.navigationBarColor = t.panelBottom
        }
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

    override fun onEvaluateFullscreenMode() = false

    override fun onWindowShown() {
        super.onWindowShown()
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
        bar.animations = prefs.animations
        bar.style = prefs.suggAnim
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

    private fun showKeys() {
        val wasEmoji = emoji.visibility == View.VISIBLE
        emoji.visibility = View.GONE
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
                val p = eng.predict(prev, start, 3)
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
        if (!::kb.isInitialized || layoutKind != LayoutKind.LETTERS || shift == 2) return
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
        emoji.fixedHeight = kb.height
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

    override fun onMic() {
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
                    return
                }
            }
        }
        Toast.makeText(this, if (hu) "Nincs bekapcsolt hangalapú bevitel (pl. Google hangbevitel)." else "No voice input method is enabled.", Toast.LENGTH_LONG).show()
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
