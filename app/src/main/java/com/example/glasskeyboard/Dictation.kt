package com.example.glasskeyboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Dictation inside the keyboard, with the phone's speech recognizers.
 *
 * Several ways are tried one after the other until one works, best first:
 *  1. Google's recognizer, recording by itself (the usual way; best Hungarian);
 *  2. Google's recognizer fed with the keyboard's own recording (Android 13+). A recognizer in
 *     another app runs in the background and on some phones / in some situations (e.g. while the
 *     screen is being recorded) it is given only silence. The visible keyboard itself may always
 *     record, so it records and passes the sound on;
 *  3. the phone's offline recognizer (fed with our recording, then by itself);
 *  4. the system default and any other recognizer.
 *
 * A way is given up when it fails, when it gets only silence while the keyboard's own recording
 * hears the user, or when it answers with an empty result. Every step goes to [DiagLog].
 */
class Dictation(private val ctx: Context, private val main: Handler, private val ui: Callbacks) {
    interface Callbacks {
        fun status(text: String)
        fun partial(text: String)
        fun result(text: String)
        /** finished; [message] is shown to the user (null = nothing to say) */
        fun end(message: String?)
    }

    private data class Way(val comp: ComponentName?, val selfAudio: Boolean)

    private val onDevice = ComponentName("ondevice", "ondevice")
    private val google = "com.google.android.googlequicksearchbox"
    private val mic = MicPipe()

    private var rec: SpeechRecognizer? = null
    private var recComp: ComponentName? = null
    /** the recognizer reached "ready for speech" before: it is connected and can be reused */
    private var recWarm = false

    private var ways: List<Way> = emptyList()
    private var index = 0
    private var retries = 0
    private var attempt = 0
    private var hu = true
    private var useLanguage = true
    private var startedAt = 0L
    private var heard = false
    /** words actually arrived (partial result) – "speech begins" alone is not trusted */
    private var gotWords = false
    /** the user tapped the mic to finish: no more ways are tried after this */
    private var stopped = false
    /** our own recording was silent: the keyboard process itself may not use the microphone */
    private var ownSilent = false
    private var boosted = false
    private var maxRms = -100f
    private val errors = ArrayList<String>()
    private var modelAsked = false
    private var lastIntent: Intent? = null

    /** a dictation is running (possibly between two ways) */
    var active = false
        private set
    /** the current way is listening */
    var listening = false
        private set

    private fun log(msg: String) = DiagLog.add(ctx, "mic", msg)

    private fun services(): List<ComponentName> = try {
        ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun onDeviceAvailable() = Build.VERSION.SDK_INT >= 31 && try {
        SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
    } catch (_: Exception) {
        false
    }

    /** Google first (the Google app, then "Speech Services by Google"), then others; the default last. */
    private fun rank(c: ComponentName): Int = when {
        c.packageName == google -> 0
        c.packageName == "com.google.android.tts" -> 1
        c.packageName.startsWith("com.google") -> 2
        c.packageName == ctx.packageName -> 9
        else -> 5
    }

    private fun plan(): List<Way> {
        val names = services().filter { it.packageName != ctx.packageName }.sortedBy { rank(it) }
        val self = Build.VERSION.SDK_INT >= 33
        val out = ArrayList<Way>()
        // a recognizer in another app recording by itself often gets only silence (log: rms
        // stays at -2): first feed it the keyboard's own recording, then let it record itself
        for (n in names) {
            if (self) out += Way(n, true)
            out += Way(n, false)
        }
        if (onDeviceAvailable()) {
            if (self) out += Way(onDevice, true)
            out += Way(onDevice, false)
        }
        // the system default is often not set up (instant "client error")
        out += Way(null, false)
        return out
    }

    private fun name(c: ComponentName?) = when {
        c == null -> "default"
        c == onDevice -> "offline"
        c.packageName == google -> "google"
        c.packageName == "com.google.android.tts" -> "google-speech"
        else -> c.packageName.substringAfterLast('.')
    }

    private fun create(comp: ComponentName?): SpeechRecognizer =
        if (comp == onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        else if (comp != null) SpeechRecognizer.createSpeechRecognizer(ctx, comp)
        else SpeechRecognizer.createSpeechRecognizer(ctx)

    private fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        if (useLanguage) {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (hu) "hu-HU" else "en-US")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, if (hu) "hu-HU" else "en-US")
        }
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
    }

    /**
     * Connects Google's recognizer in advance (Android 13+), so the first dictation does not hit
     * a cold service (the first press used to fail).
     */
    fun warmUp(hu: Boolean) {
        if (active || Build.VERSION.SDK_INT < 33 || recWarm) return
        val g = services().firstOrNull { it.packageName == google } ?: return
        try {
            if (rec == null || recComp != g) {
                rec?.destroy()
                rec = create(g)
                recComp = g
            }
            this.hu = hu
            useLanguage = true
            rec!!.checkRecognitionSupport(intent(), { it.run() }, object : android.speech.RecognitionSupportCallback {
                override fun onSupportResult(recognitionSupport: android.speech.RecognitionSupport) {
                    recWarm = true
                }

                override fun onError(error: Int) {}
            })
        } catch (_: Throwable) {
        }
    }

    fun start(hu: Boolean) {
        if (active) return
        this.hu = hu
        ways = plan()
        index = 0
        retries = 0
        errors.clear()
        stopped = false
        ownSilent = false
        boosted = false
        active = true
        log("start (${if (hu) "hu" else "en"}), ways: " + ways.joinToString { name(it.comp) + if (it.selfAudio) "+mic" else "" })
        next(0)
    }

    /** The user tapped the mic again: finish listening (the result still arrives). */
    fun stop() {
        if (!active) return
        stopped = true
        if (listening) {
            log("stop by user (rms max $maxRms, mic peak ${mic.peak}, words $gotWords)")
            mic.stop()
            try {
                rec?.stopListening()
            } catch (_: Exception) {
            }
            // if no answer comes, close anyway
            val a = attempt
            main.postDelayed({ if (active && a == attempt) finish(null) }, 4000)
        } else finish(null)
    }

    /** The text field went away: stop without a result. */
    fun cancel() {
        if (!active) return
        log("cancel")
        try {
            rec?.cancel()
        } catch (_: Exception) {
        }
        finish(null, silent = true)
    }

    fun destroy() {
        cancel()
        try {
            rec?.destroy()
        } catch (_: Exception) {
        }
        rec = null
    }

    private fun finish(message: String?, silent: Boolean = false) {
        if (boosted) MicBoostActivity.release()
        active = false
        listening = false
        attempt++
        mic.stop()
        if (!silent) ui.end(message) else ui.end(null)
    }

    /** Try the current way again ([same]) or move on to the next one, after [delay] ms. */
    private fun next(delay: Long) {
        val a = ++attempt
        listening = false
        main.postDelayed({ if (active && a == attempt) run() }, delay)
    }

    private fun giveUp(reason: String, sameAgain: Boolean = false) {
        val w = ways.getOrNull(index)
        errors += "${name(w?.comp)}${if (w?.selfAudio == true) "+mic" else ""}: $reason"
        log("× ${errors.last()}")
        mic.stop()
        try {
            rec?.cancel()
        } catch (_: Exception) {
        }
        recWarm = false
        if (stopped) {
            finish((if (hu) "Diktálás nem sikerült: " else "Dictation failed: ") + reason)
            return
        }
        if (sameAgain && retries < 2) {
            retries++
            next(400)
        } else {
            retries = 0
            index++
            useLanguage = true
            next(150)
        }
    }

    private fun run() {
        val w = ways.getOrNull(index)
        if (w == null && ownSilent && !boosted && MicBoostActivity.start(ctx)) {
            // Even the keyboard's own recording was silent: the phone does not let the keyboard
            // process use the microphone in the background. A tiny invisible screen of the app
            // makes it a foreground app for the time of the dictation; then everything again.
            boosted = true
            log("own recording silent: foreground boost, starting over")
            index = 0
            retries = 0
            next(600)
            return
        }
        if (w == null) {
            log("all ways failed")
            finish(
                (if (hu) "Diktálás nem sikerült. " else "Dictation failed. ") +
                    errors.distinct().takeLast(3).joinToString("; ")
            )
            return
        }
        val reuse = rec != null && recComp == w.comp && recWarm && retries == 0
        val r = if (reuse) rec!! else {
            try {
                rec?.destroy()
            } catch (_: Exception) {
            }
            rec = null
            recWarm = false
            try {
                create(w.comp)
            } catch (e: Throwable) {
                giveUp(e.javaClass.simpleName)
                return
            }
        }
        rec = r
        recComp = w.comp
        val a = attempt
        heard = false
        gotWords = false
        maxRms = -100f
        log("try ${name(w.comp)}${if (w.selfAudio) "+mic" else ""}${if (reuse) " (warm)" else ""}${if (retries > 0) " retry $retries" else ""}")
        r.setRecognitionListener(object : RecognitionListener {
            private fun mine() = active && a == attempt && rec === r

            override fun onReadyForSpeech(params: Bundle?) {
                if (!mine()) return
                recWarm = true
                log("ready")
                ui.status(if (hu) "Hallgatlak…" else "Listening…")
            }

            override fun onBeginningOfSpeech() {
                if (!mine()) return
                heard = true
                log("speech begins")
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (rmsdB > maxRms) maxRms = rmsdB
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                if (!mine()) return
                mic.stop()
                ui.status(if (hu) "Feldolgozás…" else "Processing…")
            }

            override fun onError(error: Int) {
                if (!mine()) return
                val ms = SystemClock.uptimeMillis() - startedAt
                log("error $error (${errorText(error)}) after ${ms}ms, rms max $maxRms, mic peak ${mic.peak}, heard $heard")
                mic.stop()
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // believable "nothing said" only if the recognizer really got sound
                        val gotSound = if (w.selfAudio) mic.peak in 40..2500 else maxRms > 0f
                        if (gotWords || (gotSound && ms > 1500)) {
                            finish(if (hu) "Nem hallottam semmit – próbáld újra." else "Didn't catch that – try again.")
                        } else giveUp(if (w.selfAudio && mic.peak > 2500) (if (hu) "nem fogadja a hangot" else "ignores the audio") else (if (hu) "csak csendet kap" else "gets only silence"))
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> giveUp(if (hu) "nincs mikrofon-engedély" else "no microphone permission")
                    12, 13 -> {
                        if (w.comp == onDevice && Build.VERSION.SDK_INT >= 33 && !modelAsked) {
                            modelAsked = true
                            try {
                                r.triggerModelDownload(lastIntent ?: intent())
                                log("asked offline recognizer to download the model")
                            } catch (_: Throwable) {
                            }
                        }
                        if (useLanguage && !hu && w.comp != onDevice) {
                            useLanguage = false
                            retries = 0
                            next(100)
                        } else giveUp(errorText(error))
                    }
                    else -> {
                        val transient = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT ||
                            error == 11 || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT || error == SpeechRecognizer.ERROR_SERVER ||
                            error == SpeechRecognizer.ERROR_NETWORK
                        giveUp(errorText(error), sameAgain = transient)
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                if (!mine()) return
                mic.stop()
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                log("result \"${text ?: ""}\" (rms max $maxRms, mic peak ${mic.peak})")
                if (!text.isNullOrBlank()) {
                    finish(null)
                    ui.result(text)
                } else if (gotWords || (!w.selfAudio && maxRms > 0f) || (w.selfAudio && mic.peak in 40..2500)) {
                    finish(if (hu) "Nem értettem – próbáld újra." else "Couldn't understand – try again.")
                } else giveUp(if (hu) "üres eredmény" else "empty result")
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!mine()) return
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    heard = true
                    gotWords = true
                    ui.partial(text)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val i = intent()
        lastIntent = Intent(i)
        var source: ParcelFileDescriptor? = null
        if (w.selfAudio && Build.VERSION.SDK_INT >= 33) {
            source = mic.start()
            if (source == null) {
                giveUp(if (hu) "saját felvétel nem indult" else "own recording failed")
                return
            }
            i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
            i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
            i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, MicPipe.SAMPLE_RATE)
        }
        listening = true
        startedAt = SystemClock.uptimeMillis()
        ui.status(if (hu) "Hallgatlak…" else "Listening…")
        try {
            r.startListening(i)
        } catch (e: Throwable) {
            source?.close()
            giveUp(e.javaClass.simpleName, sameAgain = true)
            return
        }
        try {
            source?.close() // the recognizer holds its own copy
        } catch (_: Exception) {
        }
        // no "ready" at all: the service never answered
        main.postDelayed({
            if (active && a == attempt && !recWarm && !heard) giveUp(if (hu) "nem válaszol" else "no answer")
        }, 5000)
        // listening, but is any sound arriving?
        main.postDelayed({
            if (!active || a != attempt || gotWords) return@postDelayed
            if (w.selfAudio) {
                when {
                    mic.peak < 40 -> {
                        log("own recording is silent too (mic peak ${mic.peak})")
                        ownSilent = true
                        giveUp(if (hu) "a saját felvétel is csend" else "own recording silent too")
                    }
                    mic.peak > 4000 -> giveUp(if (hu) "nem fogadja a hangot" else "ignores the audio")
                }
            } else if (maxRms <= 0f && recWarm) {
                giveUp(if (hu) "csak csendet kap" else "gets only silence")
            }
        }, 2800)
    }

    private fun errorText(e: Int): String = when (e) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> if (hu) "hálózati időtúllépés" else "network timeout"
        SpeechRecognizer.ERROR_NETWORK -> if (hu) "nincs hálózat" else "network error"
        SpeechRecognizer.ERROR_AUDIO -> if (hu) "hangfelvételi hiba" else "audio error"
        SpeechRecognizer.ERROR_SERVER -> if (hu) "szerverhiba" else "server error"
        SpeechRecognizer.ERROR_CLIENT -> if (hu) "kliens hiba" else "client error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if (hu) "nem hallott beszédet" else "no speech"
        SpeechRecognizer.ERROR_NO_MATCH -> if (hu) "nincs találat" else "no match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> if (hu) "foglalt" else "busy"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> if (hu) "nincs engedély" else "no permission"
        10 -> if (hu) "túl sok kérés" else "too many requests"
        11 -> if (hu) "kapcsolat megszakadt" else "disconnected"
        12 -> if (hu) "nyelv nem támogatott" else "language not supported"
        13 -> if (hu) "nyelv nem elérhető" else "language unavailable"
        else -> (if (hu) "hiba " else "error ") + e
    }
}
