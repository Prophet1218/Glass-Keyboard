package com.example.glasskeyboard

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small log of what dictation and the keyboard window did, kept on the phone and shown in the
 * settings (Diagnostics). It lets the user send a screenshot when something does not work.
 */
object DiagLog {
    private const val MAX = 80
    private val lines = ArrayDeque<String>()
    private var loaded = false
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val s = ctx.getSharedPreferences("diag", Context.MODE_PRIVATE).getString("log", "") ?: ""
        if (s.isNotEmpty()) s.split('\n').forEach { lines.addLast(it) }
    }

    @Synchronized
    fun add(ctx: Context, tag: String, msg: String) {
        load(ctx)
        lines.addLast("${fmt.format(Date())} [$tag] $msg")
        while (lines.size > MAX) lines.removeFirst()
        android.util.Log.i("GlassKeyboard", "[$tag] $msg")
        ctx.getSharedPreferences("diag", Context.MODE_PRIVATE).edit().putString("log", lines.joinToString("\n")).apply()
    }

    /** Writes crashes of the app into the log (saved at once, before the process dies). */
    fun installCrashLogger(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is CrashLogger) return
        Thread.setDefaultUncaughtExceptionHandler(CrashLogger(app, prev))
    }

    private class CrashLogger(val ctx: Context, val prev: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                val trace = sw.toString().lines().take(14).joinToString("\n")
                synchronized(DiagLog) {
                    load(ctx)
                    lines.addLast("${fmt.format(Date())} [crash] $trace")
                    while (lines.size > MAX) lines.removeFirst()
                    ctx.getSharedPreferences("diag", Context.MODE_PRIVATE).edit().putString("log", lines.joinToString("\n")).commit()
                }
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    @Synchronized
    fun text(ctx: Context): String {
        load(ctx)
        return lines.joinToString("\n")
    }

    @Synchronized
    fun clear(ctx: Context) {
        lines.clear()
        loaded = true
        ctx.getSharedPreferences("diag", Context.MODE_PRIVATE).edit().remove("log").apply()
    }
}
