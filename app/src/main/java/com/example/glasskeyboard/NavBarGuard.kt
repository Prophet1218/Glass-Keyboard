package com.example.glasskeyboard

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver

/**
 * Paints the system's keyboard navigation bar in the keyboard's colour.
 *
 * On Android 13+ with gesture navigation the system adds a `NavigationBarFrame` view (hide
 * chevron, keyboard switcher) to the keyboard's own window and fills it with black. The frame is
 * found by class name among the window's views (the way WM Keyboard locates it) and its background
 * is set to [color] before every frame, in case the system paints it black again.
 */
class NavBarGuard {
    private var frame: View? = null
    private var observer: ViewTreeObserver? = null
    var color: Int = 0
    private val onPreDraw = ViewTreeObserver.OnPreDrawListener {
        apply()
        true
    }

    fun attach(decor: View?, color: Int) {
        this.color = color
        val d = decor as? ViewGroup ?: return
        val found = find(d, 0)
        if (found !== frame) {
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(onPreDraw)
            frame = found
            observer = if (found != null) d.viewTreeObserver.also { it.addOnPreDrawListener(onPreDraw) } else null
        }
        apply()
    }

    private fun apply() {
        val f = frame ?: return
        val bg = f.background
        if (bg is android.graphics.drawable.ColorDrawable && bg.color == color) return
        f.setBackgroundColor(color)
    }

    private fun find(g: ViewGroup, depth: Int): View? {
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (isFrame(c)) return c
        }
        if (depth >= 3) return null
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
