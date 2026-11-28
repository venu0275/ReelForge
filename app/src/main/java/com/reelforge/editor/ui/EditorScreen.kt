@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.reelforge.editor.ui

import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.ui.text.font.FontWeight
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
import com.reelforge.editor.engine.ExportManager
import com.reelforge.editor.engine.fadeAlpha
import com.reelforge.editor.engine.overlayAnim
import com.reelforge.editor.model.Background
import com.reelforge.editor.model.Clip
import com.reelforge.editor.model.ClipType
import com.reelforge.editor.model.Cut
import com.reelforge.editor.model.OverlayAnim
import com.reelforge.editor.model.Project
import com.reelforge.editor.model.ProjectOps
import com.reelforge.editor.util.MediaUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Each tool's name, icon, colour and one line telling the user what it does. */
enum class Panel(
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val color: Color,
    val help: String,
) {
    TRIM("Fine trim", Icons.Rounded.ContentCut, ToolColors.Cut, "Set exactly where the clip starts and ends."),
    SPEED("Speed", Icons.Rounded.Speed, ToolColors.Time, "Slow motion, fast forward or a speed ramp."),
    VOLUME("Volume", Icons.Rounded.VolumeUp, ToolColors.Time, "How loud this clip's own sound is, and voice styles."),
    FILTER("Filter", Icons.Rounded.AutoAwesome, ToolColors.Look, "Give the clip a colour look."),
    ADJUST("Adjust", Icons.Rounded.Tune, ToolColors.Look, "Brightness, contrast, colour and sharpness."),
    FADE("Fade", Icons.Rounded.Gradient, ToolColors.Look, "Fade the picture and sound in or out."),
    TEXT("Text", Icons.Rounded.TextFields, ToolColors.Create, "Add titles and words on your video."),
    CAPTIONS("Captions", Icons.Rounded.ClosedCaption, ToolColors.Create, "Turn speech into subtitles automatically."),
    PHOTO_ZOOM("Photo zoom", Icons.Rounded.ZoomIn, ToolColors.Look, "Slow zoom and pan over a photo."),
    TEMPLATES("Templates", Icons.Rounded.AutoAwesomeMosaic, ToolColors.Style, "Ready-made styles in one tap."),
    COVER("Cover", Icons.Rounded.Image, ToolColors.Finish, "Make a thumbnail picture for your video."),
    RATIO("Ratio", Icons.Rounded.AspectRatio, ToolColors.Style, "The shape of your video: Reels, YouTube, square…"),
    LAYER("Layers", Icons.Rounded.Layers, ToolColors.Create, "Put photos, videos or stickers on top."),
    AUDIO("Voice & sounds", Icons.Rounded.GraphicEq, ToolColors.Sound, "Record voice-overs and add sound clips."),
    MUSIC("Music", Icons.Rounded.MusicNote, ToolColors.Sound, "Background music, volume and beat sync."),
    BACKGROUND("Backdrop", Icons.Rounded.Wallpaper, ToolColors.Style, "What shows around clips that do not fill the frame."),
    STORE("Store", Icons.Rounded.Storefront, ToolColors.Style, "Plug-ins (effects, fonts by language, text presets), animated buttons, stickers, sounds and music."),
    RECORD("Record screen", Icons.Rounded.Videocam, ToolColors.Finish, "Record your phone screen into the video."),
    DRAW("Draw", Icons.Rounded.Brush, ToolColors.Create, "Draw on your video by hand."),
    EXPORT("Export", Icons.Rounded.IosShare, ToolColors.Cut, "Save the finished video to your phone."),
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

    // The in-app media screen (folders and thumbnails, like a gallery) is open.
    var browsing by remember { mutableStateOf(false) }
    // The in-app music screen (songs, albums, folders, free music) is open.
    var browsingMusic by remember { mutableStateOf(false) }
    // A new, empty project opens straight onto it.
    LaunchedEffect(project.id) { if (project.clips.isEmpty()) browsing = true }
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> vm.addMedia(uris) }
    val pickMediaFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> vm.addMedia(uris) }
    val openGallery: () -> Unit = {
        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }
    val pickAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.setMusic(uri) }
    val context = LocalContext.current
    val pickLayer = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) vm.addImageLayer(uri) }
    val pickVideoLayer = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) vm.addVideoLayer(uri) }
    val pickVideoSound = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) vm.addSoundFromVideo(uri) }
    val pickSound = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.addAudioClip(uri) }
    // Voice-over from the video: asks Replace / Keep both when one is already at the red line.
    var askReplaceVoice by remember { mutableStateOf(false) }
    var voiceSettings by remember { mutableStateOf(false) }
    val beginVoice: () -> Unit = {
        if (vm.voiceAtPlayhead()) askReplaceVoice = true else vm.startVoiceOver(replace = false)
    }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginVoice() else vm.message = "Microphone permission is needed to record a voice-over"
    }
    var editingTextId by remember { mutableStateOf<String?>(null) }
    // True while the full-screen "new text" editor is open.
    var composing by remember { mutableStateOf(false) }
    // The text whose words are being changed there; null while writing a new one.
    var composeTarget by remember { mutableStateOf<String?>(null) }
    // First time in the editor: a short animated tour of Ratio (once; Ratio has "Show me how").
    // It waits while a popup is open (black bars, frame shape, voice-over, export, typing a text),
    // so it plays only after the user has read and closed it; a popup that opens during the tour
    // stops it, and it plays again from the start afterwards.
    val popupOpen = vm.barsOffer != null || vm.aspectSuggestion != null || askReplaceVoice ||
        voiceSettings || composing || panel == Panel.EXPORT
    LaunchedEffect(popupOpen) { if (popupOpen) vm.holdRatioTour() }
    LaunchedEffect(browsing, panel, popupOpen, vm.settings.ratioTourSeen) {
        if (!vm.settings.ratioTourSeen && !browsing && panel == null && !popupOpen) {
            delay(900)
            vm.startRatioTour()
        }
    }
    // The text or picture selected on the preview (shown with a frame and handles).
    var previewPick by remember { mutableStateOf<String?>(null) }
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
        when {
            vm.isRecording -> vm.stopRecording()
            vm.voiceCountdown > 0 -> vm.cancelVoiceCountdown()
            granted -> beginVoice()
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val addMedia: () -> Unit = {
        panel = null
        browsingMusic = false
        browsing = true
    }
    val openMusic: () -> Unit = {
        panel = null
        browsing = false
        browsingMusic = true
    }
    val musicBrowser: @Composable (Modifier) -> Unit = { modifier ->
        MusicBrowser(
            vm = vm,
            usedUris = (project.audio.map { it.uri } + listOfNotNull(project.music?.uri)).toSet(),
            modifier = modifier,
            onClose = { browsingMusic = false },
            onFiles = {
                browsingMusic = false
                pickAudio.launch(arrayOf("audio/*"))
            },
        )
    }
    val mediaBrowser: @Composable (Modifier) -> Unit = { modifier ->
        MediaBrowser(
            vm = vm,
            usedCount = project.clips.groupingBy { it.uri }.eachCount(),
            modifier = modifier,
            onClose = { browsing = false },
            onGallery = openGallery,
            onFiles = { pickMediaFiles.launch(arrayOf("video/*", "image/*")) },
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
    // A text, layer, sound or the music picked on the timeline. Clips have their own selection.
    var selectedItem by remember { mutableStateOf<Selected?>(null) }
    LaunchedEffect(vm.selectedClipId) {
        if (vm.selectedClipId != null) selectedItem = null
    }

    BackHandler {
        when {
            panel != null -> panel = null
            selectedItem != null -> selectedItem = null
            vm.selectedClipId != null -> vm.selectedClipId = null
            else -> vm.closeProject()
        }
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val preview: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier) {
            PreviewArea(
                vm, project, Modifier.fillMaxSize(), onEditText = editText, onEditLayer = editLayer,
                pickedId = previewPick,
                onPick = { previewPick = it },
                onRetype = { id ->
                    composeTarget = id
                    composing = true
                },
            )
        }
    }

    val messageBar: @Composable () -> Unit = {
        // Right after a voice-over: Retake or Undo, for a few seconds.
        val justRecorded = vm.lastVoice
        LaunchedEffect(justRecorded) {
            if (justRecorded != null) {
                delay(8_000)
                if (vm.lastVoice == justRecorded) vm.lastVoice = null
            }
        }
        if (justRecorded != null) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${justRecorded.title} added", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vm.retakeVoice() }) { Text("Retake") }
                TextButton(onClick = { vm.undoVoice() }) { Text("Undo") }
                TextButton(onClick = { vm.lastVoice = null }) { Text("✕") }
            }
        }
    }

    val timeline: @Composable (Modifier) -> Unit = { modifier ->
        Timeline(
            vm, project, modifier,
            addMedia = addMedia,
            selectedId = selectedItem?.id,
            onSelect = { kind, id ->
                // Tapping a bar selects it (its actions show in the menu); tapping again clears it.
                val picked = Selected(kind, id)
                selectedItem = if (selectedItem == picked) null else picked
                if (selectedItem != null) vm.selectedClipId = null
                // A text opens its tool at once, so its options can be seen.
                if (kind == ItemKind.TEXT && selectedItem != null) editText(id)
            },
        )
    }

    val menu: @Composable (Modifier, Boolean) -> Unit = { modifier, showExport ->
        val item = selectedItem?.takeIf { it.existsIn(project) }
        if (item != null) {
            ItemMenu(
                vm, project, modifier, item,
                onBack = { selectedItem = null },
                onEdit = {
                    when (item.kind) {
                        ItemKind.TEXT -> editText(item.id)
                        ItemKind.LAYER, ItemKind.VIDEO -> editLayer(item.id)
                        ItemKind.SOUND -> panel = Panel.AUDIO
                        ItemKind.MUSIC -> panel = Panel.MUSIC
                    }
                },
            )
        } else EditorMenu(
            vm, project, modifier,
            showExport = showExport,
            addMedia = addMedia,
            replace = replace,
            recordVoice = record,
            openMusic = openMusic,
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
                // The tool closes first: in portrait it is a sheet that would sit on top of the
                // typing screen.
                newText = {
                    composeTarget = null
                    composing = true
                    panel = null
                },
                retype = { textId ->
                    composeTarget = textId
                    composing = true
                    panel = null
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
                pickVideo = {
                    pickVideoLayer.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    )
                },
                draw = { panel = Panel.DRAW },
            )
            Panel.AUDIO -> AudioPanel(
                vm, project,
                record = record,
                pickSound = { openMusic() },
                pickVideoSound = {
                    pickVideoSound.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                },
            )
            Panel.MUSIC -> MusicPanel(vm, project) { openMusic() }
            Panel.STORE -> StorePanel(vm)
            Panel.CAPTIONS -> CaptionsPanel(vm, project)
            Panel.PHOTO_ZOOM -> clip?.let { PhotoZoomPanel(vm, it) }
            Panel.TEMPLATES -> TemplatesPanel(vm, project) { panel = null }
            Panel.COVER -> CoverPanel(vm, project)
            Panel.RATIO -> RatioPanel(vm, project)
            Panel.RECORD -> RecordPanel(vm, onStarted = { panel = null })
            Panel.EXPORT -> Unit
        }
    }

    val open = panel
    val sidePanel = open != null && open != Panel.EXPORT && open != Panel.DRAW && open != Panel.STORE

    // Other places ask for a tool (a Store link in a tool, "Use" on a download).
    LaunchedEffect(vm.panelRequest) {
        vm.panelRequest?.let {
            if (it == Panel.TEXT) editingTextId = null
            panel = it
            vm.panelRequest = null
        }
    }
    // A tool that is opened has been seen: its "new" dot goes.
    LaunchedEffect(panel) { panel?.let { vm.newIn.remove(it) } }
    // A text sticker was added: open it so the words can be changed straight away.
    LaunchedEffect(vm.editTextRequest) {
        val id = vm.editTextRequest ?: return@LaunchedEffect
        vm.editTextRequest = null
        editingTextId = id
        previewPick = id
        panel = Panel.TEXT
    }
    // "Use" on something downloaded in the Store: go to the tool it is for.
    LaunchedEffect(vm.useRequest) {
        val use = vm.useRequest ?: return@LaunchedEffect
        vm.useRequest = null
        when (use) {
            is StoreUse.Fonts -> {
                val id = vm.addLanguageText(use.group)
                if (id.isNotEmpty()) {
                    vm.fontTabRequest = true
                    editingTextId = id
                    previewPick = id
                    panel = Panel.TEXT
                }
            }
            is StoreUse.Captions -> {
                vm.captionLanguageRequest = use.language
                panel = Panel.CAPTIONS
            }
            StoreUse.VocalSeparator -> {
                val clip = vm.selectedClip
                when {
                    project.music != null -> panel = Panel.MUSIC
                    clip != null && clip.type == ClipType.VIDEO && clip.hasAudio -> panel = Panel.VOLUME
                    project.audio.isNotEmpty() -> {
                        panel = Panel.AUDIO
                        vm.toast("Tap a sound below, then Split voice and music", ToastKind.INFO)
                    }
                    else -> {
                        panel = null
                        vm.toast(
                            "Add music (Music) or select a video clip with singing, then Split voice and music " +
                                "in its Volume.",
                            ToastKind.INFO,
                        )
                    }
                }
            }
        }
    }

    // A finished screen recording is put into the project as soon as it arrives.
    val finishedRecording = com.reelforge.editor.record.ScreenRecording.finished
    LaunchedEffect(finishedRecording) {
        if (finishedRecording != null) {
            com.reelforge.editor.record.ScreenRecording.finished = null
            vm.useScreenRecording(finishedRecording)
        }
    }
    // While recording, a small bar with the time, Pause and Stop floats near the top.
    if (com.reelforge.editor.record.ScreenRecording.busy) {
        androidx.compose.ui.window.Popup(
            alignment = if (landscape) Alignment.TopEnd else Alignment.TopCenter,
            offset = IntOffset(0, with(LocalDensity.current) { 36.dp.roundToPx() }),
        ) {
            Box(Modifier.padding(horizontal = 12.dp)) { RecordingBar() }
        }
    }
    val root = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .systemBarsPadding()
        .displayCutoutPadding()

    if (landscape) {
        Column(root) {
            // The media screen takes the place of the preview and menu; the timeline stays below.
            if (browsing) mediaBrowser(Modifier.weight(1f).fillMaxWidth())
            else if (browsingMusic) musicBrowser(Modifier.weight(1f).fillMaxWidth())
            else Row(Modifier.weight(1f)) {
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
                        ToolHeader(open.title, open.help, open.icon, open.color) {
                            IconButton(onClick = { panel = null }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close tool")
                            }
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
                    onClick = {
                        if (project.clips.isNotEmpty()) panel = Panel.EXPORT
                        else vm.toast("Add a video or photo first: tap Media", ToastKind.WARNING)
                    },
                    modifier = Modifier.padding(end = 8.dp),
                ) { Text("Export") }
            }
            if (browsing || browsingMusic) {
                if (browsing) mediaBrowser(Modifier.weight(1f).fillMaxWidth())
                else musicBrowser(Modifier.weight(1f).fillMaxWidth())
                messageBar()
                timeline(Modifier.fillMaxWidth())
            } else {
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
                    PlayTime(vm, project.durationMs)
                }
                timeline(Modifier.fillMaxWidth())
                if (open == Panel.TEXT) {
                    // Docked under the timeline, not a sheet over the preview, so the text can be
                    // seen and dragged while its options are changed.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        ToolHeader(open.title, open.help, open.icon, open.color) {
                            IconButton(onClick = { panel = null }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close tool")
                            }
                        }
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 16.dp)
                        ) {
                            panelBody(open)
                        }
                    }
                } else {
                    menu(Modifier.fillMaxWidth().heightIn(max = 190.dp), false)
                }
            }
        }
    }

    vm.barsOffer?.let { offer ->
        val same = offer.pictureShape == project.aspect
        CutDialog(
            icon = Icons.Rounded.Crop,
            color = ToolColors.Frame,
            onDismissRequest = { vm.barsOffer = null },
            title = { Text("This video has black bars") },
            text = {
                Text(
                    if (same) "The picture inside this video has black bars around it. Zoom in so the picture fills the frame?"
                    else "The picture inside this video is about ${offer.pictureShape.label}, with black bars " +
                        "around it, so it shows small. Change the frame to ${offer.pictureShape.label} and remove " +
                        "the bars? Or keep ${project.aspect.label} and zoom in (the sides of the picture are cut off)."
                )
            },
            confirmButton = {
                WideButton(onClick = { vm.applyBarsFix(offer, changeRatio = !same) }) {
                    Text(if (same) "Remove bars" else "Use ${ratioName(offer.pictureShape)}")
                }
            },
            dismissButton = {
                if (!same) {
                    WideOutlinedButton(onClick = { vm.applyBarsFix(offer, changeRatio = false) }) {
                        Text("Zoom in, keep ${project.aspect.label}")
                    }
                }
                TextButton(onClick = { vm.barsOffer = null }) { Text("Not now") }
            },
        )
    }

    vm.aspectSuggestion?.let { better ->
        CutDialog(
            icon = Icons.Rounded.AspectRatio,
            color = ToolColors.Style,
            onDismissRequest = { vm.aspectSuggestion = null },
            title = { Text("Change the frame to ${better.label}?") },
            text = {
                Text(
                    "Your video is ${better.label} but this project's frame is ${project.aspect.label}, so the " +
                        "video shows small with bars around it. You can change this any time with Ratio."
                )
            },
            confirmButton = {
                WideButton(onClick = { vm.setAspect(better) }) { Text("Use ${ratioName(better)}") }
            },
            dismissButton = {
                TextButton(onClick = { vm.aspectSuggestion = null }) { Text("Keep ${project.aspect.label}") }
            },
        )
    }

    if (askReplaceVoice) {
        CutDialog(
            icon = Icons.Rounded.Mic,
            color = ToolColors.Sound,
            onDismissRequest = { askReplaceVoice = false },
            title = { Text("A voice-over is already here") },
            text = { Text("Replace it where the new one goes, or keep both playing together?") },
            confirmButton = {
                WideButton(onClick = {
                    askReplaceVoice = false
                    vm.startVoiceOver(replace = true)
                }) { Text("Replace") }
            },
            dismissButton = {
                WideOutlinedButton(onClick = {
                    askReplaceVoice = false
                    vm.startVoiceOver(replace = false)
                }) { Text("Keep both") }
            },
        )
    }
    if (voiceSettings) {
        VoiceSettingsDialog(vm, project) { voiceSettings = false }
    }

    if (composing) {
        TextComposer(
            vm,
            existing = project.texts.firstOrNull { it.id == composeTarget },
            onClose = {
                composing = false
                composeTarget = null
            },
            onSaved = { id ->
                // A new text closes the tool so it is in view, selected, and can be dragged into
                // place. Changing the words of an existing one leaves the screen as it was.
                // The text's tool opens on it straight away, so its options are in view; the
                // preview stays visible so the text can still be dragged into place.
                panel = Panel.TEXT
                composing = false
                composeTarget = null
                editingTextId = id
                previewPick = id
            },
        )
    }

    if (open == Panel.EXPORT) {
        ExportDialog(vm, project) { panel = null }
    } else if (open == Panel.STORE) {
        StoreScreen(vm) { panel = null }
    } else if (open == Panel.DRAW) {
        DrawScreen(
            project = project,
            onCancel = { panel = null },
            onDone = { strokes ->
                vm.addDrawing(strokes)
                panel = null
            },
        )
    } else if (open != null && !landscape && open != Panel.TEXT) {
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

/** "0:12 / 1:30". Its own composable so only this text redraws while the video plays. */
@Composable
private fun PlayTime(vm: EditorViewModel, totalMs: Long) {
    Text(
        "${MediaUtils.formatTime(vm.playheadMs)} / ${MediaUtils.formatTime(totalMs)}",
        style = MaterialTheme.typography.labelMedium,
    )
}

/** The time on the playhead line; only this redraws while the video plays. */
@Composable
private fun PlayheadPill(
    vm: EditorViewModel,
    modifier: Modifier,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
) {
    Text(MediaUtils.formatTime(vm.playheadMs), modifier = modifier, color = color, fontSize = fontSize)
}

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
    recordVoice: () -> Unit,
    openMusic: () -> Unit,
    openPanel: (Panel) -> Unit,
) {
    val clip = vm.selectedClip
    val hasClips = project.clips.isNotEmpty()
    // Whole seconds only, so the menu redraws once a second while recording rather than on
    // every playback tick.
    val voiceSeconds by remember(vm) {
        derivedStateOf { ((vm.playheadMs - vm.recordingFromMs) / 1000).coerceAtLeast(0) }
    }
    val screenSeconds by remember {
        derivedStateOf { com.reelforge.editor.record.ScreenRecording.elapsedMs / 1000 }
    }

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
                    vm.clipName(clip).ifBlank { if (clip.type == ClipType.VIDEO) "Video clip" else "Photo" } +
                        " · " + MediaUtils.formatTime(clip.durationMs),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
                    Button(onClick = {
                        if (hasClips) openPanel(Panel.EXPORT)
                        else vm.toast("Add a video or photo first: tap Media", ToastKind.WARNING)
                    }) { Text("Export") }
                }
            }
        }
        if (clip != null) {
            Text(
                if (vm.canCutSelectedClip) {
                    "Trim left removes what is before the red line. Trim right removes what is after it. " +
                        "Drag or pinch the picture above to move or resize it."
                } else {
                    "To trim or split, or to move the picture, move the red line onto this clip."
                },
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val menuScroll = rememberScrollState()
        var menuTop by remember { mutableFloatStateOf(0f) }
        var ratioTileY by remember { mutableFloatStateOf(-1f) }
        LaunchedEffect(vm.tourStep) {
            if (vm.tourStep == EditorViewModel.TourStep.FIND && ratioTileY >= 0f) {
                val target = menuScroll.value + (ratioTileY - menuTop - 30f).toInt()
                menuScroll.animateScrollTo(target.coerceIn(0, menuScroll.maxValue), tween(700))
            }
        }
        Box(Modifier.fillMaxWidth().onGloballyPositioned { menuTop = it.positionInRoot().y }) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(menuScroll)
                .padding(horizontal = 8.dp)
                // Room under the last row, so it can scroll clear of the "More tools" pill.
                .padding(bottom = 36.dp),
        ) {
            val I = Icons.Rounded
            val addFirst = "Add a video or photo first: tap Media"
            if (clip == null) {
                ToolGroup("Create", ToolColors.Create) {
                    ToolTile("Media", I.AddPhotoAlternate, ToolColors.Create, active = !hasClips) { addMedia() }
                    ToolTile("Text", I.TextFields, ToolColors.Create, enabled = hasClips, whyOff = addFirst, dot = vm.newIn[Panel.TEXT] == true) { openPanel(Panel.TEXT) }
                    val captions = vm.captionWork != null
                    ToolTile(
                        if (captions) "Captions ${(vm.captionProgress * 100).toInt()}%" else "Captions",
                        I.ClosedCaption, ToolColors.Create, enabled = hasClips, active = captions, whyOff = addFirst,
                        dot = vm.newIn[Panel.CAPTIONS] == true,
                    ) { openPanel(Panel.CAPTIONS) }
                    ToolTile("Layers", I.Layers, ToolColors.Create, enabled = hasClips, whyOff = addFirst) { openPanel(Panel.LAYER) }
                    ToolTile("Draw", I.Brush, ToolColors.Create, enabled = hasClips, whyOff = addFirst) { openPanel(Panel.DRAW) }
                }
                ToolGroup("Sound", ToolColors.Sound) {
                    ToolTile(
                        when {
                            vm.voiceCountdown > 0 -> "In ${vm.voiceCountdown}… tap to stop"
                            vm.isRecording -> "Stop " + MediaUtils.formatTime(voiceSeconds * 1000).substringBefore('.')
                            else -> "Record voice"
                        },
                        if (vm.isRecording) I.Stop else I.Mic,
                        ToolColors.Sound,
                        enabled = hasClips,
                        active = vm.isRecording || vm.voiceCountdown > 0,
                        whyOff = addFirst,
                    ) { recordVoice() }
                    ToolTile("Voice & sounds", I.GraphicEq, ToolColors.Sound, enabled = hasClips, whyOff = addFirst, dot = vm.newIn[Panel.AUDIO] == true) { openPanel(Panel.AUDIO) }
                    // Opens the music screen; with music already added, its settings are one tap away there.
                    ToolTile("Music", I.MusicNote, ToolColors.Sound, dot = vm.newIn[Panel.MUSIC] == true) {
                        if (project.music == null) openMusic() else openPanel(Panel.MUSIC)
                    }
                }
                ToolGroup("Style", ToolColors.Style) {
                    ToolTile("Templates", I.AutoAwesomeMosaic, ToolColors.Style, enabled = hasClips, whyOff = addFirst) { openPanel(Panel.TEMPLATES) }
                    val touring = vm.tourStep != EditorViewModel.TourStep.NONE
                    ToolTile(
                        "Ratio ${(vm.tourAspect ?: project.aspect).label}", I.AspectRatio, ToolColors.Style,
                        highlight = touring,
                        tapping = vm.tourStep == EditorViewModel.TourStep.TAP,
                        modifier = Modifier.onGloballyPositioned { ratioTileY = it.positionInRoot().y },
                    ) {
                        vm.stopRatioTour()
                        openPanel(Panel.RATIO)
                    }
                    ToolTile("Backdrop", I.Wallpaper, ToolColors.Style, enabled = hasClips, whyOff = addFirst) { openPanel(Panel.BACKGROUND) }
                    ToolTile("Store", I.Storefront, ToolColors.Style, enabled = hasClips, whyOff = addFirst) { openPanel(Panel.STORE) }
                }
                ToolGroup("Finish", ToolColors.Finish) {
                    ToolTile("Cover", I.Image, ToolColors.Finish, enabled = hasClips, whyOff = addFirst) { openPanel(Panel.COVER) }
                    // Shows its own time while recording.
                    val screenRec = com.reelforge.editor.record.ScreenRecording
                    ToolTile(
                        when (screenRec.status) {
                            com.reelforge.editor.record.ScreenRecording.Status.IDLE -> "Record screen"
                            com.reelforge.editor.record.ScreenRecording.Status.COUNTDOWN -> "Starts in ${screenRec.countdown}…"
                            com.reelforge.editor.record.ScreenRecording.Status.SAVING -> "Saving…"
                            else -> "Stop " + MediaUtils.formatTime(screenSeconds * 1000).substringBefore('.')
                        },
                        if (screenRec.busy) I.Stop else I.Videocam,
                        ToolColors.Finish,
                        active = screenRec.busy,
                    ) { openPanel(Panel.RECORD) }
                    ToolTile("Settings", I.Settings, ToolColors.Finish) { vm.showSettings = true }
                }
            } else {
                val id = clip.id
                val isVideo = clip.type == ClipType.VIDEO
                // Cuts at the red line. They light up when the line is on this clip.
                val canCut = vm.canCutSelectedClip
                ToolGroup("Cut at the red line", ToolColors.Cut) {
                    CutTile("Trim left", CutKind.LEFT, canCut) { vm.trimLeftOfPlayhead() }
                    CutTile("Split", CutKind.SPLIT, canCut) { vm.splitAtPlayhead() }
                    CutTile("Trim right", CutKind.RIGHT, canCut) { vm.trimRightOfPlayhead() }
                    if (isVideo) CutTile("Split + freeze", CutKind.FREEZE, canCut) { vm.freezeFrame() }
                    ToolTile("Fine trim", I.ContentCut, ToolColors.Cut) { openPanel(Panel.TRIM) }
                }
                ToolGroup("Time & sound", ToolColors.Time) {
                    if (isVideo) ToolTile("Speed", I.Speed, ToolColors.Time) { openPanel(Panel.SPEED) }
                    if (isVideo) {
                        // Shows its progress on the tile; tap again to stop.
                        val reversing = vm.reverseWork
                        ToolTile(reversing ?: "Reverse", I.FastRewind, ToolColors.Time, active = reversing != null) {
                            if (reversing != null) vm.cancelReverse() else vm.reverseClip(id)
                        }
                    }
                    if (isVideo) ToolTile("Volume", I.VolumeUp, ToolColors.Time) { openPanel(Panel.VOLUME) }
                    if (isVideo && vm.canExtractSound(clip) && (!clip.muted || vm.soundWork != null)) {
                        val working = vm.soundWork
                        ToolTile(working ?: "Extract sound", I.LibraryMusic, ToolColors.Time, active = working != null) {
                            if (working != null) vm.cancelSoundWork() else vm.extractSound(id)
                        }
                    }
                    ToolTile("Replace", I.SwapHoriz, ToolColors.Time) { replace(id) }
                }
                ToolGroup("Look", ToolColors.Look) {
                    ToolTile("Filter", I.AutoAwesome, ToolColors.Look) { openPanel(Panel.FILTER) }
                    ToolTile("Adjust", I.Tune, ToolColors.Look) { openPanel(Panel.ADJUST) }
                    ToolTile("Fade", I.Gradient, ToolColors.Look) { openPanel(Panel.FADE) }
                    if (!isVideo) ToolTile("Photo zoom", I.ZoomIn, ToolColors.Look) { openPanel(Panel.PHOTO_ZOOM) }
                    if (!isVideo && vm.superResolutionReady) {
                        val working = vm.enhanceWork
                        ToolTile(working ?: "Make sharper", I.AutoFixHigh, ToolColors.Look, active = working != null) {
                            if (working != null) vm.cancelSharpen() else vm.sharpenPhotos(listOf(id), emptyList())
                        }
                    }
                }
                ToolGroup("Frame", ToolColors.Frame) {
                    ToolTile(if (clip.fill) "Fit" else "Fill", if (clip.fill) I.FitScreen else I.Fullscreen, ToolColors.Frame) {
                        vm.updateClip(id) { it.copy(fill = !it.fill) }
                        vm.toast(if (clip.fill) "Whole picture fits inside the frame" else "Picture fills the frame", undo = true)
                    }
                    if (isVideo) ToolTile("Remove black bars", I.Crop, ToolColors.Frame) { vm.removeBlackBars(id) }
                    if (com.reelforge.editor.engine.EffectFactory.isFramed(clip)) {
                        ToolTile("Reset position", I.CenterFocusStrong, ToolColors.Frame) { vm.resetFraming(id) }
                    }
                    ToolTile("Rotate", I.RotateRight, ToolColors.Frame) {
                        vm.updateClip(id) { it.copy(rotation = (it.rotation + 90) % 360) }
                        vm.toast("Turned to ${(clip.rotation + 90) % 360}°", undo = true)
                    }
                    ToolTile("Mirror", I.Flip, ToolColors.Frame) {
                        vm.updateClip(id) { it.copy(flipH = !it.flipH) }
                        vm.toast(if (clip.flipH) "Mirror off" else "Mirrored left to right", undo = true)
                    }
                }
                ToolGroup("Arrange", ToolColors.Arrange) {
                    ToolTile("Copy", I.ContentCopy, ToolColors.Arrange) { vm.duplicateClip(id) }
                    ToolTile("Move left", I.KeyboardArrowLeft, ToolColors.Arrange) { vm.moveClip(id, -1) }
                    ToolTile("Move right", I.KeyboardArrowRight, ToolColors.Arrange) { vm.moveClip(id, 1) }
                }
            }
        }
        MoreBelowHint(menuScroll)
        }
    }
}

/** A fade and "More tools" arrow at the bottom while more tools are hidden below; tap to scroll. */
@Composable
private fun BoxScope.MoreBelowHint(state: ScrollState) {
    if (!state.canScrollForward) return
    val scope = rememberCoroutineScope()
    val bg = MaterialTheme.colorScheme.surface
    // The fade only draws (taps go through to the tiles); just the small pill takes taps.
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(18.dp)
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(bg.copy(alpha = 0f), bg)))
    )
    Surface(
        onClick = { scope.launch { state.animateScrollTo(state.value + state.viewportSize * 2 / 3) } },
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 4.dp,
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 4.dp),
    ) {
        run {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("More tools", style = MaterialTheme.typography.labelSmall)
                Icon(Icons.Rounded.KeyboardArrowDown, null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** A titled group of tool tiles that wrap onto new rows. */
@Composable
private fun ToolGroup(title: String, color: Color, tiles: @Composable () -> Unit) {
    ToolSection(title, color)
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { tiles() }
}

/** A trim or split tile, drawn with its own cut picture. */
@Composable
private fun CutTile(label: String, kind: CutKind, enabled: Boolean, onClick: () -> Unit) {
    ToolTile(
        label, Icons.Rounded.ContentCut, ToolColors.Cut, enabled = enabled,
        whyOff = "Move the red line onto this clip (away from its ends) to cut it",
        iconContent = { CutIcon(kind) },
        onClick = onClick,
    )
}

/** The kinds of thing, other than clips, that can be selected on the timeline. */
enum class ItemKind { TEXT, LAYER, VIDEO, SOUND, MUSIC }

private const val MUSIC_ID = "background-music"

private data class Selected(val kind: ItemKind, val id: String) {
    fun existsIn(project: Project): Boolean = when (kind) {
        ItemKind.TEXT -> project.texts.any { it.id == id }
        ItemKind.LAYER -> project.layers.any { it.id == id }
        ItemKind.VIDEO -> project.videoLayers.any { it.id == id }
        ItemKind.SOUND -> project.audio.any { it.id == id }
        ItemKind.MUSIC -> project.music != null
    }
}

/**
 * The menu for a text, layer, sound or the music selected on the timeline: cuts at the red line,
 * Edit for everything else, and Delete in the corner.
 */
@Composable
private fun ItemMenu(
    vm: EditorViewModel,
    project: Project,
    modifier: Modifier,
    item: Selected,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    val id = item.id
    // Title and the stretch of the timeline the item covers.
    val (title, start, end) = when (item.kind) {
        ItemKind.TEXT -> project.texts.first { it.id == id }.let {
            Triple("Text · " + it.text.replace("\n", " ").take(16), it.startMs, it.endMs)
        }
        ItemKind.LAYER -> project.layers.first { it.id == id }.let {
            Triple("Image layer", it.startMs, it.endMs)
        }
        ItemKind.VIDEO -> project.videoLayers.first { it.id == id }.let {
            Triple("Video layer", it.startMs, it.startMs + it.durationMs)
        }
        ItemKind.SOUND -> project.audio.first { it.id == id }.let {
            Triple((if (it.voice) "Voice-over · " else "Sound · ") + it.title.take(16), it.startMs, it.startMs + it.durationMs)
        }
        ItemKind.MUSIC -> project.music!!.let {
            Triple("Music · " + it.title.take(16), it.startMs, it.windowEnd(project.durationMs))
        }
    }
    val cut: (Cut) -> Unit = { which ->
        when (item.kind) {
            ItemKind.TEXT -> vm.cutText(id, which)
            ItemKind.LAYER -> vm.cutLayer(id, which)
            ItemKind.VIDEO -> vm.cutVideoLayer(id, which)
            ItemKind.SOUND -> vm.cutAudio(id, which)
            ItemKind.MUSIC -> vm.cutMusic(which)
        }
    }
    val delete: () -> Unit = {
        when (item.kind) {
            ItemKind.TEXT -> vm.deleteText(id)
            ItemKind.LAYER -> vm.deleteLayer(id)
            ItemKind.VIDEO -> vm.deleteVideoLayer(id)
            ItemKind.SOUND -> vm.deleteAudioClip(id)
            ItemKind.MUSIC -> vm.removeMusic()
        }
    }
    val canCut = vm.canCut(start, end)

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to main menu")
            }
            Text(
                title,
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = {
                delete()
                onBack()
            }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete item")
            }
        }
        Text(
            if (canCut) {
                "Trim left removes what is before the red line. Trim right removes what is after it."
            } else {
                "To trim, move the red line onto this bar. Long-press the bar to slide it."
            },
            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp)
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CutTile("Trim left", CutKind.LEFT, canCut) { cut(Cut.LEFT) }
            if (item.kind != ItemKind.MUSIC) CutTile("Split", CutKind.SPLIT, canCut) { cut(Cut.SPLIT) }
            CutTile("Trim right", CutKind.RIGHT, canCut) { cut(Cut.RIGHT) }
            ToolTile("Edit", Icons.Rounded.Edit, ToolColors.Time) { onEdit() }
            if (item.kind == ItemKind.TEXT) ToolTile("Copy", Icons.Rounded.ContentCopy, ToolColors.Arrange) { vm.duplicateText(id) }
            ToolTile("Delete", Icons.Rounded.Delete, ToolColors.Cut) {
                delete()
                onBack()
            }
        }
    }
}

// --------------------------------------------------------------------- timeline

private val RULER_HEIGHT = 22.dp
private val TRACK_HEIGHT = 56.dp
/** Height of one line of bars (texts, stickers, sounds…) under the video track. */
private val ROW_HEIGHT = 36.dp
/** The bright yellow frame of a selected bar (and its handles), like KineMaster. */
private val SELECTED_YELLOW = Color(0xFFFFC107)

/** The colour and icon of each kind of bar, so they are easy to tell apart. */
private object BarKind {
    val TEXT = Color(0xFFFFC107)
    val STICKER = Color(0xFFFF8A65)
    val PHOTO = Color(0xFF26A69A)
    val VIDEO = Color(0xFF42A5F5)
    val VOICE = Color(0xFFEF5350)
    val SOUND = Color(0xFF66BB6A)
    val EXTRACTED = Color(0xFF26C6DA)
    val MUSIC = Color(0xFFAB47BC)
}
private val TILE_WIDTH = 64.dp

private class Bar(
    val id: String,
    val label: String,
    val startMs: Long,
    val endMs: Long,
    /** The kind's colour (stripe, icon) and icon; null = the row's colour and no icon. */
    val color: Color? = null,
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
)

/** The bars split into lines so overlapping ones never cover each other. */
private fun laned(bars: List<Bar>): List<List<Bar>> {
    if (bars.isEmpty()) return emptyList()
    val lane = ProjectOps.lanes(bars.map { it.startMs to it.endMs })
    return (0..lane.max()).map { l -> bars.filterIndexed { i, _ -> lane[i] == l } }
}

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
    selectedId: String?,
    onSelect: (ItemKind, String) -> Unit,
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

    // Dragging the timeline pauses playback and scrubs. Finger and fling are watched together, so
    // a quick tap or flick can never leave "the user is scrolling" switched on (which stopped the
    // timeline following playback).
    LaunchedEffect(Unit) {
        snapshotFlow { dragged to scroll.isScrollInProgress }.collect { (finger, moving) ->
            if (finger) {
                if (!userScrolling) {
                    userScrolling = true
                    vm.pause()
                }
            } else if (!moving && userScrolling) {
                userScrolling = false
                scroll.scrollTo((vm.playheadMs * latestPxPerMs).roundToInt())
            }
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
    // The playhead is watched inside the effect, so playback scrolls the timeline without
    // rebuilding it 25 times a second.
    LaunchedEffect(pxPerMs, contentPx, zoomAnchorMs, scroll.maxValue) {
        val anchor = zoomAnchorMs
        if (anchor != null) {
            scroll.scrollTo((anchor * pxPerMs).roundToInt())
            return@LaunchedEffect
        }
        snapshotFlow { vm.playheadMs }.collect { ms ->
            // Safety net: while the video plays and nobody touches the timeline, it always follows.
            if (userScrolling && vm.isPlaying && !dragged && !scroll.isScrollInProgress) userScrolling = false
            if (!userScrolling) scroll.scrollTo((ms * pxPerMs).roundToInt())
        }
    }

    val I = Icons.Rounded
    val textBars = project.texts.map {
        Bar(it.id, it.text.replace("\n", " ").ifBlank { "Text" }, it.startMs, it.endMs, BarKind.TEXT, I.TextFields)
    }
    val layerBars = project.layers.mapIndexed { i, l ->
        val animated = com.reelforge.editor.engine.MovingArt.of(l.button)
        val (name, color, icon) = when {
            animated?.fullFrame == true -> Triple(animated.title, BarKind.STICKER, I.Celebration)
            animated != null -> Triple(animated.title, BarKind.STICKER, I.AutoAwesome)
            "/stickers/" in l.uri || "social_" in l.uri -> Triple("Sticker", BarKind.STICKER, I.EmojiEmotions)
            "/drawings/" in l.uri -> Triple("Drawing", BarKind.PHOTO, I.Brush)
            else -> Triple("Photo ${i + 1}", BarKind.PHOTO, I.Image)
        }
        Bar(l.id, name, l.startMs, l.endMs, color, icon)
    }
    val audioBars = project.audio.map {
        val (color, icon) = when {
            it.voice -> BarKind.VOICE to I.Mic
            it.duck || it.title.startsWith("Sound:") -> BarKind.EXTRACTED to I.LibraryMusic
            else -> BarKind.SOUND to I.GraphicEq
        }
        Bar(it.id, it.title, it.startMs, it.startMs + it.durationMs, color, icon)
    } +
        // The voice-over being recorded grows on the timeline as you speak.
        (if (vm.isRecording) listOf(
            Bar("recording", "● REC", vm.recordingFromMs, maxOf(vm.playheadMs, vm.recordingFromMs + 100), BarKind.VOICE, I.Mic)
        ) else emptyList())
    val videoBars = project.videoLayers.mapIndexed { i, v ->
        Bar(v.id, "Video ${i + 1}", v.startMs, v.startMs + v.durationMs, BarKind.VIDEO, I.VideoLibrary)
    }
    val textLanes = laned(textBars)
    val layerLanes = laned(layerBars)
    val audioLanes = laned(audioBars)
    val videoLanes = laned(videoBars)
    val rows = textLanes.size + layerLanes.size + audioLanes.size + videoLanes.size +
        (if (project.music != null) 1 else 0)
    val height = RULER_HEIGHT + TRACK_HEIGHT + ROW_HEIGHT * rows + 10.dp
    val configuration = LocalConfiguration.current
    val sideways = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val cap = configuration.screenHeightDp.dp * (if (sideways) 0.42f else 0.34f)
    val rowsScroll = rememberScrollState()

    BoxWithConstraints(
        modifier
            .height(minOf(height, maxOf(cap, RULER_HEIGHT + TRACK_HEIGHT + ROW_HEIGHT * 2)))
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
        // How close (in time) a dragged edge or bar must be to a point to snap to it.
        val snapMs = if (vm.settings.snap) (with(density) { 10.dp.toPx() } / pxPerMs).toLong() else 0L

        Row(Modifier.fillMaxHeight().horizontalScroll(scroll)) {
            Spacer(Modifier.width(half))
            Column(Modifier.width(contentWidth).verticalScroll(rowsScroll)) {
                val beats = remember(project.music, total) { com.reelforge.editor.model.CreativeOps.beatTimes(project) }
                Ruler(total, pxPerMs, visibleFrom, visibleTo, Modifier.width(contentWidth).height(RULER_HEIGHT), beats)

                Row(Modifier.height(TRACK_HEIGHT)) {
                    var startPx = 0f
                    project.clips.forEach { clip ->
                        val clipStartPx = startPx
                        startPx += clip.durationMs * pxPerMs
                        key(clip.id) {
                            ClipBlock(
                                vm, clip, clipStartPx, pxPerMs, tilePx, visibleFrom, visibleTo,
                                selected = clip.id == vm.selectedClipId,
                                snapMs = snapMs,
                                onReorder = { dragPx ->
                                    // Drop position: count the other clips whose middle is to
                                    // the left of where this clip's middle was let go.
                                    val middle = clipStartPx + clip.durationMs * pxPerMs / 2 + dragPx
                                    var edge = 0f
                                    var target = 0
                                    project.clips.forEach { other ->
                                        val width = other.durationMs * pxPerMs
                                        if (other.id != clip.id && edge + width / 2 < middle) target++
                                        edge += width
                                    }
                                    vm.moveClipTo(clip.id, target)
                                },
                            )
                        }
                    }
                }

                // A dropped bar snaps to the playhead and to the edges of clips and other bars.
                val dropAt: (id: String, startMs: Long, lengthMs: Long, deltaMs: Long) -> Long =
                    { id, startMs, lengthMs, deltaMs ->
                        val points = ProjectOps.snapPoints(project, excludeId = id) + vm.playheadMs
                        ProjectOps.snapStart(startMs + deltaMs, lengthMs, points, snapMs)
                    }
                textLanes.forEach { lane ->
                    BarRow(
                        lane, pxPerMs, BarKind.TEXT, selectedId, { onSelect(ItemKind.TEXT, it) },
                        limitMs = total,
                        onResize = { id, atStart, deltaMs -> vm.resizeBar(ItemKind.TEXT, id, atStart, deltaMs, snapMs) },
                    ) { id, deltaMs ->
                        vm.updateText(id) {
                            val start = dropAt(id, it.startMs, it.endMs - it.startMs, deltaMs)
                            it.copy(startMs = start, endMs = start + (it.endMs - it.startMs))
                        }
                    }
                }
                videoLanes.forEach { lane ->
                    BarRow(
                        lane, pxPerMs, BarKind.VIDEO, selectedId, { onSelect(ItemKind.VIDEO, it) },
                        limitMs = total,
                        onResize = { id, atStart, deltaMs -> vm.resizeBar(ItemKind.VIDEO, id, atStart, deltaMs, snapMs) },
                    ) { id, deltaMs ->
                        vm.updateVideoLayer(id) {
                            it.copy(startMs = dropAt(id, it.startMs, it.durationMs, deltaMs))
                        }
                    }
                }
                layerLanes.forEach { lane ->
                    BarRow(
                        lane, pxPerMs, BarKind.PHOTO, selectedId, { onSelect(ItemKind.LAYER, it) },
                        limitMs = total,
                        onResize = { id, atStart, deltaMs -> vm.resizeBar(ItemKind.LAYER, id, atStart, deltaMs, snapMs) },
                    ) { id, deltaMs ->
                        vm.updateLayer(id) {
                            val start = dropAt(id, it.startMs, it.endMs - it.startMs, deltaMs)
                            it.copy(startMs = start, endMs = start + (it.endMs - it.startMs))
                        }
                    }
                }
                audioLanes.forEach { lane ->
                    BarRow(
                        lane, pxPerMs, BarKind.SOUND, selectedId, { onSelect(ItemKind.SOUND, it) },
                        limitMs = total,
                        onResize = { id, atStart, deltaMs -> vm.resizeBar(ItemKind.SOUND, id, atStart, deltaMs, snapMs) },
                    ) { id, deltaMs ->
                        vm.updateAudioClip(id) { it.copy(startMs = dropAt(id, it.startMs, it.durationMs, deltaMs)) }
                    }
                }
                project.music?.let { music ->
                    // The solid bar is the part that is heard with the video. When the music file is
                    // longer than the video, the rest of it is shown faded past the end of the video.
                    val heardEnd = music.windowEnd(total)
                    val length = if (music.durationMs > 0) "  ${MediaUtils.formatTime(music.durationMs)}" else ""
                    val bar = Bar(MUSIC_ID, "${music.title}$length", music.startMs, heardEnd, BarKind.MUSIC, Icons.Rounded.MusicNote)
                    val rest = Modifier.ghostBar(
                        startPx = heardEnd * pxPerMs,
                        widthPx = (music.fileEnd() - heardEnd).coerceAtLeast(0) * pxPerMs,
                        color = BarKind.MUSIC.copy(alpha = 0.25f),
                    )
                    BarRow(
                        listOf(bar), pxPerMs, BarKind.MUSIC, selectedId, { onSelect(ItemKind.MUSIC, it) },
                        rowModifier = rest,
                        onResize = { id, atStart, deltaMs -> vm.resizeBar(ItemKind.MUSIC, id, atStart, deltaMs, snapMs) },
                    ) { _, deltaMs ->
                        vm.shiftMusic(deltaMs)
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

        // A scrollbar on the right while there are more lines of bars than fit.
        if (rowsScroll.maxValue > 0) {
            val viewport = rowsScroll.viewportSize.toFloat().coerceAtLeast(1f)
            val content = viewport + rowsScroll.maxValue
            val thumbPx = (viewport * viewport / content).coerceAtLeast(with(density) { 24.dp.toPx() })
            val thumbY = (viewport - thumbPx) * rowsScroll.value / rowsScroll.maxValue
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 2.dp)
                    .offset { IntOffset(0, thumbY.roundToInt()) }
                    .width(4.dp)
                    .height(with(density) { thumbPx.toDp() })
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            )
        }

        // Fixed playhead with the current time; total length at the right.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .width(2.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary)
        )
        PlayheadPill(
            vm,
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
private fun Ruler(
    totalMs: Long,
    pxPerMs: Float,
    visibleFrom: Float,
    visibleTo: Float,
    modifier: Modifier,
    beats: List<Long> = emptyList(),
) {
    val beatColor = Color(0xFFFFC107)
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
        // Beats of the music, as small amber marks along the bottom.
        for (b in beats) {
            val x = b * pxPerMs
            if (x < visibleFrom - 10 || x > visibleTo + 10) continue
            drawLine(beatColor, Offset(x, size.height * 0.7f), Offset(x, size.height), strokeWidth = 3f)
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
    snapMs: Long,
    onReorder: (dragPx: Float) -> Unit,
) {
    val density = LocalDensity.current
    val widthPx = clip.durationMs * pxPerMs
    // A dragged edge that lands near the playhead snaps onto it.
    val startMs = (startPx / pxPerMs).toLong()
    val snapEdge: (edgeMs: Long, deltaMs: Long) -> Long = { edgeMs, deltaMs ->
        ProjectOps.snap(edgeMs + deltaMs, listOf(vm.playheadMs), snapMs) - edgeMs
    }
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
    // Long-press and drag a clip sideways to move it to another place in the video.
    var reorderDrag by remember { mutableFloatStateOf(0f) }
    val drop by rememberUpdatedState(onReorder)
    Box(
        Modifier
            .width(with(density) { widthPx.toDp() })
            .fillMaxHeight()
            .zIndex(if (reorderDrag != 0f) 2f else if (selected) 1f else 0f)
            .offset { IntOffset(reorderDrag.roundToInt(), 0) }
            .alpha(if (reorderDrag != 0f) 0.75f else 1f)
            .pointerInput(clip.id) {
                detectDragGesturesAfterLongPress(
                    onDragEnd = {
                        drop(reorderDrag)
                        reorderDrag = 0f
                    },
                    onDragCancel = { reorderDrag = 0f },
                ) { change, amount ->
                    change.consume()
                    reorderDrag += amount.x
                }
            }
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
        // A header strip with the clip's kind, speed and file name, so each scene can be told apart.
        LaunchedEffect(clip.uri) { vm.requestClipName(clip) }
        if (widthPx > with(density) { 28.dp.toPx() }) {
            val name = vm.clipName(clip)
            val speed = clip.effectiveSpeed
            val speedText = if (speed % 1f == 0f) "${speed.toInt()}x" else "${(speed * 100).roundToInt() / 100f}x"
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(horizontal = 1.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.75f), Color.Black.copy(alpha = 0.35f)),
                        ),
                        RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp),
                    )
                    .padding(start = 6.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(14.dp).background(Color.White, RoundedCornerShape(3.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (isVideo) "▶" else "▣", color = Color.Black, fontSize = 8.sp, lineHeight = 8.sp)
                }
                Text(
                    (if (isVideo) "$speedText " else "") + name,
                    Modifier.padding(start = 5.dp),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected && widthPx > with(density) { 64.dp.toPx() }) {
            TrimHandle(atStart = true) { dragPx ->
                vm.trimClipStart(clip.id, snapEdge(startMs, (dragPx / pxPerMs).toLong()))
            }
            TrimHandle(atStart = false) { dragPx ->
                vm.trimClipEnd(clip.id, snapEdge(startMs + clip.durationMs, (dragPx / pxPerMs).toLong()))
            }
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
 * A row of bars (text, layers or sounds). Tap a bar to select it; long-press and drag to move it
 * in time. A selected bar has handles on both ends: drag them to make it longer or shorter.
 * The part of a bar after [limitMs] (the end of the video) is shown faded, as it is not seen.
 */
@Composable
private fun BarRow(
    bars: List<Bar>,
    pxPerMs: Float,
    color: Color,
    selectedId: String?,
    onTap: (String) -> Unit,
    rowModifier: Modifier = Modifier,
    limitMs: Long? = null,
    onResize: ((id: String, atStart: Boolean, deltaMs: Long) -> Unit)? = null,
    rowHeight: androidx.compose.ui.unit.Dp = ROW_HEIGHT,
    onMove: (id: String, deltaMs: Long) -> Unit,
) {
    val density = LocalDensity.current
    val move by rememberUpdatedState(onMove)
    val resize by rememberUpdatedState(onResize)
    val scale by rememberUpdatedState(pxPerMs)
    val handleWidth = 18.dp
    val handlePx = with(density) { handleWidth.toPx() }
    val minPx = with(density) { 14.dp.toPx() }
    Box(Modifier.fillMaxWidth().height(rowHeight).then(rowModifier)) {
        bars.forEach { bar ->
            key(bar.id) {
                var drag by remember { mutableFloatStateOf(0f) }
                var leftDrag by remember { mutableFloatStateOf(0f) }
                var rightDrag by remember { mutableFloatStateOf(0f) }
                val selected = bar.id == selectedId
                val baseStartPx = bar.startMs * pxPerMs
                val baseWidthPx = (bar.endMs - bar.startMs) * pxPerMs
                val startPx = baseStartPx + drag + leftDrag
                val widthPx = maxOf(baseWidthPx - leftDrag + rightDrag, minPx)
                val resizing = leftDrag != 0f || rightDrag != 0f
                // While an end is dragged, the bar shows where it starts and ends and how long it is.
                val shownStart = bar.startMs + (leftDrag / pxPerMs).toLong()
                val shownEnd = bar.endMs + (rightDrag / pxPerMs).toLong()
                val label = if (resizing) {
                    MediaUtils.formatTime(shownStart.coerceAtLeast(0)) + " → " + MediaUtils.formatTime(shownEnd) +
                        " · " + MediaUtils.formatTime((shownEnd - shownStart).coerceAtLeast(0))
                } else bar.label
                val fadeFromPx = limitMs?.let { (it - bar.startMs) * pxPerMs - leftDrag }
                Box(
                    Modifier
                        .offset { IntOffset(startPx.roundToInt(), 0) }
                        .width(with(density) { widthPx.toDp() })
                        .fillMaxHeight()
                        .padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(6.dp))
                        // Dark bar with the kind's colour mixed in and a stripe on the left, so white
                        // words read clearly (colours follow the app's theme).
                        .background(
                            androidx.compose.ui.graphics.lerp(
                                MaterialTheme.colorScheme.surfaceVariant, accentOf(bar, color), if (drag != 0f) 0.45f else 0.22f,
                            )
                        )
                        .drawBehind {
                            drawRect(accentOf(bar, color), size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height))
                        }
                        .drawWithContent {
                            drawContent()
                            if (fadeFromPx != null && fadeFromPx < size.width) {
                                val x0 = fadeFromPx.coerceAtLeast(0f)
                                drawRect(
                                    Color.Black.copy(alpha = 0.55f),
                                    topLeft = androidx.compose.ui.geometry.Offset(x0, 0f),
                                    size = androidx.compose.ui.geometry.Size(size.width - x0, size.height),
                                )
                            }
                        }
                        .then(if (selected) Modifier.border(2.dp, SELECTED_YELLOW, RoundedCornerShape(6.dp)) else Modifier)
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
                        .padding(horizontal = if (selected && onResize != null) handleWidth else 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    val wide = widthPx > with(density) { 110.dp.toPx() }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val icon = bar.icon
                        if (icon != null && !resizing) {
                            Box(
                                Modifier
                                    .padding(start = 2.dp)
                                    .size(22.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(accentOf(bar, color).copy(alpha = 0.25f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(icon, contentDescription = null, tint = accentOf(bar, color), modifier = Modifier.size(15.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        // How long it is on the video, when there is room.
                        if (wide && !resizing) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                shortLength(bar.endMs - bar.startMs),
                                fontSize = 10.sp,
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (selected && onResize != null && drag == 0f) {
                    BarHandle(
                        xPx = startPx,
                        widthDp = handleWidth,
                        atStart = true,
                        onDrag = { dx -> leftDrag = (leftDrag + dx).coerceAtMost(baseWidthPx - minPx) },
                        onEnd = {
                            resize?.invoke(bar.id, true, (leftDrag / scale).toLong())
                            leftDrag = 0f
                        },
                        onCancel = { leftDrag = 0f },
                    )
                    BarHandle(
                        xPx = startPx + widthPx - handlePx,
                        widthDp = handleWidth,
                        atStart = false,
                        onDrag = { dx -> rightDrag = (rightDrag + dx).coerceAtLeast(minPx - baseWidthPx) },
                        onEnd = {
                            resize?.invoke(bar.id, false, (rightDrag / scale).toLong())
                            rightDrag = 0f
                        },
                        onCancel = { rightDrag = 0f },
                    )
                }
            }
        }
    }
}

private fun accentOf(bar: Bar, rowColor: Color) = bar.color ?: rowColor

/** "3.0s", "12.5s", "1:05" for a bar's length. */
private fun shortLength(ms: Long): String {
    val tenths = (ms.coerceAtLeast(0) + 50) / 100
    return if (tenths < 600) "${tenths / 10}.${tenths % 10}s"
    else "${tenths / 600}:${((tenths / 10) % 60).toString().padStart(2, '0')}"
}

/** A grab handle on one end of a selected bar. */
@Composable
private fun BarHandle(
    xPx: Float,
    widthDp: androidx.compose.ui.unit.Dp,
    atStart: Boolean,
    onDrag: (Float) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
) {
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    val cancel by rememberUpdatedState(onCancel)
    Box(
        Modifier
            .offset { IntOffset(xPx.roundToInt(), 0) }
            .width(widthDp)
            .fillMaxHeight()
            .padding(vertical = 2.dp)
            .clip(
                if (atStart) RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)
                else RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)
            )
            .background(Color(0xFFFFC107))
            .semantics { contentDescription = if (atStart) "Drag to change start" else "Drag to change end" }
            .pointerInput(atStart) {
                detectHorizontalDragGestures(
                    onDragEnd = { end() },
                    onDragCancel = { cancel() },
                ) { change, amount ->
                    change.consume()
                    drag(amount)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(3.dp, 14.dp).clip(CircleShape).background(Color(0xFF3E2723)))
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
    pickedId: String?,
    onPick: (String?) -> Unit,
    onRetype: (String) -> Unit,
) {
    val view = androidx.compose.ui.platform.LocalView.current
    val location = project.locate(vm.playheadMs)
    val current = location?.let { project.clips[it.first] }
    val local = location?.second ?: 0L

    // During the Ratio tour the preview shows other shapes (the project is not changed); the
    // change is animated so it is easy to follow.
    val shownShape = vm.tourAspect ?: project.aspect
    val shownRatio by animateFloatAsState(shownShape.ratio, tween(650), label = "ratio")

    // Dark grey around the frame, so the frame's own edges (and black bars) can be seen.
    Box(
        modifier.fillMaxWidth().background(Color(0xFF26272B)),
        contentAlignment = Alignment.Center,
    ) {
        if (vm.tourStep != EditorViewModel.TourStep.NONE) {
            RatioTourCard(vm, shownShape, Modifier.align(Alignment.TopCenter).zIndex(5f))
        }
        BoxWithConstraints(
            Modifier
                .aspectRatio(shownRatio)
                .background(Color.Black)
                .clipToBounds()
                // Tapping the picture itself, away from any item, lets go of the selection.
                .onEmptyTap { onPick(null) }
                // Remember where the preview sits on the screen, for "Preview only" recordings.
                .onGloballyPositioned { coords ->
                    val bounds = coords.boundsInWindow()
                    val rootW = view.rootView.width.toFloat()
                    val rootH = view.rootView.height.toFloat()
                    if (rootW > 0f && rootH > 0f) {
                        vm.previewCrop = com.reelforge.editor.record.CropBox(
                            (bounds.left / rootW).coerceIn(0f, 1f), (bounds.top / rootH).coerceIn(0f, 1f),
                            (bounds.right / rootW).coerceIn(0f, 1f), (bounds.bottom / rootH).coerceIn(0f, 1f),
                        )
                    }
                }
        ) {
            val frameHeight = maxHeight
            if (project.clips.isEmpty()) EmptyFrame(shownShape)

            // The backdrop around a video set to Fit is drawn here, behind the player, so it shows
            // in the preview on every phone. (The exported video draws its own.)
            if (current != null && current.type == ClipType.VIDEO && !current.fill) {
                when (project.background) {
                    Background.COLOR -> Box(Modifier.fillMaxSize().background(Color(project.backgroundColor)))
                    Background.BLUR -> {
                        val sourceMs = current.trimStartMs + (local * current.effectiveSpeed).toLong()
                        SideEffect { vm.requestFrame(current.uri, sourceMs) }
                        LaunchedEffect(vm.thumbKey(current)) { vm.requestThumb(current) }
                        // The frame for this second, else one shortly before it while it loads.
                        val frame = (0..4).firstNotNullOfOrNull { back ->
                            vm.frames[vm.frameKey(current.uri, (sourceMs - back * 1000L).coerceAtLeast(0L))]
                        } ?: vm.thumbs[vm.thumbKey(current)]
                        BlurredBackdrop(frame)
                    }
                    Background.BLACK -> Unit
                }
            }

            // A drag / pinch of the picture in progress, shown live and saved when the finger lifts.
            var liveClipId by remember { mutableStateOf<String?>(null) }
            var livePan by remember { mutableStateOf(Offset.Zero) }
            var liveZoom by remember { mutableFloatStateOf(1f) }

            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        // See-through around the picture, so the backdrop behind it shows.
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
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
                modifier = Modifier
                    .fillMaxSize()
                    // The picture's size and place in the frame, and Photo zoom (Ken Burns). Read per
                    // frame here, so moving them does not rebuild the screen.
                    .graphicsLayer {
                        val at = project.locate(vm.playheadMs)
                        val shown = at?.let { project.clips.getOrNull(it.first) }
                        val into = at?.second ?: 0L
                        var scale = 1f
                        var dx = 0f
                        var dy = 0f
                        if (shown != null) {
                            val f = com.reelforge.editor.model.ClipFraming.of(shown)
                            val live = shown.id == liveClipId
                            scale = f.zoom * (if (live) liveZoom else 1f)
                            dx = f.panX * size.width + (if (live) livePan.x else 0f)
                            dy = f.panY * size.height + (if (live) livePan.y else 0f)
                            if (shown.type == ClipType.IMAGE && shown.motion != com.reelforge.editor.model.PhotoMotion.NONE) {
                                val pose = shown.motion.pose(into.toFloat() / shown.durationMs.coerceAtLeast(1))
                                scale *= pose.scale
                                dx += pose.dx * size.width / 2 * scale
                                dy -= pose.dy * size.height / 2 * scale
                            }
                        }
                        scaleX = scale
                        scaleY = scale
                        translationX = dx
                        translationY = dy
                    },
            )

            // With the clip under the red line selected, drag moves the picture and pinching
            // resizes it. Texts and layers on top get their own touches first.
            val selectedHere = current != null && vm.selectedClipId == current.id
            if (selectedHere) {
                val clipId = current!!.id
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(clipId) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = true)
                                var moved = false
                                var travel = Offset.Zero
                                var pinch = 1f
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.any { it.isConsumed }) break
                                    val pan = event.calculatePan()
                                    val zoom = event.calculateZoom()
                                    if (!moved) {
                                        travel += pan
                                        pinch *= zoom
                                        moved = travel.getDistance() > viewConfiguration.touchSlop ||
                                            abs(pinch - 1f) > 0.03f
                                        if (moved) {
                                            liveClipId = clipId
                                            livePan = travel
                                            liveZoom = pinch
                                        }
                                    } else {
                                        livePan += pan
                                        liveZoom *= zoom
                                    }
                                    if (moved) event.changes.forEach { it.consume() }
                                    if (event.changes.none { it.pressed }) break
                                }
                                if (moved) {
                                    vm.frameClip(clipId, livePan.x / size.width, livePan.y / size.height, liveZoom)
                                }
                                liveClipId = null
                                livePan = Offset.Zero
                                liveZoom = 1f
                            }
                        },
                )
            }

            if (current == null) {
                Text(
                    "Add videos or photos to begin",
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White.copy(alpha = 0.7f),
                )
            }

            // Photos are drawn by Compose; the player only keeps time for them.
            if (current != null && current.type == ClipType.IMAGE) {
                val still = vm.previews[current.uri]
                // Asked for whenever it is missing, including after being dropped to save memory.
                if (still == null) SideEffect { vm.requestPreview(current) }
                val backdrop = if (!current.fill && project.background == Background.COLOR) {
                    Color(project.backgroundColor)
                } else Color.Black
                Box(Modifier.fillMaxSize().background(backdrop)) {
                    if (still != null && !current.fill && project.background == Background.BLUR) {
                        BlurredBackdrop(still)
                    }
                    if (still != null) {
                        Image(
                            bitmap = still,
                            contentDescription = null,
                            // Same placement as the export: the user's size and position (and a
                            // drag / pinch in progress), plus Photo zoom movement. The playhead is
                            // read here only, so playing does not rebuild the screen.
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                val f = com.reelforge.editor.model.ClipFraming.of(current)
                                val live = current.id == liveClipId
                                var scale = f.zoom * (if (live) liveZoom else 1f)
                                var dx = f.panX * size.width + (if (live) livePan.x else 0f)
                                var dy = f.panY * size.height + (if (live) livePan.y else 0f)
                                if (current.motion != com.reelforge.editor.model.PhotoMotion.NONE) {
                                    val into = project.locate(vm.playheadMs)?.second ?: 0L
                                    val pose = current.motion.pose(into.toFloat() / current.durationMs.coerceAtLeast(1))
                                    scale *= pose.scale
                                    dx += pose.dx * size.width / 2 * scale
                                    dy -= pose.dy * size.height / 2 * scale
                                }
                                scaleX = scale * (if (current.flipH) -1f else 1f)
                                scaleY = scale
                                translationX = dx
                                translationY = dy
                                rotationZ = current.rotation.toFloat()
                            },
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
            val frameWidthPx = with(density) { maxWidth.toPx() }
            val now = vm.playheadMs

            // Video layers play in their own small players, stacked over the main picture.
            project.videoLayers.forEach { layer ->
                key(layer.id) {
                    val layerPlayer = remember(layer.id) { vm.layerPlayer(layer) }
                    LaunchedEffect(layer.id) { vm.resyncLayers() }
                    val local = now - layer.startMs
                    val active = local >= 0 && local < layer.durationMs
                    val pose = if (layer.keyframes.isEmpty()) null
                    else ProjectOps.poseAt(layer.keyframes, local, layer.keyframes.first())
                    val layerHeight = frameHeight * layer.scale
                    AndroidView(
                        factory = { context ->
                            TextureView(context).apply {
                                isOpaque = false
                                layerPlayer.setVideoTextureView(this)
                            }
                        },
                        onRelease = { layerPlayer.clearVideoTextureView(it) },
                        modifier = Modifier
                            .align(BiasAlignment(pose?.x ?: layer.x, -(pose?.y ?: layer.y)))
                            .height(layerHeight)
                            .width(layerHeight * layer.aspect)
                            .graphicsLayer(
                                alpha = if (active) (pose?.opacity ?: layer.opacity).coerceIn(0f, 1f) else 0f,
                                scaleX = pose?.scale ?: 1f,
                                scaleY = pose?.scale ?: 1f,
                                rotationZ = -(pose?.rotation ?: layer.rotation),
                            )
                            .clickable(enabled = active) { onEditLayer(layer.id) },
                    )
                }
            }

            project.layers
                .filter { now >= it.startMs && now < it.endMs }
                .forEach { layer ->
                    key(layer.id) {
                        val still = vm.previews[layer.uri]
                        if (still == null) SideEffect { vm.requestStill(layer.uri) }
                        val anim = overlayAnim(
                            now - layer.startMs, layer.endMs - layer.startMs, layer.animIn, layer.animOut, layer.loop
                        )
                        val pose = if (layer.keyframes.isEmpty()) null
                        else ProjectOps.poseAt(layer.keyframes, now - layer.startMs, layer.keyframes.first())
                        if (still != null) {
                            val gesture = rememberDragState()
                            val itemHeightPx = frameHeightPx * layer.scale
                            val itemWidthPx = itemHeightPx * still.width / still.height.coerceAtLeast(1)
                            val place = if (pose != null) BiasAlignment(pose.x, -pose.y)
                            else glide(
                                layer.x, layer.y, layer.x2, layer.y2, layer.moving,
                                now - layer.startMs, layer.endMs - layer.startMs,
                            )
                            val shownScale = anim.scale * (pose?.scale ?: 1f)
                            val shownAngle = -((pose?.rotation ?: layer.rotation) + anim.rotation)
                            val art = com.reelforge.editor.engine.MovingArt.of(layer.button)
                            // A scene effect covers the whole video and stays put.
                            val canMove = layer.keyframes.isEmpty() && art?.fullFrame != true
                            LayerPicture(
                                still,
                                art,
                                now - layer.startMs,
                                layer.endMs - layer.startMs,
                                frameWidthPx / frameHeightPx.coerceAtLeast(1f),
                                modifier = Modifier
                                    .align(place)
                                    .height(frameHeight * layer.scale)
                                    // Drag to move, pinch to resize, twist to turn. The first tap
                                    // selects it; a tap when selected opens its settings.
                                    .itemGestures(
                                        gesture,
                                        enabled = canMove,
                                        onTap = { if (pickedId == layer.id) onEditLayer(layer.id) else onPick(layer.id) },
                                        onEnd = { pan, zoom, turn ->
                                            onPick(layer.id)
                                            vm.updateLayer(layer.id) {
                                                val nx = ProjectOps.movedBias(it.x, pan.x, frameWidthPx, itemWidthPx)
                                                val ny = ProjectOps.movedBias(it.y, -pan.y, frameHeightPx, itemHeightPx)
                                                it.copy(
                                                    x = nx,
                                                    y = ny,
                                                    x2 = (it.x2 + nx - it.x).coerceIn(-1f, 1f),
                                                    y2 = (it.y2 + ny - it.y).coerceIn(-1f, 1f),
                                                    scale = (it.scale * zoom).coerceIn(0.03f, 2f),
                                                    rotation = it.rotation - turn,
                                                )
                                            }
                                        },
                                    )
                                    .graphicsLayer(
                                        alpha = (anim.alpha * (pose?.opacity ?: layer.opacity)).coerceIn(0f, 1f),
                                        scaleX = shownScale * gesture.zoom,
                                        scaleY = shownScale * gesture.zoom,
                                        translationX = anim.dx * frameWidthPx / 2f + gesture.pan.x,
                                        translationY = -anim.dy * frameHeightPx / 2f + gesture.pan.y,
                                        rotationZ = shownAngle + gesture.turn,
                                    ),
                            )
                            if (pickedId == layer.id && canMove) {
                                SelectionHandles(
                                    centre = Offset(
                                        (frameWidthPx - itemWidthPx) / 2f * (1f + place.horizontalBias) +
                                            itemWidthPx / 2f + anim.dx * frameWidthPx / 2f,
                                        (frameHeightPx - itemHeightPx) / 2f * (1f + place.verticalBias) +
                                            itemHeightPx / 2f - anim.dy * frameHeightPx / 2f,
                                    ),
                                    halfWidth = itemWidthPx / 2f * shownScale,
                                    halfHeight = itemHeightPx / 2f * shownScale,
                                    angle = shownAngle,
                                    state = gesture,
                                    onCommit = { zoom, turn ->
                                        vm.updateLayer(layer.id) {
                                            it.copy(
                                                scale = (it.scale * zoom).coerceIn(0.03f, 2f),
                                                rotation = it.rotation - turn,
                                            )
                                        }
                                    },
                                    onEdit = null,
                                    onDelete = {
                                        onPick(null)
                                        vm.deleteLayer(layer.id)
                                    },
                                )
                            }
                        }
                    }
                }

            // Text is drawn from the same bitmap the export uses, so it looks identical.
            project.texts
                .filter { now >= it.startMs && now < it.endMs }
                .forEach { t ->
                    key(t.id) {
                        val anim = overlayAnim(now - t.startMs, t.endMs - t.startMs, t.animIn, t.animOut, t.loop)
                        val look = t.copy(
                            x = 0f, y = 0f, x2 = 0f, y2 = 0f, startMs = 0, endMs = 0, moving = false,
                            animIn = OverlayAnim.NONE, animOut = OverlayAnim.NONE,
                            opacity = 1f, rotation = 0f, keyframes = emptyList(),
                        )
                        val pose = if (t.keyframes.isEmpty()) null
                        else ProjectOps.poseAt(t.keyframes, now - t.startMs, t.keyframes.first())
                        val heightPx = frameHeightPx.toInt().coerceAtLeast(16)
                        // While a text is being revealed, only the letters that have appeared are drawn.
                        val revealSteps = remember(t.text, t.reveal) {
                            com.reelforge.editor.model.revealSteps(t.reveal, t.text)
                        }
                        val showing = com.reelforge.editor.model.revealCount(
                            revealSteps, now - t.startMs, t.endMs - t.startMs,
                        ) ?: Int.MAX_VALUE
                        val bitmap = remember(look, heightPx, showing) {
                            EffectFactory.renderText(look, heightPx, showing).asImageBitmap()
                        }
                        val gesture = rememberDragState()
                        val textWidthPx = bitmap.width.toFloat()
                        val textHeightPx = bitmap.height.toFloat()
                        val place = if (pose != null) BiasAlignment(pose.x, -pose.y)
                        else glide(t.x, t.y, t.x2, t.y2, t.moving, now - t.startMs, t.endMs - t.startMs)
                        val shownScale = anim.scale * (pose?.scale ?: 1f)
                        val shownAngle = -((pose?.rotation ?: t.rotation) + anim.rotation)
                        val canMove = t.keyframes.isEmpty()
                        Image(
                            bitmap = bitmap,
                            contentDescription = t.text,
                            modifier = Modifier
                                .align(place)
                                // Drag to move, pinch to resize, twist to turn. The first tap
                                // selects it; a tap when selected opens its settings.
                                .itemGestures(
                                    gesture,
                                    enabled = canMove,
                                    onTap = {
                                        onPick(t.id)
                                        onEditText(t.id)
                                    },
                                    onEnd = { pan, zoom, turn ->
                                        onPick(t.id)
                                        vm.updateText(t.id) {
                                            val nx = ProjectOps.movedBias(it.x, pan.x, frameWidthPx, textWidthPx)
                                            val ny = ProjectOps.movedBias(it.y, -pan.y, frameHeightPx, textHeightPx)
                                            it.copy(
                                                x = nx,
                                                y = ny,
                                                x2 = (it.x2 + nx - it.x).coerceIn(-1f, 1f),
                                                y2 = (it.y2 + ny - it.y).coerceIn(-1f, 1f),
                                                size = (it.size * zoom).coerceIn(0.02f, 0.5f),
                                                rotation = it.rotation - turn,
                                            )
                                        }
                                    },
                                )
                                .graphicsLayer(
                                    alpha = (anim.alpha * (pose?.opacity ?: t.opacity)).coerceIn(0f, 1f),
                                    scaleX = shownScale * gesture.zoom,
                                    scaleY = shownScale * gesture.zoom,
                                    translationX = anim.dx * frameWidthPx / 2f + gesture.pan.x,
                                    translationY = -anim.dy * frameHeightPx / 2f + gesture.pan.y,
                                    rotationZ = shownAngle + gesture.turn,
                                ),
                        )
                        if (pickedId == t.id && canMove) {
                            SelectionHandles(
                                centre = Offset(
                                    (frameWidthPx - textWidthPx) / 2f * (1f + place.horizontalBias) +
                                        textWidthPx / 2f + anim.dx * frameWidthPx / 2f,
                                    (frameHeightPx - textHeightPx) / 2f * (1f + place.verticalBias) +
                                        textHeightPx / 2f - anim.dy * frameHeightPx / 2f,
                                ),
                                halfWidth = textWidthPx / 2f * shownScale,
                                halfHeight = textHeightPx / 2f * shownScale,
                                angle = shownAngle,
                                state = gesture,
                                onCommit = { zoom, turn ->
                                    vm.updateText(t.id) {
                                        it.copy(
                                            size = (it.size * zoom).coerceIn(0.02f, 0.5f),
                                            rotation = it.rotation - turn,
                                        )
                                    }
                                },
                                onEdit = { onRetype(t.id) },
                                onDelete = {
                                    onPick(null)
                                    vm.deleteText(t.id)
                                },
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

// ------------------------------------------------------------------- export

@Composable
private fun ExportDialog(vm: EditorViewModel, project: Project, onClose: () -> Unit) {
    val state = vm.export
    var shortSide by remember { mutableIntStateOf(vm.settings.exportSide) }
    var quality by remember { mutableIntStateOf(vm.settings.exportQuality) }
    var fps by remember { mutableIntStateOf(vm.settings.exportFps) }
    var hevc by remember { mutableStateOf(vm.settings.exportHevc) }
    val context = LocalContext.current
    // Android 13+: ask once to show notifications, so the export's progress shows while the user
    // is in other apps. The export starts whatever the answer.
    val askNotify = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { vm.startExport(shortSide, quality, fps, hevc) }
    fun export() {
        val needs = android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needs) askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        else vm.startExport(shortSide, quality, fps, hevc)
    }

    // The same three sizes on every phone (see AppSettings.SIDES).
    val sizes = remember { com.reelforge.editor.data.AppSettings.SIDES.map { it to "${it}p" } }
    val canHevc = remember(project.aspect) {
        val (w, h) = ExportManager.outputSize(project.aspect, 720)
        ExportManager.canEncode(true, w, h, 30)
    }
    // HEVC chosen earlier (or on another phone) but this phone has no hardware HEVC encoder:
    // the option is hidden, so it must not be used either.
    if (!canHevc && hevc) hevc = false
    if (sizes.none { it.first == shortSide }) shortSide = if (shortSide > sizes.last().first) sizes.last().first else 720

    // Keep the screen on while exporting: some phones slow or pause apps soon after the screen
    // goes off, which leaves the export sitting at the same percent.
    val hostView = androidx.compose.ui.platform.LocalView.current
    val exporting = state is ExportState.Running
    androidx.compose.runtime.DisposableEffect(exporting) {
        hostView.keepScreenOn = exporting
        onDispose { hostView.keepScreenOn = false }
    }

    CutDialog(
        icon = Icons.Rounded.IosShare,
        centred = false,
        dismissOnClickOutside = state !is ExportState.Running,
        onDismissRequest = {
            if (state !is ExportState.Running) {
                vm.dismissExport()
                onClose()
            }
        },
        title = { Text("Export video") },
        text = {
            // No scroll here: the dialog scrolls its message itself (a scroll inside a scroll crashes).
            Column {
                when (state) {
                    ExportState.Idle -> {
                        // Sideways, each choice sits on one line (name, then the options), so all of
                        // them fit above the buttons; upright, the name is above the options.
                        val sideways = LocalConfiguration.current.screenHeightDp < 500
                        ExportOption("Resolution", sideways) {
                            ChipRow(sizes.map { it.second }, sizes.indexOfFirst { it.first == shortSide }) {
                                shortSide = sizes[it].first
                            }
                        }
                        ExportOption("Frame rate", sideways) {
                            ChipRow(listOf("30 fps", "60 fps"), if (fps == 60) 1 else 0) { fps = if (it == 1) 60 else 30 }
                        }
                        ExportOption("Quality", sideways) {
                            ChipRow(listOf("Small file", "Balanced", "Best"), quality) { quality = it }
                        }
                        if (canHevc) {
                            ExportOption("Format", sideways) {
                                ChipRow(
                                    if (sideways) listOf("H.264", "H.265 (smaller)")
                                    else listOf("H.264 (plays everywhere)", "H.265 (smaller file)"),
                                    if (hevc) 1 else 0,
                                ) { hevc = it == 1 }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "MP4 · ${project.aspect.label} · " + MediaUtils.formatTime(project.durationMs) +
                                ". 60 fps is smoother only for clips filmed at 60 fps.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    is ExportState.Running -> {
                        if (vm.exportAttempt > 1) {
                            // Progress went back to 0: say why, so it does not look stuck.
                            Text(
                                "Trying again with safer settings (${vm.exportAttempt} of 3)…",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            )
                        }
                        Text("Rendering… ${state.progress}%")
                        vm.exportSecondsLeft?.let { left ->
                            Text(
                                when {
                                    left < 60 -> "Less than a minute left"
                                    left < 120 -> "About 1 minute left"
                                    else -> "About ${left / 60} minutes left"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { state.progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        // Many phones slow down or pause apps when the screen goes off or the user
                        // switches away, which leaves the export stuck at the same percent.
                        Text(
                            "Please stay on this screen and don't turn the screen off until the " +
                                "export finishes. Some phones pause the export otherwise.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    is ExportState.Done -> Text("Saved to Movies/CutMitra in your gallery.")
                    is ExportState.Failed -> Text("Export failed: ${state.message}")
                }
            }
        },
        confirmButton = {
            when (state) {
                ExportState.Idle -> WideButton(onClick = { export() }) {
                    Text("Export")
                }
                is ExportState.Running -> WideOutlinedButton(onClick = { vm.cancelExport() }) { Text("Cancel") }
                is ExportState.Done -> WideButton(onClick = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("video/mp4")
                        .putExtra(android.content.Intent.EXTRA_STREAM, state.uri)
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(android.content.Intent.createChooser(send, "Share video"))
                }) { Text("Share") }
                is ExportState.Failed -> WideOutlinedButton(onClick = {
                    // Lets the user send what happened on their phone to the developer.
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_SUBJECT, "CutMitra export problem")
                        .putExtra(android.content.Intent.EXTRA_TEXT, vm.exportReport())
                    context.startActivity(android.content.Intent.createChooser(send, "Share report"))
                }) { Text("Share report") }
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

/** One export choice: its name and its options, in one line when [inline]. */
@Composable
private fun ExportOption(name: String, inline: Boolean, options: @Composable () -> Unit) {
    if (inline) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(84.dp),
            )
            Box(Modifier.weight(1f)) { options() }
        }
    } else {
        Text(
            name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
        )
        options()
    }
}

@Composable
private fun ChipRow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    // Wraps onto a second line instead of scrolling, so every choice is in view.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, label ->
            FilterChip(selected = selected == i, onClick = { onSelect(i) }, label = { Text(label) })
        }
    }
}


/** The empty frame of a new project: the CutMitra icon and name, and the frame's shape. */
@Composable
private fun EmptyFrame(shape: com.reelforge.editor.model.AspectRatio) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF1E2027), Color(0xFF121317)))
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFFFF4D5E), Color(0xFFFF9F43)))
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    androidx.compose.ui.res.painterResource(com.reelforge.editor.R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(84.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text("CutMitra", color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(
                "${shape.label} · ${ratioUse(shape)}",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** The card shown during the Ratio tour: which shapes there are, the one in view lit up. */
@Composable
private fun RatioTourCard(vm: EditorViewModel, shape: com.reelforge.editor.model.AspectRatio, modifier: Modifier) {
    Surface(
        modifier = modifier.padding(6.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xEE23262D),
        border = androidx.compose.foundation.BorderStroke(1.dp, ToolColors.Style.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AspectRatio, null, tint = ToolColors.Style, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "Ratio changes your video's shape",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Skip",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { vm.stopRatioTour() }.padding(4.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                listOf(
                    com.reelforge.editor.model.AspectRatio.PORTRAIT,
                    com.reelforge.editor.model.AspectRatio.LANDSCAPE,
                    com.reelforge.editor.model.AspectRatio.SQUARE,
                ).forEach { a ->
                    val on = vm.tourAspect == a
                    Text(
                        "${a.label} ${ratioUse(a)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (on) Color.Black else Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (on) ToolColors.Style else Color.White.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}


/**
 * An image layer in the preview: its picture, or for an animated button the frame for [sinceMs],
 * drawn by the same code as the exported video.
 */
@Composable
private fun LayerPicture(
    still: androidx.compose.ui.graphics.ImageBitmap,
    button: com.reelforge.editor.engine.MovingArt?,
    sinceMs: Long,
    durationMs: Long,
    frameAspect: Float,
    modifier: Modifier,
) {
    if (button == null) {
        Image(bitmap = still, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier)
        return
    }
    Canvas(modifier.aspectRatio(button.aspect(frameAspect), matchHeightConstraintsFirst = true)) {
        drawIntoCanvas { c ->
            button.draw(c.nativeCanvas, sinceMs, durationMs, size.width, size.height)
        }
    }
}
