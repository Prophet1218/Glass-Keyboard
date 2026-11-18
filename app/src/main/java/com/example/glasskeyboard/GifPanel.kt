package com.example.glasskeyboard

import android.annotation.SuppressLint
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.Executors

/** One GIF from the search service. */
data class GifItem(val id: String, val title: String, val thumb: String, val full: String, val page: String)

/**
 * GIF search through KLIPY (the Tenor API was shut down by Google on 30 June 2026; KLIPY is the
 * Tenor-style replacement). Needs a free API key from partner.klipy.com, set in the settings.
 */
object GifApi {
    private const val BASE = "https://api.klipy.com/api/v1/"

    fun customerId(prefs: Prefs): String {
        var id = prefs.sp.getString("gif_customer", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.sp.edit().putString("gif_customer", id).apply()
        }
        return id
    }

    /** Trending GIFs when [query] is blank, otherwise a search. Runs on a background thread. */
    fun fetch(key: String, query: String, locale: String, customer: String): List<GifItem> {
        val q = query.trim()
        val path = if (q.isEmpty()) "gifs/trending" else "gifs/search"
        val url = StringBuilder(BASE).append(URLEncoder.encode(key, "UTF-8")).append('/').append(path)
            .append("?page=1&per_page=36&customer_id=").append(customer)
            .append("&locale=").append(locale)
        if (q.isNotEmpty()) url.append("&q=").append(URLEncoder.encode(q, "UTF-8"))
        val body = String(download(url.toString()), Charsets.UTF_8)
        val root = JSONObject(body)
        val arr = root.optJSONObject("data")?.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<GifItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type") == "ad") continue
            val file = o.optJSONObject("file") ?: continue
            fun pick(vararg sizes: String): String? {
                for (s in sizes) {
                    val u = file.optJSONObject(s)?.optJSONObject("gif")?.optString("url")
                    if (!u.isNullOrEmpty()) return u
                }
                return null
            }
            val thumb = pick("sm", "xs", "md") ?: continue
            val full = pick("md", "sm", "hd") ?: thumb
            val slug = o.optString("slug")
            out += GifItem(o.optString("id", slug), o.optString("title"), thumb, full, if (slug.isNotEmpty()) "https://klipy.com/gifs/$slug" else full)
        }
        return out
    }

    fun download(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "GlassKeyboard")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
}

/** Serves downloaded GIFs to the app they are sent into (content:// URIs). */
class GifProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.example.glasskeyboard.gifs"
        fun dir(ctx: Context) = File(ctx.cacheDir, "gifs").also { it.mkdirs() }
        fun uriFor(name: String): Uri = Uri.parse("content://$AUTHORITY/$name")
    }

    override fun onCreate() = true

    private fun file(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.contains("..")) return null
        val f = File(dir(context!!), name)
        return if (f.exists()) f else null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = file(uri) ?: return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/gif"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = file(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        if (f != null) c.addRow(cols.map { if (it == OpenableColumns.SIZE) f.length() else f.name }.toTypedArray())
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

/**
 * GIF panel shown in place of the keys: category chips (or what you had typed) on top, a grid of
 * animated previews below; a tap sends the GIF.
 */
@SuppressLint("ViewConstructor")
class GifPanel(
    context: Context,
    private val prefs: Prefs,
    private val onSend: (GifItem) -> Unit,
    private val onBack: () -> Unit,
) : FrameLayout(context) {
    var theme: GlassTheme = GlassTheme(context, 0)
    var hungarian = true
    var fixedHeight = 0
    private val dp = resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(4)
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context)
    private val status = TextView(context)
    private var generation = 0
    private val running = ArrayList<Drawable>()

    init {
        scroll.addView(grid)
        scroll.isVerticalScrollBarEnabled = false
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (fixedHeight > 0) fixedHeight else (260 * dp).toInt()
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    private fun chip(text: String, selected: Boolean = false, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(if (selected) 0xFFFFFFFF.toInt() else theme.text)
        typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        background = GradientDrawable().apply {
            setColor(if (selected) theme.accent else theme.key)
            cornerRadius = 16 * dp
        }
        setPadding((14 * dp).toInt(), (7 * dp).toInt(), (14 * dp).toInt(), (7 * dp).toInt())
        setOnClickListener { onClick() }
    }

    private fun categories(): List<String> = if (hungarian)
        listOf("haha", "szia", "köszi", "szeretlek", "wow", "igen", "nem", "oké", "szomorú", "buli", "taps", "jó éjt")
    else listOf("lol", "hi", "thanks", "love", "wow", "yes", "no", "ok", "sad", "party", "clap", "good night")

    /** Opens the panel; [query] (e.g. the word you had typed) searches straight away. */
    fun open(query: String) {
        column.removeAllViews()
        // header: ABC + categories
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
        }
        head.addView(chip("ABC") { onBack() })
        val cats = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val hs = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(cats)
        }
        val trending = if (hungarian) "Népszerű" else "Trending"
        val all = listOf(trending) + (if (query.isNotBlank() && query !in categories()) listOf(query) else emptyList()) + categories()
        fun select(label: String) {
            cats.removeAllViews()
            for (c in all) {
                cats.addView(chip(c, c == label) { select(c) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = (6 * dp).toInt() })
            }
            load(if (label == trending) "" else label)
        }
        head.addView(hs, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(head)
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        select(if (query.isNotBlank()) query else trending)
    }

    fun close() {
        generation++
        for (d in running) (d as? Animatable)?.stop()
        running.clear()
        grid.removeAllViews()
    }

    private fun message(text: String) {
        grid.removeAllViews()
        status.text = text
        status.setTextColor(theme.textSoft)
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        status.gravity = Gravity.CENTER
        status.setPadding((20 * dp).toInt(), (30 * dp).toInt(), (20 * dp).toInt(), 0)
        (status.parent as? ViewGroup)?.removeView(status)
        grid.addView(status)
    }

    private fun load(query: String) {
        val gen = ++generation
        for (d in running) (d as? Animatable)?.stop()
        running.clear()
        val key = prefs.klipyKey
        if (key.isEmpty()) {
            message(
                if (hungarian) "A GIF-ekhez egy ingyenes KLIPY API kulcs kell (a Tenor API-t a Google 2026 júniusában leállította).\n\nKérj kulcsot a partner.klipy.com oldalon, és írd be a Glass Keyboard beállításaiban a „GIF” résznél."
                else "GIFs need a free KLIPY API key (Google shut down the Tenor API in June 2026). Get one at partner.klipy.com and enter it in the Glass Keyboard settings."
            )
            return
        }
        message(if (hungarian) "Betöltés…" else "Loading…")
        val locale = if (hungarian) "hu_HU" else "en_US"
        val customer = GifApi.customerId(prefs)
        pool.execute {
            val result = try {
                Result.success(GifApi.fetch(key, query, locale, customer))
            } catch (e: Exception) {
                Result.failure(e)
            }
            main.post {
                if (gen != generation) return@post
                result.onSuccess { show(it, gen) }.onFailure {
                    message((if (hungarian) "Nem sikerült betölteni a GIF-eket: " else "Could not load GIFs: ") + (it.message ?: it.javaClass.simpleName))
                }
            }
        }
    }

    private fun show(items: List<GifItem>, gen: Int) {
        grid.removeAllViews()
        if (items.isEmpty()) {
            message(if (hungarian) "Nincs találat." else "No results.")
            return
        }
        val cols = 3
        val gap = (4 * dp).toInt()
        val cell = ((width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - gap * (cols + 1)) / cols
        grid.setPadding(gap, 0, gap, gap)
        var row: LinearLayout? = null
        for ((i, item) in items.withIndex()) {
            if (i % cols == 0) {
                row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = gap })
            }
            val iv = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    setColor(theme.key)
                    cornerRadius = 10 * dp
                }
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, 10 * dp)
                    }
                }
                contentDescription = item.title
                setOnClickListener { onSend(item) }
            }
            row!!.addView(iv, LinearLayout.LayoutParams(cell, (cell * 0.8f).toInt()).apply { if (i % cols != 0) leftMargin = gap })
            pool.execute {
                val d = try {
                    decode(GifApi.download(item.thumb))
                } catch (_: Exception) {
                    null
                }
                main.post {
                    if (gen != generation || d == null) return@post
                    iv.setImageDrawable(d)
                    (d as? Animatable)?.start()
                    running += d
                }
            }
        }
        scroll.scrollTo(0, 0)
    }

    private fun decode(bytes: ByteArray): Drawable? {
        if (Build.VERSION.SDK_INT >= 28) {
            val src = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
            return android.graphics.ImageDecoder.decodeDrawable(src)
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }
}
