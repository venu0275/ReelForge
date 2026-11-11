package com.reelforge.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reelforge.editor.model.Clip
import com.reelforge.editor.model.ClipType
import com.reelforge.editor.model.FilterPreset
import com.reelforge.editor.model.Project
import com.reelforge.editor.util.MediaUtils
import kotlin.math.roundToInt

@Composable
private fun PanelTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
}

/** A slider that keeps its own value while dragging and reports it once on release. */
@Composable
private fun CommitSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    val safeRange = range.start..maxOf(range.endInclusive, range.start + 0.001f)
    Column {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text(format(local), style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = local.coerceIn(safeRange.start, safeRange.endInclusive),
            onValueChange = { local = it },
            valueRange = safeRange,
            onValueChangeFinished = { onCommit(local) },
        )
    }
}

@Composable
fun TrimPanel(vm: EditorViewModel, clip: Clip) {
    if (clip.type == ClipType.IMAGE) {
        PanelTitle("Photo duration")
        CommitSlider(
            label = "Show for",
            value = (clip.trimEndMs - clip.trimStartMs) / 1000f,
            range = 0.5f..15f,
            format = { "%.1f s".format(it) },
        ) { seconds ->
            val ms = (seconds * 1000).toLong()
            vm.updateClip(clip.id) {
                it.copy(trimStartMs = 0, trimEndMs = ms, sourceDurationMs = ms).clampFades()
            }
        }
        return
    }

    PanelTitle("Trim")
    var range by remember(clip.id, clip.trimStartMs, clip.trimEndMs) {
        mutableStateOf(clip.trimStartMs.toFloat()..clip.trimEndMs.toFloat())
    }
    val minGap = 300f
    Row {
        Text(MediaUtils.formatTime(range.start.toLong()), Modifier.weight(1f))
        Text(MediaUtils.formatTime(range.endInclusive.toLong()))
    }
    RangeSlider(
        value = range,
        onValueChange = { r ->
            if (r.endInclusive - r.start >= minGap) range = r
        },
        valueRange = 0f..maxOf(clip.sourceDurationMs.toFloat(), minGap),
        onValueChangeFinished = {
            vm.updateClip(clip.id) {
                it.copy(
                    trimStartMs = range.start.toLong(),
                    trimEndMs = range.endInclusive.toLong(),
                ).clampFades()
            }
        },
    )
    Text(
        "Selected: ${MediaUtils.formatTime((range.endInclusive - range.start).toLong())} of " +
            MediaUtils.formatTime(clip.sourceDurationMs),
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun Clip.clampFades(): Clip {
    val half = durationMs / 2
    return copy(fadeInMs = fadeInMs.coerceIn(0, half), fadeOutMs = fadeOutMs.coerceIn(0, half))
}

@Composable
fun SpeedPanel(vm: EditorViewModel, clip: Clip) {
    PanelTitle("Speed")
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 4f).forEach { s ->
            FilterChip(
                selected = clip.speed == s,
                onClick = { vm.updateClip(clip.id) { it.copy(speed = s).clampFades() } },
                label = { Text("${s}x") },
            )
        }
    }
    Text(
        "Clip length: ${MediaUtils.formatTime(clip.durationMs)}",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
fun VolumePanel(vm: EditorViewModel, clip: Clip) {
    PanelTitle("Volume")
    if (!clip.hasAudio) {
        Text("This clip has no audio track.")
        return
    }
    CommitSlider(
        label = "Level",
        value = clip.volume,
        range = 0f..2f,
        format = { "${(it * 100).roundToInt()}%" },
    ) { v -> vm.updateClip(clip.id) { it.copy(volume = v) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Mute", Modifier.weight(1f))
        Switch(
            checked = clip.muted,
            onCheckedChange = { m -> vm.updateClip(clip.id) { it.copy(muted = m) } },
        )
    }
    Text(
        "Levels above 100% apply on export; the preview plays at up to 100%.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
fun FilterPanel(vm: EditorViewModel, clip: Clip) {
    PanelTitle("Filter")
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterPreset.entries.forEach { f ->
            FilterChip(
                selected = clip.filter == f,
                onClick = { vm.updateClip(clip.id) { it.copy(filter = f) } },
                label = { Text(f.label) },
            )
        }
    }
    if (clip.type == ClipType.IMAGE) {
        Text(
            "Filters on photos are applied on export (not shown in the preview yet).",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun AdjustPanel(vm: EditorViewModel, clip: Clip) {
    PanelTitle("Adjust")
    CommitSlider("Brightness", clip.brightness, -0.5f..0.5f, { "%+.2f".format(it) }) { v ->
        vm.updateClip(clip.id) { it.copy(brightness = v) }
    }
    CommitSlider("Contrast", clip.contrast, -0.8f..0.8f, { "%+.2f".format(it) }) { v ->
        vm.updateClip(clip.id) { it.copy(contrast = v) }
    }
    CommitSlider("Saturation", clip.saturation, -100f..100f, { "%+.0f".format(it) }) { v ->
        vm.updateClip(clip.id) { it.copy(saturation = v) }
    }
    TextButton(onClick = {
        vm.updateClip(clip.id) { it.copy(brightness = 0f, contrast = 0f, saturation = 0f) }
    }) { Text("Reset") }
}

@Composable
fun FadePanel(vm: EditorViewModel, clip: Clip) {
    PanelTitle("Fade from / to black")
    val max = minOf(3000L, clip.durationMs / 2).toFloat()
    CommitSlider("Fade in", clip.fadeInMs.toFloat(), 0f..max, { "%.1f s".format(it / 1000f) }) { v ->
        vm.updateClip(clip.id) { it.copy(fadeInMs = v.toLong()) }
    }
    CommitSlider("Fade out", clip.fadeOutMs.toFloat(), 0f..max, { "%.1f s".format(it / 1000f) }) { v ->
        vm.updateClip(clip.id) { it.copy(fadeOutMs = v.toLong()) }
    }
}

private val TextColors = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFFF4D5E, 0xFFFFC107, 0xFF4CAF50, 0xFF4DD0E1, 0xFF7C4DFF, 0xFFFF80AB,
).map { it.toInt() }

@Composable
fun TextPanel(vm: EditorViewModel, project: Project) {
    var editingId by remember { mutableStateOf<String?>(null) }
    val editing = project.texts.firstOrNull { it.id == editingId }

    if (editing == null) {
        PanelTitle("Text")
        project.texts.forEach { t ->
            Row(
                Modifier.fillMaxWidth().clickable { editingId = t.id }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    t.text.replace("\n", " "),
                    Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${MediaUtils.formatTime(t.startMs)} – ${MediaUtils.formatTime(t.endMs)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Button(onClick = { editingId = vm.addText() }) { Text("Add text at playhead") }
        return
    }

    val id = editing.id
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Edit text", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = {
            editingId = null
            vm.deleteText(id)
        }) { Text("Delete") }
        TextButton(onClick = { editingId = null }) { Text("Done") }
    }
    OutlinedTextField(
        value = editing.text,
        onValueChange = { v -> vm.updateText(id) { it.copy(text = v) } },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TextColors.forEach { c ->
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(
                        width = if (editing.color == c) 3.dp else 1.dp,
                        color = if (editing.color == c) MaterialTheme.colorScheme.primary else Color.Gray,
                        shape = CircleShape,
                    )
                    .clickable { vm.updateText(id) { it.copy(color = c) } }
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    LabeledSlider("Size", editing.size, 0.03f..0.25f) { v -> vm.updateText(id) { it.copy(size = v) } }
    LabeledSlider("Left ↔ Right", editing.x, -1f..1f) { v -> vm.updateText(id) { it.copy(x = v) } }
    LabeledSlider("Bottom ↔ Top", editing.y, -1f..1f) { v -> vm.updateText(id) { it.copy(y = v) } }

    val total = maxOf(project.durationMs, 500L).toFloat()
    Text(
        "Visible ${MediaUtils.formatTime(editing.startMs)} – ${MediaUtils.formatTime(editing.endMs)}",
        style = MaterialTheme.typography.labelLarge,
    )
    RangeSlider(
        value = editing.startMs.toFloat().coerceIn(0f, total)..editing.endMs.toFloat().coerceIn(0f, total),
        onValueChange = { r ->
            if (r.endInclusive - r.start >= 200f) {
                vm.updateText(id) {
                    it.copy(startMs = r.start.toLong(), endMs = r.endInclusive.toLong())
                }
            }
        },
        valueRange = 0f..total,
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
}

@Composable
fun MusicPanel(vm: EditorViewModel, project: Project, pickAudio: () -> Unit) {
    PanelTitle("Background music")
    val music = project.music
    if (music == null) {
        Text("Add a song or any audio file from your phone.")
        Spacer(Modifier.height(8.dp))
        Button(onClick = pickAudio) { Text("Choose audio") }
        return
    }
    Text(music.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(MediaUtils.formatTime(music.durationMs), style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    CommitSlider(
        label = "Volume",
        value = music.volume,
        range = 0f..1f,
        format = { "${(it * 100).roundToInt()}%" },
    ) { v -> vm.updateMusic { it.copy(volume = v) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Loop to fill the video", Modifier.weight(1f))
        Switch(checked = music.loop, onCheckedChange = { l -> vm.updateMusic { it.copy(loop = l) } })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = pickAudio) { Text("Replace") }
        TextButton(onClick = { vm.removeMusic() }) { Text("Remove") }
    }
}
