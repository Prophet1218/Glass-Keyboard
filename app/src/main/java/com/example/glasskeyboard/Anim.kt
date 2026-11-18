package com.example.glasskeyboard

/** Global animation speed (settings slider): 1 = normal, 2 = twice as fast, 0.5 = half speed. */
object Anim {
    @JvmStatic
    var speed = 1f

    /** A duration in ms adjusted to the chosen speed. */
    fun ms(base: Float): Float = base / speed.coerceIn(0.25f, 4f)
}
