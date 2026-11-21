package com.reelforge.editor.ui

import androidx.compose.material.icons.rounded.*
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.alpha
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
internal fun PanelTitle(text: String) {
    // The tool's name is in its header; inside, titles mark sections.
    ToolSection(text, MaterialTheme.colorScheme.primary)
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
    HintRow(spacing = 8.dp) {
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
    SpeedRampSection(vm, clip)
}

@Composable
fun VolumePanel(vm: EditorViewModel, clip: Clip) {
    if (!clip.hasAudio) {
        Text("This clip has no audio track.")
        return
    }
    CommitSlider(
        label = "Level",
        value = clip.volume,
        range = 0f..2f,
        format = { "${(it * 100).roundToInt()}%" },
    ) { v -> vm.updateClip(clip.id) { it.copy(volume = v, muted = v <= 0f) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Mute", Modifier.weight(1f))
        Switch(
            checked = clip.muted,
            onCheckedChange = { m -> vm.updateClip(clip.id) { it.copy(muted = m, volume = if (!m && it.volume <= 0f) 1f else it.volume) } },
        )
    }
    TextButton(onClick = { vm.setAllClipVolume(if (clip.muted) 0f else clip.volume) }) {
        Text("Use this level for all clips")
    }
    Text(
        "Slide to 0% to silence this scene, so only a voice-over is heard. Above 100% makes quiet " +
            "videos louder (up to double); very loud sound can crackle.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    DuckSlider(vm, project = vm.project)
    Spacer(Modifier.height(8.dp))
    Text("Voice changer", style = MaterialTheme.typography.labelLarge)
    HintRow(spacing = 8.dp) {
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
    HintRow(spacing = 8.dp) {
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
    CommitSlider("Brightness", clip.brightness, -0.5f..0.5f, { "%+.2f".format(it) }) { v ->
        vm.updateClip(clip.id) { it.copy(brightness = v) }
    }
    CommitSlider("Contrast", clip.contrast, -0.8f..0.8f, { "%+.2f".format(it) }) { v ->
        vm.updateClip(clip.id) { it.copy(contrast = v) }
    }
    CommitSlider("Saturation", clip.saturation, -100f..100f, { "%+.0f".format(it) }) { v ->
        vm.updateClip(clip.id) { it.copy(saturation = v) }
    }
    CommitSlider("Size", clip.zoom, 0.3f..4f, { "%.1fx".format(it) }) { v ->
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
    HintRow(spacing = 10.dp) {
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
fun ColorPicker(selected: Int, onPick: (Int) -> Unit) {
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
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    /** When set, the switch cannot be used now; tapping it says why in a toast. */
    whyOff: String? = null,
    note: String? = null,
    onChange: (Boolean) -> Unit,
) {
    val hint = LocalHint.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = if (whyOff != null) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified)
            if (note != null) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(
            checked = checked && whyOff == null,
            onCheckedChange = { on -> if (whyOff != null) hint(whyOff) else onChange(on) },
            modifier = Modifier.alpha(if (whyOff != null) 0.5f else 1f),
        )
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
    retype: (String) -> Unit,
) {
    val editing = project.texts.firstOrNull { it.id == editingId }

    if (editing == null) {
        // The Add button stays at the top, however many texts there are.
        ActionCard(
            "Add text", "Type a title or words; it starts at the red line.",
            androidx.compose.material.icons.Icons.Rounded.Add, ToolColors.Create, main = true, onClick = newText,
        )
        if (project.texts.isNotEmpty()) {
            PanelTitle("Your texts (${project.texts.size})")
        }
        // Listed in the order they appear in the video.
        project.texts.sortedBy { it.startMs }.forEach { t ->
            ItemCard(
                t.text.replace("\n", " "),
                "${MediaUtils.formatTime(t.startMs)} – ${MediaUtils.formatTime(t.endMs)}",
                androidx.compose.material.icons.Icons.Rounded.TextFields, ToolColors.Create,
                onEdit = { onEditing(t.id) },
                onDelete = { vm.deleteText(t.id) },
            )
        }
        if (project.texts.size > 1) {
            QuietAction("Delete all texts", androidx.compose.material.icons.Icons.Rounded.DeleteSweep) { vm.deleteAllTexts() }
        }
        PanelTitle("Stickers")
        HintRow(spacing = 4.dp) {
            Stickers.forEach { emoji ->
                TextButton(onClick = { onEditing(vm.addText(emoji, 0.18f)) }) {
                    Text(emoji, style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
        TipBox("Tap any text or sticker on the video to edit it, or drag it to move it.")
        return
    }

    val id = editing.id
    var tab by remember { mutableStateOf(TextTab.LOOK) }
    // Which effect is open on the Effects tab: 0 outline, 1 shadow, 2 glow, 3 background, 4 opacity.
    var effect by remember { mutableStateOf(0) }

    // The words stay at the top whichever tab is open.
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = editing.text,
            onValueChange = { v -> vm.updateText(id) { it.copy(text = v) } },
            modifier = Modifier.weight(1f),
            maxLines = 2,
        )
        // Opens the full typing screen.
        TextButton(onClick = { retype(id) }) { Text("\u270E") }
        TextButton(onClick = { onEditing(null) }) { Text("Done") }
    }
    // Every tab is in view at once (they wrap onto a second line), so all the options can be seen.
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextTab.entries.forEach { t ->
            FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) })
        }
    }
    Text(tab.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            TextLookExtras(vm, editing)
        }

        TextTab.FONT -> {
            Text("Font", style = MaterialTheme.typography.labelLarge)
            FontTiles(editing) { f -> vm.updateText(id) { it.copy(font = f, fontPath = null) } }
            FilterChip(
                selected = editing.fontPath != null,
                onClick = { pickFont(id) },
                label = { Text(if (editing.fontPath == null) "Import font file" else "Imported font") },
            )
            FontStore(vm, editing.fontPath, id)
        }

        TextTab.EFFECTS -> {
            // One effect at a time, so each has room for its own controls.
            HintRow(spacing = 8.dp) {
                listOf("Outline", "Shadow", "Glow", "Background", "Opacity").forEachIndexed { i, name ->
                    FilterChip(selected = effect == i, onClick = { effect = i }, label = { Text(name) })
                }
            }
            when (effect) {
                0 -> {
                    SwitchRow("Outline", editing.outline) { b -> vm.updateText(id) { it.copy(outline = b) } }
                    if (editing.outline) {
                        Text("Outline colour", style = MaterialTheme.typography.labelLarge)
                        ColorPicker(editing.outlineColor) { c -> vm.updateText(id) { it.copy(outlineColor = c) } }
                    }
                    TextEffectExtras(vm, editing, 0)
                }
                1 -> TextEffectExtras(vm, editing, 1)
                2 -> TextEffectExtras(vm, editing, 2)
                3 -> {
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
                else -> {
                    if (editing.keyframes.isEmpty()) {
                        LabeledSlider(
                            "Opacity (alpha): ${(editing.opacity * 100).toInt()}%", editing.opacity, 0.05f..1f,
                        ) { v -> vm.updateText(id) { it.copy(opacity = v) } }
                        Text(
                            "100% is solid. Lower values let the video show through the text.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(
                            "This text is animated with keyframes, so its opacity is set in Place and time.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        TextTab.STYLES -> {
            TextStyleExtras(vm, editing)
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
            TextMotionExtras(vm, editing)
        }

        TextTab.PLACE -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onEditing(vm.duplicateText(id)) }) { Text("Copy") }
                TextButton(onClick = {
                    onEditing(null)
                    vm.deleteText(id)
                }) { Text("Delete") }
                // Adds a spoken version of these words as a sound at the red line.
                TextButton(onClick = { vm.speakText(editing.text) }, enabled = !vm.isSpeaking) {
                    Text(if (vm.isSpeaking) "Speaking…" else "Read aloud")
                }
            }
            CutRow(vm.canCut(editing.startMs, editing.endMs)) { cut -> vm.cutText(id, cut) }
            Text("Turn", style = MaterialTheme.typography.labelLarge)
            HintRow(spacing = 8.dp) {
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
            // Quick ways to make the text last longer.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = false,
                    onClick = {
                        vm.updateText(id) {
                            it.copy(endMs = maxOf(it.startMs + ProjectOps.MIN_BAR_MS, ProjectOps.clipEndAt(project, it.startMs)))
                        }
                    },
                    label = { Text("Stretch to clip end") },
                )
                FilterChip(
                    selected = false,
                    onClick = {
                        vm.updateText(id) { it.copy(endMs = maxOf(it.startMs + ProjectOps.MIN_BAR_MS, project.durationMs)) }
                    },
                    label = { Text("Stretch to video end") },
                )
            }
            TimeRange(editing.startMs, editing.endMs, project.durationMs) { s, e ->
                vm.updateText(id) { it.copy(startMs = s, endMs = e) }
            }
        }
    }
}

private enum class TextTab(val label: String, val hint: String) {
    LOOK("Look", "Colour, size, bold, italic, underline, alignment and spacing"),
    FONT("Font", "Built-in fonts, more fonts to download, or your own font file"),
    EFFECTS("Effects", "Outline, shadow, glow, background box and opacity"),
    STYLES("Styles", "Ready-made looks, your saved styles, apply to all texts"),
    MOTION("Motion", "Animate in and out, keep moving, letter-by-letter reveal"),
    PLACE("Place and time", "Position, turn, when it shows, stretch, cut, copy, read aloud"),
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
    val music = project.music
    if (music == null) {
        ActionCard(
            "Choose music", "Free songs, or any audio file on your phone.",
            androidx.compose.material.icons.Icons.Rounded.MusicNote, ToolColors.Sound, main = true, onClick = pickAudio,
        )
        return
    }
    PanelTitle("Playing")
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
        range = 0f..2f,
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
    BeatSyncSection(vm, project)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = pickAudio) { Text("Replace") }
        TextButton(onClick = { vm.removeMusic() }) { Text("Remove") }
    }
}

@Composable
private fun AnimChips(label: String, value: OverlayAnim, onChange: (OverlayAnim) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    HintRow(spacing = 8.dp) {
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
        val I = androidx.compose.material.icons.Icons.Rounded
        Text(
            "Put a video, photo, logo or watermark on top of your video, at the red line.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        ActionRow {
            SmallAction("Video", I.VideoLibrary, ToolColors.Create, onClick = pickVideo)
            SmallAction("Photo / logo", I.AddPhotoAlternate, ToolColors.Create, onClick = pickImage)
            SmallAction("Draw", I.Brush, ToolColors.Create, onClick = draw)
        }
        if (project.layers.isNotEmpty() || project.videoLayers.isNotEmpty()) {
            PanelTitle("On your video (${project.layers.size + project.videoLayers.size})")
        }
        project.videoLayers.forEachIndexed { i, layer ->
            ItemCard(
                "Video ${i + 1}" + if (layer.chromaKey) " · green screen" else "",
                "${MediaUtils.formatTime(layer.startMs)} – " + MediaUtils.formatTime(layer.startMs + layer.durationMs),
                I.VideoLibrary, ToolColors.Create,
                onEdit = { onEditing(layer.id) },
                onDelete = { vm.deleteVideoLayer(layer.id) },
            )
        }
        project.layers.forEachIndexed { i, layer ->
            ItemCard(
                "Layer ${i + 1}",
                "${MediaUtils.formatTime(layer.startMs)} – ${MediaUtils.formatTime(layer.endMs)}",
                I.Layers, ToolColors.Create,
                onEdit = { onEditing(layer.id) },
                onDelete = { vm.deleteLayer(layer.id) },
            )
        }
        if (project.layers.size > 1) {
            QuietAction("Delete all layers", I.DeleteSweep) { vm.deleteAllLayers() }
        }
        TipBox("Tap any layer on the video to edit it; drag or pinch it to move and resize.")
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
    RemoveBackgroundRow(vm, video = false) { vm.removeImageBackground(id) }
    LabeledSlider("Size", editing.scale, 0.05f..1f) { v -> vm.updateLayer(id) { it.copy(scale = v) } }
    PoseControls(vm, ItemKind.LAYER, id)
    TimeRange(editing.startMs, editing.endMs, project.durationMs) { s, e ->
        vm.updateLayer(id) { it.copy(startMs = s, endMs = e) }
    }
    AnimChips("Animate in", editing.animIn) { a -> vm.updateLayer(id) { it.copy(animIn = a) } }
    AnimChips("Animate out", editing.animOut) { a -> vm.updateLayer(id) { it.copy(animOut = a) } }
    Text("Keep moving (like a GIF)", style = MaterialTheme.typography.labelLarge)
    HintRow(spacing = 8.dp) {
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
fun AudioPanel(
    vm: EditorViewModel,
    project: Project,
    record: () -> Unit,
    pickSound: () -> Unit,
    pickVideoSound: () -> Unit = {},
) {
    if (vm.isRecording) {
        Text("Recording… the video is playing so you can speak along with it.")
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.stopRecording() }) { Text("Stop recording") }
        return
    }
    val I = androidx.compose.material.icons.Icons.Rounded
    ActionCard(
        "Record voice-over", "Speak while the video plays, from the red line.",
        I.Mic, ToolColors.Sound, main = true, onClick = record,
    )
    val soundWork = vm.soundWork
    if (soundWork != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(soundWork, Modifier.weight(1f))
            TextButton(onClick = { vm.cancelSoundWork() }) { Text("Cancel") }
        }
    } else {
        ActionRow {
            SmallAction("Audio file", I.AudioFile, ToolColors.Sound, onClick = pickSound)
            SmallAction("Sound from a video", I.LibraryMusic, ToolColors.Sound, onClick = pickVideoSound)
        }
        TipBox(
            "Sound from a video takes the sound of any video on your phone. For a clip already on the " +
                "timeline, select it and tap Extract sound."
        )
    }
    PanelTitle("Recording")
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
    SwitchRow("Clean voice (remove background noise)", vm.settings.cleanVoice) { on ->
        vm.updateSettings { it.copy(cleanVoice = on) }
    }
    Text(
        if (vm.settings.cleanVoice) "New voice-overs are recorded with the phone's noise and echo removal, " +
            "then evened out so soft and loud words sound alike."
        else "New voice-overs are recorded exactly as the microphone hears them.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    FxChips(vm.settings.recordFx, voice = true, title = "Voice style for new recordings", hint = false) { fx ->
        vm.updateSettings { it.copy(recordFx = fx) }
    }
    CommitSlider(
        label = "Pitch for new recordings",
        value = vm.settings.recordSemitones.toFloat(),
        range = -12f..12f,
        format = { semitoneText(it.roundToInt()) },
    ) { v -> vm.updateSettings { it.copy(recordSemitones = v.roundToInt()) } }
    Text(
        "Each new voice-over gets this style and pitch. You can still change it on the clip below.",
        style = MaterialTheme.typography.bodySmall,
    )
    SwitchRow("Play the video silently while recording", vm.settings.silentWhileRecording) { on ->
        vm.updateSettings { it.copy(silentWhileRecording = on) }
    }
    Text(
        "Without headphones, the microphone also picks up the video's sound. Use headphones, or turn " +
            "this on to record only your voice.",
        style = MaterialTheme.typography.bodySmall,
    )

    // The video's own sound, for replacing it with a voice-over or making it louder.
    Spacer(Modifier.height(8.dp))
    Text("Video's own sound (all clips)", style = MaterialTheme.typography.labelLarge)
    val clips = project.clips.filter { it.type == ClipType.VIDEO && it.hasAudio }
    if (clips.isEmpty()) {
        Text("Your clips have no sound.", style = MaterialTheme.typography.bodySmall)
    } else {
        val levels = clips.map { if (it.muted) 0f else it.volume }
        val same = levels.distinct().size == 1
        CommitSlider(
            label = "Level",
            value = levels.average().toFloat(),
            range = 0f..2f,
            format = { "${(it * 100).roundToInt()}%" },
        ) { v -> vm.setAllClipVolume(v) }
        Text(
            if (!same) "Scenes have different levels; moving the line sets them all."
            else "Slide to 0% to remove the video's sound, so only your voice-over is heard.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Spacer(Modifier.height(8.dp))
    DuckSlider(vm, project)
    Spacer(Modifier.height(8.dp))
    var speech by remember { mutableStateOf("") }
    OutlinedTextField(
        value = speech,
        onValueChange = { speech = it },
        label = { Text("Type words to be spoken") },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
    )
    val hint = LocalHint.current
    Button(
        onClick = {
            when {
                vm.isSpeaking -> hint("Still creating the last voice, one moment")
                speech.isBlank() -> hint("Type the words to be spoken first")
                else -> vm.speakText(speech)
            }
        },
        modifier = Modifier.alpha(if (speech.isNotBlank() && !vm.isSpeaking) 1f else 0.5f),
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
            TextButton(onClick = {
                if (vm.soundWork == null) vm.saveSoundToPhone(clip.id) else hint("Wait for the current sound job to finish")
            }) {
                Text("Save to phone")
            }
            TextButton(onClick = { vm.deleteAudioClip(clip.id) }) { Text("Delete") }
        }
        CutRow(vm.canCut(clip.startMs, clip.startMs + clip.durationMs)) { cut -> vm.cutAudio(clip.id, cut) }
        SwitchRow("Clean voice", clip.clean) { on -> vm.updateAudioClip(clip.id) { it.copy(clean = on) } }
        if (!clip.voice) {
            SwitchRow("Lower under voice-overs", clip.duck) { on -> vm.updateAudioClip(clip.id) { it.copy(duck = on) } }
        }
        FxChips(clip.audioFx, voice = true) { fx -> vm.updateAudioClip(clip.id) { it.copy(audioFx = fx) } }
        VoiceTuning(vm, clip)
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
        HintRow(spacing = 8.dp) {
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

    RemoveBackgroundRow(vm, video = true) { vm.removeVideoBackground(id) }
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

/**
 * "Remove background": cuts the person out of a picture or video layer on the phone. Shows the
 * progress, with Stop, while it works.
 */
@Composable
private fun RemoveBackgroundRow(vm: EditorViewModel, video: Boolean, onRemove: () -> Unit) {
    val work = vm.cutoutWork
    if (work != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(work, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { vm.cancelCutout() }) { Text("Stop") }
        }
        androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
        return
    }
    OutlinedButton(onClick = onRemove) { Text("✂ Remove background") }
    Text(
        if (video) "Keeps the person and removes everything behind them (up to 1 minute of video; it can " +
            "take about as long as the video). First time only: downloads a ${com.reelforge.editor.engine.CutOut.MODEL_MB} MB model."
        else "Keeps the person and makes the rest see-through. First time only: downloads a " +
            "${com.reelforge.editor.engine.CutOut.MODEL_MB} MB model.",
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Voice styles in groups, so the long list is easy to scan. */
private val FX_GROUPS = listOf(
    "Pitch" to listOf(AudioFx.NONE, AudioFx.CHIPMUNK, AudioFx.KID, AudioFx.DEEP, AudioFx.MONSTER),
    "Character" to listOf(
        AudioFx.ROBOT, AudioFx.ALIEN, AudioFx.GHOST, AudioFx.MEGAPHONE, AudioFx.RADIO,
        AudioFx.PHONE, AudioFx.UNDERWATER,
    ),
    "Room" to listOf(AudioFx.ECHO, AudioFx.REVERB, AudioFx.CAVE),
    "Tone" to listOf(AudioFx.BASS, AudioFx.TREBLE),
)

/** Sound effect choices. They are added when the video is exported. */
@Composable
private fun FxChips(
    current: AudioFx,
    voice: Boolean = false,
    title: String = if (voice) "Voice style" else "Sound effect",
    hint: Boolean = true,
    onChange: (AudioFx) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    FX_GROUPS.forEach { (group, styles) ->
        HintRow(spacing = 8.dp, verticalAlignment = Alignment.CenterVertically) {
            Text(group, Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall)
            styles.forEach { fx ->
                FilterChip(selected = current == fx, onClick = { onChange(fx) }, label = { Text(fx.label) })
            }
        }
    }
    if (hint && current != AudioFx.NONE) {
        Text(
            "Play the video to hear it; the exported video sounds the same.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** "+3" / "-5" / "0" for a pitch shift in semitones. */
private fun semitoneText(semitones: Int) = when {
    semitones > 0 -> "+$semitones"
    else -> "$semitones"
}

/** Fine pitch (in semitones, on top of the style) and how strong the style is, for one voice clip. */
@Composable
private fun VoiceTuning(vm: EditorViewModel, clip: com.reelforge.editor.model.AudioClip) {
    val semis = com.reelforge.editor.model.semitonesOfPitch(clip.pitch).roundToInt()
    CommitSlider(
        label = "Pitch",
        value = semis.toFloat(),
        range = -12f..12f,
        format = { semitoneText(it.roundToInt()) },
    ) { v ->
        val p = com.reelforge.editor.model.pitchOfSemitones(v.roundToInt().toFloat())
        vm.updateAudioClip(clip.id) { it.copy(pitch = p) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Lower or raise the voice without changing its speed.",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
        )
        if (semis != 0) {
            TextButton(onClick = { vm.updateAudioClip(clip.id) { it.copy(pitch = 1f) } }) { Text("Reset") }
        }
    }
    if (clip.audioFx.adjustable) {
        CommitSlider(
            label = "Effect strength",
            value = clip.fxAmount,
            range = 0f..1f,
            format = { "${(it * 100).roundToInt()}%" },
        ) { v -> vm.updateAudioClip(clip.id) { it.copy(fxAmount = v) } }
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
        HintRow(spacing = 8.dp) {
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

/**
 * How loud the video's own sound (and music) plays while a voice-over speaks, as a line from 0%
 * (silent) to 100% (not lowered). It applies to the whole video.
 */
@Composable
fun DuckSlider(vm: EditorViewModel, project: Project?) {
    if (project == null) return
    Text("While a voice-over plays, the video's sound goes to", style = MaterialTheme.typography.labelLarge)
    CommitSlider(
        label = "Under voice-over",
        value = project.duckLevel,
        range = 0f..1f,
        format = { if (it >= 0.995f) "100% (not lowered)" else "${(it * 100).roundToInt()}%" },
    ) { v -> vm.setDuckLevel(v) }
    Text(
        "For the whole video. It fades down only while your voice speaks, then comes back.",
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * Auto captions: listens to the video's sound and voice-overs and writes what is said as timed
 * text. The speech model is downloaded once per language; the sound itself never leaves the phone.
 */
@Composable
fun CaptionsPanel(vm: EditorViewModel, project: Project) {
    var language by remember { mutableStateOf(com.reelforge.editor.engine.CaptionLanguage.EN_IN) }
    val hasVoice = project.audio.any { it.voice }
    val hasVideoSound = project.clips.any { it.type == ClipType.VIDEO && it.hasAudio && !it.muted }
    var fromVideo by remember { mutableStateOf(true) }
    var fromVoice by remember { mutableStateOf(true) }
    // Re-checked after each run, so a finished download shows as installed.
    val ready = remember(language, vm.captionWork) { vm.captionModelReady(language) }

    val work = vm.captionWork
    if (work != null) {
        PanelTitle("Working")
        Text(work)
        androidx.compose.material3.LinearProgressIndicator(
            progress = { vm.captionProgress },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        TextButton(onClick = { vm.stopCaptions() }) { Text("Stop") }
        Text(
            "You can keep editing while this runs.",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    PanelTitle("1. Spoken language")
    HintRow(spacing = 8.dp) {
        com.reelforge.editor.engine.CaptionLanguage.entries.forEach { lang ->
            FilterChip(selected = language == lang, onClick = { language = lang }, label = { Text(lang.label) })
        }
    }
    Text(
        if (ready) "${language.label} is ready on this phone."
        else "First time only: downloads the ${language.label} speech model (about ${language.sizeMb} MB).",
        style = MaterialTheme.typography.bodySmall,
    )

    PanelTitle("2. Listen to")
    SwitchRow(
        "The video's own sound", fromVideo,
        whyOff = if (hasVideoSound) null
        else "Your clips have no sound to listen to (they are photos, silent or muted)",
        note = if (hasVideoSound) null else "No clip with sound yet",
    ) { fromVideo = it }
    SwitchRow(
        "Voice-overs", fromVoice,
        whyOff = if (hasVoice) null
        else "No voice-over yet. Record one first: Voice & sounds, then Record voice-over",
        note = if (hasVoice) null else "No voice-over recorded yet",
    ) { fromVoice = it }

    val useVideo = fromVideo && hasVideoSound
    val useVoice = fromVoice && hasVoice
    PanelTitle("3. Make them")
    ActionCard(
        if (vm.autoCaptionCount > 0) "Make captions again" else "Make captions",
        if (ready) "Runs on your phone; takes about as long as the video." else "Downloads the speech model first.",
        androidx.compose.material.icons.Icons.Rounded.ClosedCaption, ToolColors.Create,
        enabled = useVideo || useVoice, main = true,
        whyOff = "Turn on at least one sound to listen to (step 2)",
    ) { vm.makeCaptions(language, useVideo, useVoice) }
    TipBox(
        "Captions appear as texts on the timeline: tap one to fix a word, or use the Text tool to " +
            "change the style. Clear speech without music works best."
    )
    if (vm.autoCaptionCount > 0) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${vm.autoCaptionCount} captions", Modifier.weight(1f))
            TextButton(onClick = { vm.removeAutoCaptions() }) { Text("Remove all captions") }
        }
    }
    if (ready) {
        TextButton(onClick = { vm.deleteCaptionModel(language) }) {
            Text("Delete ${language.label} model (frees ${language.sizeMb} MB)")
        }
    }
}
