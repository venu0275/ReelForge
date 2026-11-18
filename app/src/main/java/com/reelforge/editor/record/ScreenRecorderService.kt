package com.reelforge.editor.record

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.reelforge.editor.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the screen while the user works, even in other apps. Android requires this to run as a
 * foreground service with a notification, which also carries Pause and Stop buttons.
 */
class ScreenRecorderService : Service() {

    companion object {
        const val ACTION_START = "com.reelforge.editor.record.START"
        const val ACTION_PAUSE = "com.reelforge.editor.record.PAUSE"
        const val ACTION_RESUME = "com.reelforge.editor.record.RESUME"
        const val ACTION_STOP = "com.reelforge.editor.record.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        private const val CHANNEL = "screen_recording"
        private const val NOTIFICATION_ID = 4107

        fun send(context: Context, action: String) {
            context.startService(Intent(context, ScreenRecorderService::class.java).setAction(action))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var output: File? = null
    private var startedAt = 0L
    private var pausedAt = 0L
    private var pausedTotal = 0L
    private var finishing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> finish()
            else -> if (projection == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (projection != null) return
        finishing = false
        val settings = ScreenRecording.settings
        val useMic = settings.microphone &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        // The notification must be up, with the right type, before the screen can be captured.
        createChannel()
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (useMic && Build.VERSION.SDK_INT >= 30) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            startForeground(NOTIFICATION_ID, notification(), types)
        } catch (e: Exception) {
            fail("Screen recording could not start: ${e.message}")
            return
        }

        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        val p = runCatching { if (data == null) null else manager.getMediaProjection(code, data) }.getOrNull()
        if (p == null) {
            fail("Screen recording was not allowed")
            return
        }
        // Android 14+ needs this before capturing; it also tells us if the user stops sharing.
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { finish() }
            }
        }, handler)
        projection = p

        ScreenRecording.error = null
        ScreenRecording.elapsedMs = 0
        val seconds = settings.countdownSec
        if (seconds > 0) {
            ScreenRecording.status = ScreenRecording.Status.COUNTDOWN
            countdown(seconds, useMic)
        } else {
            begin(useMic)
        }
    }

    private fun countdown(left: Int, useMic: Boolean) {
        if (finishing) return
        if (left <= 0) {
            begin(useMic)
            return
        }
        ScreenRecording.countdown = left
        handler.postDelayed({ countdown(left - 1, useMic) }, 1_000)
    }

    private fun begin(useMic: Boolean) {
        val p = projection ?: return
        val settings = ScreenRecording.settings
        val (screenW, screenH, dpi) = screenSize()
        val (w, h) = recordingSize(screenW, screenH, settings.shortSide)
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val file = File(File(filesDir, "recordings").apply { mkdirs() }, "Screen_$stamp.mp4")

        // Some encoders want sizes in steps of 16; try the exact size first, then that.
        val sizes = listOf(w to h, (w / 16 * 16).coerceAtLeast(16) to (h / 16 * 16).coerceAtLeast(16)).distinct()
        var started: Pair<Int, Int>? = null
        for ((tw, th) in sizes) {
            val r = newRecorder()
            try {
                if (useMic) r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setOutputFile(file.absolutePath)
                r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                if (useMic) {
                    r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    r.setAudioSamplingRate(44_100)
                    r.setAudioEncodingBitRate(128_000)
                }
                r.setVideoSize(tw, th)
                r.setVideoFrameRate(settings.fps)
                r.setVideoEncodingBitRate(recordingBitrate(tw, th, settings.fps))
                r.prepare()
                display = p.createVirtualDisplay(
                    "CutMitra screen recording", tw, th, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null,
                )
                r.start()
                recorder = r
                started = tw to th
                break
            } catch (e: Exception) {
                display?.release()
                display = null
                runCatching { r.release() }
                file.delete()
            }
        }
        val size = started
        if (size == null) {
            fail("This phone could not start screen recording")
            return
        }
        output = file
        ScreenRecording.finishedWidth = size.first
        ScreenRecording.finishedHeight = size.second
        startedAt = SystemClock.elapsedRealtime()
        pausedTotal = 0
        ScreenRecording.status = ScreenRecording.Status.RECORDING
        refreshNotification()
        tick()
    }

    private fun tick() {
        if (ScreenRecording.status == ScreenRecording.Status.RECORDING) {
            ScreenRecording.elapsedMs = SystemClock.elapsedRealtime() - startedAt - pausedTotal
        }
        if (recorder != null && !finishing) handler.postDelayed({ tick() }, 250)
    }

    private fun pause() {
        val r = recorder ?: return
        if (ScreenRecording.status != ScreenRecording.Status.RECORDING) return
        runCatching { r.pause() }.onSuccess {
            pausedAt = SystemClock.elapsedRealtime()
            ScreenRecording.status = ScreenRecording.Status.PAUSED
            refreshNotification()
        }
    }

    private fun resume() {
        val r = recorder ?: return
        if (ScreenRecording.status != ScreenRecording.Status.PAUSED) return
        runCatching { r.resume() }.onSuccess {
            pausedTotal += SystemClock.elapsedRealtime() - pausedAt
            ScreenRecording.status = ScreenRecording.Status.RECORDING
            refreshNotification()
        }
    }

    /** Stops everything. A recording with anything in it is handed over to the editor. */
    private fun finish() {
        if (finishing) return
        finishing = true
        handler.removeCallbacksAndMessages(null)
        var ok = false
        recorder?.let { r ->
            if (ScreenRecording.status == ScreenRecording.Status.PAUSED) runCatching { r.resume() }
            ok = runCatching { r.stop() }.isSuccess
            runCatching { r.release() }
        }
        recorder = null
        display?.release()
        display = null
        projection?.let { runCatching { it.stop() } }
        projection = null
        val file = output
        output = null
        if (ok && file != null && file.length() > 0) {
            ScreenRecording.finished = file
            ScreenRecording.status = ScreenRecording.Status.SAVING
        } else {
            file?.delete()
            ScreenRecording.status = ScreenRecording.Status.IDLE
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(message: String) {
        ScreenRecording.error = message
        finishing = false
        finish()
    }

    override fun onDestroy() {
        if (!finishing) finish()
        super.onDestroy()
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()

    /** The full screen size in pixels and its density, as measured by the editor screen. */
    private fun screenSize(): Triple<Int, Int, Int> {
        val metrics = resources.displayMetrics
        val w = ScreenRecording.screenWidth.takeIf { it > 0 } ?: metrics.widthPixels
        val h = ScreenRecording.screenHeight.takeIf { it > 0 } ?: metrics.heightPixels
        val dpi = ScreenRecording.screenDpi.takeIf { it > 0 } ?: metrics.densityDpi
        return Triple(w, h, dpi)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Screen recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun action(action: String, request: Int): PendingIntent = PendingIntent.getService(
        this, request, Intent(this, ScreenRecorderService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notification(): Notification {
        val paused = ScreenRecording.status == ScreenRecording.Status.PAUSED
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (paused) "Screen recording paused" else "Recording the screen")
            .setContentText("Tap Stop to finish and add it to your video.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        if (recorder != null) {
            builder.addAction(
                0, if (paused) "Resume" else "Pause",
                action(if (paused) ACTION_RESUME else ACTION_PAUSE, 1),
            )
        }
        builder.addAction(0, "Stop", action(ACTION_STOP, 2))
        return builder.build()
    }

    private fun refreshNotification() {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification()) }
    }
}
