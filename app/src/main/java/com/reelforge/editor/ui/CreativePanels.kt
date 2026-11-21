package com.reelforge.editor.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.reelforge.editor.engine.CoverStyle
import com.reelforge.editor.model.Clip
import com.reelforge.editor.model.CreativeOps
import com.reelforge.editor.model.PhotoMotion
import com.reelforge.editor.model.Project
import com.reelforge.editor.model.SpeedRamp
import com.reelforge.editor.model.Templates
import kotlinx.coroutines.delay

/** Chips in a sideways-scrolling row. */
@Composable
private fun <T> ChipChoices(items: List<T>, selected: T?, label: (T) -> String, onPick: (T) -> Unit) {
    HintRow(spacing = 8.dp) {
        items.forEach { item ->
            FilterChip(selected = item == selected, onClick = { onPick(item) }, label = { Text(label(item)) })
        }
    }
}

/** A line saying what is happening, a moving bar and Stop. */
@Composable
private fun Working(text: String, onStop: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f))
        TextButton(onClick = onStop) { Text("Stop") }
    }
    LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
}

// ------------------------------------------------------------------------------ photo zoom

/** Slow pan / zoom for a photo clip (Ken Burns). */
@Composable
fun PhotoZoomPanel(vm: EditorViewModel, clip: Clip) {
    Text("Slowly moves over the photo while it shows, so a slideshow feels alive.")
    Spacer(Modifier.height(8.dp))
    ChipChoices(PhotoMotion.entries, clip.motion, { it.label }) { vm.setPhotoMotion(clip.id, it) }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = { vm.setPhotoMotionForAll(clip.motion) }) {
        Text(if (clip.motion == PhotoMotion.NONE) "Turn off for all photos" else "Use ${clip.motion.label} for all photos")
    }
    Text(
        "Play the video to see it. Longer photos move more slowly.",
        style = MaterialTheme.typography.bodySmall,
    )
}

// ------------------------------------------------------------------------------ speed ramp

/** Speed that changes inside a clip, shown under the plain speed choices. */
@Composable
fun SpeedRampSection(vm: EditorViewModel, clip: Clip) {
    Spacer(Modifier.height(12.dp))
    Text("Speed ramp", style = MaterialTheme.typography.labelLarge)
    Text(
        "Changes speed smoothly inside the clip, e.g. fast → slow motion → fast.",
        style = MaterialTheme.typography.bodySmall,
    )
    ChipChoices(SpeedRamp.entries, null, { it.label }) { vm.applySpeedRamp(clip.id, it) }
    Text(
        "The clip becomes ${CreativeOps.RAMP_STEPS} pieces with stepped speeds; Undo takes it back. " +
            "The preview may pause for a moment between pieces; the exported video does not.",
        style = MaterialTheme.typography.bodySmall,
    )
}

// ------------------------------------------------------------------------------ beat sync

/** Beat sync inside the Music tool. */
@Composable
fun BeatSyncSection(vm: EditorViewModel, project: Project) {
    val music = project.music ?: return
    Spacer(Modifier.height(12.dp))
    Text("Beat sync", style = MaterialTheme.typography.labelLarge)
    val work = vm.beatWork
    if (work != null) {
        Working(work) { vm.cancelBeats() }
        return
    }
    if (music.beats.isEmpty()) {
        Text(
            "Finds the beat of this music and marks it on the timeline, so you can cut on the beat.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { vm.findBeats() }) { Text("♪ Find beats") }
        return
    }
    val onTimeline = remember(project) { CreativeOps.beatTimes(project).size }
    Text(
        "About ${CreativeOps.bpm(music.beats)} BPM · $onTimeline beats marked in amber on the timeline. " +
            "Dragged edges stick to them.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text("Change clips on", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1 to "Every beat", 2 to "Every 2", 4 to "Every 4").forEach { (n, label) ->
            OutlinedButton(onClick = { vm.cutClipsToBeats(n) }) { Text(label) }
        }
    }
    Text(
        "Makes each clip end on a beat. Photos can be any length; videos only as long as their file.",
        style = MaterialTheme.typography.bodySmall,
    )
    TextButton(onClick = { vm.clearBeats() }) { Text("Remove beat marks") }
}

// ------------------------------------------------------------------------------ templates

/** Ready-made looks applied to the clips already added. */
@Composable
fun TemplatesPanel(vm: EditorViewModel, project: Project, onDone: () -> Unit) {
    val hint = LocalHint.current
    var picked by remember { mutableStateOf<String?>(null) }
    val template = picked?.let { Templates.byId(it) }
    if (template == null) {
        Text(
            if (project.clips.isEmpty()) "Add your photos or videos first, then pick a look."
            else "Pick a look. It sets photo length, colour, movement, transitions and titles for your clips.",
        )
        Spacer(Modifier.height(8.dp))
        Templates.all.forEach { t ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    .clickable {
                        if (project.clips.isNotEmpty()) picked = t.id
                        else hint("Add your photos or videos first, then pick a template")
                    }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(t.icon, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.name, fontWeight = FontWeight.Bold)
                    Text(t.about, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        return
    }
    var title by remember(template.id) { mutableStateOf(template.title) }
    var ending by remember(template.id) { mutableStateOf(template.ending) }
    Text("${template.icon} ${template.name}", fontWeight = FontWeight.Bold)
    Text(template.about, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(title, { title = it }, label = { Text("Opening title") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(ending, { ending = it }, label = { Text("Closing line") }, modifier = Modifier.fillMaxWidth())
    Text(
        "Photos: ${template.photoMs / 1000f} s each. Leave a box empty to skip that text.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            vm.applyTemplate(template, title, ending)
            onDone()
        }) { Text("Apply") }
        TextButton(onClick = { picked = null }) { Text("Back") }
    }
    Text(
        "Your clips' colour, length and transitions are replaced; Undo puts them back.",
        style = MaterialTheme.typography.bodySmall,
    )
}

// ------------------------------------------------------------------------------ cover

/** A cover (thumbnail) picture with a title, saved to the gallery. */
@Composable
fun CoverPanel(vm: EditorViewModel, project: Project) {
    var title by remember { mutableStateOf(project.name) }
    var style by remember { mutableStateOf(CoverStyle.BOTTOM) }
    var picture by remember { mutableStateOf<Uri?>(null) }
    var addToStart by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) picture = uri
    }
    // A fresh preview shortly after anything changes (and when the red line moves).
    val frameStep by remember { androidx.compose.runtime.derivedStateOf { vm.playheadMs / 500 } }
    LaunchedEffect(title, style, picture, frameStep) {
        delay(250)
        vm.previewCover(title, style, picture)
    }

    vm.coverPreview?.let { image ->
        Image(
            image, contentDescription = "Cover preview",
            modifier = Modifier.heightIn(max = 220.dp).clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.height(8.dp))
    }
    Text("Picture", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = picture == null, onClick = { picture = null }, label = { Text("Frame at red line") })
        FilterChip(
            selected = picture != null,
            onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            label = { Text("Choose a photo") },
        )
    }
    if (picture == null) {
        Text("Move the timeline to choose the frame.", style = MaterialTheme.typography.bodySmall)
    }
    OutlinedTextField(title, { title = it }, label = { Text("Title on the cover") }, modifier = Modifier.fillMaxWidth())
    ChipChoices(CoverStyle.entries, style, { it.label }) { style = it }
    SwitchRow("Also show it at the start of the video (0.5 s)", addToStart) { addToStart = it }
    val hint = LocalHint.current
    Button(
        onClick = {
            when {
                vm.coverBusy -> hint("Still saving the cover, one moment")
                project.clips.isEmpty() -> hint("Add a video or photo first: the cover is made from it")
                else -> vm.saveCover(title, style, picture, addToStart)
            }
        },
    ) { Text(if (vm.coverBusy) "Saving…" else "Save cover") }
    Text(
        "Saved to Pictures/CutMitra. When you post, choose it as the cover in Instagram or YouTube.",
        style = MaterialTheme.typography.bodySmall,
    )
}


/** Ratio: the shape of the video. */
@Composable
fun RatioPanel(vm: EditorViewModel, project: com.reelforge.editor.model.Project) {
    Text(
        "The shape of your video. Clips that are a different shape get bars (or the Backdrop) around " +
            "them; select a clip and tap Fill to fill the frame instead.",
        style = MaterialTheme.typography.bodySmall,
    )
    RatioChoices(project.aspect) { vm.setAspect(it) }
    if (project.clips.isNotEmpty()) {
        OutlinedButton(onClick = { vm.matchAspect() }) { Text("Match video") }
        Text("Sets the shape to the clip under the red line.", style = MaterialTheme.typography.bodySmall)
    }
}

/** "9:16 Reels / Shorts" and so on. */
fun ratioName(a: com.reelforge.editor.model.AspectRatio) = when (a) {
    com.reelforge.editor.model.AspectRatio.PORTRAIT -> "9:16 Reels"
    com.reelforge.editor.model.AspectRatio.LANDSCAPE -> "16:9 YouTube"
    com.reelforge.editor.model.AspectRatio.SQUARE -> "1:1 Square"
    com.reelforge.editor.model.AspectRatio.FOUR_FIVE -> "4:5 Post"
    else -> a.label
}


/** Frame shapes as small cards, each drawn in its own shape with its name and where it is used. */
@Composable
fun RatioChoices(selected: com.reelforge.editor.model.AspectRatio, onPick: (com.reelforge.editor.model.AspectRatio) -> Unit) {
    HintRow(spacing = 8.dp) {
        com.reelforge.editor.model.AspectRatio.entries.forEach { a ->
            val on = a == selected
            androidx.compose.material3.Surface(
                onClick = { onPick(a) },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                color = if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
                border = if (on) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
            ) {
                androidx.compose.foundation.layout.Column(
                    Modifier.width(74.dp).padding(vertical = 10.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                ) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(40.dp),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        val r = a.ratio
                        val w = if (r >= 1f) 38f else 38f * r
                        val h = if (r >= 1f) 38f / r else 38f
                        androidx.compose.foundation.layout.Box(
                            Modifier
                                .size(w.dp, h.dp)
                                .border(
                                    2.dp,
                                    if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                                ),
                        )
                    }
                    Text(a.label, style = MaterialTheme.typography.labelLarge)
                    Text(ratioUse(a), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Where each frame shape is mostly used. */
fun ratioUse(a: com.reelforge.editor.model.AspectRatio) = when (a) {
    com.reelforge.editor.model.AspectRatio.PORTRAIT -> "Reels, Shorts"
    com.reelforge.editor.model.AspectRatio.LANDSCAPE -> "YouTube"
    com.reelforge.editor.model.AspectRatio.SQUARE -> "Square"
    com.reelforge.editor.model.AspectRatio.FOUR_FIVE -> "Insta post"
    com.reelforge.editor.model.AspectRatio.THREE_FOUR -> "Portrait"
    com.reelforge.editor.model.AspectRatio.FOUR_THREE -> "Classic"
    com.reelforge.editor.model.AspectRatio.CINEMA -> "Cinema"
}
