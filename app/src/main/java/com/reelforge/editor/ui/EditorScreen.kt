@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.reelforge.editor.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.reelforge.editor.engine.EffectFactory
import com.reelforge.editor.engine.fadeAlpha
import com.reelforge.editor.engine.overlayAnim
import com.reelforge.editor.model.Background
import com.reelforge.editor.model.Clip
import com.reelforge.editor.model.ClipType
import com.reelforge.editor.model.OverlayAnim
import com.reelforge.editor.model.Project
import com.reelforge.editor.util.MediaUtils
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

enum class Panel(val title: String) {
    TRIM("Trim"),
    SPEED("Speed"),
    VOLUME("Volume"),
    FILTER("Filter"),
    ADJUST("Adjust"),
    FADE("Fade"),
    TEXT("Text"),
    LAYER("Layers"),
    AUDIO("Voice and sounds"),
    MUSIC("Music"),
    BACKGROUND("Backdrop"),
    DRAW("Draw"),
    EXPORT("Export"),
}

/**
 * The editor. The timeline scrolls under a fixed playhead, and the menu shows only what applies:
 * things to add when nothing is selected, or the actions for the selected clip.
 */
@Composable
fun EditorScreen(vm: EditorViewModel) {
    val project = vm.project ?: return
    var panel by remember { mutableStateOf<Panel?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            vm.tick()
            delay(40)
        }
    }
    LaunchedEffect(vm.message) {
        if (vm.message != null) {
            delay(3500)
            vm.message = null
        }
    }

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> vm.addMedia(uris) }
    val pickAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.setMusic(uri) }
    val context = LocalContext.current
    val pickLayer = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) vm.addImageLayer(uri) }
    val pickSound = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.addAudioClip(uri) }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.startRecording() else vm.message = "Microphone permission is needed to record"
    }
    var editingTextId by remember { mutableStateOf<String?>(null) }
    var editingLayerId by remember { mutableStateOf<String?>(null) }
    var fontTarget by remember { mutableStateOf<String?>(null) }
    val pickFont = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val target = fontTarget
        if (uri != null && target != null) vm.importFont(uri, target)
    }
    var replaceTarget by remember { mutableStateOf<String?>(null) }
    val pickReplacement = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val target = replaceTarget
        if (uri != null && target != null) vm.replaceClip(target, uri)
    }
    val record: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) vm.startRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    val addMedia: () -> Unit = {
        pickMedia.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
        )
    }
    val replace: (String) -> Unit = { clipId ->
        replaceTarget = clipId
        pickReplacement.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
        )
    }
    val editText: (String) -> Unit = { id ->
        editingTextId = id
        panel = Panel.TEXT
    }
    val editLayer: (String) -> Unit = { id ->
        editingLayerId = id
        panel = Panel.LAYER
    }

    // Back steps out one level at a time: tool, then selection, then the project.
    BackHandler {
        when {
            panel != null -> panel = null
            vm.selectedClipId != null -> vm.selectedClipId = null
            else -> vm.closeProject()
        }
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val preview: @Composable (Modifier) -> Unit = { modifier ->
        PreviewArea(vm, project, modifier, onEditText = editText, onEditLayer = editLayer)
    }

    val messageBar: @Composable () -> Unit = {
        vm.message?.let { msg ->
            Text(
                msg,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { vm.message = null }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    val timeline: @Composable (Modifier) -> Unit = { modifier ->
        Timeline(
            vm, project, modifier,
            addMedia = addMedia,
            onEditText = editText,
            onEditLayer = editLayer,
            onOpenAudio = { panel = Panel.AUDIO },
            onOpenMusic = { panel = Panel.MUSIC },
        )
    }

    val menu: @Composable (Modifier, Boolean) -> Unit = { modifier, showExport ->
        EditorMenu(
            vm, project, modifier,
            showExport = showExport,
            addMedia = addMedia,
            replace = replace,
            openPanel = { which ->
                if (which == Panel.TEXT) editingTextId = null
                if (which == Panel.LAYER) editingLayerId = null
                panel = which
            },
        )
    }

    // The controls of one tool; shown in a bottom sheet (portrait) or the side pane (landscape).
    val panelBody: @Composable (Panel) -> Unit = { which ->
        val clip = vm.selectedClip
        when (which) {
            Panel.TRIM -> clip?.let { TrimPanel(vm, it) }
            Panel.SPEED -> clip?.let { SpeedPanel(vm, it) }
            Panel.VOLUME -> clip?.let { VolumePanel(vm, it) }
            Panel.FILTER -> clip?.let { FilterPanel(vm, it) }
            Panel.ADJUST -> clip?.let { AdjustPanel(vm, it) }
            Panel.FADE -> clip?.let { FadePanel(vm, it) }
            Panel.TEXT -> TextPanel(
                vm, project,
                editingId = editingTextId,
                onEditing = { editingTextId = it },
                pickFont = { textId ->
                    fontTarget = textId
                    pickFont.launch(arrayOf("*/*"))
                },
            )
            Panel.BACKGROUND -> BackgroundPanel(vm, project)
            Panel.DRAW -> Unit
            Panel.LAYER -> LayerPanel(
                vm, project,
                editingId = editingLayerId,
                onEditing = { editingLayerId = it },
                pickImage = {
                    pickLayer.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                draw = { panel = Panel.DRAW },
            )
            Panel.AUDIO -> AudioPanel(
                vm, project,
                record = record,
                pickSound = { pickSound.launch(arrayOf("audio/*")) },
            )
            Panel.MUSIC -> MusicPanel(vm, project) { pickAudio.launch(arrayOf("audio/*")) }
            Panel.EXPORT -> Unit
        }
    }

    val open = panel
    val sidePanel = open != null && open != Panel.EXPORT && open != Panel.DRAW
    val root = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .systemBarsPadding()
        .displayCutoutPadding()

    if (landscape) {
        Column(root) {
            Row(Modifier.weight(1f)) {
                // Left rail: leave, undo, redo, play.
                Column(
                    Modifier.width(56.dp).fillMaxHeight().padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    IconButton(onClick = { vm.closeProject() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to projects")
                    }
                    GlyphButton("↶", "Undo", vm.canUndo) { vm.undo() }
                    GlyphButton("↷", "Redo", vm.canRedo) { vm.redo() }
                    Spacer(Modifier.weight(1f))
                    PlayButton(vm, project)
                }
                preview(Modifier.weight(1f).fillMaxHeight())
                Column(
                    Modifier
                        .width(300.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    if (open != null && sidePanel) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { panel = null }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close tool")
                            }
                            Text(open.title, style = MaterialTheme.typography.titleMedium)
                        }
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 16.dp)
                        ) {
                            panelBody(open)
                        }
                    } else {
                        menu(Modifier.fillMaxSize(), true)
                    }
                }
            }
            messageBar()
            Row {
                Column(
                    Modifier.width(56.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    JumpButtons(vm, project, vertical = true)
                }
                timeline(Modifier.weight(1f))
            }
        }
    } else {
        Column(root) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.closeProject() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to projects")
                }
                Text(
                    project.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                GlyphButton("↶", "Undo", vm.canUndo) { vm.undo() }
                GlyphButton("↷", "Redo", vm.canRedo) { vm.redo() }
                Button(
                    onClick = { panel = Panel.EXPORT },
                    enabled = project.clips.isNotEmpty(),
                    modifier = Modifier.padding(end = 8.dp),
                ) { Text("Export") }
            }
            preview(Modifier.weight(1f))
            messageBar()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                JumpButtons(vm, project, vertical = false)
                Spacer(Modifier.weight(1f))
                PlayButton(vm, project)
                Spacer(Modifier.weight(1f))
                Text(
                    "${MediaUtils.formatTime(vm.playheadMs)} / ${MediaUtils.formatTime(project.durationMs)}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            timeline(Modifier.fillMaxWidth())
            menu(Modifier.fillMaxWidth().heightIn(max = 190.dp), false)
        }
    }

    if (open == Panel.EXPORT) {
        ExportDialog(vm, project) { panel = null }
    } else if (open == Panel.DRAW) {
        DrawScreen(
            project = project,
            onCancel = { panel = null },
            onDone = { strokes ->
                vm.addDrawing(strokes)
                panel = null
            },
        )
    } else if (open != null && !landscape) {
        ModalBottomSheet(onDismissRequest = { panel = null }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
            ) {
                panelBody(open)
            }
        }
    }
}

// ---------------------------------------------------------------- small controls

@Composable
private fun GlyphButton(glyph: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Text(glyph, fontSize = 24.sp)
    }
}

@Composable
private fun PlayButton(vm: EditorViewModel, project: Project) {
    FilledIconButton(
        onClick = { vm.togglePlay() },
        enabled = project.clips.isNotEmpty(),
        modifier = Modifier.semantics { contentDescription = if (vm.isPlaying) "Pause" else "Play" },
    ) {
        if (vm.isPlaying) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(4.dp, 14.dp).background(LocalContentColor.current))
                Box(Modifier.size(4.dp, 14.dp).background(LocalContentColor.current))
            }
        } else {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
        }
    }
}

/** Jump to the start / end of the video. */
@Composable
private fun JumpButtons(vm: EditorViewModel, project: Project, vertical: Boolean) {
    val enabled = project.clips.isNotEmpty()
    val buttons: @Composable () -> Unit = {
        GlyphButton("⇤", "Go to start", enabled) { vm.jumpToStart() }
        GlyphButton("⇥", "Go to end", enabled) { vm.jumpToEnd() }
    }
    if (vertical) Column { buttons() } else Row { buttons() }
}

// ------------------------------------------------------------------------- menu

/**
 * Shows what can be added when nothing is selected, or the actions for the selected clip.
 */
@Composable
private fun EditorMenu(
    vm: EditorViewModel,
    project: Project,
    modifier: Modifier,
    showExport: Boolean,
    addMedia: () -> Unit,
    replace: (String) -> Unit,
    openPanel: (Panel) -> Unit,
) {
    val clip = vm.selectedClip
    val hasClips = project.clips.isNotEmpty()

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (clip != null) {
                IconButton(onClick = { vm.selectedClipId = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to main menu")
                }
                Text(
                    (if (clip.type == ClipType.VIDEO) "Video clip" else "Photo") +
                        " · " + MediaUtils.formatTime(clip.durationMs),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                IconButton(onClick = { vm.deleteClip(clip.id) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete clip")
                }
            } else {
                Text(
                    if (hasClips) "Add to your video" else "Start by adding media",
                    Modifier.weight(1f).padding(start = 12.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (showExport) {
                    Button(onClick = { openPanel(Panel.EXPORT) }, enabled = hasClips) { Text("Export") }
                }
            }
        }

        FlowRow(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp)
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (clip == null) {
                MenuTile("Media", accent = true) { addMedia() }
                MenuTile("Text", enabled = hasClips) { openPanel(Panel.TEXT) }
                MenuTile("Layer", enabled = hasClips) { openPanel(Panel.LAYER) }
                MenuTile("Draw", enabled = hasClips) { openPanel(Panel.DRAW) }
                MenuTile("Voice", enabled = hasClips) { openPanel(Panel.AUDIO) }
                MenuTile("Music") { openPanel(Panel.MUSIC) }
                MenuTile("Backdrop", enabled = hasClips) { openPanel(Panel.BACKGROUND) }
            } else {
                val id = clip.id
                val isVideo = clip.type == ClipType.VIDEO
                MenuTile("Split", accent = true) { vm.splitAtPlayhead() }
                MenuTile("Trim") { openPanel(Panel.TRIM) }
                MenuTile("Replace") { replace(id) }
                if (isVideo) MenuTile("Speed") { openPanel(Panel.SPEED) }
                if (isVideo) MenuTile("Volume") { openPanel(Panel.VOLUME) }
                MenuTile("Filter") { openPanel(Panel.FILTER) }
                MenuTile("Adjust") { openPanel(Panel.ADJUST) }
                MenuTile("Fade") { openPanel(Panel.FADE) }
                MenuTile("Rotate") { vm.updateClip(id) { it.copy(rotation = (it.rotation + 90) % 360) } }
                MenuTile("Mirror") { vm.updateClip(id) { it.copy(flipH = !it.flipH) } }
                MenuTile(if (clip.fill) "Fit" else "Fill") { vm.updateClip(id) { it.copy(fill = !it.fill) } }
                if (isVideo) MenuTile("Freeze") { vm.freezeFrame() }
                MenuTile("Copy") { vm.duplicateClip(id) }
                MenuTile("Move left") { vm.moveClip(id, -1) }
                MenuTile("Move right") { vm.moveClip(id, 1) }
            }
        }
    }
}

@Composable
private fun MenuTile(
    label: String,
    enabled: Boolean = true,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (accent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.alpha(if (enabled) 1f else 0.4f),
    ) {
        Text(
            label,
            modifier = Modifier.widthIn(min = 84.dp).padding(horizontal = 12.dp, vertical = 14.dp),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
    }
}

// --------------------------------------------------------------------- timeline

private val RULER_HEIGHT = 22.dp
private val TRACK_HEIGHT = 56.dp
private val ROW_HEIGHT = 24.dp
private val TILE_WIDTH = 64.dp

private class Bar(val id: String, val label: String, val startMs: Long, val endMs: Long)

/**
 * The timeline. Content scrolls under a playhead fixed at the centre: dragging scrubs the video,
 * pinching zooms, tapping a clip selects it and dragging a selected clip's edges trims it.
 * Text, layers, sounds and music appear as bars on their own rows.
 */
@Composable
private fun Timeline(
    vm: EditorViewModel,
    project: Project,
    modifier: Modifier,
    addMedia: () -> Unit,
    onEditText: (String) -> Unit,
    onEditLayer: (String) -> Unit,
    onOpenAudio: () -> Unit,
    onOpenMusic: () -> Unit,
) {
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    var dpPerSecond by rememberSaveable { mutableFloatStateOf(48f) }

    val total = project.durationMs
    // Keep the scrolling content within what a layout can measure.
    val widest = if (total > 0) 60_000f / (total / 1000f) else 240f
    val scale = dpPerSecond.coerceIn(4f, maxOf(4f, minOf(240f, widest)))
    val pxPerMs = with(density) { scale.dp.toPx() } / 1000f
    val contentPx = total * pxPerMs
    val contentWidth = with(density) { contentPx.toDp() }
    val latestPxPerMs by rememberUpdatedState(pxPerMs)

    val dragged by scroll.interactionSource.collectIsDraggedAsState()
    var userScrolling by remember { mutableStateOf(false) }
    var zoomAnchorMs by remember { mutableStateOf<Long?>(null) }

    // Dragging the timeline pauses playback and scrubs.
    LaunchedEffect(dragged) {
        if (dragged) {
            userScrolling = true
            vm.pause()
        }
    }
    LaunchedEffect(scroll.isScrollInProgress) {
        if (!scroll.isScrollInProgress && userScrolling) {
            userScrolling = false
            scroll.scrollTo((vm.playheadMs * latestPxPerMs).roundToInt())
        }
    }
    LaunchedEffect(userScrolling) {
        if (userScrolling) {
            snapshotFlow { scroll.value }.collect { px ->
                if (zoomAnchorMs == null) vm.scrubTo((px / latestPxPerMs).toLong())
            }
        }
    }
    // Playback, seeks and zooming move the content so the playhead time stays under the line.
    LaunchedEffect(vm.playheadMs, pxPerMs, contentPx, zoomAnchorMs, scroll.maxValue) {
        val anchor = zoomAnchorMs
        if (anchor != null) {
            scroll.scrollTo((anchor * pxPerMs).roundToInt())
        } else if (!userScrolling) {
            scroll.scrollTo((vm.playheadMs * pxPerMs).roundToInt())
        }
    }

    val textBars = project.texts.map { Bar(it.id, it.text.replace("\n", " "), it.startMs, it.endMs) }
    val layerBars = project.layers.mapIndexed { i, l -> Bar(l.id, "Layer ${i + 1}", l.startMs, l.endMs) }
    val audioBars = project.audio.map { Bar(it.id, it.title, it.startMs, it.startMs + it.durationMs) }
    val rows = listOf(textBars, layerBars, audioBars).count { it.isNotEmpty() } +
        (if (project.music != null) 1 else 0)
    val height = RULER_HEIGHT + TRACK_HEIGHT + ROW_HEIGHT * rows + 10.dp

    BoxWithConstraints(
        modifier
            .height(height)
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(Unit) {
                // Two fingers zoom the timeline; one finger is left to the scroll below.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2) {
                            if (zoomAnchorMs == null) zoomAnchorMs = vm.playheadMs
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) dpPerSecond = (dpPerSecond * zoom).coerceIn(4f, 240f)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    zoomAnchorMs = null
                }
            }
    ) {
        val half = maxWidth / 2
        val viewportPx = with(density) { maxWidth.toPx() }
        val tilePx = with(density) { TILE_WIDTH.toPx() }
        // Only clips and ticks near the screen are drawn; this changes once per tile scrolled.
        val window by remember(tilePx) { derivedStateOf { (scroll.value / tilePx).toInt() } }
        val visibleFrom = window * tilePx - viewportPx
        val visibleTo = window * tilePx + viewportPx

        Row(Modifier.fillMaxHeight().horizontalScroll(scroll)) {
            Spacer(Modifier.width(half))
            Column(Modifier.width(contentWidth)) {
                Ruler(total, pxPerMs, visibleFrom, visibleTo, Modifier.width(contentWidth).height(RULER_HEIGHT))

                Row(Modifier.height(TRACK_HEIGHT)) {
                    var startPx = 0f
                    project.clips.forEach { clip ->
                        val clipStartPx = startPx
                        startPx += clip.durationMs * pxPerMs
                        key(clip.id) {
                            ClipBlock(
                                vm, clip, clipStartPx, pxPerMs, tilePx, visibleFrom, visibleTo,
                                selected = clip.id == vm.selectedClipId,
                            )
                        }
                    }
                }

                if (textBars.isNotEmpty()) {
                    BarRow(textBars, pxPerMs, Color(0xFF7C4DFF), onEditText) { id, deltaMs ->
                        vm.updateText(id) { it.shifted(deltaMs) }
                    }
                }
                if (layerBars.isNotEmpty()) {
                    BarRow(layerBars, pxPerMs, Color(0xFF009688), onEditLayer) { id, deltaMs ->
                        vm.updateLayer(id) {
                            val start = (it.startMs + deltaMs).coerceAtLeast(0)
                            it.copy(startMs = start, endMs = start + (it.endMs - it.startMs))
                        }
                    }
                }
                if (audioBars.isNotEmpty()) {
                    BarRow(audioBars, pxPerMs, Color(0xFF43A047), { onOpenAudio() }) { id, deltaMs ->
                        vm.updateAudioClip(id) { it.copy(startMs = (it.startMs + deltaMs).coerceAtLeast(0)) }
                    }
                }
                project.music?.let { music ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(ROW_HEIGHT)
                            .padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFEF6C00))
                            .clickable(onClick = onOpenMusic)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text("♪ ${music.title}", fontSize = 11.sp, maxLines = 1, color = Color.White)
                    }
                }
            }
            // Add-media button sits right after the last clip.
            Column {
                Spacer(Modifier.height(RULER_HEIGHT))
                Box(
                    Modifier
                        .padding(start = 6.dp)
                        .size(TRACK_HEIGHT)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(onClick = addMedia),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add media")
                }
            }
            Spacer(Modifier.width(half))
        }

        // Fixed playhead with the current time; total length at the right.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .width(2.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary)
        )
        Text(
            MediaUtils.formatTime(vm.playheadMs),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 8.dp, vertical = 1.dp),
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 11.sp,
        )
        Text(
            MediaUtils.formatTime(total),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 6.dp, top = 1.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White)
                .padding(horizontal = 8.dp, vertical = 1.dp),
            color = Color.Black,
            fontSize = 11.sp,
        )
        if (project.clips.isEmpty()) {
            Text(
                "Tap + to add videos or photos",
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun com.reelforge.editor.model.TextItem.shifted(deltaMs: Long): com.reelforge.editor.model.TextItem {
    val start = (startMs + deltaMs).coerceAtLeast(0)
    return copy(startMs = start, endMs = start + (endMs - startMs))
}

/** Time marks along the top of the timeline. */
@Composable
private fun Ruler(totalMs: Long, pxPerMs: Float, visibleFrom: Float, visibleTo: Float, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val style = TextStyle(color = color, fontSize = 10.sp)
    val minGapPx = with(LocalDensity.current) { 72.dp.toPx() }
    Canvas(modifier) {
        if (totalMs <= 0 || pxPerMs <= 0f) return@Canvas
        val stepMs = RULER_STEPS.firstOrNull { it * pxPerMs >= minGapPx } ?: RULER_STEPS.last()
        val firstMs = (maxOf(0f, visibleFrom) / pxPerMs).toLong() / stepMs * stepMs
        val lastMs = minOf(totalMs.toFloat(), visibleTo / pxPerMs).toLong()
        var t = firstMs
        while (t <= lastMs) {
            val x = t * pxPerMs
            drawLine(color, Offset(x, size.height * 0.55f), Offset(x, size.height), strokeWidth = 2f)
            val label = if (stepMs < 1000) MediaUtils.formatTime(t) else "%d:%02d".format(t / 60_000, (t / 1000) % 60)
            drawText(measurer.measure(label, style), topLeft = Offset(x + 6f, 0f))
            // A dot halfway between labels.
            val mid = (t + stepMs / 2) * pxPerMs
            if (mid < size.width) drawCircle(color, radius = 2.5f, center = Offset(mid, size.height * 0.75f))
            t += stepMs
        }
    }
}

private val RULER_STEPS = listOf(
    100L, 200L, 500L, 1_000L, 2_000L, 5_000L, 10_000L, 15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L,
)

/** One clip on the main track: a strip of frames, a selection outline and trim handles. */
@Composable
private fun ClipBlock(
    vm: EditorViewModel,
    clip: Clip,
    startPx: Float,
    pxPerMs: Float,
    tilePx: Float,
    visibleFrom: Float,
    visibleTo: Float,
    selected: Boolean,
) {
    val density = LocalDensity.current
    val widthPx = clip.durationMs * pxPerMs
    val isVideo = clip.type == ClipType.VIDEO
    val firstTile = maxOf(0, floor((visibleFrom - startPx) / tilePx).toInt())
    val lastTile = minOf(ceil(widthPx / tilePx).toInt() - 1, floor((visibleTo - startPx) / tilePx).toInt())
    // Each tile shows the source frame at its left edge.
    val tiles = if (lastTile >= firstTile) {
        (firstTile..lastTile).map { i ->
            i to if (isVideo) clip.trimStartMs + (i * tilePx / pxPerMs * clip.speed).toLong() else 0L
        }
    } else emptyList()

    val stillKey = vm.thumbKey(clip)
    LaunchedEffect(stillKey) { vm.requestThumb(clip) }
    SideEffect { if (isVideo) tiles.forEach { vm.requestFrame(clip.uri, it.second) } }

    val shape = RoundedCornerShape(6.dp)
    val outline = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .width(with(density) { widthPx.toDp() })
            .fillMaxHeight()
            .zIndex(if (selected) 1f else 0f)
    ) {
        Box(
            Modifier
                .matchParentSize()
                .padding(horizontal = 1.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .drawBehind {
                    tiles.forEach { (i, sourceMs) ->
                        val frame = if (isVideo) vm.frames[vm.frameKey(clip.uri, sourceMs)] else null
                        val image = frame ?: vm.thumbs[stillKey]
                        if (image != null) drawTile(image, i * tilePx, tilePx)
                    }
                }
                .then(if (selected) Modifier.border(2.dp, outline, shape) else Modifier)
                .clickable { vm.toggleClipSelection(clip.id) }
                .semantics { contentDescription = if (isVideo) "Video clip" else "Photo clip" }
        )
        if (selected && widthPx > with(density) { 64.dp.toPx() }) {
            TrimHandle(atStart = true) { dragPx -> vm.trimClipStart(clip.id, (dragPx / pxPerMs).toLong()) }
            TrimHandle(atStart = false) { dragPx -> vm.trimClipEnd(clip.id, (dragPx / pxPerMs).toLong()) }
        }
    }
}

/** Draws [image] centre-cropped into a tile starting at [x]. */
private fun DrawScope.drawTile(image: ImageBitmap, x: Float, width: Float) {
    val height = size.height
    if (width <= 0f || height <= 0f) return
    val wanted = width / height
    val actual = image.width.toFloat() / image.height
    val srcWidth = if (actual > wanted) (image.height * wanted).toInt() else image.width
    val srcHeight = if (actual > wanted) image.height else (image.width / wanted).toInt()
    drawImage(
        image,
        srcOffset = IntOffset((image.width - srcWidth) / 2, (image.height - srcHeight) / 2),
        srcSize = IntSize(srcWidth.coerceAtLeast(1), srcHeight.coerceAtLeast(1)),
        dstOffset = IntOffset(x.roundToInt(), 0),
        dstSize = IntSize(ceil(width).toInt(), height.roundToInt()),
    )
}

/** A grab bar on a selected clip's edge. The trim is applied when the finger lifts. */
@Composable
private fun BoxScope.TrimHandle(atStart: Boolean, onCommit: (dragPx: Float) -> Unit) {
    var drag by remember { mutableFloatStateOf(0f) }
    val commit by rememberUpdatedState(onCommit)
    Box(
        Modifier
            .align(if (atStart) Alignment.CenterStart else Alignment.CenterEnd)
            .offset { IntOffset(drag.roundToInt(), 0) }
            .width(20.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primary)
            .semantics { contentDescription = if (atStart) "Trim start" else "Trim end" }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        commit(drag)
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                ) { change, amount ->
                    change.consume()
                    drag += amount
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(3.dp, 20.dp).clip(CircleShape).background(Color.White))
    }
}

/**
 * A row of bars (text, layers or sounds). Tap a bar to open it; long-press and drag to move it
 * in time.
 */
@Composable
private fun BarRow(
    bars: List<Bar>,
    pxPerMs: Float,
    color: Color,
    onTap: (String) -> Unit,
    onMove: (id: String, deltaMs: Long) -> Unit,
) {
    val density = LocalDensity.current
    val move by rememberUpdatedState(onMove)
    val scale by rememberUpdatedState(pxPerMs)
    Box(Modifier.fillMaxWidth().height(ROW_HEIGHT)) {
        bars.forEach { bar ->
            key(bar.id) {
                var drag by remember { mutableFloatStateOf(0f) }
                val x = with(density) { (bar.startMs * pxPerMs).toDp() }
                val width = with(density) { ((bar.endMs - bar.startMs) * pxPerMs).toDp() }
                Box(
                    Modifier
                        .offset { IntOffset(x.roundToPx() + drag.roundToInt(), 0) }
                        .width(maxOf(width, 14.dp))
                        .fillMaxHeight()
                        .padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(color.copy(alpha = if (drag != 0f) 0.6f else 0.9f))
                        .clickable { onTap(bar.id) }
                        .pointerInput(bar.id) {
                            detectDragGesturesAfterLongPress(
                                onDragEnd = {
                                    move(bar.id, (drag / scale).toLong())
                                    drag = 0f
                                },
                                onDragCancel = { drag = 0f },
                            ) { change, amount ->
                                change.consume()
                                drag += amount.x
                            }
                        }
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(bar.label, fontSize = 11.sp, maxLines = 1, color = Color.White)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ preview

@Composable
private fun PreviewArea(
    vm: EditorViewModel,
    project: Project,
    modifier: Modifier,
    onEditText: (String) -> Unit,
    onEditLayer: (String) -> Unit,
) {
    val location = project.locate(vm.playheadMs)
    val current = location?.let { project.clips[it.first] }
    val local = location?.second ?: 0L

    Box(
        modifier.fillMaxWidth().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            Modifier
                .aspectRatio(project.aspect.w.toFloat() / project.aspect.h)
                .clipToBounds()
        ) {
            val frameHeight = maxHeight

            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        player = vm.player
                    }
                },
                update = { view ->
                    view.resizeMode = if (current?.fill == true) {
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    } else {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )

            if (current == null) {
                Text(
                    "Add videos or photos to begin",
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White.copy(alpha = 0.7f),
                )
            }

            // Photos are drawn by Compose; the player only keeps time for them.
            if (current != null && current.type == ClipType.IMAGE) {
                LaunchedEffect(current.uri) { vm.requestPreview(current) }
                val still = vm.previews[current.uri]
                val backdrop = if (!current.fill && project.background == Background.COLOR) {
                    Color(project.backgroundColor)
                } else Color.Black
                Box(Modifier.fillMaxSize().background(backdrop)) {
                    if (still != null && !current.fill && project.background == Background.BLUR) {
                        Image(
                            bitmap = still,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().blur(24.dp),
                            contentScale = ContentScale.Crop,
                            alpha = 0.7f,
                        )
                    }
                    if (still != null) {
                        Image(
                            bitmap = still,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().graphicsLayer(
                                scaleX = current.zoom * (if (current.flipH) -1f else 1f),
                                scaleY = current.zoom,
                                rotationZ = current.rotation.toFloat(),
                            ),
                            contentScale = if (current.fill) ContentScale.Crop else ContentScale.Fit,
                        )
                    }
                }
            }

            if (current != null) {
                val alpha = current.opacity.coerceIn(0f, 1f) *
                    fadeAlpha(local, current.durationMs, current.fadeInMs, current.fadeOutMs)
                if (alpha < 1f) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 1f - alpha)))
                }
            }

            val density = LocalDensity.current
            val frameHeightPx = with(density) { frameHeight.toPx() }
            val now = vm.playheadMs

            project.layers
                .filter { now >= it.startMs && now < it.endMs }
                .forEach { layer ->
                    key(layer.id) {
                        LaunchedEffect(layer.uri) { vm.requestStill(layer.uri) }
                        val still = vm.previews[layer.uri]
                        val anim = overlayAnim(
                            now - layer.startMs, layer.endMs - layer.startMs, layer.animIn, layer.animOut
                        )
                        if (still != null) {
                            Image(
                                bitmap = still,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .align(
                                        glide(
                                            layer.x, layer.y, layer.x2, layer.y2, layer.moving,
                                            now - layer.startMs, layer.endMs - layer.startMs,
                                        )
                                    )
                                    .height(frameHeight * layer.scale)
                                    .graphicsLayer(
                                        alpha = (anim.alpha * layer.opacity).coerceIn(0f, 1f),
                                        scaleX = anim.scale,
                                        scaleY = anim.scale,
                                        translationY = -anim.dy * frameHeightPx / 2f,
                                        rotationZ = -layer.rotation,
                                    )
                                    .clickable { onEditLayer(layer.id) },
                            )
                        }
                    }
                }

            // Text is drawn from the same bitmap the export uses, so it looks identical.
            project.texts
                .filter { now >= it.startMs && now < it.endMs }
                .forEach { t ->
                    key(t.id) {
                        val anim = overlayAnim(now - t.startMs, t.endMs - t.startMs, t.animIn, t.animOut)
                        val look = t.copy(
                            x = 0f, y = 0f, x2 = 0f, y2 = 0f, startMs = 0, endMs = 0, moving = false,
                            animIn = OverlayAnim.NONE, animOut = OverlayAnim.NONE,
                            opacity = 1f, rotation = 0f,
                        )
                        val heightPx = frameHeightPx.toInt().coerceAtLeast(16)
                        val bitmap = remember(look, heightPx) {
                            EffectFactory.renderText(look, heightPx).asImageBitmap()
                        }
                        Image(
                            bitmap = bitmap,
                            contentDescription = t.text,
                            modifier = Modifier
                                .align(
                                    glide(t.x, t.y, t.x2, t.y2, t.moving, now - t.startMs, t.endMs - t.startMs)
                                )
                                .graphicsLayer(
                                    alpha = (anim.alpha * t.opacity).coerceIn(0f, 1f),
                                    scaleX = anim.scale,
                                    scaleY = anim.scale,
                                    translationY = -anim.dy * frameHeightPx / 2f,
                                    rotationZ = -t.rotation,
                                )
                                .clickable { onEditText(t.id) },
                        )
                    }
                }
        }
    }
}

/** Position of an overlay at [tMs] of [durationMs], gliding to (x2, y2) when [moving]. */
private fun glide(
    x: Float, y: Float, x2: Float, y2: Float, moving: Boolean, tMs: Long, durationMs: Long,
): BiasAlignment {
    if (!moving) return BiasAlignment(x, -y)
    val p = (tMs.toFloat() / durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
    return BiasAlignment(x + (x2 - x) * p, -(y + (y2 - y) * p))
}

// ------------------------------------------------------------------- export

@Composable
private fun ExportDialog(vm: EditorViewModel, project: Project, onClose: () -> Unit) {
    val state = vm.export
    var shortSide by remember { mutableIntStateOf(720) }
    var quality by remember { mutableIntStateOf(1) }
    val context = androidx.compose.ui.platform.LocalContext.current

    AlertDialog(
        onDismissRequest = {
            if (state !is ExportState.Running) {
                vm.dismissExport()
                onClose()
            }
        },
        title = { Text("Export video") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (state) {
                    ExportState.Idle -> {
                        Text("Resolution")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(480, 720, 1080).forEach { s ->
                                FilterChip(
                                    selected = shortSide == s,
                                    onClick = { shortSide = s },
                                    label = { Text("${s}p") },
                                )
                            }
                        }
                        Text("Quality")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Small file", "Balanced", "Best").forEachIndexed { i, label ->
                                FilterChip(
                                    selected = quality == i,
                                    onClick = { quality = i },
                                    label = { Text(label) },
                                )
                            }
                        }
                        Text(
                            "MP4 (H.264 + AAC), ${project.aspect.label}, " +
                                MediaUtils.formatTime(project.durationMs),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    is ExportState.Running -> {
                        Text("Rendering… ${state.progress}%")
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { state.progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is ExportState.Done -> Text("Saved to Movies/ReelForge in your gallery.")
                    is ExportState.Failed -> Text("Export failed: ${state.message}")
                }
            }
        },
        confirmButton = {
            when (state) {
                ExportState.Idle -> TextButton(onClick = { vm.startExport(shortSide, quality) }) { Text("Export") }
                is ExportState.Running -> TextButton(onClick = { vm.cancelExport() }) { Text("Cancel") }
                is ExportState.Done -> TextButton(onClick = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("video/mp4")
                        .putExtra(android.content.Intent.EXTRA_STREAM, state.uri)
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(android.content.Intent.createChooser(send, "Share video"))
                }) { Text("Share") }
                is ExportState.Failed -> Unit
            }
        },
        dismissButton = {
            if (state !is ExportState.Running) {
                TextButton(onClick = {
                    vm.dismissExport()
                    onClose()
                }) { Text("Close") }
            }
        },
    )
}
