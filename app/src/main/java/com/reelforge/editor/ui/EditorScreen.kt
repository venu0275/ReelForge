@file:OptIn(ExperimentalMaterial3Api::class)

package com.reelforge.editor.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.reelforge.editor.engine.fadeAlpha
import com.reelforge.editor.engine.overlayAnim
import com.reelforge.editor.model.Background
import com.reelforge.editor.model.ClipType
import com.reelforge.editor.model.Project
import com.reelforge.editor.model.TextFont
import com.reelforge.editor.util.MediaUtils
import kotlinx.coroutines.delay
import java.io.File

enum class Panel { TRIM, SPEED, VOLUME, FILTER, ADJUST, FADE, TEXT, LAYER, AUDIO, MUSIC, BACKGROUND, DRAW, EXPORT }

@Composable
fun EditorScreen(vm: EditorViewModel) {
    val project = vm.project ?: return
    var panel by remember { mutableStateOf<Panel?>(null) }

    BackHandler { vm.closeProject() }

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
    var fontTarget by remember { mutableStateOf<String?>(null) }
    val pickFont = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val target = fontTarget
        if (uri != null && target != null) vm.importFont(uri, target)
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

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
    ) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.closeProject() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                project.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = { vm.undo() }, enabled = vm.canUndo) { Text("Undo") }
            TextButton(onClick = { vm.redo() }, enabled = vm.canRedo) { Text("Redo") }
            Button(
                onClick = { panel = Panel.EXPORT },
                enabled = project.clips.isNotEmpty(),
                modifier = Modifier.padding(end = 8.dp),
            ) { Text("Export") }
        }

        PreviewArea(vm, project, Modifier.weight(1f))

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

        Transport(vm, project)
        Timeline(vm, project, addMedia)
        Tools(vm, project, addMedia) { panel = it }
    }

    val open = panel
    if (open == Panel.EXPORT) {
        ExportDialog(vm, project) { panel = null }
    } else if (open == Panel.DRAW) {
        BackHandler { panel = null }
        DrawScreen(
            project = project,
            onCancel = { panel = null },
            onDone = { strokes ->
                vm.addDrawing(strokes)
                panel = null
            },
        )
    } else if (open != null) {
        ModalBottomSheet(onDismissRequest = { panel = null }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
            ) {
                val clip = vm.selectedClip
                when (open) {
                    Panel.TRIM -> clip?.let { TrimPanel(vm, it) }
                    Panel.SPEED -> clip?.let { SpeedPanel(vm, it) }
                    Panel.VOLUME -> clip?.let { VolumePanel(vm, it) }
                    Panel.FILTER -> clip?.let { FilterPanel(vm, it) }
                    Panel.ADJUST -> clip?.let { AdjustPanel(vm, it) }
                    Panel.FADE -> clip?.let { FadePanel(vm, it) }
                    Panel.TEXT -> TextPanel(vm, project) { textId ->
                        fontTarget = textId
                        pickFont.launch(arrayOf("*/*"))
                    }
                    Panel.BACKGROUND -> BackgroundPanel(vm, project)
                    Panel.DRAW -> Unit
                    Panel.LAYER -> LayerPanel(
                        vm, project,
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
        }
    }
}

// ------------------------------------------------------------------ preview

@Composable
private fun PreviewArea(vm: EditorViewModel, project: Project, modifier: Modifier) {
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
                                    ),
                            )
                        }
                    }
                }

            project.texts
                .filter { now >= it.startMs && now < it.endMs }
                .forEach { t ->
                    key(t.id) {
                        val anim = overlayAnim(now - t.startMs, t.endMs - t.startMs, t.animIn, t.animOut)
                        val fontSize = with(density) { (frameHeight * t.size).toSp() }
                        val custom = remember(t.fontPath) {
                            t.fontPath?.let { path ->
                                runCatching { FontFamily(Font(File(path))) }.getOrNull()
                            }
                        }
                        val family = custom ?: when (t.font) {
                            TextFont.SANS -> FontFamily.SansSerif
                            TextFont.SERIF -> FontFamily.Serif
                            TextFont.MONO -> FontFamily.Monospace
                        }
                        Box(
                            Modifier
                                .align(
                                    glide(t.x, t.y, t.x2, t.y2, t.moving, now - t.startMs, t.endMs - t.startMs)
                                )
                                .graphicsLayer(
                                    alpha = anim.alpha,
                                    scaleX = anim.scale,
                                    scaleY = anim.scale,
                                    translationY = -anim.dy * frameHeightPx / 2f,
                                )
                                .then(
                                    if (t.background) {
                                        Modifier
                                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    } else Modifier
                                )
                        ) {
                            if (t.outline) {
                                Text(
                                    t.text,
                                    color = Color.Black,
                                    fontSize = fontSize,
                                    lineHeight = fontSize * 1.2f,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = family,
                                    textAlign = TextAlign.Center,
                                    style = TextStyle(
                                        drawStyle = Stroke(
                                            width = frameHeightPx * t.size * 0.12f,
                                            join = StrokeJoin.Round,
                                        )
                                    ),
                                )
                            }
                            Text(
                                t.text,
                                color = Color(t.color),
                                fontSize = fontSize,
                                lineHeight = fontSize * 1.2f,
                                fontWeight = FontWeight.Bold,
                                fontFamily = family,
                                textAlign = TextAlign.Center,
                                style = TextStyle(
                                    shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 2f), 6f)
                                ),
                            )
                        }
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

// ---------------------------------------------------------------- transport

@Composable
private fun Transport(vm: EditorViewModel, project: Project) {
    val total = project.durationMs
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledIconButton(onClick = { vm.togglePlay() }, enabled = project.clips.isNotEmpty()) {
            if (vm.isPlaying) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(4.dp, 14.dp).background(LocalContentColor.current))
                    Box(Modifier.size(4.dp, 14.dp).background(LocalContentColor.current))
                }
            } else {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play")
            }
        }
        Slider(
            value = vm.playheadMs.toFloat(),
            onValueChange = { vm.seekTo(it.toLong()) },
            valueRange = 0f..maxOf(total, 1L).toFloat(),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text(
            "${MediaUtils.formatTime(vm.playheadMs)} / ${MediaUtils.formatTime(total)}",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

// ----------------------------------------------------------------- timeline

@Composable
private fun Timeline(vm: EditorViewModel, project: Project, addMedia: () -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().height(72.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(project.clips, key = { it.id }) { item ->
            val key = vm.thumbKey(item)
            LaunchedEffect(key) { vm.requestThumb(item) }
            val selected = item.id == vm.selectedClipId
            val width = (item.durationMs / 1000f * 28f).coerceIn(56f, 200f).dp
            Box(
                Modifier
                    .width(width)
                    .height(56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        width = if (selected) 2.dp else 0.5.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else Color.DarkGray,
                        shape = RoundedCornerShape(6.dp),
                    )
                    .clickable { vm.selectClip(item.id) }
            ) {
                vm.thumbs[key]?.let { thumb ->
                    Image(
                        bitmap = thumb,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                Text(
                    MediaUtils.formatTime(item.durationMs),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 3.dp),
                    fontSize = 10.sp,
                    color = Color.White,
                )
            }
        }
        item {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = addMedia),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add media")
            }
        }
    }
}

// -------------------------------------------------------------------- tools

@Composable
private fun Tools(
    vm: EditorViewModel,
    project: Project,
    addMedia: () -> Unit,
    openPanel: (Panel) -> Unit,
) {
    val clip = vm.selectedClip
    val hasClip = clip != null
    val isVideo = clip?.type == ClipType.VIDEO
    val id = clip?.id.orEmpty()

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ToolButton("Media", true, addMedia)
        ToolButton("Split", project.clips.isNotEmpty()) { vm.splitAtPlayhead() }
        ToolButton("Trim", hasClip) { openPanel(Panel.TRIM) }
        ToolButton("Speed", isVideo) { openPanel(Panel.SPEED) }
        ToolButton("Volume", isVideo) { openPanel(Panel.VOLUME) }
        ToolButton("Filter", hasClip) { openPanel(Panel.FILTER) }
        ToolButton("Adjust", hasClip) { openPanel(Panel.ADJUST) }
        ToolButton("Fade", hasClip) { openPanel(Panel.FADE) }
        ToolButton("Rotate", hasClip) {
            vm.updateClip(id) { it.copy(rotation = (it.rotation + 90) % 360) }
        }
        ToolButton("Mirror", hasClip) { vm.updateClip(id) { it.copy(flipH = !it.flipH) } }
        ToolButton(if (clip?.fill == true) "Fit" else "Fill", hasClip) {
            vm.updateClip(id) { it.copy(fill = !it.fill) }
        }
        ToolButton("Text", project.clips.isNotEmpty()) { openPanel(Panel.TEXT) }
        ToolButton("Backdrop", project.clips.isNotEmpty()) { openPanel(Panel.BACKGROUND) }
        ToolButton("Layer", project.clips.isNotEmpty()) { openPanel(Panel.LAYER) }
        ToolButton("Draw", project.clips.isNotEmpty()) { openPanel(Panel.DRAW) }
        ToolButton("Voice", project.clips.isNotEmpty()) { openPanel(Panel.AUDIO) }
        ToolButton("Music", true) { openPanel(Panel.MUSIC) }
        ToolButton("Freeze", isVideo) { vm.freezeFrame() }
        ToolButton("Copy", hasClip) { vm.duplicateClip(id) }
        ToolButton("◀ Move", hasClip) { vm.moveClip(id, -1) }
        ToolButton("Move ▶", hasClip) { vm.moveClip(id, 1) }
        ToolButton("Delete", hasClip) { vm.deleteClip(id) }
    }
}

@Composable
private fun ToolButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.alpha(if (enabled) 1f else 0.4f),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
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
            Column {
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
