package com.reelforge.editor.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.reelforge.editor.R

/** True while one of the app's screens is on display (set by MainActivity). */
object AppVisibility {
    @Volatile var visible: Boolean = false
}

/**
 * Keeps long jobs (export, auto captions, reverse, extract sound, sticker downloads) running when
 * the user leaves the app or turns the screen off: a foreground service with a notification that
 * shows the progress and a Stop button. The jobs themselves run in the editor as before; this
 * only tells Android not to stop the app while they run.
 */
class WorkService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            onStopRequested?.invoke()
            return START_NOT_STICKY
        }
        val note = build(this, current.title, current.progress)
        val type = when {
            Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        runCatching { ServiceCompat.startForeground(this, NOTE_ID, note, type) }
            .onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    /** Android 15+: a media-processing job may run up to 6 hours a day; stop cleanly if reached. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        onStopRequested?.invoke()
        stopSelf()
    }

    data class Job(val title: String, val progress: Int?)

    companion object {
        private const val CHANNEL = "work"
        private const val DONE_CHANNEL = "work_done"
        private const val NOTE_ID = 4101
        private const val DONE_ID = 4102
        private const val ACTION_STOP = "com.reelforge.editor.work.STOP"

        @Volatile private var current = Job("Working…", null)
        @Volatile private var running = false

        /** Called when the user taps Stop in the notification. */
        @Volatile var onStopRequested: (() -> Unit)? = null

        private fun channels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Working in the background", NotificationManager.IMPORTANCE_LOW)
                        .apply { description = "Export, captions and other long jobs while you use other apps" }
                )
            }
            if (nm.getNotificationChannel(DONE_CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(DONE_CHANNEL, "Finished jobs", NotificationManager.IMPORTANCE_DEFAULT)
                        .apply { description = "Tells you when your video is saved" }
                )
            }
        }

        private fun openApp(context: Context): PendingIntent? =
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
            }

        private fun build(context: Context, title: String, progress: Int?): Notification {
            channels(context)
            val stop = PendingIntent.getService(
                context, 1, Intent(context, WorkService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(
                    if (progress != null) "$progress% · keeps going while you use other apps"
                    else "Keeps going while you use other apps"
                )
                .setProgress(100, progress ?: 0, progress == null)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(openApp(context))
                .addAction(0, "Stop", stop)
                .build()
        }

        /** A long job started (call while the app is on screen) or moved on. */
        fun show(context: Context, title: String, progress: Int?) {
            val app = context.applicationContext
            current = Job(title, progress)
            if (!running) {
                running = true
                runCatching {
                    ContextCompat.startForegroundService(app, Intent(app, WorkService::class.java))
                }.onFailure { running = false }
            } else {
                // Updates go straight to the notification (no new service start from the background).
                runCatching {
                    app.getSystemService(NotificationManager::class.java)?.notify(NOTE_ID, build(app, title, progress))
                }
            }
        }

        /** No long job is running any more. */
        fun hide(context: Context) {
            if (!running) return
            running = false
            val app = context.applicationContext
            runCatching { app.stopService(Intent(app, WorkService::class.java)) }
        }

        /**
         * Says a job finished, only when the user is away from the app (on screen, the app shows
         * it). Tapping opens [open] (e.g. the saved video) or the app.
         */
        fun finished(context: Context, title: String, text: String, open: Uri? = null, video: Boolean = false) {
            if (AppVisibility.visible) return
            val app = context.applicationContext
            channels(app)
            val tap = if (open != null) {
                PendingIntent.getActivity(
                    app, 2,
                    Intent(Intent.ACTION_VIEW).setDataAndType(open, if (video) "video/mp4" else null)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            } else {
                openApp(app)
            }
            val note = NotificationCompat.Builder(app, DONE_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(tap)
                .build()
            runCatching { app.getSystemService(NotificationManager::class.java)?.notify(DONE_ID, note) }
        }
    }
}
