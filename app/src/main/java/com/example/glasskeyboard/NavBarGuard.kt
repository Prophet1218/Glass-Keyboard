package com.example.glasskeyboard

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window

/**
 * Keeps the strip under the keyboard (where the system draws the gesture handle) the keyboard's
 * own colour.
 *
 * On Android 13+ with gesture navigation the system adds a `NavigationBarFrame` view (hide
 * chevron, keyboard switcher) to the keyboard's own window and fills it with black; the window's
 * navigation bar colour is also reset by the system now and then (e.g. when the keyboard's size
 * or insets change). Before every frame this checks both and puts the keyboard's colour back. The
 * frame is found by class name among the window's views (the way WM Keyboard locates it) and
 * searched again when the system replaced it.
 */
class NavBarGuard {
    private var frame: View? = null
    private var decor: ViewGroup? = null
    private var window: Window? = null
    private var observer: ViewTreeObserver? = null
    var color: Int = 0
        private set
    private val onPreDraw = ViewTreeObserver.OnPreDrawListener {
        apply()
        true
    }

    fun attach(window: Window, color: Int) {
        this.color = color
        this.window = window
        val d = window.decorView as? ViewGroup ?: return
        if (d !== decor) {
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(onPreDraw)
            decor = d
            observer = d.viewTreeObserver.also { it.addOnPreDrawListener(onPreDraw) }
            frame = null
        }
        apply()
    }

    private fun apply() {
        val d = decor ?: return
        val w = window
        if (w != null) {
            @Suppress("DEPRECATION")
            if (w.navigationBarColor != color) w.navigationBarColor = color
            if (Build.VERSION.SDK_INT >= 29 && w.isNavigationBarContrastEnforced) w.isNavigationBarContrastEnforced = false
        }
        // the window's own nav bar colour view and any (also phone-maker) navigation view that
        // the system put directly into the keyboard window get the keyboard's colour
        for (i in 0 until d.childCount) {
            val c = d.getChildAt(i)
            if (c.id == android.R.id.navigationBarBackground || (c !is ViewGroup && c.javaClass.name.contains("Navigation", true))) tint(c)
        }
        var f = frame
        if (f == null || !f.isAttachedToWindow || f.rootView !== d) {
            f = find(d, 0)
            frame = f
        }
        if (f == null) return
        tint(f)
        // its inner bar views should not paint a background of their own either
        if (f is ViewGroup) clearInner(f, 0)
    }

    private fun tint(v: View) {
        val bg = v.background
        if (bg is android.graphics.drawable.ColorDrawable && bg.color == color) return
        if (bg == null && color == 0) return
        v.setBackgroundColor(color)
    }

    private fun clearInner(g: ViewGroup, depth: Int) {
        if (depth > 2) return
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            val bg = c.background
            if (bg is android.graphics.drawable.ColorDrawable && bg.color != 0) c.background = null
            if (c is ViewGroup) clearInner(c, depth + 1)
        }
    }

    private fun find(g: ViewGroup, depth: Int): View? {
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (isFrame(c)) return c
        }
        if (depth >= 4) return null
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i) as? ViewGroup ?: continue
            if (c.id == android.R.id.inputArea || c.id == android.R.id.extractArea) continue
            find(c, depth + 1)?.let { return it }
        }
        return null
    }

    private fun isFrame(v: View): Boolean {
        var t: Class<*>? = v.javaClass
        while (t != null && t != View::class.java) {
            if (t.simpleName == "NavigationBarFrame") return true
            t = t.superclass
        }
        return false
    }
}
