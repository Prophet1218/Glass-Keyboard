package com.example.glasskeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray

/** Recent clipboard texts (kept on the phone, newest first). */
class ClipStore(private val prefs: Prefs) {
    private val max = 25

    fun all(): MutableList<String> {
        val raw = prefs.sp.getString(KEY, "[]") ?: "[]"
        return try {
            val a = JSONArray(raw)
            MutableList(a.length()) { a.getString(it) }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun save(l: List<String>) {
        val a = JSONArray()
        l.take(max).forEach { a.put(it) }
        prefs.sp.edit().putString(KEY, a.toString()).apply()
    }

    fun add(text: String) {
        val t = text.take(4000)
        if (t.isBlank()) return
        val l = all()
        l.remove(t)
        l.add(0, t)
        save(l)
    }

    fun remove(text: String) {
        val l = all()
        l.remove(text)
        save(l)
    }

    fun clear() = save(emptyList())

    companion object {
        private const val KEY = "clip_history"
    }
}

/**
 * Clipboard panel shown in place of the keys: a scrolling list of recent copies, tap to paste,
 * long-press to delete, with "ABC" to go back.
 */
@SuppressLint("ViewConstructor")
class ClipboardPanel(
    context: Context,
    private val store: ClipStore,
    private val onPaste: (String) -> Unit,
    private val onBack: () -> Unit,
) : FrameLayout(context) {
    var theme: GlassTheme = GlassTheme(context, 0)
    var hungarian = true
    var fixedHeight = 0
    private val dp = resources.displayMetrics.density
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context)

    init {
        scroll.addView(list)
        scroll.isVerticalScrollBarEnabled = false
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (fixedHeight > 0) fixedHeight else (260 * dp).toInt()
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    private fun chip(text: String, bold: Boolean = false): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(theme.text)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply {
            setColor(theme.key)
            cornerRadius = 12 * dp
        }
        setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
    }

    fun refresh() {
        list.removeAllViews()
        val pad = (8 * dp).toInt()
        list.setPadding(pad, pad, pad, pad)
        // header: ABC · title · clear
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(chip("ABC", true).apply { setOnClickListener { onBack() } })
        head.addView(TextView(context).apply {
            text = if (hungarian) "Vágólap" else "Clipboard"
            setTextColor(theme.textSoft)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(chip(if (hungarian) "Összes törlése" else "Clear all").apply {
            setOnClickListener {
                store.clear()
                refresh()
            }
        })
        list.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = pad })
        val items = store.all()
        if (items.isEmpty()) {
            list.addView(TextView(context).apply {
                text = if (hungarian) "Még nincs másolt szöveg. Amit kimásolsz, itt jelenik meg." else "Nothing copied yet."
                setTextColor(theme.textSoft)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                setPadding(0, (30 * dp).toInt(), 0, 0)
            })
        }
        for (s in items) {
            val v = chip(s).apply {
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setOnClickListener { onPaste(s) }
                setOnLongClickListener {
                    store.remove(s)
                    refresh()
                    true
                }
            }
            list.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (6 * dp).toInt() })
        }
        scroll.scrollTo(0, 0)
    }

    @Suppress("unused")
    private fun gone(v: View) {
        v.visibility = View.GONE
    }
}
