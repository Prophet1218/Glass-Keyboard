package com.example.glasskeyboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager

/**
 * An invisible, untouchable screen that exists only while dictating, as a last resort: while one
 * of the app's screens is in front, the phone treats the app as a foreground app and lets it (and
 * the recognizer working for it) use the microphone. It never takes the focus, so the text field
 * and the keyboard stay as they are.
 */
class MicBoostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )
        super.onCreate(savedInstanceState)
        current = this
        if (!wanted) finish()
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        @Volatile private var wanted = false
        private var current: MicBoostActivity? = null

        fun start(ctx: Context): Boolean = try {
            wanted = true
            ctx.startActivity(
                Intent(ctx, MicBoostActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                )
            )
            true
        } catch (_: Exception) {
            wanted = false
            false
        }

        fun release() {
            wanted = false
            current?.finish()
        }
    }
}
