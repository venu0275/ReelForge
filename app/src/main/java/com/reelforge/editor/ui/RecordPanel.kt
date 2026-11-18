package com.reelforge.editor.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.reelforge.editor.record.RecordArea
import com.reelforge.editor.record.RecordSettings
import com.reelforge.editor.record.RecordTarget
import com.reelforge.editor.record.ScreenRecorderService
import com.reelforge.editor.record.ScreenRecording
import com.reelforge.editor.util.MediaUtils

private enum class RecordButton { START, PAUSE, RESUME, STOP, SETTINGS }

/**
 * The Record screen tool: Start, Pause / Resume, Stop and Settings, with the time recorded so far.
 * [onStarted] is called once the recording has been allowed, so the tool can get out of the way.
 */
@Composable
fun RecordPanel(vm: EditorViewModel, onStarted: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { ScreenRecording.load(context) }
    val s = ScreenRecording.settings
    val status = ScreenRecording.status
    var showSettings by remember { mutableStateOf(false) }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            // Remember the screen size now, from the screen itself, and the preview's place on it.
            val metrics = DisplayMetrics()
            val activity = context as? Activity
            if (Build.VERSION.SDK_INT >= 30 && activity != null) {
                val bounds = activity.windowManager.maximumWindowMetrics.bounds
                ScreenRecording.screenWidth = bounds.width()
                ScreenRecording.screenHeight = bounds.height()
            } else if (activity != null) {
                @Suppress("DEPRECATION") activity.windowManager.defaultDisplay.getRealMetrics(metrics)
                ScreenRecording.screenWidth = metrics.widthPixels
                ScreenRecording.screenHeight = metrics.heightPixels
            }
            ScreenRecording.screenDpi = context.resources.displayMetrics.densityDpi
            ScreenRecording.crop = if (s.area == RecordArea.PREVIEW) vm.previewCrop?.takeIf { it.isValid } else null
            val intent = Intent(context, ScreenRecorderService::class.java)
                .setAction(ScreenRecorderService.ACTION_START)
                .putExtra(ScreenRecorderService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(ScreenRecorderService.EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(context, intent)
            onStarted()
        } else {
            vm.message = "Screen recording was cancelled"
        }
    }
    val askConsent: () -> Unit = {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        consent.launch(manager.createScreenCaptureIntent())
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        askConsent()
    }
    val askNotifications: () -> Unit = {
        val needs = Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED
        if (needs) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else askConsent()
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            ScreenRecording.update(context) { it.copy(microphone = false) }
            vm.message = "Recording without sound: microphone permission was not given"
        }
        askNotifications()
    }
    val start: () -> Unit = {
        val needsMic = s.microphone && ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) != PackageManager.PERMISSION_GRANTED
        if (needsMic) microphone.launch(Manifest.permission.RECORD_AUDIO) else askNotifications()
    }

    // Time and state.
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            when (status) {
                ScreenRecording.Status.IDLE -> "Ready"
                ScreenRecording.Status.COUNTDOWN -> "Starting in ${ScreenRecording.countdown}…"
                ScreenRecording.Status.RECORDING -> "Recording"
                ScreenRecording.Status.PAUSED -> "Paused"
                ScreenRecording.Status.SAVING -> "Adding to your video…"
            },
            color = if (status == ScreenRecording.Status.RECORDING) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            MediaUtils.formatTime(ScreenRecording.elapsedMs).substringBefore('.'),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
    }
    Spacer(Modifier.height(8.dp))

    // Start, Pause / Resume, Stop, Settings.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        val idle = status == ScreenRecording.Status.IDLE
        RoundAction(RecordButton.START, "Start", enabled = idle, accent = true) { start() }
        if (status == ScreenRecording.Status.PAUSED) {
            RoundAction(RecordButton.RESUME, "Resume", enabled = true) {
                ScreenRecorderService.send(context, ScreenRecorderService.ACTION_RESUME)
            }
        } else {
            RoundAction(RecordButton.PAUSE, "Pause", enabled = status == ScreenRecording.Status.RECORDING) {
                ScreenRecorderService.send(context, ScreenRecorderService.ACTION_PAUSE)
            }
        }
        RoundAction(
            RecordButton.STOP, "Stop",
            enabled = status == ScreenRecording.Status.RECORDING || status == ScreenRecording.Status.PAUSED ||
                status == ScreenRecording.Status.COUNTDOWN,
        ) { ScreenRecorderService.send(context, ScreenRecorderService.ACTION_STOP) }
        RoundAction(RecordButton.SETTINGS, "Settings", enabled = true, selected = showSettings) {
            showSettings = !showSettings
        }
    }
    ScreenRecording.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(8.dp))
    Text(
        if (s.area == RecordArea.SCREEN) {
            "Records everything on the screen, even after you leave the app. Stop it here, from the red bar, " +
                "or from the notification."
        } else {
            "Records only the video preview: the recording is cut down to the preview's area when you stop."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (showSettings) {
        val enabled = status == ScreenRecording.Status.IDLE
        Spacer(Modifier.height(8.dp))
        SettingChips("Record", RecordArea.entries.map { it to it.label }, s.area, enabled) { v ->
            ScreenRecording.update(context) { it.copy(area = v) }
        }
        SettingChips("Quality", RecordSettings.SIDES.map { it to "${it}p" }, s.shortSide, enabled) { v ->
            ScreenRecording.update(context) { it.copy(shortSide = v) }
        }
        SettingChips("Frames per second", RecordSettings.FPS.map { it to "$it" }, s.fps, enabled) { v ->
            ScreenRecording.update(context) { it.copy(fps = v) }
        }
        SettingChips(
            "Countdown", RecordSettings.COUNTDOWNS.map { it to if (it == 0) "Off" else "$it s" }, s.countdownSec, enabled,
        ) { v -> ScreenRecording.update(context) { it.copy(countdownSec = v) } }
        SettingChips("When finished", RecordTarget.entries.map { it to it.label }, s.target, enabled) { v ->
            ScreenRecording.update(context) { it.copy(target = v) }
        }
        SettingSwitch("Record sound from the microphone", s.microphone, enabled) { on ->
            ScreenRecording.update(context) { it.copy(microphone = on) }
        }
        SettingSwitch("Also save to the gallery (Movies/CutMitra)", s.saveToGallery, enabled) { on ->
            ScreenRecording.update(context) { it.copy(saveToGallery = on) }
        }
        Text(
            "Sound from other apps can't be recorded here, only the microphone. Settings can be changed " +
                "while not recording.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun <T> SettingChips(label: String, options: List<Pair<T, String>>, value: T, enabled: Boolean, onPick: (T) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (option, text) ->
            FilterChip(selected = option == value, onClick = { onPick(option) }, enabled = enabled, label = { Text(text) })
        }
    }
}

@Composable
private fun SettingSwitch(label: String, value: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange, enabled = enabled)
    }
}

/** A round button with a drawn symbol and a label under it. */
@Composable
private fun RoundAction(
    kind: RecordButton,
    label: String,
    enabled: Boolean,
    accent: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val red = MaterialTheme.colorScheme.primary
    val fill = when {
        !enabled -> Color.White.copy(alpha = 0.06f)
        accent -> red
        selected -> Color.White.copy(alpha = 0.24f)
        else -> Color.White.copy(alpha = 0.12f)
    }
    val ink = if (enabled) Color.White else Color.White.copy(alpha = 0.3f)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(fill)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (kind == RecordButton.SETTINGS) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = ink)
            } else {
                RecordSymbol(kind, ink, if (accent && enabled) Color.White else red)
            }
        }
        Text(label, fontSize = 12.sp, color = ink, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun RecordSymbol(kind: RecordButton, ink: Color, dot: Color) {
    Canvas(Modifier.size(22.dp)) {
        val s = size.minDimension
        when (kind) {
            RecordButton.START -> drawCircle(dot, radius = s * 0.42f)
            RecordButton.PAUSE -> {
                drawRect(ink, topLeft = Offset(s * 0.2f, s * 0.15f), size = Size(s * 0.2f, s * 0.7f))
                drawRect(ink, topLeft = Offset(s * 0.6f, s * 0.15f), size = Size(s * 0.2f, s * 0.7f))
            }
            RecordButton.RESUME -> drawPath(
                Path().apply { moveTo(s * 0.25f, s * 0.12f); lineTo(s * 0.85f, s * 0.5f); lineTo(s * 0.25f, s * 0.88f); close() },
                ink,
            )
            RecordButton.STOP -> drawRoundRect(
                ink, topLeft = Offset(s * 0.18f, s * 0.18f), size = Size(s * 0.64f, s * 0.64f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.08f),
            )
            RecordButton.SETTINGS -> Unit
        }
    }
}

/**
 * A small floating bar shown while recording, wherever the user is in the editor, with the time
 * and Pause / Resume and Stop.
 */
@Composable
fun RecordingBar() {
    val context = LocalContext.current
    val status = ScreenRecording.status
    if (status == ScreenRecording.Status.IDLE) return
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xE6202024))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(10.dp).clip(CircleShape)
                .background(if (status == ScreenRecording.Status.RECORDING) MaterialTheme.colorScheme.primary else Color.Gray)
        )
        Text(
            when (status) {
                ScreenRecording.Status.COUNTDOWN -> "  Starts in ${ScreenRecording.countdown}"
                ScreenRecording.Status.SAVING -> "  Adding…"
                ScreenRecording.Status.PAUSED -> "  Paused " + MediaUtils.formatTime(ScreenRecording.elapsedMs).substringBefore('.')
                else -> "  REC " + MediaUtils.formatTime(ScreenRecording.elapsedMs).substringBefore('.')
            },
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (status == ScreenRecording.Status.RECORDING || status == ScreenRecording.Status.PAUSED) {
            Spacer(Modifier.width(10.dp))
            val paused = status == ScreenRecording.Status.PAUSED
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).clickable {
                    ScreenRecorderService.send(
                        context,
                        if (paused) ScreenRecorderService.ACTION_RESUME else ScreenRecorderService.ACTION_PAUSE,
                    )
                },
                contentAlignment = Alignment.Center,
            ) { RecordSymbol(if (paused) RecordButton.RESUME else RecordButton.PAUSE, Color.White, Color.White) }
        }
        if (status != ScreenRecording.Status.SAVING) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).clickable {
                    ScreenRecorderService.send(context, ScreenRecorderService.ACTION_STOP)
                },
                contentAlignment = Alignment.Center,
            ) { RecordSymbol(RecordButton.STOP, Color.White, Color.White) }
        }
    }
}
