package com.example.glasskeyboard

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import kotlin.concurrent.thread

/**
 * Records the microphone inside the keyboard's own process and streams raw 16 kHz mono PCM into
 * a pipe, whose read end is handed to the speech recognizer (RecognizerIntent.EXTRA_AUDIO_SOURCE,
 * Android 13+).
 *
 * Why: the visible keyboard may use the microphone, but a recognizer service running in another
 * app's process (Google) is in the background and gets only silence on some phones — it listened
 * ("Hallgatlak…") and heard nothing. Fed with our audio it has something to recognise.
 */
class MicPipe {
    @Volatile private var running = false
    @Volatile var peak = 0
        private set
    @Volatile var level = 0f
        private set
    private var worker: Thread? = null

    /** Starts recording; returns the pipe's read end (caller passes it on and closes its copy), or null. */
    @SuppressLint("MissingPermission")
    fun start(): ParcelFileDescriptor? {
        stop()
        val rate = SAMPLE_RATE
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        } catch (_: Exception) {
            return null
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (_: Exception) {
            rec.release()
            return null
        }
        try {
            rec.startRecording()
        } catch (_: Exception) {
            rec.release()
            pipe[0].close()
            pipe[1].close()
            return null
        }
        running = true
        peak = 0
        level = 0f
        worker = thread(name = "glass-mic") {
            val out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
            val buf = ShortArray(rate / 50) // 20 ms
            val bytes = ByteArray(buf.size * 2)
            try {
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    var pk = 0
                    for (i in 0 until n) {
                        val s = buf[i].toInt()
                        val a = if (s < 0) -s else s
                        if (a > pk) pk = a
                        sum += (s * s).toDouble()
                        bytes[2 * i] = (s and 0xFF).toByte()
                        bytes[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
                    }
                    if (pk > peak) peak = pk
                    level = Math.sqrt(sum / n).toFloat()
                    out.write(bytes, 0, n * 2)
                }
            } catch (_: Exception) {
                // the recognizer closed its end: done
            } finally {
                try {
                    rec.stop()
                } catch (_: Exception) {
                }
                rec.release()
                try {
                    out.close()
                } catch (_: Exception) {
                }
                running = false
            }
        }
        return pipe[0]
    }

    val isRunning get() = running

    fun stop() {
        running = false
        worker = null
    }

    companion object {
        const val SAMPLE_RATE = 16000
    }
}
