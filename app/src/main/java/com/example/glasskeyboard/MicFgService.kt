package com.example.glasskeyboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * A foreground service of type "microphone" that runs only while dictating.
 *
 * The phone lets a recognizer record only for an app that may use the microphone right now; the
 * keyboard counts as a background app, so the recognizer got only silence. While this service is
 * in the foreground (a small notification), the app may use the microphone.
 */
class MicFgService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = "dictation"
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(ch) == null) {
                nm.createNotificationChannel(NotificationChannel(ch, if (Ui.hu) "Diktálás" else "Dictation", NotificationManager.IMPORTANCE_MIN))
            }
            val n = Notification.Builder(this, ch)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(if (Ui.hu) "Diktálás…" else "Dictating…")
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 30) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(7, n)
            running = true
            DiagLog.add(this, "mic", "foreground service running")
        } catch (e: Throwable) {
            running = false
            DiagLog.add(this, "mic", "foreground service not allowed: ${e.javaClass.simpleName} ${e.message ?: ""}".take(200))
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val STOP = "stop"
        @Volatile var running = false
            private set

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, MicFgService::class.java))
            } catch (e: Throwable) {
                DiagLog.add(ctx, "mic", "foreground service start failed: ${e.javaClass.simpleName}")
            }
        }

        fun stop(ctx: Context) {
            if (!running) return
            try {
                ctx.startService(Intent(ctx, MicFgService::class.java).setAction(STOP))
            } catch (_: Throwable) {
                try {
                    ctx.stopService(Intent(ctx, MicFgService::class.java))
                } catch (_: Throwable) {
                }
            }
        }
    }
}
