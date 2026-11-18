package com.reelforge.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reelforge.editor.model.Project
import com.reelforge.editor.util.MediaUtils

/**
 * The red microphone beside Play. Tap: record a voice-over from the red line while the video
 * plays (tap again to stop). Long-press: voice-over settings.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VoiceButton(vm: EditorViewModel, onTap: () -> Unit, onLongPress: () -> Unit) {
    val red = MaterialTheme.colorScheme.primary
    val busy = vm.isRecording || vm.voiceCountdown > 0
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (busy) Color.White else red)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .semantics {
                contentDescription = when {
                    vm.isRecording -> "Stop voice-over"
                    vm.voiceCountdown > 0 -> "Cancel voice-over"
                    else -> "Record voice-over"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            vm.voiceCountdown > 0 -> Text("${vm.voiceCountdown}", color = red, fontWeight = FontWeight.Bold)
            vm.isRecording -> Box(Modifier.size(14.dp).clip(RoundedCornerShape(2.dp)).background(red))
            else -> MicGlyph(Color.White)
        }
    }
}

/** A drawn microphone. */
@Composable
private fun MicGlyph(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val s = size.minDimension
        val w = s * 0.09f
        drawRoundRect(
            color, topLeft = Offset(s * 0.35f, s * 0.05f), size = Size(s * 0.3f, s * 0.5f),
            cornerRadius = CornerRadius(s * 0.15f),
        )
        drawArc(
            color, startAngle = 0f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(s * 0.22f, s * 0.2f), size = Size(s * 0.56f, s * 0.5f), style = Stroke(w),
        )
        drawLine(color, Offset(s * 0.5f, s * 0.7f), Offset(s * 0.5f, s * 0.88f), w)
        drawLine(color, Offset(s * 0.34f, s * 0.9f), Offset(s * 0.66f, s * 0.9f), w)
    }
}

/** Shown over the video: the countdown, then "● REC 0:05" and a big Stop button. */
@Composable
fun BoxScope.VoiceOverOverlay(vm: EditorViewModel) {
    val red = MaterialTheme.colorScheme.primary
    if (vm.voiceCountdown > 0) {
        Box(
            Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${vm.voiceCountdown}", color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.Bold)
                Text("Get ready to speak", color = Color.White)
                TextButton(onClick = { vm.cancelVoiceCountdown() }) { Text("Cancel", color = Color.White) }
            }
        }
    }
    if (vm.isRecording) {
        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(red))
            Text(
                "  REC " + MediaUtils.formatTime((vm.playheadMs - vm.recordingFromMs).coerceAtLeast(0)).substringBefore('.'),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(14.dp)
                .size(58.dp)
                .clip(CircleShape)
                .background(Color.White)
                .combinedClickableStop { vm.stopRecording() }
                .semantics { contentDescription = "Stop voice-over" },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(3.dp)).background(red))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableStop(onClick: () -> Unit): Modifier = combinedClickable(onClick = onClick)

/** Long-press on the microphone: countdown, silent video while recording, and lowering under voice-overs. */
@Composable
fun VoiceSettingsDialog(vm: EditorViewModel, project: Project, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Voice-over settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Countdown before recording", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 3, 5).forEach { sec ->
                        FilterChip(
                            selected = vm.settings.voiceCountdown == sec,
                            onClick = { vm.updateSettings { it.copy(voiceCountdown = sec) } },
                            label = { Text(if (sec == 0) "Off" else "$sec s") },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Play the video silently while recording", Modifier.weight(1f))
                    Switch(
                        checked = vm.settings.silentWhileRecording,
                        onCheckedChange = { on -> vm.updateSettings { it.copy(silentWhileRecording = on) } },
                    )
                }
                DuckSlider(vm, project)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
}
