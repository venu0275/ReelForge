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
import com.reelforge.editor.model.AudioFx
import com.reelforge.editor.model.Background
import com.reelforge.editor.model.Clip
import com.reelforge.editor.model.ClipType
import com.reelforge.editor.model.FilterPreset
import com.reelforge.editor.model.OverlayAnim
import com.reelforge.editor.model.Project
import com.reelforge.editor.model.ProjectOps
import com.reelforge.editor.model.TextAlignH
import com.reelforge.editor.model.TextFont
import com.reelforge.editor.model.TransitionStyle
import com.reelforge.editor.model.VideoLayer
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
    // Slip: keep the clip's length and place, but show an earlier or later part of the video.
    val slack = clip.sourceDurationMs - (clip.trimEndMs - clip.trimStartMs)
    if (slack > 0) {
        Spacer(Modifier.height(8.dp))
        CommitSlider(
            label = "Slip (which part of the video plays)",
            value = clip.trimStartMs.toFloat(),
            range = 0f..slack.toFloat(),
            format = { "from " + MediaUtils.formatTime(it.toLong()) },
        ) { v -> vm.slipClip(clip.id, v.toLong()) }
    }
}

private fun Clip.clampFades(): Clip = ProjectOps.clampFades(this)

@Composable
fun SpeedPanel(vm: EditorViewModel, clip: Clip) {
    PanelTitle("Speed")
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(0.1f, 0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 4f, 8f, 16f).forEach { s ->
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
    if (clip.speed > 8f) {
        Text(
            "The preview plays at up to 8x; the exported video uses the full speed.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
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
    Spacer(Modifier.height(8.dp))
    Text("Voice changer", style = MaterialTheme.typography.labelLarge)
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(1f to "Normal", 0.7f to "Deep", 0.85f to "Low", 1.25f to "High", 1.6f to "Chipmunk")
            .forEach { (pitch, label) ->
                FilterChip(
                    selected = clip.pitch == pitch,
                    onClick = { vm.updateClip(clip.id) { it.copy(pitch = pitch) } },
                    label = { Text(label) },
                )
            }
    }
    Spacer(Modifier.height(8.dp))
    FxChips(clip.audioFx) { fx -> vm.updateClip(clip.id) { it.copy(audioFx = fx) } }
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
    CommitSlider("Zoom", clip.zoom, 1f..3f, { "%.1fx".format(it) }) { v ->
        vm.updateClip(clip.id) { it.copy(zoom = v) }
    }
    CommitSlider("Opacity", clip.opacity, 0f..1f, { "${(it * 100).roundToInt()}%" }) { v ->
        vm.updateClip(clip.id) { it.copy(opacity = v) }
    }
    TextButton(onClick = {
        vm.updateClip(clip.id) {
            it.copy(brightness = 0f, contrast = 0f, saturation = 0f, zoom = 1f, opacity = 1f)
        }
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
    Text("Fade style", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TransitionStyle.entries.forEach { style ->
            FilterChip(
                selected = clip.transition == style,
                onClick = { vm.updateClip(clip.id) { it.copy(transition = style) } },
                label = { Text(style.label) },
            )
        }
    }
    Text(
        "Slide and Zoom are rendered on export; the preview shows them as a fade.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text("Transition to the next clip", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0L to "None", 300L to "0.3 s", 500L to "0.5 s", 1000L to "1 s").forEach { (ms, label) ->
            FilterChip(
                selected = false,
                onClick = { vm.setTransitionAfter(clip.id, ms) },
                label = { Text(label) },
            )
        }
    }
}

private val Stickers = listOf(
    "😀", "😂", "😍", "😎", "🥳", "😢", "😡", "👍", "👏", "🙏", "❤️", "🔥", "⭐", "🎉", "🎵", "✅",
)

private val Palette = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFF9E9E9E, 0xFF607D8B, 0xFFF44336, 0xFFE91E63, 0xFFFF80AB,
    0xFF9C27B0, 0xFF673AB7, 0xFF3F51B5, 0xFF2196F3, 0xFF03A9F4, 0xFF00BCD4, 0xFF009688,
    0xFF4CAF50, 0xFF8BC34A, 0xFFCDDC39, 0xFFFFEB3B, 0xFFFFC107, 0xFFFF9800, 0xFFFF5722,
    0xFF795548,
).map { it.toInt() }

@Composable
private fun Swatches(selected: Int, onPick: (Int) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Palette.forEach { c ->
            val isSelected = (selected and 0xFFFFFF) == (c and 0xFFFFFF)
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
                        shape = CircleShape,
                    )
                    .clickable { onPick(c) }
            )
        }
    }
}

/** Palette dots plus a hue slider for any other colour. */
@Composable
private fun ColorPicker(selected: Int, onPick: (Int) -> Unit) {
    Swatches(selected, onPick)
    var hue by remember { mutableFloatStateOf(0f) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Custom", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = hue,
            onValueChange = {
                hue = it
                onPick(android.graphics.Color.HSVToColor(floatArrayOf(it, 1f, 1f)))
            },
            valueRange = 0f..360f,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Box(Modifier.size(24.dp).clip(CircleShape).background(Color(selected)))
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun TextPanel(
    vm: EditorViewModel,
    project: Project,
    editingId: String?,
    onEditing: (String?) -> Unit,
    pickFont: (String) -> Unit,
    newText: () -> Unit,
) {
    val editing = project.texts.firstOrNull { it.id == editingId }

    if (editing == null) {
        PanelTitle("Text and stickers")
        project.texts.forEach { t ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.text.replace("\n", " "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${MediaUtils.formatTime(t.startMs)} – ${MediaUtils.formatTime(t.endMs)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { onEditing(t.id) }) { Text("Edit") }
                TextButton(onClick = { vm.deleteText(t.id) }) { Text("Delete") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = newText) { Text("Add text at playhead") }
            if (project.texts.isNotEmpty()) {
                TextButton(onClick = { vm.deleteAllTexts() }) { Text("Delete all") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Stickers", style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Stickers.forEach { emoji ->
                TextButton(onClick = { onEditing(vm.addText(emoji, 0.18f)) }) {
                    Text(emoji, style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
        Text(
            "Tip: tap any text or sticker on the video to edit it.",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    val id = editing.id
    var tab by remember { mutableStateOf(TextTab.LOOK) }

    // The words stay at the top whichever tab is open.
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = editing.text,
            onValueChange = { v -> vm.updateText(id) { it.copy(text = v) } },
            modifier = Modifier.weight(1f),
            maxLines = 2,
        )
        TextButton(onClick = { onEditing(null) }) { Text("Done") }
    }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextTab.entries.forEach { t ->
            FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) })
        }
    }
    Spacer(Modifier.height(8.dp))

    when (tab) {
        TextTab.LOOK -> {
            Text("Colour", style = MaterialTheme.typography.labelLarge)
            ColorPicker(editing.color) { c -> vm.updateText(id) { it.copy(color = c) } }
            LabeledSlider("Size", editing.size, 0.02f..0.4f) { v -> vm.updateText(id) { it.copy(size = v) } }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = editing.bold,
                    onClick = { vm.updateText(id) { it.copy(bold = !it.bold) } },
                    label = { Text("Bold") },
                )
                FilterChip(
                    selected = editing.italic,
                    onClick = { vm.updateText(id) { it.copy(italic = !it.italic) } },
                    label = { Text("Italic") },
                )
            }
            Text("Line up", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextAlignH.entries.forEach { a ->
                    FilterChip(
                        selected = editing.align == a,
                        onClick = { vm.updateText(id) { it.copy(align = a) } },
                        label = { Text(a.label) },
                    )
                }
            }
            if (editing.keyframes.isEmpty()) {
                LabeledSlider("See-through (left) to solid (right)", editing.opacity, 0.1f..1f) { v ->
                    vm.updateText(id) { it.copy(opacity = v) }
                }
            }
        }

        TextTab.FONT -> {
            Text("Font", style = MaterialTheme.typography.labelLarge)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextFont.entries.forEach { f ->
                    FilterChip(
                        selected = editing.fontPath == null && editing.font == f,
                        onClick = { vm.updateText(id) { it.copy(font = f, fontPath = null) } },
                        label = { Text(f.label) },
                    )
                }
            }
            FilterChip(
                selected = editing.fontPath != null,
                onClick = { pickFont(id) },
                label = { Text(if (editing.fontPath == null) "Import font file" else "Imported font") },
            )
            FontStore(vm, editing.fontPath, id)
        }

        TextTab.EFFECTS -> {
            SwitchRow("Outline", editing.outline) { b -> vm.updateText(id) { it.copy(outline = b) } }
            if (editing.outline) {
                Text("Outline colour", style = MaterialTheme.typography.labelLarge)
                ColorPicker(editing.outlineColor) { c -> vm.updateText(id) { it.copy(outlineColor = c) } }
            }
            SwitchRow("Background box", editing.background) { b ->
                vm.updateText(id) { it.copy(background = b) }
            }
            Text("Background colour", style = MaterialTheme.typography.labelLarge)
            // Picking a colour turns the box on and keeps its current see-through level.
            ColorPicker(editing.boxColor or (0xFF shl 24)) { c ->
                vm.updateText(id) {
                    it.copy(background = true, boxColor = (c and 0xFFFFFF) or (it.boxColor and (0xFF shl 24)))
                }
            }
            LabeledSlider("Background opacity", ((editing.boxColor ushr 24) and 0xFF) / 255f, 0.1f..1f) { v ->
                vm.updateText(id) {
                    it.copy(
                        background = true,
                        boxColor = (it.boxColor and 0xFFFFFF) or ((v * 255).toInt().coerceIn(0, 255) shl 24),
                    )
                }
            }
        }

        TextTab.STYLES -> {
            Text(
                "Ready-made looks. Tap one to apply it; your words, place and timing stay as they are.",
                style = MaterialTheme.typography.bodySmall,
            )
            com.reelforge.editor.model.TextStyle.entries.forEach { style ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(style.label)
                        Text(style.hint, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { vm.updateText(id) { style.applyTo(it) } }) { Text("Use") }
                }
            }
        }

        TextTab.MOTION -> {
            AnimChips("In animation (how it appears)", editing.animIn) { a ->
                vm.updateText(id) { it.copy(animIn = a) }
            }
            Spacer(Modifier.height(8.dp))
            AnimChips("Out animation (how it leaves)", editing.animOut) { a ->
                vm.updateText(id) { it.copy(animOut = a) }
            }
            Spacer(Modifier.height(8.dp))
            MotionControls(editing.moving, editing.x2, editing.y2,
                onToggle = { on ->
                    vm.updateText(id) {
                        if (on) it.copy(moving = true, x2 = it.x, y2 = it.y) else it.copy(moving = false)
                    }
                },
                onX = { v -> vm.updateText(id) { it.copy(x2 = v) } },
                onY = { v -> vm.updateText(id) { it.copy(y2 = v) } },
            )
        }

        TextTab.PLACE -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onEditing(vm.duplicateText(id)) }) { Text("Copy") }
                TextButton(onClick = {
                    onEditing(null)
                    vm.deleteText(id)
                }) { Text("Delete") }
            }
            CutRow(vm.canCut(editing.startMs, editing.endMs)) { cut -> vm.cutText(id, cut) }
            Text("Turn", style = MaterialTheme.typography.labelLarge)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = false,
                    onClick = { vm.editPose(ItemKind.TEXT, id) { it.copy(rotation = turned(it.rotation, 90f)) } },
                    label = { Text("Turn left 90°") },
                )
                FilterChip(
                    selected = false,
                    onClick = { vm.editPose(ItemKind.TEXT, id) { it.copy(rotation = turned(it.rotation, -90f)) } },
                    label = { Text("Turn right 90°") },
                )
                FilterChip(
                    selected = false,
                    onClick = { vm.editPose(ItemKind.TEXT, id) { it.copy(rotation = 0f) } },
                    label = { Text("Straight") },
                )
            }
            PoseControls(vm, ItemKind.TEXT, id)
            TimeRange(editing.startMs, editing.endMs, project.durationMs) { s, e ->
                vm.updateText(id) { it.copy(startMs = s, endMs = e) }
            }
        }
    }
}

private enum class TextTab(val label: String) {
    LOOK("Look"),
    FONT("Font"),
    EFFECTS("Effects"),
    STYLES("Styles"),
    MOTION("Motion"),
    PLACE("Place and time"),
}

/** Adds [by] degrees and keeps the angle within -180..180 (positive is counter-clockwise). */
private fun turned(angle: Float, by: Float): Float {
    var next = (angle + by) % 360f
    if (next > 180f) next -= 360f
    if (next < -180f) next += 360f
    return next
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
    val musicEnd = music.windowEnd(project.durationMs)
    Text(
        "Song length ${MediaUtils.formatTime(music.durationMs)} · plays " +
            "${MediaUtils.formatTime(music.startMs)} – ${MediaUtils.formatTime(musicEnd)}",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    // Trim left = start the music here; Trim right = stop it here.
    CutRow(vm.canCut(music.startMs, musicEnd), showSplit = false) { cut -> vm.cutMusic(cut) }
    if (music.startMs > 0 || music.stopMs > 0 || music.offsetMs > 0) {
        TextButton(onClick = { vm.resetMusicRange() }) { Text("Play over the whole video again") }
    }
    CommitSlider(
        label = "Volume",
        value = music.volume,
        range = 0f..1f,
        format = { "${(it * 100).roundToInt()}%" },
    ) { v -> vm.updateMusic { it.copy(volume = v) } }
    CommitSlider("Fade in", music.fadeInMs.toFloat(), 0f..5000f, { "%.1f s".format(it / 1000f) }) { v ->
        vm.updateMusic { it.copy(fadeInMs = v.toLong()) }
    }
    CommitSlider("Fade out", music.fadeOutMs.toFloat(), 0f..5000f, { "%.1f s".format(it / 1000f) }) { v ->
        vm.updateMusic { it.copy(fadeOutMs = v.toLong()) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Loop to fill the video", Modifier.weight(1f))
        Switch(checked = music.loop, onCheckedChange = { l -> vm.updateMusic { it.copy(loop = l) } })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = pickAudio) { Text("Replace") }
        TextButton(onClick = { vm.removeMusic() }) { Text("Remove") }
    }
}

@Composable
private fun AnimChips(label: String, value: OverlayAnim, onChange: (OverlayAnim) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OverlayAnim.entries.forEach { a ->
            FilterChip(selected = value == a, onClick = { onChange(a) }, label = { Text(a.label) })
        }
    }
}

@Composable
private fun TimeRange(startMs: Long, endMs: Long, totalMs: Long, onChange: (Long, Long) -> Unit) {
    val total = maxOf(totalMs, 500L).toFloat()
    Text(
        "Visible ${MediaUtils.formatTime(startMs)} – ${MediaUtils.formatTime(endMs)}",
        style = MaterialTheme.typography.labelLarge,
    )
    RangeSlider(
        value = startMs.toFloat().coerceIn(0f, total)..endMs.toFloat().coerceIn(0f, total),
        onValueChange = { r ->
            if (r.endInclusive - r.start >= 200f) onChange(r.start.toLong(), r.endInclusive.toLong())
        },
        valueRange = 0f..total,
    )
}

/** Picture-in-picture image layers: photos, logos, watermarks and drawings on top of the video. */
@Composable
fun LayerPanel(
    vm: EditorViewModel,
    project: Project,
    editingId: String?,
    onEditing: (String?) -> Unit,
    pickImage: () -> Unit,
    pickVideo: () -> Unit,
    draw: () -> Unit,
) {
    val editingVideo = project.videoLayers.firstOrNull { it.id == editingId }
    if (editingVideo != null) {
        VideoLayerEditor(vm, project, editingVideo) { onEditing(null) }
        return
    }
    val editing = project.layers.firstOrNull { it.id == editingId }

    if (editing == null) {
        PanelTitle("Layers")
        if (project.layers.isEmpty() && project.videoLayers.isEmpty()) {
            Text("Put a video, photo, logo or watermark on top of your video.")
        }
        project.videoLayers.forEachIndexed { i, layer ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Video ${i + 1}" + if (layer.chromaKey) " · green screen" else "")
                    Text(
                        "${MediaUtils.formatTime(layer.startMs)} – " +
                            MediaUtils.formatTime(layer.startMs + layer.durationMs),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { onEditing(layer.id) }) { Text("Edit") }
                TextButton(onClick = { vm.deleteVideoLayer(layer.id) }) { Text("Delete") }
            }
        }
        project.layers.forEachIndexed { i, layer ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Layer ${i + 1}")
                    Text(
                        "${MediaUtils.formatTime(layer.startMs)} – ${MediaUtils.formatTime(layer.endMs)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { onEditing(layer.id) }) { Text("Edit") }
                TextButton(onClick = { vm.deleteLayer(layer.id) }) { Text("Delete") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = pickVideo) { Text("Add video") }
            Button(onClick = pickImage) { Text("Add image") }
            TextButton(onClick = draw) { Text("Draw") }
            if (project.layers.isNotEmpty()) {
                TextButton(onClick = { vm.deleteAllLayers() }) { Text("Delete all") }
            }
        }
        Text(
            "Tip: tap any layer on the video to edit it.",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    val id = editing.id
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Edit layer", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = {
            onEditing(null)
            vm.deleteLayer(id)
        }) { Text("Delete") }
        TextButton(onClick = { onEditing(null) }) { Text("Done") }
    }
    CutRow(vm.canCut(editing.startMs, editing.endMs)) { cut -> vm.cutLayer(id, cut) }
    LabeledSlider("Size", editing.scale, 0.05f..1f) { v -> vm.updateLayer(id) { it.copy(scale = v) } }
    PoseControls(vm, ItemKind.LAYER, id)
    TimeRange(editing.startMs, editing.endMs, project.durationMs) { s, e ->
        vm.updateLayer(id) { it.copy(startMs = s, endMs = e) }
    }
    AnimChips("Animate in", editing.animIn) { a -> vm.updateLayer(id) { it.copy(animIn = a) } }
    AnimChips("Animate out", editing.animOut) { a -> vm.updateLayer(id) { it.copy(animOut = a) } }
    Text("Keep moving (like a GIF)", style = MaterialTheme.typography.labelLarge)
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        com.reelforge.editor.model.LoopFx.entries.forEach { fx ->
            FilterChip(
                selected = editing.loop == fx,
                onClick = { vm.updateLayer(id) { it.copy(loop = fx) } },
                label = { Text(fx.label) },
            )
        }
    }
    MotionControls(editing.moving, editing.x2, editing.y2,
        onToggle = { on ->
            vm.updateLayer(id) { if (on) it.copy(moving = true, x2 = it.x, y2 = it.y) else it.copy(moving = false) }
        },
        onX = { v -> vm.updateLayer(id) { it.copy(x2 = v) } },
        onY = { v -> vm.updateLayer(id) { it.copy(y2 = v) } },
    )
}

/** Voice-over recordings and sound clips placed on the timeline. */
@Composable
fun AudioPanel(vm: EditorViewModel, project: Project, record: () -> Unit, pickSound: () -> Unit) {
    PanelTitle("Voice-over and sounds")
    if (vm.isRecording) {
        Text("Recording… the video is playing so you can speak along with it.")
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.stopRecording() }) { Text("Stop recording") }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = record) { Text("Record at playhead") }
        TextButton(onClick = pickSound) { Text("Add audio file") }
    }
    Text(
        "Use headphones while recording so the microphone does not pick up the video sound.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    var speech by remember { mutableStateOf("") }
    OutlinedTextField(
        value = speech,
        onValueChange = { speech = it },
        label = { Text("Type words to be spoken") },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
    )
    Button(
        onClick = { vm.speakText(speech) },
        enabled = speech.isNotBlank() && !vm.isSpeaking,
    ) { Text(if (vm.isSpeaking) "Creating voice…" else "Add as spoken voice") }
    project.audio.forEach { clip ->
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(clip.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Starts ${MediaUtils.formatTime(clip.startMs)} · ${MediaUtils.formatTime(clip.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = { vm.deleteAudioClip(clip.id) }) { Text("Delete") }
        }
        CutRow(vm.canCut(clip.startMs, clip.startMs + clip.durationMs)) { cut -> vm.cutAudio(clip.id, cut) }
        FxChips(clip.audioFx) { fx -> vm.updateAudioClip(clip.id) { it.copy(audioFx = fx) } }
        CommitSlider(
            label = "Starts at",
            value = clip.startMs.toFloat(),
            range = 0f..maxOf(project.durationMs - 100, 1L).toFloat(),
            format = { MediaUtils.formatTime(it.toLong()) },
        ) { v -> vm.updateAudioClip(clip.id) { it.copy(startMs = v.toLong()) } }
        CommitSlider(
            label = "Volume",
            value = clip.volume,
            range = 0f..2f,
            format = { "${(it * 100).roundToInt()}%" },
        ) { v -> vm.updateAudioClip(clip.id) { it.copy(volume = v) } }
        CommitSlider("Fade in", clip.fadeInMs.toFloat(), 0f..3000f, { "%.1f s".format(it / 1000f) }) { v ->
            vm.updateAudioClip(clip.id) { it.copy(fadeInMs = v.toLong()) }
        }
        CommitSlider("Fade out", clip.fadeOutMs.toFloat(), 0f..3000f, { "%.1f s".format(it / 1000f) }) { v ->
            vm.updateAudioClip(clip.id) { it.copy(fadeOutMs = v.toLong()) }
        }
    }
    if (project.audio.isNotEmpty()) {
        TextButton(onClick = { vm.deleteAllAudio() }) { Text("Delete all sounds") }
    }
}

/** Simple two-point motion: the item glides from its position to an end position. */
@Composable
private fun MotionControls(
    moving: Boolean,
    x2: Float,
    y2: Float,
    onToggle: (Boolean) -> Unit,
    onX: (Float) -> Unit,
    onY: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Move while visible", Modifier.weight(1f))
        Switch(checked = moving, onCheckedChange = onToggle)
    }
    if (moving) {
        LabeledSlider("End position: Left ↔ Right", x2, -1f..1f, onX)
        LabeledSlider("End position: Bottom ↔ Top", y2, -1f..1f, onY)
    }
}

/** What fills the bars around clips that do not fill the frame. */
@Composable
fun BackgroundPanel(vm: EditorViewModel, project: Project) {
    PanelTitle("Backdrop")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Background.entries.forEach { b ->
            FilterChip(
                selected = project.background == b,
                onClick = { vm.setBackground(b) },
                label = { Text(b.label) },
            )
        }
    }
    if (project.background == Background.COLOR) {
        Spacer(Modifier.height(8.dp))
        ColorPicker(project.backgroundColor) { c -> vm.setBackground(Background.COLOR, c) }
    }
    Text(
        "Applies to clips set to Fit. Clips set to Fill cover the whole frame.",
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * More fonts, downloaded the first time each one is picked and kept on the phone afterwards.
 * Tapping a font applies it to the text being edited.
 */
@Composable
private fun FontStore(vm: EditorViewModel, currentPath: String?, textId: String) {
    if (vm.storeFonts.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    Text("More fonts (free download)", style = MaterialTheme.typography.labelLarge)
    vm.storeFonts.groupBy { it.group }.forEach { (group, fonts) ->
        Text(group, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            fonts.forEach { font ->
                val installed = font.id in vm.installedFonts
                val progress = vm.fontProgress[font.id]
                FilterChip(
                    selected = installed && currentPath == vm.fontPath(font),
                    onClick = { vm.useStoreFont(font, textId) },
                    label = {
                        Text(
                            when {
                                progress != null -> "${font.name} $progress%"
                                installed -> font.name
                                else -> "↓ ${font.name}"
                            }
                        )
                    },
                )
            }
        }
    }
    if (vm.installedFonts.isNotEmpty()) {
        Text(
            "${vm.installedFonts.size} downloaded. Fonts marked ↓ download when tapped.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Settings for one picture-in-picture video layer, including green-screen removal. */
@Composable
private fun VideoLayerEditor(vm: EditorViewModel, project: Project, layer: VideoLayer, onDone: () -> Unit) {
    val id = layer.id
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Edit video layer", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = {
            onDone()
            vm.deleteVideoLayer(id)
        }) { Text("Delete") }
        TextButton(onClick = onDone) { Text("Done") }
    }
    CutRow(vm.canCut(layer.startMs, layer.startMs + layer.durationMs)) { cut -> vm.cutVideoLayer(id, cut) }
    LabeledSlider("Size", layer.scale, 0.1f..1f) { v -> vm.updateVideoLayer(id) { it.copy(scale = v) } }
    PoseControls(vm, ItemKind.VIDEO, id)

    CommitSlider(
        label = "Starts at",
        value = layer.startMs.toFloat(),
        range = 0f..maxOf(project.durationMs - 100, 1L).toFloat(),
        format = { MediaUtils.formatTime(it.toLong()) },
    ) { v -> vm.updateVideoLayer(id) { it.copy(startMs = v.toLong()) } }

    var range by remember(id, layer.trimStartMs, layer.trimEndMs) {
        mutableStateOf(layer.trimStartMs.toFloat()..layer.trimEndMs.toFloat())
    }
    Text(
        "Use ${MediaUtils.formatTime(range.start.toLong())} – ${MediaUtils.formatTime(range.endInclusive.toLong())}" +
            " of this video",
        style = MaterialTheme.typography.labelLarge,
    )
    RangeSlider(
        value = range,
        onValueChange = { r -> if (r.endInclusive - r.start >= 300f) range = r },
        valueRange = 0f..maxOf(layer.sourceDurationMs.toFloat(), 300f),
        onValueChangeFinished = {
            vm.updateVideoLayer(id) {
                it.copy(trimStartMs = range.start.toLong(), trimEndMs = range.endInclusive.toLong())
            }
        },
    )

    if (layer.hasAudio) {
        CommitSlider("Volume", layer.volume, 0f..2f, { "${(it * 100).roundToInt()}%" }) { v ->
            vm.updateVideoLayer(id) { it.copy(volume = v) }
        }
        SwitchRow("Mute", layer.muted) { m -> vm.updateVideoLayer(id) { it.copy(muted = m) } }
    }

    SwitchRow("Remove green screen (chroma key)", layer.chromaKey) { on ->
        vm.updateVideoLayer(id) { it.copy(chromaKey = on) }
    }
    if (layer.chromaKey) {
        Text("Colour to remove", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0xFF00FF00.toInt() to "Green", 0xFF0000FF.toInt() to "Blue").forEach { (color, label) ->
                FilterChip(
                    selected = layer.keyColor == color,
                    onClick = { vm.updateVideoLayer(id) { it.copy(keyColor = color) } },
                    label = { Text(label) },
                )
            }
        }
        LabeledSlider("Strength", layer.keyTolerance, 0.05f..0.5f) { v ->
            vm.updateVideoLayer(id) { it.copy(keyTolerance = v) }
        }
        Text(
            "Raise the strength if some of the screen colour is left; lower it if the subject starts to disappear.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Sound effect choices. They are added when the video is exported. */
@Composable
private fun FxChips(current: AudioFx, onChange: (AudioFx) -> Unit) {
    Text("Sound effect", style = MaterialTheme.typography.labelLarge)
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AudioFx.entries.forEach { fx ->
            FilterChip(selected = current == fx, onClick = { onChange(fx) }, label = { Text(fx.label) })
        }
    }
    if (current != AudioFx.NONE) {
        Text(
            "You will hear this effect in the exported video; the preview plays the original sound.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Place, angle and opacity of a text, image layer or video layer at the red line, with optional
 * keyframe animation. With animation on, changing a value saves a keyframe at the red line.
 */
@Composable
private fun PoseControls(vm: EditorViewModel, kind: ItemKind, id: String) {
    val pose = vm.poseOf(kind, id) ?: return
    val keys = vm.keyframesOf(kind, id)
    val animated = keys.isNotEmpty()

    SwitchRow("Animate with keyframes", animated) { on -> vm.setKeyframing(kind, id, on) }
    if (animated) {
        Text(
            "Move the red line to another moment, then change the place, zoom, angle or opacity. " +
                "A keyframe is saved there and the item glides between keyframes.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            keys.forEach { key ->
                FilterChip(
                    selected = kotlin.math.abs(key.tMs - pose.tMs) <= ProjectOps.KEYFRAME_MERGE_MS,
                    onClick = { vm.deleteKeyframe(kind, id, key.tMs) },
                    label = { Text(MediaUtils.formatTime(key.tMs.coerceAtLeast(0)) + "  ✕") },
                )
            }
        }
        LabeledSlider("Zoom: %.1fx".format(pose.scale), pose.scale, 0.2f..3f) { v ->
            vm.editPose(kind, id) { it.copy(scale = v) }
        }
    }
    LabeledSlider("Left ↔ Right", pose.x, -1f..1f) { v -> vm.editPose(kind, id) { it.copy(x = v) } }
    LabeledSlider("Bottom ↔ Top", pose.y, -1f..1f) { v -> vm.editPose(kind, id) { it.copy(y = v) } }
    LabeledSlider("Angle: ${pose.rotation.roundToInt()}°", pose.rotation, -180f..180f) { v ->
        vm.editPose(kind, id) { it.copy(rotation = v) }
    }
    LabeledSlider("Opacity", pose.opacity, 0f..1f) { v -> vm.editPose(kind, id) { it.copy(opacity = v) } }
}
