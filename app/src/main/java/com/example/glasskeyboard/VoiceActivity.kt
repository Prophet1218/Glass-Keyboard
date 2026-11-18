package com.example.glasskeyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast

/**
 * Last-resort dictation: opens the phone's own voice input screen (Google's speech dialog) and
 * hands the recognised text back to the keyboard, which types it once the text field is back.
 */
class VoiceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val hu = intent.getBooleanExtra(EXTRA_HU, true)
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (hu) "hu-HU" else "en-US")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, if (hu) "hu-HU" else "en-US")
            putExtra(RecognizerIntent.EXTRA_PROMPT, if (hu) "Diktálj…" else "Speak…")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, 1)
        } catch (_: Exception) {
            Toast.makeText(this, if (hu) "Nincs elérhető beszédfelismerés a telefonon (pl. Google alkalmazás)." else "No speech recognition is available.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (resultCode == RESULT_OK && !text.isNullOrBlank()) {
            pending = text
            pendingAt = System.currentTimeMillis()
            listener?.invoke()
        }
        finish()
        overridePendingTransitionCompat()
    }

    @Suppress("DEPRECATION")
    private fun overridePendingTransitionCompat() = overridePendingTransition(0, 0)

    companion object {
        const val EXTRA_HU = "hu"
        @Volatile var pending: String? = null
        @Volatile var pendingAt = 0L
        /** Set by the keyboard: called when a result arrived. */
        var listener: (() -> Unit)? = null

        /** The dictated text, if it arrived in the last minute (taken only once). */
        fun take(): String? {
            val t = pending ?: return null
            pending = null
            return if (System.currentTimeMillis() - pendingAt < 60_000) t else null
        }
    }
}
