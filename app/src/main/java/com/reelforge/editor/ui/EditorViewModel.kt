package com.reelforge.editor.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.reelforge.editor.data.AppSettings
import com.reelforge.editor.data.AssetCatalog
import com.reelforge.editor.data.SettingsStore
import com.reelforge.editor.data.MusicResult
import com.reelforge.editor.data.MusicSearch
import com.reelforge.editor.data.SocialSticker
import com.reelforge.editor.data.SoundEffect
import com.reelforge.editor.data.SoundMaker
import com.reelforge.editor.data.StickerAsset
import com.reelforge.editor.data.StickerCatalog
import com.reelforge.editor.data.AssetDownloader
import com.reelforge.editor.data.FontAsset
import com.reelforge.editor.data.ProjectRepository
import com.reelforge.editor.engine.ChromaKeyEffect
import com.reelforge.editor.engine.EffectFactory
import com.reelforge.editor.engine.ExportManager
import com.reelforge.editor.engine.fadeAlpha
import com.reelforge.editor.model.AspectRatio
import com.reelforge.editor.model.AudioClip
import com.reelforge.editor.model.Background
import com.reelforge.editor.model.ImageLayer
import com.reelforge.editor.model.Keyframe
import com.reelforge.editor.model.Clip
import com.reelforge.editor.model.ClipType
import com.reelforge.editor.model.Cut
import com.reelforge.editor.model.MusicTrack
import com.reelforge.editor.model.History
import com.reelforge.editor.model.Project
import com.reelforge.editor.model.ProjectOps
import com.reelforge.editor.model.TextItem
import com.reelforge.editor.model.VideoLayer
import com.reelforge.editor.model.newId
import com.reelforge.editor.util.MediaUtils
import com.reelforge.editor.util.SpeechMaker
import androidx.compose.runtime.mutableFloatStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.withContext
import java.io.File

/** One finger stroke; points are fractions (0..1) of the frame width and height. */
/** One pen line; [width] is a share of the picture's height. */
/** How a toast looks: a tick, an "i", a warning sign or an error sign, each in its own colour. */
enum class ToastKind { SUCCESS, INFO, WARNING, ERROR }

/** A short message at the top of the editor. [id] makes each one new, even with the same words. */
data class Toast(val text: String, val kind: ToastKind, val undo: Boolean, val id: Long) {
    companion object {
        /** Guesses the kind of an older plain message from its words. */
        fun kindOf(text: String): ToastKind {
            val t = text.lowercase()
            return when {
                listOf("could not", "couldn't", "failed", "problem", "cannot", "can't").any { it in t } -> ToastKind.ERROR
                listOf("move the", "first", "no ", "too short", "up to", "needs", "only", "works on", "trim this")
                    .any { it in t } -> ToastKind.WARNING
                listOf("added", "saved", "removed", "applied", "extracted", "reversed", "set to", "found", "now")
                    .any { it in t } -> ToastKind.SUCCESS
                else -> ToastKind.INFO
            }
        }
    }
}

class DrawStroke(val color: Int, val points: List<Offset>, val width: Float = 0.012f)

private const val SCRUB_INTERVAL_MS = 90L

/** How far past a clip's end the player may run before the editor steps in (see tick()). */
private const val OVERSTAY_MS = 150L

/** Texts made by Auto captions have ids starting with this, so they can be replaced together. */
const val CAPTION_PREFIX = "cap_"



// Timeline frames kept in memory (each is small, about 60 KB).
private const val MAX_FRAMES = 400

// Full-size photo stills kept for the preview (up to about 4 MB each).
private const val MAX_STILLS = 16
private const val MAX_LAYERED_EXPORT_ATTEMPTS = 3

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val progress: Int) : ExportState
    data class Done(val uri: Uri) : ExportState
    data class Failed(val message: String) : ExportState
}

@OptIn(UnstableApi::class)
class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Application get() = getApplication()
    private val repo = ProjectRepository(app)

    // --------------------------------------------------------------- settings

    private val settingsStore = SettingsStore(app)

    var settings by mutableStateOf(settingsStore.load())
        private set

    var showSettings by mutableStateOf(false)

    fun updateSettings(block: (AppSettings) -> AppSettings) {
        settings = block(settings).sanitized()
        settingsStore.save(settings)
    }

    val appVersion: String
        get() = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""

    var downloadSizeLabel by mutableStateOf("0 MB")
        private set

    private fun downloadFiles(): List<File> =
        listOf("fonts", "stickers", "music").flatMap { File(ctx.filesDir, it).listFiles()?.toList().orEmpty() }
            .filter { it.isFile && (it.parentFile?.name != "fonts" || it.name.startsWith("store_")) }

    fun refreshDownloadSize() {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { downloadFiles().sumOf { it.length() } }
            downloadSizeLabel = if (bytes < 1024 * 1024) "${bytes / 1024} KB" else "${bytes / (1024 * 1024)} MB"
        }
    }

    /** Deletes store downloads that no saved project refers to. */
    fun deleteUnusedDownloads() {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) {
                val used = HashSet<String>()
                (repo.list() + listOfNotNull(project)).forEach { p ->
                    p.layers.forEach { used += it.uri }
                    p.audio.forEach { used += it.uri }
                    p.music?.let { used += it.uri }
                    p.texts.forEach { t -> t.fontPath?.let { used += it } }
                }
                downloadFiles().count { f ->
                    val inUse = f.absolutePath in used || Uri.fromFile(f).toString() in used
                    !inUse && f.delete()
                }
            }
            refreshInstalledFonts()
            refreshInstalledStickers()
            refreshDownloadSize()
            message = if (removed == 0) "Nothing to delete" else "Deleted $removed downloads"
        }
    }

    var projects by mutableStateOf(emptyList<Project>())
        private set
    var project by mutableStateOf<Project?>(null)
        private set
    var selectedClipId by mutableStateOf<String?>(null)
    var playheadMs by mutableLongStateOf(0L)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    /** The message shown in the toast at the top of the editor (null when none). */
    var toast by mutableStateOf<Toast?>(null)
        private set

    /** Shows a toast. [undo] adds an Undo button that takes the change back. */
    fun toast(text: String, kind: ToastKind = ToastKind.SUCCESS, undo: Boolean = false) {
        toast = Toast(text, kind, undo, System.nanoTime())
    }

    fun dismissToast(id: Long) {
        if (toast?.id == id) toast = null
    }

    /** Older way to show a message; its kind is guessed from the words. */
    var message: String?
        get() = toast?.text
        set(value) {
            if (value == null) toast = null else toast(value, Toast.kindOf(value))
        }
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var export by mutableStateOf<ExportState>(ExportState.Idle)
        private set

    /** Timeline thumbnails keyed by [thumbKey]; full-size stills for image clips keyed by uri. */
    val thumbs = mutableStateMapOf<String, ImageBitmap>()
    val previews = mutableStateMapOf<String, ImageBitmap>()
    // Full-size stills are large (several MB each), so only the most recently loaded are kept;
    // the screen asks again for any it needs that was dropped.
    private val previewOrder = LinkedHashSet<String>()

    /** Frames shown along video clips on the timeline, keyed by [frameKey]. */
    val frames = mutableStateMapOf<String, ImageBitmap>()
    // Which frames were shown least recently, so the oldest can be dropped first.
    private val frameOrder = LinkedHashSet<String>()
    private val frameQueue = Channel<Pair<String, Long>>(Channel.UNLIMITED)
    private var scrubJob: Job? = null
    private var lastScrubSeekAt = 0L

    // Sound effects and Clean voice are heard in the preview too, not only in the export.
    private val mainFx = com.reelforge.editor.engine.LiveAudioFx()
    val player: ExoPlayer = ExoPlayer.Builder(app, com.reelforge.editor.engine.renderersWithFx(app, mainFx)).build()
    // Music files are often long MP3s with no seek table; without this setting the player cannot
    // jump into the middle of them and keeps restarting from the beginning.
    private val musicPlayer: ExoPlayer = ExoPlayer.Builder(app)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(app, DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true))
        )
        .build()
    private var lastMusicSeekAt = 0L
    // Voice-overs and other sounds (effects, sound taken from a video) play on separate players,
    // so a voice-over can be heard over a sound in the preview, as in the export.
    private val voiceLane = SoundLane(app)
    private val soundLane = SoundLane(app)

    var isRecording by mutableStateOf(false)
        private set
    private var recorder: com.reelforge.editor.record.VoiceCapture? = null
    // Whether the voice-over being recorded used Clean voice.
    private var recordingClean = false
    private var recordFile: File? = null
    private var recordStartMs = 0L

    private var playlistSignature = ""
    // The clips grouped into the pieces the player was given (see ProjectOps.playGroups).
    private var playGroups: List<IntRange> = emptyList()
    private var shownClipIndex = -1
    private var effectSignature = ""
    private var musicSignature = ""
    private var effectsEverSet = false
    // Set when this phone's preview cannot play the effects; the preview then shows the plain
    // picture and sound (the exported video still has everything).
    private var previewEffectsOff = false
    private var lastPlayerErrorAt = 0L
    private val history = History<Project>()
    private var saveJob: Job? = null
    private var transformer: Transformer? = null
    private val requested = mutableSetOf<String>()

    init {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                applyClipParams()
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) {
                    startMusic()
                } else {
                    musicPlayer.pause()
                    stopVoice()
                    project?.let { syncLayers(it, false) }
                }
            }

            override fun onPlayerError(error: PlaybackException) = recoverFromPlayerError(error)
        })
        refreshProjects()
        startFrameLoader()
    }

    // ---------------------------------------------------------------- projects

    fun refreshProjects() {
        viewModelScope.launch {
            projects = withContext(Dispatchers.IO) { repo.list() }
        }
    }

    fun createProject(name: String, aspect: AspectRatio) {
        val p = Project(name = name.trim().ifBlank { defaultProjectName() }, aspect = aspect)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.save(p) }
            openProject(p)
        }
    }

    fun openProject(p: Project) {
        project = p
        history.clear()
        canUndo = false
        canRedo = false
        selectedClipId = null
        playheadMs = 0
        playlistSignature = ""
        effectSignature = ""
        musicSignature = ""
        syncPlayer()
    }

    fun closeProject() {
        if (isRecording) stopRecording()
        player.pause()
        player.clearMediaItems()
        stopVoice()
        releaseLayerPlayers()
        musicPlayer.stop()
        musicPlayer.clearMediaItems()
        val p = project
        project = null
        saveJob?.cancel()
        viewModelScope.launch {
            if (p != null) withContext(Dispatchers.IO) { repo.save(p) }
            projects = withContext(Dispatchers.IO) { repo.list() }
        }
    }

    fun renameProject(p: Project, name: String) {
        if (name.isBlank()) {
            toast("Type a name first", ToastKind.WARNING)
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.save(p.copy(name = name.trim())) }
            projects = withContext(Dispatchers.IO) { repo.list() }
            toast("Renamed to “${name.trim()}”")
        }
    }

    /** Makes a separate copy of a project, listed first. */
    fun duplicateProject(p: Project) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.save(p.copy(id = newId(), name = p.name + " copy", updatedAt = System.currentTimeMillis()))
            }
            projects = withContext(Dispatchers.IO) { repo.list() }
        }
    }

    /** A dated name for a project created without one, like "12 Nov project 2". */
    private fun defaultProjectName(): String {
        val day = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date())
        val base = "$day project"
        val taken = projects.map { it.name }.toSet()
        if (base !in taken) return base
        var n = 2
        while ("$base $n" in taken) n++
        return "$base $n"
    }

    fun deleteProject(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.delete(id) }
            projects = withContext(Dispatchers.IO) { repo.list() }
            toast("Project deleted")
        }
    }

    private fun mutate(block: (Project) -> Project) {
        val current = project ?: return
        val changed = block(current)
        if (changed == current) return
        // Rapid changes (dragging a slider) collapse into a single undo step.
        val now = System.currentTimeMillis()
        history.record(current, now)
        commit(changed.copy(updatedAt = now))
    }

    private fun commit(next: Project) {
        project = next
        canUndo = history.canUndo
        canRedo = history.canRedo
        if (next.clips.none { it.id == selectedClipId }) selectedClipId = null
        playheadMs = playheadMs.coerceIn(0, next.durationMs)
        syncPlayer()
        syncLayers(next, player.isPlaying)
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(400)
            withContext(Dispatchers.IO) { repo.save(next) }
        }
    }

    fun undo() {
        val current = project ?: return
        val back = history.undo(current)
        if (back == null) {
            toast("Nothing to undo", ToastKind.INFO)
            return
        }
        commit(back)
        toast("Undone", ToastKind.INFO)
    }

    fun redo() {
        val current = project ?: return
        val again = history.redo(current)
        if (again == null) {
            toast("Nothing to redo", ToastKind.INFO)
            return
        }
        commit(again)
        toast("Redone", ToastKind.INFO)
    }

    // ------------------------------------------------------------------- clips

    val selectedClip: Clip? get() = project?.clips?.firstOrNull { it.id == selectedClipId }

    fun addMedia(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val clips = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        ctx.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    MediaUtils.probe(ctx, uri)?.let { info ->
                        Clip(
                            uri = uri.toString(),
                            name = MediaUtils.displayName(ctx, uri),
                            type = info.type,
                            sourceDurationMs = if (info.type == ClipType.IMAGE) settings.photoMs else info.durationMs,
                            hasAudio = info.hasAudio,
                        )
                    }
                }
            }
            if (clips.size < uris.size) message = "Some files could not be read"
            else if (clips.isNotEmpty()) {
                toast(if (clips.size == 1) "Added to the end of your video" else "${clips.size} clips added to the end")
            }
            if (clips.isNotEmpty()) {
                val first = project?.clips?.isEmpty() == true
                mutate { it.copy(clips = it.clips + clips) }
                // The first clip in a project: offer to remove black bars baked into it, or to
                // match the frame to its shape if the shapes differ.
                // No popup here (it came up while still picking media): a short tip says what can be
                // done, and Remove black bars / Ratio ask when the user chooses them.
                if (first) {
                    val bars = barsOfferFor(clips[0])
                    if (bars != null) {
                        val now = project?.aspect
                        toast(
                            if (now != null && bars.pictureShape != now) {
                                "Tip: the picture looks small because of black bars. Tap \u201cRatio ${now.label}\u201d " +
                                    "below and choose ${ratioName(bars.pictureShape)}."
                            } else {
                                "Tip: the picture has black bars. Tap the clip, then \u201cRemove black bars\u201d."
                            },
                            ToastKind.INFO,
                        )
                    } else {
                        val shape = withContext(Dispatchers.IO) {
                            MediaUtils.mediaAspect(ctx, Uri.parse(clips[0].uri), clips[0].type)
                        }
                        val best = shape?.let { AspectRatio.closest(it) }
                        val now = project?.aspect
                        if (best != null && now != null && best != now) {
                            toast(
                                "Tip: your video is ${best.label}. Tap \u201cRatio ${now.label}\u201d below: choose " +
                                    "${ratioName(best)}, or keep ${now.label} with \u201cMake it ${now.label}\u201d " +
                                    "(blur, fill or follow the person).",
                                ToastKind.INFO,
                            )
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ frame shape (ratio)

    /** A frame shape that fits the first video better than the project's, to offer once. */
    var aspectSuggestion by mutableStateOf<AspectRatio?>(null)

    /** Changes the shape of the video (9:16, 16:9, …). Texts and layers keep their places. */
    fun setAspect(aspect: AspectRatio) {
        aspectSuggestion = null
        if (project?.aspect != aspect) {
            mutate { it.copy(aspect = aspect) }
            toast("Video shape is now ${aspect.label}", undo = true)
        }
    }

    /** Black bars found inside a video clip, offered for removal. */
    data class BarsOffer(
        val clipId: String,
        val box: com.reelforge.editor.util.ContentBox,
        val videoAspect: Float,
        /** The frame shape nearest to the picture inside the bars. */
        val pictureShape: AspectRatio,
    )

    var barsOffer by mutableStateOf<BarsOffer?>(null)

    /** Looks for black bars baked into a video clip; null if there are none (or it is a photo). */
    private suspend fun barsOfferFor(clip: Clip): BarsOffer? {
        if (clip.type != ClipType.VIDEO) return null
        return withContext(Dispatchers.IO) {
            val uri = Uri.parse(clip.uri)
            val shape = MediaUtils.videoAspect(ctx, uri) ?: return@withContext null
            val box = com.reelforge.editor.util.BlackBars.find(ctx, uri, clip.sourceDurationMs)
                ?.takeIf { it.hasBars } ?: return@withContext null
            BarsOffer(clip.id, box, shape, AspectRatio.closest(box.aspect(shape)))
        }
    }

    /** Remove black bars: finds bars baked into the clip and offers to zoom them away. */
    fun removeBlackBars(id: String) {
        val clip = project?.clips?.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            val offer = barsOfferFor(clip)
            if (offer == null) message = "No black bars found in this clip"
            else if (offer.pictureShape == project?.aspect) applyBarsFix(offer, changeRatio = false)
            else barsOffer = offer
        }
    }

    /**
     * Zooms the clip so its picture fills the frame, optionally first changing the frame to the
     * picture's shape (so nothing is cut off). One Undo step.
     */
    fun applyBarsFix(offer: BarsOffer, changeRatio: Boolean) {
        barsOffer = null
        aspectSuggestion = null
        mutate { p ->
            val aspect = if (changeRatio) offer.pictureShape else p.aspect
            p.copy(
                aspect = aspect,
                clips = p.clips.map { c ->
                    if (c.id != offer.clipId) c else {
                        // Keeping a frame of another shape means filling it (the sides are cut, as
                        // the dialog says); otherwise the whole picture is kept.
                        val cover = !changeRatio && offer.pictureShape != p.aspect
                        val f = com.reelforge.editor.util.BlackBars.fillFraming(
                            offer.box, offer.videoAspect, aspect.ratio, c.fill, cover,
                        )
                        c.copy(zoom = f.zoom, panX = f.panX, panY = f.panY)
                    }
                },
            )
        }
        message = "Black bars removed. Tap Reset position on the clip to undo just this."
    }

    // ------------------------------------------------------------ ratio tour

    /** Steps of the short animated tour that shows what Ratio does. */
    enum class TourStep { NONE, FIND, TAP, SHAPES }

    var tourStep by mutableStateOf(TourStep.NONE)
        private set

    /** The shape shown in the preview during the tour (the project itself is not changed). */
    var tourAspect by mutableStateOf<AspectRatio?>(null)
        private set
    private var tourJob: Job? = null
    /** The tour was stopped because a popup opened; it plays again (not marked as seen). */
    private var tourInterrupted = false

    /**
     * Plays the tour: the menu scrolls to Ratio, it is tapped, then the preview changes to 9:16,
     * 16:9 and 1:1 and back. Only shows the shapes; nothing in the project changes.
     */
    fun startRatioTour() {
        if (tourJob?.isActive == true) return
        tourJob = viewModelScope.launch {
            try {
                tourStep = TourStep.FIND
                delay(1_200)
                tourStep = TourStep.TAP
                delay(800)
                tourStep = TourStep.SHAPES
                for (shape in listOf(AspectRatio.PORTRAIT, AspectRatio.LANDSCAPE, AspectRatio.SQUARE)) {
                    tourAspect = shape
                    delay(1_600)
                }
            } finally {
                tourAspect = null
                tourStep = TourStep.NONE
                if (!tourInterrupted && !settings.ratioTourSeen) updateSettings { it.copy(ratioTourSeen = true) }
                tourInterrupted = false
            }
        }
    }

    fun stopRatioTour() {
        tourJob?.cancel()
    }

    /** A popup opened over the tour: stop it for now; it plays from the start once the popup is closed. */
    fun holdRatioTour() {
        if (tourJob?.isActive != true) return
        tourInterrupted = true
        tourJob?.cancel()
    }

    // ------------------------------------------------------------ make it 9:16 (reframe)

    /** Progress of "Follow the person" while it looks at the clips (null when idle). */
    var reframeWork by mutableStateOf<String?>(null)
        private set
    private var reframeJob: Job? = null

    fun cancelReframe() {
        reframeJob?.cancel()
    }

    /**
     * Changes the video to [target] (e.g. 9:16) and fits every clip in one step (one Undo):
     * BLUR keeps whole pictures over a blurred copy, FILL crops from the centre, FOLLOW crops
     * around the person found in each clip.
     */
    fun reframeProject(target: AspectRatio, style: com.reelforge.editor.model.ReframeStyle) {
        val p = project ?: return
        if (p.clips.isEmpty()) {
            toast("Add a video or photo first: tap Media", ToastKind.WARNING)
            return
        }
        when (style) {
            com.reelforge.editor.model.ReframeStyle.BLUR -> {
                mutate { pr ->
                    pr.copy(
                        aspect = target,
                        background = com.reelforge.editor.model.Background.BLUR,
                        clips = pr.clips.map { it.copy(fill = false, zoom = 1f, panX = 0f, panY = 0f) },
                    )
                }
                toast("Now ${target.label} with a blurred background. Whole pictures are kept.", undo = true)
            }
            com.reelforge.editor.model.ReframeStyle.FILL -> {
                mutate { pr ->
                    pr.copy(aspect = target, clips = pr.clips.map { it.copy(fill = true, zoom = 1f, panX = 0f, panY = 0f) })
                }
                toast("Now ${target.label}, filling the screen. Drag a clip on the video to move it.", undo = true)
            }
            com.reelforge.editor.model.ReframeStyle.FOLLOW -> followPeople(target)
        }
    }

    /** FOLLOW: finds the person in a few frames of each clip and centres the crop on them. */
    private fun followPeople(target: AspectRatio) {
        if (reframeJob?.isActive == true) return
        val start = project ?: return
        pause()
        reframeJob = viewModelScope.launch {
            try {
                if (!com.reelforge.editor.engine.CutOut.hasModel(ctx)) {
                    reframeWork = "Downloading the person finder (${com.reelforge.editor.engine.CutOut.MODEL_MB} MB)…"
                    withContext(Dispatchers.IO) {
                        com.reelforge.editor.engine.CutOut.downloadModel(ctx) { pct ->
                            postStatus(cutoutHandler, "Downloading the person finder… $pct%") { reframeWork = it }
                        }
                    }
                }
                var found = 0
                val pans = HashMap<String, Pair<Float, Float>>()
                withContext(Dispatchers.IO) {
                    com.reelforge.editor.engine.PersonSegmenter(com.reelforge.editor.engine.CutOut.modelFile(ctx)).use { finder ->
                        start.clips.forEachIndexed { i, clip ->
                            withContext(Dispatchers.Main) { reframeWork = "Finding people… clip ${i + 1} of ${start.clips.size}" }
                            val uri = Uri.parse(clip.uri)
                            val shape = MediaUtils.mediaAspect(ctx, uri, clip.type) ?: return@forEachIndexed
                            val pictures = if (clip.type == ClipType.VIDEO) {
                                listOf(0.2, 0.4, 0.6, 0.8).mapNotNull { f ->
                                    val at = clip.trimStartMs + ((clip.trimEndMs - clip.trimStartMs) * f).toLong()
                                    MediaUtils.videoFrame(ctx, uri, at, 320)
                                }
                            } else {
                                listOfNotNull(MediaUtils.decodeImage(ctx, uri, 640))
                            }
                            var person = false
                            val centres = pictures.mapNotNull { pic ->
                                // The person if there is one; otherwise the main action.
                                val c = com.reelforge.editor.model.Reframe.personCentre(finder.personMask(pic), 256)
                                    ?.also { person = true }
                                    ?: run {
                                        val px = IntArray(pic.width * pic.height)
                                        pic.getPixels(px, 0, pic.width, 0, 0, pic.width, pic.height)
                                        com.reelforge.editor.model.Reframe.busyCentre(px, pic.width, pic.height)
                                    }
                                pic.recycle()
                                c
                            }
                            if (person) found++
                            if (centres.isNotEmpty()) {
                                val cx = centres.map { it.first }.average().toFloat()
                                val cy = centres.map { it.second }.average().toFloat()
                                pans[clip.id] = com.reelforge.editor.model.Reframe.followPan(shape, target.ratio, cx, cy)
                            }
                        }
                    }
                }
                mutate { pr ->
                    pr.copy(
                        aspect = target,
                        clips = pr.clips.map { c ->
                            val (px, py) = pans[c.id] ?: (0f to 0f)
                            c.copy(fill = true, zoom = 1f, panX = px, panY = py)
                        },
                    )
                }
                toast(
                    when (found) {
                        0 -> "Now ${target.label}. No person found, so each clip is framed on its main action; drag one to adjust."
                        start.clips.size -> "Now ${target.label}, following the person in every clip."
                        else -> "Now ${target.label}. Followed the person in $found of ${start.clips.size} clips, the main action in the rest."
                    },
                    ToastKind.SUCCESS,
                    undo = true,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                message = "Could not download the person finder. Check the internet and try again."
            } catch (e: Throwable) {
                android.util.Log.e("CutMitra", "reframe", e)
                message = "Could not look for people on this phone. Try Fill the screen instead."
            } finally {
                reframeWork = null
            }
        }
    }

    /** Sets the frame to the shape of the clip under the red line (or the first clip). */
    fun matchAspect() {
        val p = project ?: return
        val clip = p.locate(playheadMs)?.let { p.clips[it.first] } ?: p.clips.firstOrNull() ?: return
        viewModelScope.launch {
            val shape = withContext(Dispatchers.IO) { MediaUtils.mediaAspect(ctx, Uri.parse(clip.uri), clip.type) }
            if (shape == null) {
                message = "Could not read the shape of this clip"
                return@launch
            }
            val best = AspectRatio.closest(shape)
            setAspect(best)
            message = "Frame set to ${best.label} to match the clip"
        }
    }

    fun updateClip(id: String, block: (Clip) -> Clip) = mutate { p ->
        p.copy(clips = p.clips.map { if (it.id == id) block(it) else it })
    }

    /** Saves a drag / pinch of the picture in the preview (one Undo step). */
    fun frameClip(id: String, dxFraction: Float, dyFraction: Float, pinch: Float) {
        val clip = project?.clips?.firstOrNull { it.id == id } ?: return
        val f = com.reelforge.editor.model.ClipFraming.after(clip, dxFraction, dyFraction, pinch)
        updateClip(id) { it.copy(zoom = f.zoom, panX = f.panX, panY = f.panY) }
    }

    /** Puts the picture back to its normal size and place in the frame. */
    fun resetFraming(id: String) {
        updateClip(id) { it.copy(zoom = 1f, panX = 0f, panY = 0f) }
        toast("Picture back to its normal size and place", undo = true)
    }

    fun deleteClip(id: String) {
        mutate { ProjectOps.delete(it, id) }
        if (selectedClipId == id) selectedClipId = null
        toast("Clip deleted", undo = true)
    }

    /** Tapping a clip on the timeline selects it; tapping it again clears the selection. */
    fun toggleClipSelection(id: String) {
        selectedClipId = if (selectedClipId == id) null else id
    }

    /** True when the playhead is far enough inside the selected clip to cut it there. */
    val canCutSelectedClip: Boolean
        get() {
            val p = project ?: return false
            val id = selectedClipId ?: return false
            return ProjectOps.cutPoint(p, id, playheadMs) != null
        }

    /** Removes everything in the selected clip before the playhead. */
    fun trimLeftOfPlayhead() {
        val p = project ?: return
        val id = selectedClipId ?: return
        val result = ProjectOps.trimLeftOfPlayhead(p, id, playheadMs)
        if (result == null) {
            message = "Move the red line onto the selected clip, away from its edges"
            return
        }
        // The kept part now begins where the clip used to begin; stay on that first frame.
        val clipStart = p.clipStartMs(p.clips.indexOfFirst { it.id == id })
        mutate { result }
        seekTo(clipStart)
        toast("Cut away the part before the red line", undo = true)
    }

    /** Removes everything in the selected clip after the playhead. */
    fun trimRightOfPlayhead() {
        val p = project ?: return
        val id = selectedClipId ?: return
        val result = ProjectOps.trimRightOfPlayhead(p, id, playheadMs)
        if (result == null) {
            message = "Move the red line onto the selected clip, away from its edges"
            return
        }
        mutate { result }
        // Stay on the last frame that was kept.
        seekTo((playheadMs - 34).coerceAtLeast(0))
        toast("Cut away the part after the red line", undo = true)
    }

    // ------------------------------------------------------------------ keyframes

    private class PoseAccess(val startMs: Long, val endMs: Long, val keys: List<Keyframe>, val base: Keyframe)

    private fun poseAccess(p: Project, kind: ItemKind, id: String): PoseAccess? = when (kind) {
        ItemKind.TEXT -> p.texts.firstOrNull { it.id == id }?.let {
            PoseAccess(it.startMs, it.endMs, it.keyframes, Keyframe(0, it.x, it.y, 1f, it.rotation, it.opacity))
        }
        ItemKind.LAYER -> p.layers.firstOrNull { it.id == id }?.let {
            PoseAccess(it.startMs, it.endMs, it.keyframes, Keyframe(0, it.x, it.y, 1f, it.rotation, it.opacity))
        }
        ItemKind.VIDEO -> p.videoLayers.firstOrNull { it.id == id }?.let {
            PoseAccess(
                it.startMs, it.startMs + it.durationMs, it.keyframes,
                Keyframe(0, it.x, it.y, 1f, it.rotation, it.opacity),
            )
        }
        else -> null
    }

    /** Writes new keyframes and / or a new resting pose to a text, image layer or video layer. */
    private fun writePose(kind: ItemKind, id: String, keys: List<Keyframe>?, base: Keyframe?) = mutate { p ->
        when (kind) {
            ItemKind.TEXT -> p.copy(texts = p.texts.map {
                if (it.id != id) it else it.copy(
                    keyframes = keys ?: it.keyframes, x = base?.x ?: it.x, y = base?.y ?: it.y,
                    rotation = base?.rotation ?: it.rotation, opacity = base?.opacity ?: it.opacity,
                )
            })
            ItemKind.LAYER -> p.copy(layers = p.layers.map {
                if (it.id != id) it else it.copy(
                    keyframes = keys ?: it.keyframes, x = base?.x ?: it.x, y = base?.y ?: it.y,
                    rotation = base?.rotation ?: it.rotation, opacity = base?.opacity ?: it.opacity,
                )
            })
            ItemKind.VIDEO -> p.copy(videoLayers = p.videoLayers.map {
                if (it.id != id) it else it.copy(
                    keyframes = keys ?: it.keyframes, x = base?.x ?: it.x, y = base?.y ?: it.y,
                    rotation = base?.rotation ?: it.rotation, opacity = base?.opacity ?: it.opacity,
                )
            })
            else -> p
        }
    }

    private fun localTime(access: PoseAccess): Long =
        (playheadMs - access.startMs).coerceIn(0, (access.endMs - access.startMs).coerceAtLeast(0))

    /** The item's place, turn, zoom and opacity at the red line. */
    fun poseOf(kind: ItemKind, id: String): Keyframe? {
        val access = poseAccess(project ?: return null, kind, id) ?: return null
        return ProjectOps.poseAt(access.keys, localTime(access), access.base)
    }

    fun keyframesOf(kind: ItemKind, id: String): List<Keyframe> =
        project?.let { poseAccess(it, kind, id) }?.keys ?: emptyList()

    /**
     * Changes the item's pose. Without keyframes this changes it for its whole time; with
     * keyframes it saves (or updates) a keyframe at the red line.
     */
    fun editPose(kind: ItemKind, id: String, change: (Keyframe) -> Keyframe) {
        val access = poseAccess(project ?: return, kind, id) ?: return
        val local = localTime(access)
        val changed = change(ProjectOps.poseAt(access.keys, local, access.base)).copy(tMs = local)
        if (access.keys.isEmpty()) writePose(kind, id, keys = null, base = changed)
        else writePose(kind, id, keys = ProjectOps.upsertKeyframe(access.keys, changed), base = null)
    }

    /** Turns keyframe animation on (first keyframe at the red line) or off (keeps the current pose). */
    fun setKeyframing(kind: ItemKind, id: String, on: Boolean) {
        val access = poseAccess(project ?: return, kind, id) ?: return
        val pose = ProjectOps.poseAt(access.keys, localTime(access), access.base)
        if (on && access.keys.isEmpty()) writePose(kind, id, keys = listOf(pose), base = null)
        if (!on && access.keys.isNotEmpty()) writePose(kind, id, keys = emptyList(), base = pose)
    }

    fun deleteKeyframe(kind: ItemKind, id: String, tMs: Long) {
        val access = poseAccess(project ?: return, kind, id) ?: return
        val kept = access.keys.filterNot { it.tMs == tMs }
        // Removing the last keyframe leaves the item where that keyframe had it.
        writePose(kind, id, keys = kept, base = if (kept.isEmpty()) access.keys.first() else null)
    }

    // ------------------------------------------------------------ tracking

    /** What is shown while an item is being made to follow something (null when idle). */
    var trackWork by mutableStateOf<String?>(null)
        private set
    var trackProgress by mutableFloatStateOf(0f)
        private set
    private var trackJob: Job? = null

    /**
     * Makes a text or picture follow what it covers in the video: from the red line to the item's
     * end, the video under the item's middle is followed frame by frame and the item gets
     * keyframes along that path. Undo removes them.
     */
    fun trackItem(kind: ItemKind, id: String) {
        val p = project ?: return
        if (trackJob?.isActive == true) return
        val access = poseAccess(p, kind, id) ?: return
        val from = playheadMs.coerceIn(access.startMs, (access.endMs - 200).coerceAtLeast(access.startMs))
        val clipIndex = run {
            var t = 0L
            p.clips.indexOfFirst { c -> val hit = from >= t && from < t + c.durationMs; t += c.durationMs; hit }
        }
        val clip = p.clips.getOrNull(clipIndex)
        if (clip == null || clip.type != ClipType.VIDEO) {
            toast("Tracking follows things in a video. Move the red line onto a video clip, over the thing to follow.", ToastKind.INFO)
            return
        }
        val clipStart = p.clipStartMs(clipIndex)
        // Follow until the item ends or the clip ends (a new shot), whichever is first; at most a minute.
        val until = minOf(access.endMs, clipStart + clip.durationMs, from + 60_000)
        val startPose = ProjectOps.poseAt(access.keys, from - access.startMs, access.base)
        val frameAspect = p.aspect.ratio
        pause()
        trackProgress = 0f
        trackWork = "Following… 0%"
        trackJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val (itemW, itemH) = itemSize(kind, id, p, frameAspect)
                    val sizeW = itemW * startPose.scale
                    val sizeH = itemH * startPose.scale
                    val cx = com.reelforge.editor.model.Placement.centre(startPose.x, sizeW)
                    val cy = com.reelforge.editor.model.Placement.centre(-startPose.y, sizeH)
                    val points = followVideo(clip, clipStart, from, until, frameAspect, cx, cy, sizeW, sizeH) { f ->
                        postStatus(cutoutHandler, "Following… ${(f * 100).toInt()}%") { trackWork = it }
                        cutoutHandler.post { trackProgress = f }
                    }
                    Triple(points, sizeW, sizeH)
                }
                val (points, sizeW, sizeH) = result
                if (points.size < 2) {
                    toast(
                        "Could not find anything to follow under it. Place it over something with clear " +
                            "edges (a face, a ball, a sign) and try again.",
                        ToastKind.WARNING,
                    )
                    return@launch
                }
                val path = com.reelforge.editor.model.TrackPath.simplify(com.reelforge.editor.model.TrackPath.smooth(points))
                val keys = path.map { pt ->
                    startPose.copy(
                        tMs = pt.tMs - access.startMs,
                        x = com.reelforge.editor.model.Placement.bias(pt.x, sizeW),
                        y = -com.reelforge.editor.model.Placement.bias(pt.y, sizeH),
                    )
                }
                // Before the red line the item waits where it was placed.
                val lead = if (from > access.startMs) listOf(startPose.copy(tMs = 0)) else emptyList()
                writePose(kind, id, keys = lead + keys, base = null)
                val followed = points.last().tMs - from
                val all = until - from
                toast(
                    if (followed >= all - 300) "Done. It now follows that spot. Play to see it; Undo to remove."
                    else "Followed for ${MediaUtils.formatTime(followed)}, then lost it (it moved fast or was covered). Undo to remove.",
                    if (followed >= all - 300) ToastKind.SUCCESS else ToastKind.INFO,
                    undo = true,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                toast("Tracking stopped", ToastKind.INFO)
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("CutMitra", "Tracking failed", e)
                toast("Could not follow in this video", ToastKind.ERROR)
            } finally {
                cutoutHandler.removeCallbacksAndMessages(null)
                trackWork = null
                trackProgress = 0f
            }
        }
    }

    fun cancelTracking() {
        trackJob?.cancel()
    }

    /** An item's width and height as fractions of the frame (before keyframe zoom). */
    private fun itemSize(kind: ItemKind, id: String, p: Project, frameAspect: Float): Pair<Float, Float> {
        val refHeight = 720
        return when (kind) {
            ItemKind.TEXT -> {
                val t = p.texts.first { it.id == id }
                val bmp = EffectFactory.renderText(t, refHeight)
                val size = bmp.width / (refHeight * frameAspect) to bmp.height / refHeight.toFloat()
                bmp.recycle()
                size
            }
            ItemKind.LAYER -> {
                val l = p.layers.first { it.id == id }
                val aspect = com.reelforge.editor.engine.MovingArt.of(l.button)?.aspect(frameAspect)
                    ?: MediaUtils.photoSize(ctx, Uri.parse(l.uri))?.let { it.first.toFloat() / it.second } ?: 1f
                l.scale * aspect / frameAspect to l.scale
            }
            else -> 0.2f to 0.2f
        }
    }

    /**
     * Follows the video [clip] (on the timeline from [clipStart]) from timeline time [from] to
     * [until], starting at frame point ([cx], [cy]). Returns the path in frame fractions.
     */
    private suspend fun followVideo(
        clip: com.reelforge.editor.model.Clip,
        clipStart: Long,
        from: Long,
        until: Long,
        frameAspect: Float,
        cx: Float,
        cy: Float,
        itemW: Float,
        itemH: Float,
        onProgress: (Float) -> Unit,
    ): List<com.reelforge.editor.model.TrackPoint> {
        val uri = Uri.parse(clip.uri)
        val shown = MediaUtils.mediaAspect(ctx, uri, ClipType.VIDEO) ?: return emptyList()
        val turned = clip.rotation % 180 != 0
        val pictureAspect = if (turned) 1f / shown else shown
        val framing = com.reelforge.editor.model.ClipFraming.of(clip)
        // Small grey pictures are plenty to follow with, and quick.
        val long = 256
        val tw = if (pictureAspect >= 1f) long else (long * pictureAspect).toInt().coerceAtLeast(32)
        val th = if (pictureAspect >= 1f) (long / pictureAspect).toInt().coerceAtLeast(32) else long
        val (u0, v0) = com.reelforge.editor.model.Placement.toPicture(cx, cy, pictureAspect, frameAspect, clip.fill, framing)
        if (u0 !in 0f..1f || v0 !in 0f..1f) return emptyList()
        // The patch followed is about the item's size, within limits.
        val (rw, rh) = com.reelforge.editor.model.Placement.fitted(pictureAspect, frameAspect, clip.fill)
        val boxW = itemW / (rw * framing.zoom) * tw
        val boxH = itemH / (rh * framing.zoom) * th
        val half = (minOf(boxW, boxH) / 2f).toInt().coerceIn(6, 24)
        val tracker = com.reelforge.editor.model.ObjectTracker(half, radius = 18)
        val step = 100L
        val out = ArrayList<com.reelforge.editor.model.TrackPoint>()
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val recorded = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val pixels = IntArray(tw * th)
            var t = from
            var first = true
            while (t <= until) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val sourceMs = clip.trimStartMs + ((t - clipStart) * clip.effectiveSpeed).toLong()
                val frame = r.getScaledFrameAtTime(
                    sourceMs * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST, tw, th,
                ) ?: break
                val shownFrame = orient(frame, clip, tw, th, recorded)
                shownFrame.getPixels(pixels, 0, tw, 0, 0, tw, th)
                if (shownFrame !== frame) shownFrame.recycle()
                frame.recycle()
                val grey = com.reelforge.editor.model.ObjectTracker.grey(pixels)
                val ok = if (first) tracker.start(grey, tw, th, u0 * tw, v0 * th) else tracker.step(grey, tw, th)
                if (!ok) break
                first = false
                val (fx, fy) = com.reelforge.editor.model.Placement.toFrame(
                    tracker.x / tw, tracker.y / th, pictureAspect, frameAspect, clip.fill, framing,
                )
                out += com.reelforge.editor.model.TrackPoint(t, fx, fy)
                onProgress(((t - from).toFloat() / (until - from).coerceAtLeast(1)).coerceIn(0f, 1f))
                t += step
            }
        } finally {
            runCatching { r.release() }
        }
        return out
    }

    /**
     * The frame as the clip shows it: the right way up (some phones give frames still turned the
     * way the camera recorded them), then the clip's own turn and mirror. Always [w] x [h].
     */
    private fun orient(frame: Bitmap, clip: com.reelforge.editor.model.Clip, w: Int, h: Int, recorded: Int): Bitmap {
        val m = android.graphics.Matrix()
        val sourceWide = frame.width > frame.height
        val wantWide = (if (clip.rotation % 180 != 0) h > w else w > h)
        // A frame that comes sideways is turned upright first, the way the camera recorded it.
        if (sourceWide != wantWide && frame.width != frame.height) {
            m.postRotate(if (recorded % 180 != 0) recorded.toFloat() else 90f)
        }
        if (clip.flipH) m.postScale(-1f, 1f)
        if (clip.rotation % 360 != 0) m.postRotate(clip.rotation.toFloat())
        if (m.isIdentity && frame.width == w && frame.height == h) return frame
        val turned = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, m, true)
        if (turned.width == w && turned.height == h) return turned
        return Bitmap.createScaledBitmap(turned, w, h, true).also { if (it !== turned && turned !== frame) turned.recycle() }
    }

    // ------------------------------------------------------------ clip order, slip

    /** Drops a dragged clip at [index] among the clips. */
    fun moveClipTo(id: String, index: Int) = mutate { ProjectOps.moveTo(it, id, index) }

    /** Slip: plays a different part of the source without moving or resizing the clip. */
    fun slipClip(id: String, newTrimStartMs: Long) = mutate { ProjectOps.slip(it, id, newTrimStartMs) }

    // -------------------------------------------------- cuts on layers, text, sounds

    private fun applyCut(result: Project?) {
        if (result == null) message = "Move the red line onto this item, away from its edges"
        else mutate { result }
    }

    /** True when the playhead is far enough inside the range to cut it. */
    fun canCut(startMs: Long, endMs: Long): Boolean =
        ProjectOps.rangeCutPoint(startMs, endMs, playheadMs) != null

    fun cutText(id: String, cut: Cut) {
        applyCut(ProjectOps.cutText(project ?: return, id, playheadMs, cut))
    }

    fun cutLayer(id: String, cut: Cut) {
        applyCut(ProjectOps.cutLayer(project ?: return, id, playheadMs, cut))
    }

    fun cutVideoLayer(id: String, cut: Cut) {
        val p = project ?: return
        if (cut == Cut.SPLIT && p.videoLayers.size >= ExportManager.MAX_VIDEO_LAYERS) {
            message = "Splitting needs a free video layer (the limit is ${ExportManager.MAX_VIDEO_LAYERS})"
            return
        }
        applyCut(ProjectOps.cutVideoLayer(p, id, playheadMs, cut))
    }

    fun cutMusic(cut: Cut) {
        applyCut(ProjectOps.cutMusic(project ?: return, playheadMs, cut))
    }

    fun shiftMusic(deltaMs: Long) = mutate { ProjectOps.shiftMusic(it, deltaMs) }

    /** Lets the music run over the whole video again. */
    fun resetMusicRange() = mutate { p ->
        p.copy(music = p.music?.copy(startMs = 0, stopMs = 0, offsetMs = 0))
    }

    fun cutAudio(id: String, cut: Cut) {
        applyCut(ProjectOps.cutAudio(project ?: return, id, playheadMs, cut))
    }

    /** Edge drags on the timeline. [deltaMs] is timeline time; see [ProjectOps.trimStart]. */
    fun trimClipStart(id: String, deltaMs: Long) = mutate { ProjectOps.trimStart(it, id, deltaMs) }

    fun trimClipEnd(id: String, deltaMs: Long) = mutate { ProjectOps.trimEnd(it, id, deltaMs) }

    /** Swaps the clip's video or photo for another one, keeping its edits. */
    fun replaceClip(id: String, uri: Uri) {
        viewModelScope.launch {
            val (info, name) = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                MediaUtils.probe(ctx, uri) to MediaUtils.displayName(ctx, uri)
            }
            if (info == null) {
                message = "That file could not be read"
                return@launch
            }
            mutate {
                val replaced = ProjectOps.replaceMedia(it, id, uri.toString(), info.type, info.durationMs, info.hasAudio)
                replaced.copy(clips = replaced.clips.map { c -> if (c.id == id) c.copy(name = name) else c })
            }
        }
    }

    fun duplicateClip(id: String) {
        mutate { ProjectOps.duplicate(it, id) }
        toast("Copy added after the clip", undo = true)
    }

    fun moveClip(id: String, delta: Int) {
        val p = project ?: return
        val at = p.clips.indexOfFirst { it.id == id }
        val to = at + delta
        if (at < 0 || to < 0 || to >= p.clips.size) {
            toast(if (delta < 0) "This clip is already first" else "This clip is already last", ToastKind.INFO)
            return
        }
        mutate { ProjectOps.move(it, id, delta) }
        toast(if (delta < 0) "Moved one place left" else "Moved one place right", undo = true)
    }

    /** Splits the clip under the playhead into two clips. */
    fun splitAtPlayhead() {
        val p = project ?: return
        val secondId = newId()
        val result = ProjectOps.split(p, playheadMs, secondId)
        if (result == null) {
            message = "Move the playhead further inside the clip to split"
            return
        }
        mutate { result }
        selectedClipId = secondId
        toast("Split into two clips at the red line", undo = true)
    }

    // ------------------------------------------------------------ text + music

    // ----------------------------------------------------------- text styles

    /** Looks the user saved with "Save my style"; kept on the phone for every project. */
    var savedStyles by mutableStateOf(emptyList<TextItem>())
        private set

    private fun stylePrefs() = ctx.getSharedPreferences("text_styles", android.content.Context.MODE_PRIVATE)

    init {
        savedStyles = runCatching {
            val array = org.json.JSONArray(stylePrefs().getString("styles", null) ?: "[]")
            (0 until array.length()).mapNotNull { i ->
                runCatching {
                    com.reelforge.editor.data.ProjectJson.decode(array.getString(i)).texts.firstOrNull()
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    private fun storeSavedStyles() {
        val array = org.json.JSONArray()
        savedStyles.forEach { style ->
            array.put(com.reelforge.editor.data.ProjectJson.encode(Project(name = "style", texts = listOf(style))))
        }
        stylePrefs().edit().putString("styles", array.toString()).apply()
    }

    /** Remembers the look of text [id] so it can be used again later, in any project. */
    fun saveTextStyle(id: String) {
        val source = project?.texts?.firstOrNull { it.id == id } ?: return
        val named = TextItem(text = "My style ${savedStyles.size + 1}").withLookOf(source)
        savedStyles = (savedStyles + named).takeLast(12)
        storeSavedStyles()
        message = "Style saved"
    }

    fun useSavedStyle(id: String, style: TextItem) = updateText(id) { it.withLookOf(style) }

    fun deleteSavedStyle(style: TextItem) {
        savedStyles = savedStyles.filterNot { it.id == style.id }
        storeSavedStyles()
    }

    /** Gives every text in the project the look of text [id]. */
    fun applyLookToAllTexts(id: String) = mutate { p ->
        val source = p.texts.firstOrNull { it.id == id }
        if (source == null) p else p.copy(texts = p.texts.map { it.withLookOf(source) })
    }

    /** Gives every text in the project the movement of text [id]. */
    fun applyMotionToAllTexts(id: String) = mutate { p ->
        val source = p.texts.firstOrNull { it.id == id }
        if (source == null) p else p.copy(texts = p.texts.map { it.withMotionOf(source) })
    }

    /** Adds a text written in the full-screen editor; it starts at the playhead. */
    fun addTextItem(draft: TextItem): String {
        val p = project ?: return ""
        if (draft.text.isBlank()) return ""
        val start = playheadMs
        val end = (start + settings.itemMs).coerceAtMost(maxOf(p.durationMs, start + 500))
        val item = draft.copy(startMs = start, endMs = end)
        mutate { it.copy(texts = it.texts + item) }
        return item.id
    }

    fun addText(text: String = "Your text", size: Float = 0.08f): String {
        val p = project ?: return ""
        val start = playheadMs
        val end = (start + settings.itemMs).coerceAtMost(maxOf(p.durationMs, start + 500))
        val item = TextItem(text = text, size = size, startMs = start, endMs = end)
        mutate { it.copy(texts = it.texts + item) }
        return item.id
    }

    fun updateText(id: String, block: (TextItem) -> TextItem) = mutate { p ->
        p.copy(texts = p.texts.map { if (it.id == id) block(it) else it })
    }

    fun deleteText(id: String) {
        mutate { p -> p.copy(texts = p.texts.filterNot { it.id == id }) }
        toast("Text deleted", undo = true)
    }

    fun duplicateText(id: String): String {
        val source = project?.texts?.firstOrNull { it.id == id } ?: return ""
        val copy = source.copy(id = newId(), y = (source.y - 0.2f).coerceIn(-1f, 1f))
        mutate { it.copy(texts = it.texts + copy) }
        toast("Text copied", undo = true)
        return copy.id
    }

    fun deleteAllTexts() {
        mutate { it.copy(texts = emptyList()) }
        toast("All texts deleted", undo = true)
    }

    fun deleteAllLayers() {
        mutate { it.copy(layers = emptyList()) }
        toast("All layers deleted", undo = true)
    }

    fun deleteAllAudio() = mutate { it.copy(audio = emptyList()) }


    fun deleteAllClips() {
        mutate { it.copy(clips = emptyList()) }
        selectedClipId = null
    }

    fun setMusic(uri: Uri) {
        viewModelScope.launch {
            val track = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                MusicTrack(
                    uri = uri.toString(),
                    title = MediaUtils.displayName(ctx, uri),
                    durationMs = MediaUtils.audioDurationMs(ctx, uri),
                )
            }
            val videoMs: Long = project?.durationMs ?: 0L
            if (videoMs > 0 && track.durationMs > videoMs + 1_000) {
                // Longer than the video: it plays up to the end of the video and fades out there.
                mutate { it.copy(music = track.copy(fadeOutMs = minOf(2_000L, videoMs / 4))) }
                message = "Music is ${MediaUtils.formatTime(track.durationMs)}, video is " +
                    "${MediaUtils.formatTime(videoMs)}. The music plays to the end of the video and fades out."
                return@launch
            }
            mutate { it.copy(music = track) }
        }
    }

    fun updateMusic(block: (MusicTrack) -> MusicTrack) = mutate { p ->
        p.copy(music = p.music?.let(block))
    }

    fun removeMusic() {
        mutate { it.copy(music = null) }
        toast("Music removed", undo = true)
    }

    // ------------------------------------------------------------ image layers

    fun addImageLayer(uri: Uri): String {
        val p = project ?: return ""
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val start = playheadMs
        val end = (start + settings.itemMs).coerceAtMost(maxOf(p.durationMs, start + 500))
        val layer = ImageLayer(uri = uri.toString(), startMs = start, endMs = end)
        mutate { it.copy(layers = it.layers + layer) }
        return layer.id
    }

    fun updateLayer(id: String, block: (ImageLayer) -> ImageLayer) = mutate { p ->
        p.copy(layers = p.layers.map { if (it.id == id) block(it) else it })
    }

    fun deleteLayer(id: String) {
        mutate { p -> p.copy(layers = p.layers.filterNot { it.id == id }) }
        toast("Layer deleted", undo = true)
    }

    /** Renders finger-drawn strokes (points in 0..1 of the frame) to a full-frame image layer. */
    fun addDrawing(strokes: List<DrawStroke>) {
        val p = project ?: return
        if (strokes.isEmpty()) return
        val start = playheadMs
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val (w, h) = ExportManager.outputSize(p.aspect, 1080)
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        strokeWidth = h * 0.012f
                    }
                    strokes.forEach { stroke ->
                        if (stroke.points.isEmpty()) return@forEach
                        paint.color = stroke.color
                        paint.strokeWidth = h * stroke.width
                        val path = android.graphics.Path()
                        stroke.points.forEachIndexed { i, pt ->
                            if (i == 0) path.moveTo(pt.x * w, pt.y * h) else path.lineTo(pt.x * w, pt.y * h)
                        }
                        if (stroke.points.size == 1) {
                            path.lineTo(stroke.points[0].x * w + 0.5f, stroke.points[0].y * h)
                        }
                        canvas.drawPath(path, paint)
                    }
                    val dir = File(ctx.filesDir, "drawings").apply { mkdirs() }
                    File(dir, "drawing_${System.currentTimeMillis()}.png").also { f ->
                        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }.getOrNull()
            }
            if (file == null) {
                message = "Could not save the drawing"
                return@launch
            }
            val end = (start + settings.itemMs).coerceAtMost(maxOf(p.durationMs, start + 500))
            val layer = ImageLayer(
                uri = Uri.fromFile(file).toString(), scale = 1f, startMs = start, endMs = end,
            )
            mutate { it.copy(layers = it.layers + layer) }
        }
    }

    // -------------------------------------------------------------- font store

    /** Fonts that can be downloaded on demand (see assets/font_catalog.json). */
    val storeFonts: List<FontAsset> = AssetCatalog.fonts(app)

    /** Ids of store fonts that are on this phone. */
    var installedFonts by mutableStateOf(emptySet<String>())
        private set

    /** Download progress (0..100) per font id while a download is running. */
    val fontProgress = mutableStateMapOf<String, Int>()

    init {
        refreshInstalledFonts()
    }

    fun fontPath(asset: FontAsset): String = AssetCatalog.fontFile(ctx, asset).absolutePath

    private fun refreshInstalledFonts() {
        installedFonts = storeFonts.filter { AssetCatalog.fontFile(ctx, it).length() > 0 }.map { it.id }.toSet()
    }

    /** Downloads a store font if needed, then applies it to [textId] (when given). */
    fun useStoreFont(asset: FontAsset, textId: String?) {
        ensureFont(asset) { path ->
            if (path != null && textId != null) updateText(textId) { it.copy(fontPath = path) }
        }
    }

    /**
     * Calls [onReady] with the font's file once it is on the phone (downloading it first if
     * needed), or with null when the download failed (the user has been told).
     */
    private fun ensureFont(asset: FontAsset, quiet: Boolean = false, onReady: (String?) -> Unit) {
        val file = AssetCatalog.fontFile(ctx, asset)
        if (file.length() > 0) {
            onReady(file.absolutePath)
            return
        }
        if (fontProgress.containsKey(asset.id)) return
        fontProgress[asset.id] = 0
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { downloadFont(asset, file) }
            fontProgress.remove(asset.id)
            refreshInstalledFonts()
            if (!ok && !quiet) toast("Could not download ${asset.name}. Check the internet connection.", ToastKind.ERROR)
            onReady(if (ok) file.absolutePath else null)
        }
    }

    /** Downloads one font to [file]; false (and no file) when it failed. Call off the main thread. */
    private fun downloadFont(asset: FontAsset, file: File): Boolean = runCatching {
        AssetDownloader.download(asset.url, file) { percent ->
            viewModelScope.launch { if (fontProgress.containsKey(asset.id)) fontProgress[asset.id] = percent }
        }
        // Throws if what arrived is not a usable font.
        Typeface.createFromFile(file)
    }.onFailure { file.delete() }.isSuccess

    // ------------------------------------------------------------ plug-in store

    /** The store's fonts, one pack per language or style. */
    val fontPacks: List<com.reelforge.editor.data.FontPack> by lazy {
        com.reelforge.editor.data.FontPacks.packs(storeFonts)
    }

    /** (fonts done, fonts in the pack) per pack group while a whole pack downloads. */
    val packProgress = mutableStateMapOf<String, Pair<Int, Int>>()
    private val packJobs = HashMap<String, kotlinx.coroutines.Job>()

    /** Downloads every font of [pack] that is not on the phone yet; tapping again stops it. */
    fun downloadFontPack(pack: com.reelforge.editor.data.FontPack) {
        packJobs[pack.group]?.let {
            it.cancel()
            packJobs.remove(pack.group)
            packProgress.remove(pack.group)
            toast("Stopped. Fonts already downloaded stay.", ToastKind.INFO)
            return
        }
        val missing = pack.fonts.filter { AssetCatalog.fontFile(ctx, it).length() == 0L }
        if (missing.isEmpty()) {
            toast("${pack.group} fonts are already on your phone", ToastKind.INFO)
            return
        }
        packProgress[pack.group] = 0 to missing.size
        packJobs[pack.group] = viewModelScope.launch {
            var failed = 0
            try {
                missing.forEachIndexed { i, font ->
                    val file = AssetCatalog.fontFile(ctx, font)
                    if (file.length() == 0L && !fontProgress.containsKey(font.id)) {
                        fontProgress[font.id] = 0
                        val ok = withContext(Dispatchers.IO) { downloadFont(font, file) }
                        fontProgress.remove(font.id)
                        if (!ok) failed++
                    }
                    packProgress[pack.group] = (i + 1) to missing.size
                    refreshInstalledFonts()
                }
            } finally {
                missing.forEach { fontProgress.remove(it.id) }
                packJobs.remove(pack.group)
                packProgress.remove(pack.group)
                refreshInstalledFonts()
            }
            if (failed == 0) toast("${pack.group} fonts ready. Pick them in Text → Font.")
            else toast("$failed of ${missing.size} fonts could not download. Check the internet and try again.", ToastKind.ERROR)
        }
    }

    /** Deletes the pack's fonts from the phone, except those used in this video. */
    fun deleteFontPack(pack: com.reelforge.editor.data.FontPack) {
        val used = project?.texts?.mapNotNull { it.fontPath }?.toSet().orEmpty()
        var kept = 0
        pack.fonts.forEach { font ->
            val file = AssetCatalog.fontFile(ctx, font)
            if (file.absolutePath in used) kept++ else file.delete()
        }
        refreshInstalledFonts()
        toast(
            if (kept > 0) "Deleted. $kept font${if (kept == 1) "" else "s"} used in this video kept."
            else "${pack.group} fonts deleted. You can download them again any time.",
            ToastKind.INFO,
        )
    }

    /** Ids of text presets whose font is downloading. */
    val presetBusy = mutableStateMapOf<String, Boolean>()

    /** Adds a ready-made text at the red line, downloading its font first if needed. */
    fun addTextPreset(preset: com.reelforge.editor.model.TextPreset) {
        if (project == null || presetBusy.containsKey(preset.id)) return
        val font = preset.fontId?.let { id -> storeFonts.firstOrNull { it.id == id } }
        if (font == null) {
            addPresetNow(preset, null, announce = true)
            return
        }
        presetBusy[preset.id] = true
        ensureFont(font, quiet = true) { path ->
            presetBusy.remove(preset.id)
            addPresetNow(preset, path, announce = path != null)
            if (path == null) toast("Added with a built-in font (no internet for ${font.name}).", ToastKind.INFO)
        }
    }

    private fun addPresetNow(preset: com.reelforge.editor.model.TextPreset, fontPath: String?, announce: Boolean) {
        val id = addTextItem(preset.item(fontPath))
        if (id.isNotEmpty() && announce) toast("\"${preset.text.lines().first()}\" added at the red line", undo = true)
    }

    /** Sets the noise remover on every video clip with sound and every voice-over. */
    fun setNoiseRemovalEverywhere(level: com.reelforge.editor.model.NoiseRemoval) {
        if (project == null) return
        mutate { p ->
            p.copy(
                clips = p.clips.map { if (it.type == ClipType.VIDEO && it.hasAudio) it.copy(denoise = level) else it },
                audio = p.audio.map { if (it.voice) it.copy(denoise = level) else it },
            )
        }
        toast(
            if (level == com.reelforge.editor.model.NoiseRemoval.OFF) "Noise remover off"
            else "Noise remover ${level.label} on. Play to hear it.",
            undo = true,
        )
    }

    /** Adds a scene effect (snow, Diwali lights, ...) over the whole video, from the red line. */
    fun addSceneEffect(effect: com.reelforge.editor.engine.SceneEffect) {
        val p = project ?: return
        viewModelScope.launch {
            val aspect = p.aspect.w.toFloat() / p.aspect.h.coerceAtLeast(1)
            val file = withContext(Dispatchers.IO) {
                File(StickerCatalog.dir(ctx), "scene_${effect.name.lowercase()}_${p.aspect.name.lowercase()}.png").also { f ->
                    if (f.length() == 0L) f.outputStream().use {
                        com.reelforge.editor.engine.MovingArt.render(effect, 1_500, effect.defaultMs, 480, aspect)
                            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
            val start = playheadMs
            val end = (start + effect.defaultMs).coerceAtMost(maxOf(p.durationMs, start + 500))
            val layer = ImageLayer(
                uri = Uri.fromFile(file).toString(), startMs = start, endMs = end,
                scale = 1f, x = 0f, y = 0f, button = effect.name,
            )
            mutate { it.copy(layers = it.layers + layer) }
            toast("${effect.title} added at the red line. Drag its ends on the timeline to make it longer.", undo = true)
        }
    }


    fun removeStoreFont(asset: FontAsset) {
        AssetCatalog.fontFile(ctx, asset).delete()
        refreshInstalledFonts()
    }

    // ------------------------------------------------------------------ store

    /** Stickers that can be downloaded on demand (see assets/sticker_catalog.json). */
    val storeStickers: List<StickerAsset> = StickerCatalog.stickers(app)

    var installedStickers by mutableStateOf(emptySet<String>())
        private set

    /** Codes of stickers that are downloading right now. */
    val stickerBusy = mutableStateMapOf<String, Boolean>()

    init {
        refreshInstalledStickers()
    }

    private fun refreshInstalledStickers() {
        installedStickers =
            storeStickers.filter { StickerCatalog.file(ctx, it).length() > 0 }.map { it.code }.toSet()
    }

    /** Downloads the sticker if needed, then puts it on the video at the playhead. */
    fun addSticker(asset: StickerAsset) {
        if (project == null) return
        val file = StickerCatalog.file(ctx, asset)
        if (file.length() > 0) {
            addImageLayer(Uri.fromFile(file))
            return
        }
        if (stickerBusy.containsKey(asset.code)) return
        stickerBusy[asset.code] = true
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    AssetDownloader.download(asset.url, file)
                    checkNotNull(android.graphics.BitmapFactory.decodeFile(file.absolutePath)) { "not a picture" }
                }.onFailure { file.delete() }.isSuccess
            }
            stickerBusy.remove(asset.code)
            refreshInstalledStickers()
            if (ok) addImageLayer(Uri.fromFile(file))
            else message = "Could not download the sticker. Check the internet connection."
        }
    }

    /** "Download all" progress: how many are done of how many (null when idle). */
    var stickerPackProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    private var stickerPackJob: Job? = null

    /** Downloads every sticker not on the phone yet, a few at a time, so they work offline. */
    fun downloadAllStickers() {
        if (stickerPackJob?.isActive == true) {
            stickerPackJob?.cancel()
            return
        }
        val missing = storeStickers.filter { StickerCatalog.file(ctx, it).length() == 0L }
        if (missing.isEmpty()) {
            toast("All stickers are already on your phone", ToastKind.INFO)
            return
        }
        stickerPackJob = viewModelScope.launch {
            var done = 0
            var failed = 0
            stickerPackProgress = 0 to missing.size
            try {
                missing.chunked(4).forEach { group ->
                    val results = group.map { asset ->
                        async(Dispatchers.IO) {
                            val file = StickerCatalog.file(ctx, asset)
                            runCatching {
                                AssetDownloader.download(asset.url, file)
                                checkNotNull(android.graphics.BitmapFactory.decodeFile(file.absolutePath)) { "not a picture" }
                            }.onFailure { file.delete() }.isSuccess
                        }
                    }.awaitAll()
                    done += results.size
                    failed += results.count { !it }
                    stickerPackProgress = done to missing.size
                }
                if (failed == 0) toast("All ${missing.size} stickers downloaded; they now work without internet")
                else toast("$failed stickers could not download. Check the internet and try again.", ToastKind.ERROR)
            } finally {
                stickerPackProgress = null
                refreshInstalledStickers()
            }
        }
    }

    /** Deletes downloaded stickers that no project on screen is using. */
    fun removeUnusedStickers() {
        val used = project?.layers?.map { it.uri }?.toSet().orEmpty()
        storeStickers.forEach { asset ->
            val file = StickerCatalog.file(ctx, asset)
            if (Uri.fromFile(file).toString() !in used) file.delete()
        }
        refreshInstalledStickers()
    }

    /** Adds one of the built-in channel buttons ("Subscribe", "Like", ...) at the playhead. */
    fun addSocialSticker(sticker: SocialSticker) {
        if (project == null) return
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                sticker.file(ctx).also { f ->
                    if (f.length() == 0L) f.outputStream().use {
                        sticker.render().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
            val id = addImageLayer(Uri.fromFile(file))
            if (id.isNotEmpty()) updateLayer(id) {
                if (sticker.burst) it.copy(scale = 0.22f, y = 0.4f, loop = sticker.loop)
                else it.copy(scale = 0.14f, y = -0.6f, loop = sticker.loop)
            }
        }
    }

    /**
     * Adds an animated channel button (Subscribe + bell, Like, Follow, Share, Comment) at the red
     * line. It is a normal layer (move, resize, time it), drawn frame by frame.
     */
    fun addAnimatedButton(kind: com.reelforge.editor.engine.AnimatedButton) {
        val p = project ?: return
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                File(StickerCatalog.dir(ctx), "animated_${kind.name.lowercase()}.png").also { f ->
                    if (f.length() == 0L) f.outputStream().use {
                        com.reelforge.editor.engine.AnimatedButtons.still(kind)
                            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
            val start = playheadMs
            val end = (start + kind.defaultMs).coerceAtMost(maxOf(p.durationMs, start + 500))
            val layer = ImageLayer(
                uri = Uri.fromFile(file).toString(), startMs = start, endMs = end,
                scale = if (kind == com.reelforge.editor.engine.AnimatedButton.SUBSCRIBE) 0.2f else 0.16f,
                y = -0.6f, button = kind.name,
            )
            mutate { it.copy(layers = it.layers + layer) }
            toast("${kind.title} added at the red line. Play to see it move.", undo = true)
        }
    }

    /** Adds one of the built-in sound effects at the playhead. */
    fun addSoundEffect(effect: SoundEffect) {
        if (project == null) return
        val start = playheadMs
        viewModelScope.launch {
            val (file, duration) = withContext(Dispatchers.IO) {
                val samples = SoundMaker.samples(effect)
                val f = effect.file(ctx)
                if (f.length() == 0L) f.writeBytes(SoundMaker.wav(samples))
                f to samples.size * 1000L / SoundMaker.SAMPLE_RATE
            }
            val clip = AudioClip(
                uri = Uri.fromFile(file).toString(), title = effect.title, startMs = start, durationMs = duration,
            )
            mutate { it.copy(audio = it.audio + clip) }
        }
    }

    // ----------------------------------------------------------- music search

    var musicResults by mutableStateOf(emptyList<MusicResult>())
        private set
    var musicSearching by mutableStateOf(false)
        private set
    var musicSearchNote by mutableStateOf("")
        private set

    /** Ids of search results that are downloading right now, with progress 0..100. */
    val musicProgress = mutableStateMapOf<String, Int>()

    /** Looks for free (public-domain) music or sound effects online. */
    fun searchMusic(query: String, soundEffects: Boolean) {
        if (query.isBlank() || musicSearching) return
        musicSearching = true
        musicSearchNote = ""
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { MusicSearch.search(query, soundEffects) } }
            musicSearching = false
            musicResults = found.getOrDefault(emptyList())
            musicSearchNote = when {
                found.isFailure -> "Search did not work. Check the internet connection and try again."
                musicResults.isEmpty() -> "Nothing found. Try a simpler word, in English."
                else -> ""
            }
        }
    }

    /** Downloads a search result, then uses it as the background music or as a sound at the playhead. */
    fun useMusicResult(result: MusicResult, asMusic: Boolean) {
        if (project == null || musicProgress.containsKey(result.id)) return
        val start = playheadMs
        val file = File(File(ctx.filesDir, "music").apply { mkdirs() }, "${result.id}.${result.fileType}")
        musicProgress[result.id] = 0
        viewModelScope.launch {
            val duration = withContext(Dispatchers.IO) {
                runCatching {
                    if (file.length() == 0L) AssetDownloader.download(result.url, file) { percent ->
                        viewModelScope.launch { if (musicProgress.containsKey(result.id)) musicProgress[result.id] = percent }
                    }
                    MediaUtils.audioDurationMs(ctx, Uri.fromFile(file)).also { check(it > 0) { "not playable" } }
                }.onFailure { file.delete() }.getOrDefault(0L)
            }
            musicProgress.remove(result.id)
            val uri = Uri.fromFile(file).toString()
            when {
                duration <= 0 -> message = "Could not download \"${result.title}\". Try another one."
                asMusic -> mutate { it.copy(music = MusicTrack(uri = uri, title = result.title, durationMs = duration)) }
                else -> mutate {
                    it.copy(audio = it.audio + AudioClip(uri = uri, title = result.title, startMs = start, durationMs = duration))
                }
            }
        }
    }

    // ------------------------------------------------------------ custom fonts

    /** Copies a .ttf / .otf file into app storage and uses it for one text item. */
    fun importFont(uri: Uri, textId: String) {
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(ctx.filesDir, "fonts").apply { mkdirs() }
                    val file = File(dir, "font_${System.currentTimeMillis()}.ttf")
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                    // Throws if the file is not a usable font.
                    Typeface.createFromFile(file)
                    file.absolutePath
                }.getOrNull()
            }
            if (path == null) message = "That file is not a usable font (.ttf or .otf)"
            else updateText(textId) { it.copy(fontPath = path) }
        }
    }

    fun setBackground(background: Background, color: Int? = null) = mutate { p ->
        p.copy(background = background, backgroundColor = color ?: p.backgroundColor)
    }

    // ------------------------------------------------------------ video layers

    private val layerPlayers = mutableMapOf<String, ExoPlayer>()
    private val layerKeySignatures = mutableMapOf<String, String>()

    /** Adds a video on top of the main track at the playhead (picture-in-picture). */
    // --------------------------------------------------------- screen recording

    /** Where the video preview sits on the screen, kept up to date by the screen. */
    var previewCrop: com.reelforge.editor.record.CropBox? = null

    /**
     * Puts a finished screen recording into the project: cut down to the preview first when
     * "Preview only" was chosen, then added as a clip or video layer, and saved to the gallery.
     */
    fun useScreenRecording(file: java.io.File) {
        val rec = com.reelforge.editor.record.ScreenRecording
        val settings = rec.settings
        val crop = rec.crop
        val done: (java.io.File) -> Unit = { result ->
            val uri = Uri.fromFile(result)
            when (settings.target) {
                com.reelforge.editor.record.RecordTarget.CLIP -> addMedia(listOf(uri))
                com.reelforge.editor.record.RecordTarget.LAYER -> addVideoLayer(uri)
                com.reelforge.editor.record.RecordTarget.NONE -> Unit
            }
            if (settings.saveToGallery) {
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { MediaUtils.saveToGallery(ctx, result, result.name) }
                    }
                }
            }
            message = when (settings.target) {
                com.reelforge.editor.record.RecordTarget.NONE ->
                    if (settings.saveToGallery) "Screen recording saved to the gallery" else "Screen recording finished"
                else -> "Screen recording added"
            }
            rec.status = com.reelforge.editor.record.ScreenRecording.Status.IDLE
        }
        if (crop == null || rec.finishedWidth <= 0 || rec.finishedHeight <= 0) {
            done(file)
            return
        }
        // Cut the recording down to the preview's area.
        val (w, h) = crop.outputSize(rec.finishedWidth, rec.finishedHeight)
        val ndc = crop.toNdc()
        val out = java.io.File(file.parentFile, file.nameWithoutExtension + "_preview.mp4")
        val edited = androidx.media3.transformer.EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(file)))
            .setEffects(
                androidx.media3.transformer.Effects(
                    emptyList(),
                    listOf(
                        androidx.media3.effect.Crop(ndc[0], ndc[1], ndc[2], ndc[3]),
                        androidx.media3.effect.Presentation.createForWidthAndHeight(
                            w, h, androidx.media3.effect.Presentation.LAYOUT_STRETCH_TO_FIT,
                        ),
                    ),
                )
            )
            .build()
        val transformer = Transformer.Builder(ctx)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    file.delete()
                    done(out)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    // Keep the whole-screen recording rather than lose it.
                    out.delete()
                    done(file)
                }
            })
            .build()
        runCatching { transformer.start(edited, out.absolutePath) }.onFailure { done(file) }
    }

    // ------------------------------------------------------- stretching bars

    /** Lengths of sound files, so a sound can be stretched only as far as its file goes. */
    private val soundLengths = mutableStateMapOf<String, Long>()

    fun soundLength(uri: String): Long? {
        soundLengths[uri]?.let { return it }
        if (requested.add("len:$uri")) {
            viewModelScope.launch {
                val ms = withContext(Dispatchers.IO) {
                    runCatching { MediaUtils.audioDurationMs(ctx, Uri.parse(uri)) }.getOrDefault(0L)
                }
                if (ms > 0) soundLengths[uri] = ms
            }
        }
        return null
    }

    /**
     * Drags one end of a timeline bar by [deltaMs]: texts, stickers and pictures change how long
     * they show; video layers, sounds and the music are trimmed.
     */
    fun resizeBar(kind: com.reelforge.editor.ui.ItemKind, id: String, atStart: Boolean, deltaMs: Long, snapMs: Long) {
        val p = project ?: return
        val points = ProjectOps.snapPoints(p, excludeId = id) + playheadMs
        when (kind) {
            com.reelforge.editor.ui.ItemKind.TEXT -> updateText(id) {
                val (s, e) = ProjectOps.resizeEdge(it.startMs, it.endMs, atStart, deltaMs, points, snapMs)
                it.copy(startMs = s, endMs = e)
            }
            com.reelforge.editor.ui.ItemKind.LAYER -> updateLayer(id) {
                val (s, e) = ProjectOps.resizeEdge(it.startMs, it.endMs, atStart, deltaMs, points, snapMs)
                it.copy(startMs = s, endMs = e)
            }
            com.reelforge.editor.ui.ItemKind.VIDEO -> updateVideoLayer(id) { v ->
                // The start can move back only as far as the video has more at its beginning.
                val end = v.startMs + v.durationMs
                val (s, e) = ProjectOps.resizeEdge(
                    v.startMs, end, atStart, deltaMs, points, snapMs,
                    minStart = maxOf(0L, v.startMs - v.trimStartMs),
                    maxEnd = v.startMs + (v.sourceDurationMs - v.trimStartMs),
                )
                if (atStart) v.copy(startMs = s, trimStartMs = v.trimStartMs + (s - v.startMs))
                else v.copy(trimEndMs = v.trimStartMs + (e - s))
            }
            com.reelforge.editor.ui.ItemKind.SOUND -> updateAudioClip(id) { a ->
                val fileLength = soundLength(a.uri) ?: (a.offsetMs + a.durationMs)
                val (s, e) = ProjectOps.resizeEdge(
                    a.startMs, a.startMs + a.durationMs, atStart, deltaMs, points, snapMs,
                    minStart = maxOf(0L, a.startMs - a.offsetMs),
                    maxEnd = a.startMs + (fileLength - a.offsetMs),
                )
                if (atStart) a.copy(startMs = s, offsetMs = a.offsetMs + (s - a.startMs), durationMs = e - s)
                else a.copy(durationMs = e - s)
            }
            com.reelforge.editor.ui.ItemKind.MUSIC -> updateMusic { m ->
                val end = m.windowEnd(p.durationMs)
                val (s, e) = ProjectOps.resizeEdge(m.startMs, end, atStart, deltaMs, points, snapMs)
                if (atStart) m.copy(startMs = s, offsetMs = (m.offsetMs + (s - m.startMs)).coerceAtLeast(0L))
                else m.copy(stopMs = e)
            }
        }
    }

    // ------------------------------------------------------------ colour clips

    /**
     * Adds a plain colour (or a top-to-bottom blend of [top] and [bottom]) as a photo clip the
     * shape of the project, for titles, intros, endings and gaps. Made on the phone: no download.
     */
    fun addColourClip(top: Int, bottom: Int = top) {
        val p = project ?: return
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val short = 1080f
                    val ratio = p.aspect.w.toFloat() / p.aspect.h
                    val w = (if (ratio >= 1f) short * ratio else short).toInt() / 2 * 2
                    val h = (if (ratio >= 1f) short else short / ratio).toInt() / 2 * 2
                    fun hex(c: Int) = "%06X".format(c and 0xFFFFFF)
                    val name = if (top == bottom) "Colour_${hex(top)}" else "Gradient_${hex(top)}_${hex(bottom)}"
                    val dir = File(ctx.filesDir, "colours").apply { mkdirs() }
                    val f = File(dir, "${name}_${w}x$h.png")
                    if (f.length() == 0L) {
                        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(bitmap)
                        val paint = android.graphics.Paint()
                        if (top == bottom) {
                            canvas.drawColor(top or (0xFF shl 24))
                        } else {
                            paint.shader = android.graphics.LinearGradient(
                                0f, 0f, 0f, h.toFloat(), top or (0xFF shl 24), bottom or (0xFF shl 24),
                                android.graphics.Shader.TileMode.CLAMP,
                            )
                            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
                        }
                        f.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                    f
                }.getOrNull()
            }
            if (file == null) {
                message = "Could not make the colour picture"
                return@launch
            }
            addMedia(listOf(Uri.fromFile(file)))
            message = "Colour added to the end of your video"
        }
    }

    fun addVideoLayer(uri: Uri) {
        val p = project ?: return
        if (p.videoLayers.size >= ExportManager.MAX_VIDEO_LAYERS) {
            message = "Up to ${ExportManager.MAX_VIDEO_LAYERS} video layers can play at once"
            return
        }
        val start = playheadMs
        viewModelScope.launch {
            val layer = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                val info = MediaUtils.probe(ctx, uri)
                val aspect = MediaUtils.videoAspect(ctx, uri)
                if (info == null || info.type != ClipType.VIDEO || aspect == null) null
                else VideoLayer(
                    uri = uri.toString(),
                    sourceDurationMs = info.durationMs,
                    startMs = start,
                    aspect = aspect,
                    hasAudio = info.hasAudio,
                )
            }
            if (layer == null) message = "That video could not be read"
            else mutate { it.copy(videoLayers = it.videoLayers + layer) }
        }
    }

    fun updateVideoLayer(id: String, block: (VideoLayer) -> VideoLayer) = mutate { p ->
        p.copy(videoLayers = p.videoLayers.map { if (it.id == id) block(it) else it })
    }

    fun deleteVideoLayer(id: String) =
        mutate { p -> p.copy(videoLayers = p.videoLayers.filterNot { it.id == id }) }

    /** The preview player for one video layer; created the first time the layer is shown. */
    fun layerPlayer(layer: VideoLayer): ExoPlayer = layerPlayers.getOrPut(layer.id) {
        ExoPlayer.Builder(ctx).build().also {
            it.setMediaItem(MediaItem.fromUri(Uri.parse(layer.uri)))
            it.prepare()
        }
    }

    /** Lines the layer players up with the playhead. Call after a layer's player is created. */
    fun resyncLayers() {
        project?.let { syncLayers(it, player.isPlaying) }
    }

    private fun syncLayers(p: Project, playing: Boolean) {
        val ids = p.videoLayers.map { it.id }.toSet()
        layerPlayers.keys.filter { it !in ids }.forEach { id ->
            layerPlayers.remove(id)?.release()
            layerKeySignatures.remove(id)
        }
        p.videoLayers.forEach { layer ->
            val layerPlayer = layerPlayers[layer.id] ?: return@forEach
            val signature = if (layer.chromaKey) "${layer.keyColor}|${layer.keyTolerance}" else ""
            if (signature != (layerKeySignatures[layer.id] ?: "")) {
                layerKeySignatures[layer.id] = signature
                layerPlayer.setVideoEffects(
                    if (layer.chromaKey) listOf(ChromaKeyEffect(layer.keyColor, layer.keyTolerance))
                    else emptyList()
                )
            }
            val layerVolume = if (layer.muted) 0f else layer.volume.coerceIn(0f, 1f)
            if (layerPlayer.volume != layerVolume) layerPlayer.volume = layerVolume
            val local = playheadMs - layer.startMs
            if (local < 0 || local >= layer.durationMs) {
                if (layerPlayer.playWhenReady) layerPlayer.playWhenReady = false
                return@forEach
            }
            val wanted = layer.trimStartMs + local
            val drift = kotlin.math.abs(layerPlayer.currentPosition - wanted)
            if (drift > (if (playing) 250 else 40)) layerPlayer.seekTo(wanted)
            if (layerPlayer.playWhenReady != playing) layerPlayer.playWhenReady = playing
        }
    }

    private fun releaseLayerPlayers() {
        layerPlayers.values.forEach { it.release() }
        layerPlayers.clear()
        layerKeySignatures.clear()
    }

    // ------------------------------------------------- voice-over + sound clips

    fun addAudioClip(uri: Uri) {
        val start = playheadMs
        viewModelScope.launch {
            val clip = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                AudioClip(
                    uri = uri.toString(),
                    title = MediaUtils.displayName(ctx, uri),
                    startMs = start,
                    durationMs = MediaUtils.audioDurationMs(ctx, uri),
                )
            }
            mutate { it.copy(audio = it.audio + clip) }
        }
    }

    var isSpeaking by mutableStateOf(false)
        private set

    /** Turns typed text into a spoken sound clip at the playhead, with the AI Voice settings. */
    fun speakText(text: String) {
        val words = text.trim().take(3_900)
        if (words.isEmpty() || isSpeaking || project == null) return
        val start = playheadMs
        val dir = File(ctx.filesDir, "voice").apply { mkdirs() }
        val file = File(dir, "speech_${System.currentTimeMillis()}.wav")
        isSpeaking = true
        stopVoicePreview()
        SpeechMaker.speak(ctx, words, file, aiVoiceName, aiRate, aiPitch) { ok ->
            if (!ok) {
                isSpeaking = false
                file.delete()
                toast(
                    if (aiVoiceName != null) "That voice could not speak. Download it in the phone's speech settings, or pick another."
                    else "This phone has no text-to-speech voice installed",
                    ToastKind.ERROR,
                )
                return@speak
            }
            viewModelScope.launch {
                val uri = Uri.fromFile(file)
                val duration = withContext(Dispatchers.IO) { MediaUtils.audioDurationMs(ctx, uri) }
                isSpeaking = false
                val clip = AudioClip(
                    uri = uri.toString(), title = "AI voice: " + words.take(18), startMs = start, durationMs = duration,
                    voice = true,
                )
                mutate { it.copy(audio = it.audio + clip) }
                toast("Voice added at the red line", undo = true)
            }
        }
    }

    // ------------------------------------------------------------ AI voice

    private var voiceEngine: android.speech.tts.TextToSpeech? = null

    /** null while the phone's speech engine starts, false when there is none. */
    var voiceEngineReady by mutableStateOf<Boolean?>(null)
        private set

    /** The phone's offline voices in the languages AI Voice offers. */
    var aiVoices by mutableStateOf<List<com.reelforge.editor.util.AiVoice>>(emptyList())
        private set

    /** The chosen voice (null = the phone's default). */
    var aiVoiceName by mutableStateOf<String?>(null)
    var aiRate by mutableFloatStateOf(1f)
    var aiPitch by mutableFloatStateOf(1f)
    var previewingVoice by mutableStateOf(false)
        private set

    /** Starts the speech engine (once) and lists its voices. Call again after voices are downloaded. */
    fun loadAiVoices(again: Boolean = false) {
        if (voiceEngine != null && !again) return
        runCatching { voiceEngine?.shutdown() }
        voiceEngineReady = null
        var engine: android.speech.tts.TextToSpeech? = null
        engine = android.speech.tts.TextToSpeech(ctx) { status ->
            val tts = engine
            if (status != android.speech.tts.TextToSpeech.SUCCESS || tts == null) {
                voiceEngineReady = false
                return@TextToSpeech
            }
            val infos = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet()).map { v ->
                com.reelforge.editor.util.VoiceInfo(
                    v.name, v.locale.toLanguageTag(), v.isNetworkConnectionRequired,
                    android.speech.tts.TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty(),
                )
            }
            aiVoices = com.reelforge.editor.util.AiVoices.choose(infos)
            if (aiVoiceName != null && aiVoices.none { it.name == aiVoiceName }) aiVoiceName = null
            tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    viewModelScope.launch { previewingVoice = false }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    viewModelScope.launch { previewingVoice = false }
                }
            })
            voiceEngineReady = true
        }
        voiceEngine = engine
    }

    /** Plays [text] (or a sample) with the chosen voice, speed and pitch, without adding anything. */
    fun previewVoice(text: String) {
        val tts = voiceEngine ?: return
        if (previewingVoice) {
            stopVoicePreview()
            return
        }
        val voice = aiVoices.firstOrNull { it.name == aiVoiceName }
        val words = text.trim().ifEmpty { sampleFor(voice?.language) }.take(400)
        runCatching {
            tts.voices?.firstOrNull { it.name == aiVoiceName }?.let { tts.voice = it }
            tts.setSpeechRate(aiRate)
            tts.setPitch(aiPitch)
            pause()
            previewingVoice = tts.speak(words, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "preview") ==
                android.speech.tts.TextToSpeech.SUCCESS
        }
    }

    fun stopVoicePreview() {
        runCatching { voiceEngine?.stop() }
        previewingVoice = false
    }

    private fun sampleFor(language: String?): String = when (language) {
        "hi-IN" -> "नमस्ते दोस्तों, मेरे चैनल में आपका स्वागत है।"
        "te-IN" -> "నమస్కారం మిత్రులారా, నా ఛానెల్‌కి స్వాగతం."
        "ta-IN" -> "வணக்கம் நண்பர்களே, என் சேனலுக்கு வரவேற்கிறேன்."
        "kn-IN" -> "ನಮಸ್ಕಾರ ಸ್ನೇಹಿತರೆ, ನನ್ನ ಚಾನೆಲ್‌ಗೆ ಸ್ವಾಗತ."
        "ml-IN" -> "നമസ്കാരം സുഹൃത്തുക്കളെ, എന്റെ ചാനലിലേക്ക് സ്വാഗതം."
        "mr-IN" -> "नमस्कार मित्रांनो, माझ्या चॅनेलवर आपले स्वागत आहे."
        "bn-IN" -> "নমস্কার বন্ধুরা, আমার চ্যানেলে স্বাগতম।"
        "gu-IN" -> "નમસ્તે મિત્રો, મારી ચેનલમાં આપનું સ્વાગત છે."
        "pa-IN" -> "ਸਤ ਸ੍ਰੀ ਅਕਾਲ ਦੋਸਤੋ, ਮੇਰੇ ਚੈਨਲ ਵਿੱਚ ਜੀ ਆਇਆਂ ਨੂੰ।"
        "ur-IN" -> "آداب دوستو، میرے چینل میں خوش آمدید۔"
        else -> "Hi friends, welcome to my channel. Don't forget to subscribe."
    }

    fun updateAudioClip(id: String, block: (AudioClip) -> AudioClip) = mutate { p ->
        p.copy(audio = p.audio.map { if (it.id == id) block(it) else it })
    }

    fun deleteAudioClip(id: String) {
        mutate { p -> p.copy(audio = p.audio.filterNot { it.id == id }) }
        toast("Sound deleted", undo = true)
    }

    /** Starts recording from the microphone at the playhead while the video plays. */
    fun startRecording() {
        val p = project ?: return
        if (isRecording || p.clips.isEmpty()) return
        val dir = File(ctx.filesDir, "voice").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        var file = File(dir, if (settings.cleanVoice) "voice_$stamp.wav" else "voice_$stamp.m4a")
        try {
            var clean = settings.cleanVoice
            val r: com.reelforge.editor.record.VoiceCapture = try {
                if (clean) com.reelforge.editor.record.CleanCapture(file).also { it.start() }
                else com.reelforge.editor.record.MicCapture(ctx, file).also { it.start() }
            } catch (e: Exception) {
                if (!clean) throw e
                // This phone's voice-call path is not available; record plainly instead.
                file.delete()
                clean = false
                file = File(dir, "voice_$stamp.m4a")
                com.reelforge.editor.record.MicCapture(ctx, file).also { it.start() }
            }
            recordingClean = clean
            recorder = r
            recordFile = file
            if (playheadMs >= p.durationMs - 200) seekTo(0)
            recordStartMs = playheadMs
            isRecording = true
            stopVoice()
            // Play the video without sound, so the microphone hears only the speaker.
            if (settings.silentWhileRecording) {
                player.volume = 0f
                musicPlayer.volume = 0f
            }
            player.play()
        } catch (e: Exception) {
            file.delete()
            message = "Could not start recording"
        }
    }

    // --------------------------------------------------- voice-over from the video

    /** Seconds left before a voice-over starts recording (0 when not counting down). */
    var voiceCountdown by mutableIntStateOf(0)
        private set

    /** The voice-over just recorded, offered for Retake / Undo for a few seconds. */
    var lastVoice by mutableStateOf<AudioClip?>(null)

    /** Where on the timeline the current voice-over started. */
    val recordingFromMs: Long get() = recordStartMs

    /** How loud the microphone is right now (0..1), for the level bar while recording. */
    var micLevel by mutableStateOf(0f)
        private set

    /** Stops the voice-over being recorded and throws it away. */
    fun cancelRecording() {
        if (!isRecording) return
        isRecording = false
        player.pause()
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        recordFile?.delete()
        recordFile = null
        micLevel = 0f
        applyClipParams()
        project?.music?.let { musicPlayer.volume = it.volume.coerceIn(0f, 1f) }
        message = "Voice-over cancelled"
    }

    private var countdownJob: Job? = null
    private var replaceVoice = false

    /** True when a voice-over is already playing at the red line. */
    fun voiceAtPlayhead(): Boolean =
        project?.audio?.any { it.voice && playheadMs >= it.startMs && playheadMs < it.startMs + it.durationMs } == true

    /**
     * Records a voice-over from the red line while the video plays, after the countdown. With
     * [replace], older voice-overs are cut out where the new one goes.
     */
    fun startVoiceOver(replace: Boolean) {
        val p = project ?: return
        if (isRecording || voiceCountdown > 0 || p.clips.isEmpty()) return
        if (playheadMs >= p.durationMs - 200) seekTo(0)
        replaceVoice = replace
        lastVoice = null
        player.pause()
        countdownJob = viewModelScope.launch {
            for (left in settings.voiceCountdown downTo 1) {
                voiceCountdown = left
                delay(1_000)
            }
            voiceCountdown = 0
            startRecording()
        }
    }

    fun cancelVoiceCountdown() {
        countdownJob?.cancel()
        voiceCountdown = 0
    }

    /** Throws away the voice-over just recorded. */
    fun undoVoice() {
        if (lastVoice == null) return
        lastVoice = null
        undo()
    }

    /** Throws away the voice-over just recorded and records it again from the same place. */
    fun retakeVoice() {
        val clip = lastVoice ?: return
        lastVoice = null
        undo()
        seekTo(clip.startMs)
        startVoiceOver(replaceVoice)
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        player.pause()
        // Bring the video's (and music's) sound back at once.
        applyClipParams()
        project?.music?.let { musicPlayer.volume = it.volume.coerceIn(0f, 1f) }
        val file = recordFile
        val ok = runCatching { recorder?.stop() == true }.getOrDefault(false)
        runCatching { recorder?.release() }
        recorder = null
        recordFile = null
        if (!ok || file == null) {
            file?.delete()
            message = "Recording was too short"
            return
        }
        val start = recordStartMs
        viewModelScope.launch {
            val uri = Uri.fromFile(file)
            val duration = withContext(Dispatchers.IO) { MediaUtils.audioDurationMs(ctx, uri) }
            val number = (project?.audio?.count { it.title.startsWith("Voice") } ?: 0) + 1
            val clip = AudioClip(
                uri = uri.toString(), title = "Voice $number", startMs = start, durationMs = duration, voice = true,
                clean = recordingClean,
                audioFx = settings.recordFx,
                pitch = com.reelforge.editor.model.pitchOfSemitones(settings.recordSemitones.toFloat()),
            )
            val replace = replaceVoice
            // One step for Undo: cutting out the old voice (if replacing) and adding the new one.
            mutate {
                val kept = if (replace) ProjectOps.cutOutVoice(it.audio, start, start + duration) else it.audio
                it.copy(audio = kept + clip)
            }
            lastVoice = clip
        }
    }

    private fun stopVoice() {
        voiceLane.stop()
        soundLane.stop()
    }

    /** Plays the voice-over and the other sound clip under the playhead in the preview. */
    private fun syncVoice(p: Project) {
        if (isRecording) {
            stopVoice()
            return
        }
        val here = p.audio.filter { playheadMs >= it.startMs && playheadMs < it.startMs + it.durationMs }
        val voice = here.firstOrNull { it.voice }
        voiceLane.sync(p, voice)
        // A sound taken from a video is lowered under voice-overs, as the video's own sound is.
        val sound = here.firstOrNull { !it.voice }
        soundLane.sync(p, sound, if (sound?.duck == true && voice != null) p.duckLevel else 1f)
    }

    /** One preview player for sound clips, with its live effect. */
    private inner class SoundLane(app: Application) {
        val fx = com.reelforge.editor.engine.LiveAudioFx()
        val player: ExoPlayer = ExoPlayer.Builder(app, com.reelforge.editor.engine.renderersWithFx(app, fx)).build()
        private var clipId: String? = null

        fun stop() {
            if (clipId != null) {
                player.pause()
                clipId = null
            }
        }

        fun effectsOff() {
            fx.fx = com.reelforge.editor.model.AudioFx.NONE
            fx.clean = false
            fx.denoise = com.reelforge.editor.model.NoiseRemoval.OFF
        }

        fun sync(p: Project, clip: AudioClip?, level: Float = 1f) {
            if (clip == null) {
                stop()
                return
            }
            // Effect and clean-up are picked up live, so changing them is heard straight away.
            fx.fx = if (previewEffectsOff) com.reelforge.editor.model.AudioFx.NONE else clip.audioFx
            fx.clean = clip.clean && !previewEffectsOff
            fx.denoise = if (previewEffectsOff) com.reelforge.editor.model.NoiseRemoval.OFF else clip.denoise
            fx.amount = clip.fxAmount
            val pitch = if (previewEffectsOff) 1f else clip.totalPitch
            if (player.playbackParameters.pitch != pitch) {
                player.playbackParameters = PlaybackParameters(1f, pitch)
            }
            if (clip.id != clipId) {
                clipId = clip.id
                player.setMediaItem(MediaItem.fromUri(Uri.parse(clip.uri)))
                player.prepare()
                player.seekTo(clip.offsetMs + playheadMs - clip.startMs)
                player.play()
            }
            val length = minOf(clip.durationMs, p.durationMs - clip.startMs)
            val volume = clip.volume.coerceIn(0f, 1f) * level *
                fadeAlpha(playheadMs - clip.startMs, length, clip.fadeInMs, clip.fadeOutMs)
            if (kotlin.math.abs(player.volume - volume) > 0.005f) player.volume = volume
        }
    }

    // ------------------------------------------------------------ auto captions

    /** What is shown while captions are being made, e.g. "Listening… 40%" (null when idle). */
    var captionWork by mutableStateOf<String?>(null)
        private set
    /** How far Auto captions has got, 0..1. */
    var captionProgress by mutableFloatStateOf(0f)
        private set
    private var captionJob: Job? = null
    // Updates from background work are handed to the main thread through these; each job clears
    // its own when it ends, so a late update cannot reappear after the job is over.
    private val captionHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val cutoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastStatusAt = 0L

    /** Shows [text] from any thread, at most a few times a second. */
    private fun postStatus(handler: android.os.Handler, text: String, set: (String) -> Unit) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatusAt < 250) return
        lastStatusAt = now
        handler.post { set(text) }
    }

    fun captionModelReady(language: com.reelforge.editor.engine.CaptionLanguage) =
        com.reelforge.editor.engine.CaptionModels.isInstalled(ctx, language)

    /** How many captions on the timeline were made by Auto captions. */
    val autoCaptionCount: Int get() = project?.texts?.count { it.id.startsWith(CAPTION_PREFIX) } ?: 0

    /** The sound to listen to, and how each file's time maps onto the timeline. */
    private fun captionPieces(p: Project, fromVideo: Boolean, fromVoice: Boolean): List<com.reelforge.editor.engine.SpeechPiece> {
        val pieces = mutableListOf<com.reelforge.editor.engine.SpeechPiece>()
        if (fromVideo) {
            p.clips.forEachIndexed { i, c ->
                if (c.type != ClipType.VIDEO || !c.hasAudio || c.muted || c.volume <= 0f) return@forEachIndexed
                val start = p.clipStartMs(i)
                val speed = c.effectiveSpeed
                pieces += com.reelforge.editor.engine.SpeechPiece(c.uri, c.trimStartMs, c.trimEndMs) { t ->
                    start + ((t - c.trimStartMs) / speed).toLong()
                }
            }
        }
        if (fromVoice) {
            p.audio.filter { it.voice }.forEach { a ->
                pieces += com.reelforge.editor.engine.SpeechPiece(a.uri, a.offsetMs, a.offsetMs + a.durationMs) { t ->
                    a.startMs + (t - a.offsetMs)
                }
            }
        }
        return pieces
    }

    /**
     * Listens to the video's sound and/or the voice-overs and adds what is said as timed texts.
     * Captions made earlier by this are replaced. The speech model is downloaded the first time.
     */
    fun makeCaptions(language: com.reelforge.editor.engine.CaptionLanguage, fromVideo: Boolean, fromVoice: Boolean) {
        val p = project ?: return
        if (captionJob?.isActive == true) return
        val pieces = captionPieces(p, fromVideo, fromVoice)
        if (pieces.isEmpty()) {
            message = if (fromVoice && !fromVideo) "There are no voice-overs to caption yet"
            else "These clips have no sound to caption"
            return
        }
        captionProgress = 0f
        pause()
        captionJob = viewModelScope.launch {
            val set: (String) -> Unit = { captionWork = it }
            try {
                if (!captionModelReady(language)) {
                    captionWork = "Downloading ${language.label} speech (${language.sizeMb} MB)…"
                    withContext(Dispatchers.IO) {
                        com.reelforge.editor.engine.CaptionModels.install(ctx, language) { pct ->
                            postStatus(captionHandler, "Downloading ${language.label} speech… $pct%", set)
                            captionHandler.post { captionProgress = pct / 100f }
                        }
                    }
                }
                captionWork = "Listening…"
                captionProgress = 0f
                // Progress counts by length, so a long clip moves the bar more than a short one.
                val soundMs = pieces.sumOf { (it.toMs - it.fromMs).coerceAtLeast(1) }.toFloat()
                val words = withContext(Dispatchers.IO) {
                    com.reelforge.editor.engine.Transcriber(ctx, language).use { t ->
                        var done = 0L
                        pieces.flatMap { piece ->
                            val length = (piece.toMs - piece.fromMs).coerceAtLeast(1)
                            val heard = t.words(ctx, piece, stop = { !isActive }) { f ->
                                val part = ((done + f * length) / soundMs).coerceIn(0f, 1f)
                                postStatus(captionHandler, "Listening… ${(part * 100).toInt()}%", set)
                                captionHandler.post { captionProgress = part }
                            }
                            done += length
                            heard
                        }
                    }
                }
                val lines = com.reelforge.editor.engine.CaptionText.group(words, spaced = language.spaced)
                if (lines.isEmpty()) {
                    message = "No speech was heard. Try another language, or check the sound is on."
                    return@launch
                }
                val total = project?.durationMs ?: p.durationMs
                val items = lines.filter { it.startMs < total }.map { line ->
                    TextItem(
                        id = CAPTION_PREFIX + newId(),
                        text = language.shown(line.text),
                        size = 0.06f,
                        y = -0.62f,
                        startMs = line.startMs.coerceAtLeast(0),
                        endMs = line.endMs.coerceAtMost(total),
                        outline = true,
                        outlineWidth = 1.4f,
                    )
                }
                mutate { it.copy(texts = it.texts.filterNot { t -> t.id.startsWith(CAPTION_PREFIX) } + items) }
                message = "Added ${items.size} captions. Tap one to fix a word or change the style."
                com.reelforge.editor.work.WorkService.finished(ctx, "Captions are ready", "${items.size} captions added. Open CutMitra to check them.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                message = "Could not download the speech model. Check the internet and try again."
            } catch (e: Throwable) {
                // Includes a speech library that cannot load on this phone.
                message = "Could not make captions on this phone"
            } finally {
                captionHandler.removeCallbacksAndMessages(null)
                captionWork = null
                captionProgress = 0f
                refreshSpeechModels()
            }
        }
    }

    /** Folder names of the speech models on this phone (Hindi and Hinglish share one). */
    var speechModels by mutableStateOf(installedSpeechModels())
        private set

    private fun installedSpeechModels(): Set<String> = com.reelforge.editor.engine.CaptionLanguage.entries
        .filter { captionModelReady(it) }.map { it.model }.toSet()

    /** Download progress (0..100) per model folder while it downloads from the plug-in store. */
    val speechProgress = mutableStateMapOf<String, Int>()
    private val speechJobs = HashMap<String, Job>()

    fun refreshSpeechModels() {
        speechModels = installedSpeechModels()
    }

    /** Downloads a language's speech model ahead of time; tapping again stops it. */
    fun downloadCaptionModel(language: com.reelforge.editor.engine.CaptionLanguage) {
        val key = language.model
        speechJobs[key]?.let {
            it.cancel()
            return
        }
        if (captionModelReady(language)) {
            toast("${language.label} captions are ready", ToastKind.INFO)
            return
        }
        speechProgress[key] = 0
        speechJobs[key] = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    com.reelforge.editor.engine.CaptionModels.install(ctx, language) { pct ->
                        captionHandler.post { if (speechProgress.containsKey(key)) speechProgress[key] = pct }
                    }
                }
                toast("${language.label} captions ready. Use them in Captions → Auto captions.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                toast("Download stopped", ToastKind.INFO)
                throw e
            } catch (e: Throwable) {
                toast("Could not download ${language.label}. Check the internet and try again.", ToastKind.ERROR)
            } finally {
                speechJobs.remove(key)
                speechProgress.remove(key)
                refreshSpeechModels()
            }
        }
    }

    /** Stops Auto captions; nothing is added. */
    fun stopCaptions() {
        captionJob?.cancel()
        message = "Auto captions stopped"
    }

    /** Removes the captions made by Auto captions (texts typed by hand stay). */
    fun removeAutoCaptions() = mutate { p -> p.copy(texts = p.texts.filterNot { it.id.startsWith(CAPTION_PREFIX) }) }

    /** Removes a downloaded speech model to free space. */
    fun deleteCaptionModel(language: com.reelforge.editor.engine.CaptionLanguage) {
        com.reelforge.editor.engine.CaptionModels.delete(ctx, language)
        refreshSpeechModels()
        toast("${language.label} speech model removed", ToastKind.INFO)
    }

    // ------------------------------------------------------------ remove background

    /** What is shown while a background is being removed (null when idle). */
    var cutoutWork by mutableStateOf<String?>(null)
        private set
    private var cutoutJob: Job? = null

    private suspend fun ensureCutoutModel() {
        if (com.reelforge.editor.engine.CutOut.hasModel(ctx)) return
        cutoutWork = "Downloading the cut-out model (${com.reelforge.editor.engine.CutOut.MODEL_MB} MB)…"
        withContext(Dispatchers.IO) {
            com.reelforge.editor.engine.CutOut.downloadModel(ctx) { pct ->
                postStatus(cutoutHandler, "Downloading the cut-out model… $pct%") { cutoutWork = it }
            }
        }
    }

    // ------------------------------------------------------------ super resolution

    /** What is shown while photos are made sharper (null when idle). */
    var enhanceWork by mutableStateOf<String?>(null)
        private set
    var enhanceProgress by mutableFloatStateOf(0f)
        private set
    private var enhanceJob: Job? = null

    /** True when this build carries the Super Resolution model. */
    val superResolutionReady: Boolean by lazy { com.reelforge.editor.engine.SuperResolution.isBundled(ctx) }

    /** Photo clips and picture layers that are small enough to gain from Super Resolution. */
    fun photosToSharpen(): Pair<List<String>, List<String>> {
        val p = project ?: return emptyList<String>() to emptyList()
        return p.clips.filter { it.type == ClipType.IMAGE }.map { it.id } to
            p.layers.filter { it.button == null }.map { it.id }
    }

    /**
     * Makes the given photo clips and picture layers four times sharper, one after another, and
     * swaps each for its sharper copy (Undo brings the old one back). Photos that are already large
     * are left as they are.
     */
    fun sharpenPhotos(clipIds: List<String>, layerIds: List<String>) {
        if (enhanceJob?.isActive == true || project == null) return
        if (!superResolutionReady) {
            toast("Super Resolution is not in this version of the app", ToastKind.ERROR)
            return
        }
        val items = clipIds.map { true to it } + layerIds.map { false to it }
        if (items.isEmpty()) {
            toast("Add a photo first", ToastKind.INFO)
            return
        }
        pause()
        enhanceProgress = 0f
        enhanceJob = viewModelScope.launch {
            var sharpened = 0
            var skipped = 0
            try {
                val upscaler = withContext(Dispatchers.IO) { com.reelforge.editor.engine.SuperResolution.Upscaler(ctx) }
                upscaler.use { up ->
                    items.forEachIndexed { index, (isClip, id) ->
                        val p = project ?: return@forEachIndexed
                        val uri = (if (isClip) p.clips.firstOrNull { it.id == id }?.uri
                        else p.layers.firstOrNull { it.id == id }?.uri) ?: return@forEachIndexed
                        val label = if (items.size == 1) "Making the photo sharper" else "Photo ${index + 1} of ${items.size}"
                        enhanceWork = "$label… 0%"
                        val file = withContext(Dispatchers.Default) {
                            val size = MediaUtils.photoSize(ctx, Uri.parse(uri))
                            if (size != null && !com.reelforge.editor.engine.SuperResolution.worthIt(size.first, size.second)) {
                                return@withContext null
                            }
                            val photo = MediaUtils.decodeImage(ctx, Uri.parse(uri), com.reelforge.editor.engine.SuperResolution.ALREADY_SHARP)
                                ?: return@withContext null
                            val big = up.upscale(photo, { f ->
                                val whole = (index + f) / items.size
                                postStatus(cutoutHandler, "$label… ${(f * 100).toInt()}%") { enhanceWork = it }
                                cutoutHandler.post { enhanceProgress = whole }
                            }, { !isActive })
                            val dir = File(ctx.filesDir, "sharper").apply { mkdirs() }
                            val alpha = photo.hasAlpha()
                            File(dir, "photo_${System.currentTimeMillis()}.${if (alpha) "png" else "jpg"}").also { f ->
                                f.outputStream().use {
                                    big.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, it)
                                }
                                big.recycle()
                                photo.recycle()
                            }
                        }
                        if (file == null) {
                            skipped++
                            return@forEachIndexed
                        }
                        val newUri = Uri.fromFile(file).toString()
                        if (isClip) updateClip(id) { it.copy(uri = newUri) }
                        else updateLayer(id) { it.copy(uri = newUri) }
                        sharpened++
                    }
                }
                toast(
                    when {
                        sharpened == 0 -> "These photos are already sharp (large enough), so nothing changed."
                        skipped > 0 -> "$sharpened photo${if (sharpened == 1) "" else "s"} made sharper; $skipped already sharp."
                        else -> "Photo${if (sharpened == 1) "" else "s"} made 4x sharper. Use Undo to go back."
                    },
                    if (sharpened == 0) ToastKind.INFO else ToastKind.SUCCESS,
                    undo = sharpened > 0,
                )
                if (sharpened > 0) com.reelforge.editor.work.WorkService.finished(ctx, "Photos are sharper", "$sharpened photo(s) done.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                toast("Stopped. Photos already done stay sharper.", ToastKind.INFO)
                throw e
            } catch (e: OutOfMemoryError) {
                toast("This phone ran out of memory for that photo", ToastKind.ERROR)
            } catch (e: Throwable) {
                android.util.Log.e("CutMitra", "Super resolution failed", e)
                toast("Could not make this photo sharper on this phone", ToastKind.ERROR)
            } finally {
                cutoutHandler.removeCallbacksAndMessages(null)
                enhanceWork = null
                enhanceProgress = 0f
            }
        }
    }

    fun cancelSharpen() {
        enhanceJob?.cancel()
    }

    /** Removes the background around the person in a picture layer. */
    fun removeImageBackground(id: String) {
        val layer = project?.layers?.firstOrNull { it.id == id } ?: return
        if (cutoutJob?.isActive == true) return
        cutoutJob = viewModelScope.launch {
            try {
                ensureCutoutModel()
                cutoutWork = "Removing the background…"
                val file = withContext(Dispatchers.IO) {
                    val picture = MediaUtils.decodeImage(ctx, Uri.parse(layer.uri), 1600) ?: error("unreadable")
                    val result = com.reelforge.editor.engine.PersonSegmenter(
                        com.reelforge.editor.engine.CutOut.modelFile(ctx)
                    ).use { it.cutOut(picture) }
                    val dir = File(ctx.filesDir, "cutout").apply { mkdirs() }
                    File(dir, "photo_${System.currentTimeMillis()}.png").also { f ->
                        f.outputStream().use { result.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
                updateLayer(id) { it.copy(uri = Uri.fromFile(file).toString()) }
                message = "Background removed. Use Undo to bring it back."
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                message = "Could not download the cut-out model. Check the internet and try again."
            } catch (e: Throwable) {
                message = "Could not remove the background of this picture"
            } finally {
                cutoutWork = null
            }
        }
    }

    /**
     * Removes the background around the person in a video layer. A copy of the part in use is
     * made with the background painted green, then keyed out like a green screen.
     */
    fun removeVideoBackground(id: String) {
        val layer = project?.videoLayers?.firstOrNull { it.id == id } ?: return
        if (cutoutJob?.isActive == true) return
        val length = layer.trimEndMs - layer.trimStartMs
        if (length > com.reelforge.editor.engine.CutOut.MAX_VIDEO_MS) {
            message = "Trim this video to 1 minute or less first (it is ${MediaUtils.formatTime(length)})"
            return
        }
        pause()
        cutoutJob = viewModelScope.launch {
            var segmenter: com.reelforge.editor.engine.PersonSegmenter? = null
            var overlay: com.reelforge.editor.engine.GreenBackgroundOverlay? = null
            var cutoutTransformer: Transformer? = null
            try {
                ensureCutoutModel()
                cutoutWork = "Removing the background… 0%"
                val dir = File(ctx.filesDir, "cutout").apply { mkdirs() }
                val out = File(dir, "video_${System.currentTimeMillis()}.mp4")
                val seg = withContext(Dispatchers.IO) {
                    com.reelforge.editor.engine.PersonSegmenter(com.reelforge.editor.engine.CutOut.modelFile(ctx))
                }
                segmenter = seg
                val ov = com.reelforge.editor.engine.GreenBackgroundOverlay(ctx, Uri.parse(layer.uri), layer.trimStartMs, seg)
                overlay = ov
                val item = androidx.media3.transformer.EditedMediaItem.Builder(
                    MediaItem.Builder().setUri(Uri.parse(layer.uri))
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(layer.trimStartMs)
                                .setEndPositionMs(layer.trimEndMs)
                                .build()
                        )
                        .build()
                ).setEffects(
                    androidx.media3.transformer.Effects(
                        emptyList(),
                        listOf(androidx.media3.effect.OverlayEffect(com.google.common.collect.ImmutableList.of<androidx.media3.effect.TextureOverlay>(ov))),
                    )
                ).build()
                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { done ->
                    val t = Transformer.Builder(ctx)
                        .setVideoMimeType(androidx.media3.common.MimeTypes.VIDEO_H264)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (done.isActive) done.resumeWith(Result.success(Unit))
                            }

                            override fun onError(
                                composition: Composition, exportResult: ExportResult, exportException: ExportException,
                            ) {
                                out.delete()
                                if (done.isActive) done.resumeWith(Result.failure(exportException))
                            }
                        })
                        .build()
                    cutoutTransformer = t
                    done.invokeOnCancellation { out.delete() }
                    t.start(item, out.absolutePath)
                    // Show progress while it works.
                    val holder = ProgressHolder()
                    val poll = object : Runnable {
                        override fun run() {
                            if (!done.isActive) return
                            if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                                cutoutWork = "Removing the background… ${holder.progress}%"
                            }
                            cutoutHandler.postDelayed(this, 500)
                        }
                    }
                    cutoutHandler.postDelayed(poll, 500)
                }
                val newLength = withContext(Dispatchers.IO) { MediaUtils.probe(ctx, Uri.fromFile(out))?.durationMs }
                    ?: length
                val uri = Uri.fromFile(out).toString()
                updateVideoLayer(id) {
                    it.copy(
                        uri = uri, sourceDurationMs = newLength, trimStartMs = 0, trimEndMs = newLength,
                        chromaKey = true, keyColor = com.reelforge.editor.engine.CutOut.KEY_GREEN, keyTolerance = 0.3f,
                    )
                }
                // The layer's player keeps playing the old file until it is given the new one.
                layerPlayers[id]?.let {
                    it.setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
                    it.prepare()
                }
                resyncLayers()
                message = "Background removed. Use Undo to bring it back."
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                message = "Could not download the cut-out model. Check the internet and try again."
            } catch (e: Throwable) {
                message = "Could not remove the background of this video"
            } finally {
                cutoutHandler.removeCallbacksAndMessages(null)
                // Does nothing if the export already finished.
                runCatching { cutoutTransformer?.cancel() }
                overlay?.close()
                segmenter?.close()
                cutoutWork = null
            }
        }
    }

    fun cancelCutout() {
        cutoutJob?.cancel()
    }

    // ------------------------------------------------------------ photo zoom + speed ramp

    fun setPhotoMotion(id: String, motion: com.reelforge.editor.model.PhotoMotion) =
        updateClip(id) { it.copy(motion = motion) }

    /** Gives every photo the same movement. */
    fun setPhotoMotionForAll(motion: com.reelforge.editor.model.PhotoMotion) = mutate { p ->
        p.copy(clips = p.clips.map { if (it.type == ClipType.IMAGE) it.copy(motion = motion) else it })
    }

    /** Splits a video clip into steps whose speeds follow [ramp]. */
    fun applySpeedRamp(id: String, ramp: com.reelforge.editor.model.SpeedRamp) {
        val p = project ?: return
        val clip = p.clips.firstOrNull { it.id == id } ?: return
        if (clip.trimEndMs - clip.trimStartMs < com.reelforge.editor.model.CreativeOps.MIN_RAMP_SOURCE_MS) {
            message = "This clip is too short for a speed ramp"
            return
        }
        mutate { com.reelforge.editor.model.CreativeOps.speedRamp(it, id, ramp) }
        message = "${ramp.label} ramp added (${com.reelforge.editor.model.CreativeOps.RAMP_STEPS} speed steps). Undo removes it."
    }

    // ------------------------------------------------------------ reverse

    /** What is shown while a clip is being reversed (null when idle). */
    var reverseWork by mutableStateOf<String?>(null)
        private set
    private var reverseJob: Job? = null

    /** Replaces a video clip with a copy that plays backwards (without sound). */
    fun reverseClip(id: String) {
        val clip = project?.clips?.firstOrNull { it.id == id } ?: return
        if (clip.type != ClipType.VIDEO || reverseJob?.isActive == true) return
        val length = clip.trimEndMs - clip.trimStartMs
        if (length > com.reelforge.editor.engine.Reverser.MAX_MS) {
            message = "Trim this clip to 30 seconds or less to reverse it (it is ${MediaUtils.formatTime(length)})"
            return
        }
        pause()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        reverseJob = viewModelScope.launch {
            reverseWork = "Reversing… 0%"
            try {
                val dir = File(ctx.filesDir, "reversed").apply { mkdirs() }
                val out = File(dir, "reverse_${System.currentTimeMillis()}.mp4")
                val newLength = withContext(Dispatchers.IO) {
                    com.reelforge.editor.engine.Reverser.reverse(
                        ctx, Uri.parse(clip.uri), clip.trimStartMs, clip.trimEndMs, out, stop = { !isActive },
                    ) { f -> postStatus(handler, "Reversing… ${(f * 100).toInt()}%") { reverseWork = it } }
                }
                if (newLength <= 0) error("empty")
                updateClip(id) {
                    it.copy(
                        uri = Uri.fromFile(out).toString(), sourceDurationMs = newLength,
                        trimStartMs = 0, trimEndMs = newLength, hasAudio = false,
                        name = "Reversed " + it.name,
                    ).let(ProjectOps::clampFades)
                }
                message = "Clip reversed (it plays without sound). Undo brings the original back."
                com.reelforge.editor.work.WorkService.finished(ctx, "Clip reversed", "Open CutMitra to keep editing.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                message = "Could not reverse this clip on this phone"
            } finally {
                handler.removeCallbacksAndMessages(null)
                reverseWork = null
            }
        }
    }

    fun cancelReverse() {
        reverseJob?.cancel()
    }

    // ------------------------------------------------------------ extract sound

    /** What is shown while a sound is being taken out of a video (null when idle). */
    var soundWork by mutableStateOf<String?>(null)
        private set
    private var soundJob: Job? = null
    private val soundHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** True when [clip] is a video with sound that can be extracted. */
    fun canExtractSound(clip: Clip) = clip.type == ClipType.VIDEO && clip.hasAudio

    fun cancelSoundWork() {
        soundJob?.cancel()
    }

    private fun soundFile() =
        File(File(ctx.filesDir, "sounds").apply { mkdirs() }, "sound_${System.currentTimeMillis()}.m4a")

    /** Runs one sound job with progress shown as "[label]… 40%"; only one runs at a time. */
    private fun soundTask(label: String, failed: String, work: suspend () -> Unit) {
        if (soundJob?.isActive == true) return
        pause()
        soundJob = viewModelScope.launch {
            soundWork = "$label… 0%"
            try {
                work()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("CutMitra", failed, e)
                message = failed
            } finally {
                soundHandler.removeCallbacksAndMessages(null)
                soundWork = null
            }
        }
    }

    private suspend fun extractTo(uri: Uri, fromMs: Long, toMs: Long, speed: Float, pitch: Float, label: String): File {
        val out = soundFile()
        com.reelforge.editor.engine.SoundExtractor.extract(ctx, uri, fromMs, toMs, speed, pitch, out) { f ->
            postStatus(soundHandler, "$label… ${(f * 100).toInt()}%") { soundWork = it }
        }
        if (!out.exists() || out.length() == 0L) error("no sound written")
        return out
    }

    /**
     * Extract sound: the video clip's sound becomes its own sound clip at the same place, and the
     * clip itself is muted, so the sound can be moved, cut, changed or kept after the picture is
     * replaced. It still sounds the same (speed, voice changer and effect carry over) and is still
     * lowered under voice-overs. One step for Undo.
     */
    fun extractSound(id: String) {
        val clip = project?.clips?.firstOrNull { it.id == id } ?: return
        if (!canExtractSound(clip)) {
            message = "This clip has no sound to extract"
            return
        }
        val label = "Extracting sound"
        soundTask(label, "Could not extract the sound of this clip on this phone") {
            val out = extractTo(Uri.parse(clip.uri), clip.trimStartMs, clip.trimEndMs, clip.effectiveSpeed, clip.pitch, label)
            val now = project ?: return@soundTask
            val index = now.clips.indexOfFirst { it.id == id }
            if (index < 0) {
                out.delete()
                return@soundTask
            }
            val current = now.clips[index]
            val sound = AudioClip(
                uri = Uri.fromFile(out).toString(),
                title = "Sound: " + current.name.ifBlank { "clip ${index + 1}" },
                startMs = now.clipStartMs(index),
                durationMs = current.durationMs,
                volume = if (current.muted) 1f else current.volume.coerceIn(0f, 2f),
                fadeInMs = current.fadeInMs,
                fadeOutMs = current.fadeOutMs,
                audioFx = current.audioFx,
                duck = true,
            )
            mutate { p ->
                p.copy(
                    clips = p.clips.map { if (it.id == id) it.copy(muted = true) else it },
                    audio = p.audio + sound,
                )
            }
            message = "Sound extracted to its own clip in Voice-over and sounds. The video clip is now muted " +
                "(Undo puts it back)."
        }
    }

    /** Takes the whole sound of a video from the gallery and adds it as a sound clip at the red line. */
    fun addSoundFromVideo(uri: Uri) {
        val start = playheadMs
        val label = "Taking the sound"
        soundTask(label, "This video has no sound, or its sound cannot be read on this phone") {
            val name = withContext(Dispatchers.IO) { MediaUtils.displayName(ctx, uri) }
            val length = withContext(Dispatchers.IO) { MediaUtils.audioDurationMs(ctx, uri) }
            if (length <= 0) error("unknown length")
            val out = extractTo(uri, 0, length, 1f, 1f, label)
            val duration = withContext(Dispatchers.IO) { MediaUtils.audioDurationMs(ctx, Uri.fromFile(out)) }
                .takeIf { it > 0 } ?: length
            val sound = AudioClip(
                uri = Uri.fromFile(out).toString(),
                title = "Sound: " + name.substringBeforeLast('.').ifBlank { "video" },
                startMs = start,
                durationMs = duration,
                duck = true,
            )
            mutate { it.copy(audio = it.audio + sound) }
            message = "Sound added at the red line"
        }
    }

    /**
     * Saves a sound clip (the part used on the timeline, without its style) to the phone's
     * Music/CutMitra folder as an .m4a file, to use in other apps or share.
     */
    fun saveSoundToPhone(id: String) {
        val clip = project?.audio?.firstOrNull { it.id == id } ?: return
        val label = "Saving sound"
        soundTask(label, "Could not save this sound on this phone") {
            val out = extractTo(Uri.parse(clip.uri), clip.offsetMs, clip.offsetMs + clip.durationMs, 1f, 1f, label)
            val saved = withContext(Dispatchers.IO) {
                com.reelforge.editor.engine.SoundExtractor.saveToMusic(ctx, out, clip.title)
            }
            out.delete()
            message = if (saved != null) "Saved to Music/CutMitra on your phone" else "Could not save this sound"
        }
    }

    // ------------------------------------------------------------ beat sync

    /** What is shown while beats are being found (null when idle). */
    var beatWork by mutableStateOf<String?>(null)
        private set
    private var beatJob: Job? = null

    /** Finds the beats in the background music and marks them on the timeline. */
    fun findBeats() {
        val music = project?.music ?: return
        if (beatJob?.isActive == true) return
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        beatJob = viewModelScope.launch {
            beatWork = "Listening for the beat…"
            try {
                val beats = withContext(Dispatchers.IO) {
                    com.reelforge.editor.engine.BeatFinder.fromFile(
                        ctx, Uri.parse(music.uri), music.durationMs, stop = { !isActive },
                    ) { f -> postStatus(handler, "Listening for the beat… ${(f * 100).toInt()}%") { beatWork = it } }
                }
                if (beats.size < 4) {
                    message = "No clear beat was found in this music"
                    return@launch
                }
                // Only if the same music is still there.
                if (project?.music?.uri == music.uri) {
                    updateMusic { it.copy(beats = beats) }
                    message = "Found the beat: about ${com.reelforge.editor.model.CreativeOps.bpm(beats)} BPM. " +
                        "Beats are marked on the timeline."
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                message = "Could not read this music"
            } finally {
                handler.removeCallbacksAndMessages(null)
                beatWork = null
            }
        }
    }

    fun cancelBeats() {
        beatJob?.cancel()
    }

    fun clearBeats() = updateMusic { it.copy(beats = emptyList()) }

    /** Lengthens or shortens clips so every cut lands on a beat ([every] = every 1st, 2nd or 4th). */
    fun cutClipsToBeats(every: Int) {
        val p = project ?: return
        val beats = com.reelforge.editor.model.CreativeOps.beatTimes(p)
        if (beats.isEmpty()) {
            message = "Find the beats in the music first"
            return
        }
        mutate { com.reelforge.editor.model.CreativeOps.cutToBeats(it, beats, every) }
        message = "Clips now change on the beat. Undo puts them back."
    }

    // ------------------------------------------------------------ templates

    fun applyTemplate(template: com.reelforge.editor.model.Template, title: String, ending: String) {
        val p = project ?: return
        if (p.clips.isEmpty()) {
            message = "Add your photos or videos first, then pick a template"
            return
        }
        mutate { com.reelforge.editor.model.Templates.apply(it, template, title, ending) }
        seekTo(0)
        message = "${template.name} look applied. Tip: add music (try \"${template.musicHint}\" in Free music)."
    }

    // ------------------------------------------------------------ cover

    /** A small picture of the cover being designed (null until made). */
    var coverPreview by mutableStateOf<ImageBitmap?>(null)
        private set
    var coverBusy by mutableStateOf(false)
        private set

    /** The picture behind the cover: [picture] if chosen, else the frame at the red line. */
    private fun coverSource(p: Project, picture: Uri?, side: Int): Bitmap? {
        if (picture != null) return MediaUtils.decodeImage(ctx, picture, side)
        val (index, local) = p.locate(playheadMs) ?: return null
        val clip = p.clips[index]
        val uri = Uri.parse(clip.uri)
        return if (clip.type == ClipType.IMAGE) MediaUtils.decodeImage(ctx, uri, side)
        else MediaUtils.videoFrame(ctx, uri, clip.trimStartMs + (local * clip.effectiveSpeed).toLong(), side)
    }

    fun previewCover(title: String, style: com.reelforge.editor.engine.CoverStyle, picture: Uri?) {
        val p = project ?: return
        viewModelScope.launch {
            val image = withContext(Dispatchers.IO) {
                runCatching {
                    val source = coverSource(p, picture, 720) ?: return@runCatching null
                    val (w, h) = com.reelforge.editor.engine.ExportManager.outputSize(p.aspect, 360)
                    com.reelforge.editor.engine.CoverMaker.render(source, w, h, title, style).asImageBitmap()
                }.getOrNull()
            }
            coverPreview = image
        }
    }

    /**
     * Saves the cover to the gallery (Pictures/CutMitra). With [addToStart], it is also put at the
     * very start of the video for half a second, so apps that use the first frame show it.
     */
    fun saveCover(title: String, style: com.reelforge.editor.engine.CoverStyle, picture: Uri?, addToStart: Boolean) {
        val p = project ?: return
        if (coverBusy) return
        coverBusy = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val source = coverSource(p, picture, 2160) ?: return@withContext null
                    val (w, h) = com.reelforge.editor.engine.ExportManager.outputSize(p.aspect, 1080)
                    val cover = com.reelforge.editor.engine.CoverMaker.render(source, w, h, title, style)
                    val name = "CutMitra_cover_${System.currentTimeMillis()}"
                    val saved = com.reelforge.editor.engine.CoverMaker.saveToGallery(ctx, cover, name)
                    val copy = if (addToStart) {
                        File(File(ctx.filesDir, "covers").apply { mkdirs() }, "$name.jpg").also { f ->
                            f.outputStream().use { cover.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                        }
                    } else null
                    saved to copy
                }
                if (result == null) {
                    message = "Could not make the cover"
                    return@launch
                }
                val (saved, copy) = result
                if (copy != null) {
                    val still = Clip(
                        uri = Uri.fromFile(copy).toString(), type = ClipType.IMAGE, sourceDurationMs = 500,
                        name = "Cover", fill = true,
                    )
                    mutate { it.copy(clips = listOf(still) + it.clips) }
                }
                message = when {
                    saved == null -> "Could not save the cover to the gallery"
                    copy != null -> "Cover saved to Pictures/CutMitra and put at the start of the video"
                    else -> "Cover saved to Pictures/CutMitra. Upload it as the cover on Instagram or YouTube."
                }
            } finally {
                coverBusy = false
            }
        }
    }

    // ------------------------------------------------------------ freeze frame

    /** Inserts a 2-second still of the frame under the playhead. */
    fun freezeFrame() {
        val p = project ?: return
        val (index, local) = p.locate(playheadMs) ?: return
        val clip = p.clips[index]
        if (clip.type != ClipType.VIDEO) {
            message = "Freeze frame works on video clips"
            return
        }
        val offset = (local * clip.effectiveSpeed).toLong()
        val sourceMs = clip.trimStartMs + offset
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                MediaUtils.saveFrame(ctx, Uri.parse(clip.uri), sourceMs)
            }
            if (file == null) {
                message = "Could not capture this frame"
                return@launch
            }
            val still = clip.copy(
                id = newId(),
                uri = Uri.fromFile(file).toString(),
                type = ClipType.IMAGE,
                sourceDurationMs = 2000,
                trimStartMs = 0,
                trimEndMs = 2000,
                speed = 1f,
                hasAudio = false,
                fadeInMs = 0,
                fadeOutMs = 0,
            )
            mutate { current ->
                val i = current.clips.indexOfFirst { it.id == clip.id }
                if (i < 0) return@mutate current
                val c = current.clips[i]
                val length = c.trimEndMs - c.trimStartMs
                val list = current.clips.toMutableList()
                when {
                    offset < 200 -> list.add(i, still)
                    length - offset < 200 -> list.add(i + 1, still)
                    else -> {
                        list[i] = c.copy(trimEndMs = sourceMs, fadeOutMs = 0)
                        list.add(i + 1, still)
                        list.add(i + 2, c.copy(id = newId(), trimStartMs = sourceMs, fadeInMs = 0))
                    }
                }
                current.copy(clips = list)
            }
            toast("Frame frozen for 2 seconds at the red line", undo = true)
            selectedClipId = still.id
        }
    }

    /** Fade the clip out and the next one in, i.e. a dip-to-black transition. 0 removes it. */
    fun setTransitionAfter(id: String, durationMs: Long) =
        mutate { ProjectOps.transitionAfter(it, id, durationMs) }

    // ---------------------------------------------------------------- playback

    /** One piece for the player: a single clip, or several that were only split apart. */
    /**
     * The preview player stopped with an error. The full error goes to the app's log (and so into
     * bug reports). The player is started again where it was; if it fails again soon after, the
     * preview's effects are switched off, which is the usual cause on phones whose video drivers
     * cannot run them, and it starts once more.
     */
    private fun recoverFromPlayerError(error: PlaybackException) {
        android.util.Log.e("CutMitra", "Preview error ${error.errorCodeName}", error)
        val now = SystemClock.elapsedRealtime()
        val again = now - lastPlayerErrorAt < 8_000
        lastPlayerErrorAt = now
        if (again && !previewEffectsOff) {
            previewEffectsOff = true
            mainFx.fx = com.reelforge.editor.model.AudioFx.NONE
            mainFx.clean = false
            mainFx.denoise = com.reelforge.editor.model.NoiseRemoval.OFF
            voiceLane.effectsOff()
            soundLane.effectsOff()
            runCatching { if (effectsEverSet) player.setVideoEffects(emptyList()) }
            message = "The preview had a problem on this phone, so filters and sound effects are " +
                "hidden in the preview. Your exported video still has them."
        } else if (again) {
            message = "The preview could not play this part (${error.errorCodeName}). " +
                "Try Report a bug in Settings so we can fix it."
            return
        }
        // Start again from the same place; playlists keep their position after prepare().
        val wasPlaying = player.playWhenReady
        runCatching {
            player.prepare()
            player.playWhenReady = wasPlaying
        }
    }

    private fun toMediaItem(clips: List<Clip>, range: IntRange): MediaItem {
        val clip = clips[range.first]
        if (range.first == range.last) return toMediaItem(clip)
        return MediaItem.Builder().setUri(Uri.parse(clip.uri)).setMediaId(clip.id)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.trimStartMs)
                    .setEndPositionMs(clips[range.last].trimEndMs)
                    .build()
            )
            .build()
    }

    /** The clip the player is showing now and how far into it, in the source file's own time. */
    private fun shownClip(p: Project): Pair<Int, Long>? =
        ProjectOps.fromPlayPosition(p.clips, playGroups, player.currentMediaItemIndex, player.currentPosition)

    private fun toMediaItem(clip: Clip): MediaItem {
        val uri = Uri.parse(clip.uri)
        val builder = MediaItem.Builder().setUri(uri).setMediaId(clip.id)
        if (clip.type == ClipType.IMAGE) {
            builder.setImageDurationMs(clip.durationMs)
            val mime = if (uri.scheme == "file") "image/jpeg" else ctx.contentResolver.getType(uri)
            if (mime != null) builder.setMimeType(mime)
        } else {
            builder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.trimStartMs)
                    .setEndPositionMs(clip.trimEndMs)
                    .build()
            )
        }
        return builder.build()
    }

    /** Brings both players in line with the current project. */
    private fun syncPlayer() {
        val p = project ?: return
        // Speed and pitch are part of this because they decide which split clips can share a piece.
        val signature = p.clips.joinToString("|") {
            "${it.id}:${it.trimStartMs}:${it.trimEndMs}:${it.uri.hashCode()}:${it.effectiveSpeed}:${it.pitch}"
        }
        if (signature != playlistSignature) {
            playlistSignature = signature
            val wasPlaying = player.isPlaying
            playGroups = ProjectOps.playGroups(p.clips)
            if (p.clips.isEmpty()) {
                player.clearMediaItems()
            } else {
                val (index, local) = p.locate(playheadMs) ?: (0 to 0L)
                val source = (local * p.clips[index].effectiveSpeed).toLong()
                val (piece, position) = ProjectOps.toPlayPosition(p.clips, playGroups, index, source)
                player.setMediaItems(playGroups.map { toMediaItem(p.clips, it) }, piece, position)
                player.prepare()
                player.playWhenReady = wasPlaying
            }
        }
        applyClipParams()
        syncMusic()
    }

    private fun applyClipParams() {
        val p = project ?: return
        val shown = shownClip(p) ?: return
        val clip = p.clips.getOrNull(shown.first) ?: return
        shownClipIndex = shown.first
        // The preview player tops out at 8x; faster clips still export at their full speed.
        mainFx.fx = if (clip.type == ClipType.VIDEO) clip.audioFx else com.reelforge.editor.model.AudioFx.NONE
        mainFx.denoise = if (clip.type == ClipType.VIDEO) clip.denoise else com.reelforge.editor.model.NoiseRemoval.OFF
        mainFx.amount = 1f
        player.playbackParameters = PlaybackParameters(
            clip.effectiveSpeed.coerceIn(0.1f, 8f),
            if (clip.type == ClipType.VIDEO) (clip.pitch * clip.audioFx.pitch).coerceIn(0.4f, 2.5f) else 1f,
        )
        setMainVolume(clip, 1f)
        if (previewEffectsOff) {
            mainFx.fx = com.reelforge.editor.model.AudioFx.NONE
            mainFx.denoise = com.reelforge.editor.model.NoiseRemoval.OFF
            return
        }
        val signature = EffectFactory.previewSignature(clip, p)
        if (signature != effectSignature) {
            effectSignature = signature
            val effects = EffectFactory.preview(clip, p)
            // Only switch the player to the effects pipeline once a clip actually needs it.
            if (effectsEverSet || effects.isNotEmpty()) {
                effectsEverSet = true
                player.setVideoEffects(effects)
            }
        }
    }

    private fun syncMusic() {
        val music = project?.music
        val signature = music?.uri.orEmpty()
        if (signature != musicSignature) {
            musicSignature = signature
            if (music == null) {
                musicPlayer.stop()
                musicPlayer.clearMediaItems()
            } else {
                musicPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(music.uri)))
                musicPlayer.prepare()
            }
        }
        if (music != null) {
            musicPlayer.volume = music.volume.coerceIn(0f, 1f)
            musicPlayer.repeatMode = if (music.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }
    }

    private fun startMusic() {
        val p = project ?: return
        val music = p.music ?: return
        // Outside the part of the timeline the music covers, it stays quiet.
        if (playheadMs < music.startMs || playheadMs >= music.windowEnd(p.durationMs)) {
            musicPlayer.pause()
            return
        }
        val position = music.filePosition(playheadMs)
        val now = SystemClock.elapsedRealtime()
        // Line the music up with the video, but never more than once a second: a long file can
        // take a moment to load, and seeking again while it loads would stop it ever starting.
        if (kotlin.math.abs(musicPlayer.currentPosition - position) > 300 && now - lastMusicSeekAt > 1_000) {
            lastMusicSeekAt = now
            musicPlayer.seekTo(position)
        }
        musicPlayer.play()
    }

    fun togglePlay() {
        val p = project ?: return
        if (p.clips.isEmpty()) return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (playheadMs >= p.durationMs - 100 || player.playbackState == Player.STATE_ENDED) seekTo(0)
            player.play()
        }
    }

    fun seekTo(globalMs: Long) {
        val p = project ?: return
        val target = globalMs.coerceIn(0, p.durationMs)
        playheadMs = target
        stopVoice()
        val (index, local) = p.locate(target) ?: return
        val (piece, position) = ProjectOps.toPlayPosition(
            p.clips, playGroups, index, (local * p.clips[index].effectiveSpeed).toLong(),
        )
        player.seekTo(piece, position)
        applyClipParams()
        syncLayers(p, player.isPlaying)
        if (player.isPlaying) startMusic()
    }

    fun pause() {
        player.pause()
    }

    fun jumpToStart() = seekTo(0)

    fun jumpToEnd() = seekTo(project?.durationMs ?: 0)

    /**
     * Follows the timeline while the user drags it. The playhead moves at once; the player is
     * asked for a new frame at most every [SCRUB_INTERVAL_MS] so dragging stays smooth.
     */
    fun scrubTo(globalMs: Long) {
        val p = project ?: return
        playheadMs = globalMs.coerceIn(0, p.durationMs)
        val now = SystemClock.elapsedRealtime()
        scrubJob?.cancel()
        if (now - lastScrubSeekAt >= SCRUB_INTERVAL_MS) {
            lastScrubSeekAt = now
            seekTo(playheadMs)
        } else {
            scrubJob = viewModelScope.launch {
                delay(SCRUB_INTERVAL_MS)
                lastScrubSeekAt = SystemClock.elapsedRealtime()
                seekTo(playheadMs)
            }
        }
    }

    fun selectClip(id: String) {
        val p = project ?: return
        selectedClipId = id
        val index = p.clips.indexOfFirst { it.id == id }
        if (index >= 0) seekTo(p.clipStartMs(index))
    }

    // ------------------------------------------------------------- loudness

    // Raises the preview above 100% (the player alone stops at 100%).
    private var booster: android.media.audiofx.LoudnessEnhancer? = null
    private var boosterSession = 0

    /**
     * Sets how loud the main video plays: its own level (0-200%), its fade [gain], lowered while a
     * voice-over plays if chosen, and silent while recording a voice-over if chosen.
     */
    private fun setMainVolume(clip: Clip, gain: Float) {
        val p = project
        if (isRecording && settings.silentWhileRecording) {
            player.volume = 0f
            return
        }
        val inVoice = p != null && p.duckLevel < 1f &&
            p.audio.any { it.voice && playheadMs >= it.startMs && playheadMs < it.startMs + it.durationMs }
        val level = (if (clip.muted) 0f else clip.volume.coerceIn(0f, 2f)) * gain * (if (inVoice) p!!.duckLevel else 1f)
        val volume = level.coerceIn(0f, 1f)
        if (kotlin.math.abs(player.volume - volume) > 0.005f) player.volume = volume
        boost(if (level > 1f) level else 1f)
    }

    // The last level given to the booster. Changing it talks to the phone's audio system, which is
    // slow, so it is only done when the level actually changes (not on every playback tick).
    private var boostedTo = 1f

    private fun boost(factor: Float) {
        val wanted = if (factor <= 1.001f) 1f else factor
        val session = player.audioSessionId
        if (kotlin.math.abs(wanted - boostedTo) < 0.02f && (booster == null || boosterSession == session)) return
        boostedTo = wanted
        runCatching {
            if (wanted == 1f) {
                booster?.enabled = false
                return
            }
            if (booster == null || boosterSession != session) {
                booster?.release()
                booster = android.media.audiofx.LoudnessEnhancer(session)
                boosterSession = session
            }
            // Loudness gain in millibels: 200% is about +6 dB.
            booster?.setTargetGain((2000 * kotlin.math.log10(factor.toDouble())).toInt())
            booster?.enabled = true
        }
    }

    /** Sets the sound level of every clip on the main track (0-200%). */
    fun setAllClipVolume(volume: Float) = mutate { p ->
        p.copy(clips = p.clips.map { it.copy(volume = volume.coerceIn(0f, 2f), muted = volume <= 0f) })
    }

    /** How loud the video's own sound plays while a voice-over speaks (1 = not lowered). */
    fun setDuckLevel(level: Float) = mutate { it.copy(duckLevel = level.coerceIn(0f, 1f)) }

    /** Called from the UI a few times per second to move the playhead while playing. */
    fun tick() {
        val p = project ?: return
        // A voice-over stops by itself at the end of the video.
        if (isRecording && (player.playbackState == Player.STATE_ENDED || playheadMs >= p.durationMs - 60)) {
            stopRecording()
            return
        }
        if (isRecording) {
            // Smoothed so the level bar moves calmly.
            val now = ((recorder?.maxAmplitude ?: 0) / 32767f).coerceIn(0f, 1f)
            micLevel = maxOf(now, micLevel * 0.8f)
        }
        if (!player.isPlaying) return
        val shown = shownClip(p)
        if (shown == null) {
            // Past the end of the last piece the player no longer maps to a clip; if the red line
            // is at the end, that is the end of the video: stop there.
            if (player.currentMediaItemIndex >= player.mediaItemCount - 1 && playheadMs >= p.durationMs - 300) {
                player.pause()
                if (playheadMs != p.durationMs) playheadMs = p.durationMs
            }
            return
        }
        val (index, source) = shown
        val clip = p.clips.getOrNull(index) ?: return
        // Split clips share one piece of video, so the player gives no signal when it crosses
        // from one to the next; pick up the new clip's filter, volume and so on here.
        if (index != shownClipIndex) applyClipParams()
        val local = (source / clip.effectiveSpeed).toLong()
        // With effects on, the player can stay on the last photo past its length without ever
        // saying it ended (seen in tests: still "playing" a minute after the end). Stop at the end
        // of the video ourselves. (Skipping ahead mid-video is left to the player: forcing it made
        // the effects pipeline time out.)
        if (index >= p.clips.lastIndex && local >= clip.durationMs + OVERSTAY_MS) {
            player.pause()
            if (playheadMs != p.durationMs) playheadMs = p.durationMs
            return
        }
        val now = (p.clipStartMs(index) + local).coerceIn(0, p.durationMs)
        if (now != playheadMs) playheadMs = now
        // Sound follows the picture fade.
        val gain = fadeAlpha(local, clip.durationMs, clip.fadeInMs, clip.fadeOutMs)
        setMainVolume(clip, gain)
        p.music?.let { music ->
            // Music plays only inside its own stretch of the timeline, fading at both ends.
            val end = music.windowEnd(p.durationMs)
            val inside = playheadMs >= music.startMs && playheadMs < end
            // "Asked to play" rather than "is playing": while a long file is still loading it is not
            // playing yet, and it must be left alone to finish loading.
            if (inside && !musicPlayer.playWhenReady) startMusic()
            if (!inside && musicPlayer.playWhenReady) musicPlayer.pause()
            // Silent while recording a voice-over (if chosen), and lowered under voice-overs.
            val quiet = isRecording && settings.silentWhileRecording
            val inVoice = p.duckLevel < 1f &&
                p.audio.any { it.voice && playheadMs >= it.startMs && playheadMs < it.startMs + it.durationMs }
            val musicVolume = if (quiet) 0f else music.volume.coerceIn(0f, 1f) *
                fadeAlpha(playheadMs - music.startMs, end - music.startMs, music.fadeInMs, music.fadeOutMs) *
                (if (inVoice) p.duckLevel else 1f)
            if (kotlin.math.abs(musicPlayer.volume - musicVolume) > 0.005f) musicPlayer.volume = musicVolume
        }
        syncVoice(p)
        syncLayers(p, true)
    }

    // -------------------------------------------------------------- thumbnails

    fun thumbKey(clip: Clip): String = "${clip.uri}@${clip.trimStartMs}"

    /** File names for clips saved before names were kept, looked up once per file. */
    private val clipNames = mutableStateMapOf<String, String>()

    /** The clip's file name, or "" while it is still being looked up. */
    fun clipName(clip: Clip): String = clip.name.ifBlank { clipNames[clip.uri].orEmpty() }

    fun requestClipName(clip: Clip) {
        if (clip.name.isNotBlank() || !requested.add("n:${clip.uri}")) return
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching { MediaUtils.displayName(ctx, Uri.parse(clip.uri)) }.getOrDefault("")
            }
            clipNames[clip.uri] = name
        }
    }

    /** Key into [frames] for the timeline frame of [uri] nearest to [sourceMs] (one per second). */
    fun frameKey(uri: String, sourceMs: Long): String = "$uri#${sourceMs / 1000}"

    /** Asks for a timeline frame; it appears in [frames] when ready. Safe to call repeatedly. */
    fun requestFrame(uri: String, sourceMs: Long) {
        val second = sourceMs / 1000
        if (requested.add("f:$uri#$second")) frameQueue.trySend(uri to second)
    }

    /**
     * Loads timeline frames in the background.
     * - Keeps a few videos open at once, so a timeline mixing clips from several files does not
     *   reopen a file for every frame (opening a video is the slow part).
     * - Hands finished frames to the screen in small batches, so the timeline redraws a few times
     *   a second while frames arrive rather than once per frame.
     * - Keeps at most [MAX_FRAMES] frames in memory; the oldest are dropped and loaded again if
     *   they scroll back into view.
     */
    private fun startFrameLoader() {
        viewModelScope.launch(Dispatchers.IO) {
            val open = object : LinkedHashMap<String, MediaMetadataRetriever>(4, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaMetadataRetriever>): Boolean {
                    if (size <= 3) return false
                    runCatching { eldest.value.release() }
                    return true
                }
            }
            val ready = ArrayList<Pair<String, ImageBitmap>>()
            var lastFlush = SystemClock.elapsedRealtime()
            suspend fun flush() {
                if (ready.isEmpty()) return
                val batch = ready.toList()
                ready.clear()
                lastFlush = SystemClock.elapsedRealtime()
                withContext(Dispatchers.Main) {
                    batch.forEach { (key, image) ->
                        frames[key] = image
                        frameOrder.remove(key)
                        frameOrder.add(key)
                    }
                    while (frameOrder.size > MAX_FRAMES) {
                        val oldest = frameOrder.first()
                        frameOrder.remove(oldest)
                        frames.remove(oldest)
                        requested.remove("f:$oldest")
                    }
                }
            }
            try {
                while (true) {
                    // Wait for work; while frames are waiting to be shown, wait only briefly.
                    val next = if (ready.isEmpty()) frameQueue.receive()
                    else withTimeoutOrNull(120) { frameQueue.receive() }
                    if (next != null) {
                        val (uri, second) = next
                        val key = "$uri#$second"
                        try {
                            val retriever = open[uri] ?: MediaMetadataRetriever().let { r ->
                                try {
                                    r.setDataSource(ctx, Uri.parse(uri))
                                } catch (e: Exception) {
                                    runCatching { r.release() }
                                    throw e
                                }
                                r.also { open[uri] = it }
                            }
                            retriever.getScaledFrameAtTime(
                                second * 1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160,
                            )?.let { ready += key to it.asImageBitmap() }
                        } catch (e: Exception) {
                            open.remove(uri)?.let { runCatching { it.release() } }
                        }
                    }
                    if (next == null || ready.size >= 6 || SystemClock.elapsedRealtime() - lastFlush > 150) flush()
                }
            } finally {
                open.values.forEach { runCatching { it.release() } }
            }
        }
    }

    fun requestThumb(clip: Clip) {
        val key = thumbKey(clip)
        if (!requested.add("t:$key")) return
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                val uri = Uri.parse(clip.uri)
                if (clip.type == ClipType.IMAGE) MediaUtils.decodeImage(ctx, uri, 256)
                else MediaUtils.videoFrame(ctx, uri, clip.trimStartMs, 256)
            }
            if (bitmap != null) thumbs[key] = bitmap.asImageBitmap()
        }
    }

    fun requestPreview(clip: Clip) {
        if (clip.type == ClipType.IMAGE) requestStill(clip.uri)
    }

    /** Loads a full-size still (photo clip or image layer) for the preview. */
    fun requestStill(uri: String) {
        if (!requested.add("p:$uri")) return
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                MediaUtils.decodeImage(ctx, Uri.parse(uri), 1280)
            }
            if (bitmap != null) {
                previews[uri] = bitmap.asImageBitmap()
                previewOrder.remove(uri)
                previewOrder.add(uri)
                while (previewOrder.size > MAX_STILLS) {
                    val oldest = previewOrder.first()
                    previewOrder.remove(oldest)
                    previews.remove(oldest)
                    requested.remove("p:$oldest")
                }
            } else {
                // Allow another try later (for example after a permission is granted).
                requested.remove("p:$uri")
            }
        }
    }

    // ------------------------------------------------------------------ export

    fun startExport(shortSide: Int, quality: Int, fps: Int = 30, hevc: Boolean = false) {
        val p = project ?: return
        if (p.clips.isEmpty() || export is ExportState.Running) return
        if (isRecording) stopRecording()
        player.pause()
        player.stop() // free hardware decoders for the export
        layerPlayers.values.forEach { it.stop() }
        export = ExportState.Running(0)
        runExport(p, shortSide, quality, fps, hevc, attempt = 1)
    }

    /**
     * One export attempt. Exports with video layers occasionally fail at start-up inside Media3,
     * so those are retried automatically before an error is shown.
     */
    private fun runExport(p: Project, shortSide: Int, quality: Int, fps: Int, hevc: Boolean, attempt: Int) {
        val output = File(ctx.cacheDir, "export_${System.currentTimeMillis()}.mp4")

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                viewModelScope.launch {
                    export = try {
                        val safeName = p.name.replace(Regex("[^A-Za-z0-9_-]"), "_")
                        val name = "${safeName}_${System.currentTimeMillis()}.mp4"
                        val uri = withContext(Dispatchers.IO) {
                            MediaUtils.saveToGallery(ctx, output, name)
                        }
                        com.reelforge.editor.work.WorkService.finished(
                            ctx, "Your video is saved", "Tap to watch it. It is also in your gallery.", uri, video = true,
                        )
                        ExportState.Done(uri)
                    } catch (e: Exception) {
                        com.reelforge.editor.work.WorkService.finished(
                            ctx, "Export did not finish", "Open CutMitra to try again.",
                        )
                        ExportState.Failed(e.message ?: "Could not save the video")
                    }
                    finishExport()
                }
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException,
            ) {
                output.delete()
                transformer = null
                val retry = p.videoLayers.isNotEmpty() && attempt < MAX_LAYERED_EXPORT_ATTEMPTS &&
                    export is ExportState.Running
                if (retry) {
                    export = ExportState.Running(0)
                    viewModelScope.launch {
                        delay(500) // let the failed attempt release its encoders and decoders
                        if (export is ExportState.Running) {
                            runExport(p, shortSide, quality, fps, hevc, attempt + 1)
                        }
                    }
                } else {
                    export = ExportState.Failed(
                        "${exportException.errorCodeName}: ${exportException.message ?: "export failed"}"
                    )
                    finishExport()
                }
            }
        }

        viewModelScope.launch {
            try {
                val composition = withContext(Dispatchers.IO) {
                    ExportManager.buildComposition(ctx, p, shortSide, fps)
                }
                if (export !is ExportState.Running) return@launch // cancelled while preparing
                val (outWidth, outHeight) = ExportManager.outputSize(p.aspect, shortSide)
                val t = ExportManager.createTransformer(
                    ctx, listener, ExportManager.bitrate(outWidth, outHeight, quality, fps, hevc), hevc,
                )
                transformer = t
                t.start(composition, output.absolutePath)
                val holder = ProgressHolder()
                while (export is ExportState.Running && transformer === t) {
                    if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        export = ExportState.Running(holder.progress)
                    }
                    delay(300)
                }
            } catch (e: Exception) {
                export = ExportState.Failed(e.message ?: "Could not start export")
                finishExport()
            }
        }
    }

    // ------------------------------------------------------------ working in the background

    /** The long job running now, as a title and a percent (null when none). */
    private fun backgroundJob(): Pair<String, Int?>? {
        val e = export
        val pack = stickerPackProgress
        return when {
            e is ExportState.Running -> "Exporting your video" to e.progress
            captionWork != null -> "Making captions" to (captionProgress * 100).toInt()
            reverseWork != null -> "Reversing a clip" to reverseWork?.substringAfterLast(' ')?.removeSuffix("%")?.toIntOrNull()
            soundWork != null -> (soundWork?.substringBefore('…') ?: "Working") to
                soundWork?.substringAfterLast(' ')?.removeSuffix("%")?.toIntOrNull()
            pack != null -> "Downloading stickers" to (pack.first * 100 / pack.second.coerceAtLeast(1))
            enhanceWork != null -> "Making photos sharper" to (enhanceProgress * 100).toInt()
            trackWork != null -> "Tracking" to (trackProgress * 100).toInt()
            else -> null
        }
    }

    /** Stop in the notification: stops whatever long job is running. */
    private fun stopAllWork() {
        if (export is ExportState.Running) cancelExport()
        if (captionWork != null) stopCaptions()
        if (reverseWork != null) cancelReverse()
        if (soundWork != null) cancelSoundWork()
        if (stickerPackProgress != null) downloadAllStickers() // second call stops it
    }

    init {
        // While a long job runs, a notification keeps the app going in the background and shows
        // its progress; it goes away when the job ends.
        viewModelScope.launch {
            androidx.compose.runtime.snapshotFlow { backgroundJob() }
                .distinctUntilChanged()
                .collect { job ->
                    if (job == null) com.reelforge.editor.work.WorkService.hide(ctx)
                    else com.reelforge.editor.work.WorkService.show(ctx, job.first, job.second)
                }
        }
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        com.reelforge.editor.work.WorkService.onStopRequested = { main.post { stopAllWork() } }
    }

    fun cancelExport() {
        transformer?.cancel()
        export = ExportState.Idle
        finishExport()
    }

    fun dismissExport() {
        if (export !is ExportState.Running) export = ExportState.Idle
    }

    private fun finishExport() {
        transformer = null
        playlistSignature = ""
        effectSignature = ""
        layerPlayers.values.forEach { it.prepare() }
        syncPlayer()
    }

    override fun onCleared() {
        runCatching { voiceEngine?.shutdown() }
        com.reelforge.editor.work.WorkService.onStopRequested = null
        com.reelforge.editor.work.WorkService.hide(ctx)
        runCatching { booster?.release() }
        transformer?.cancel()
        runCatching { recorder?.release() }
        player.release()
        musicPlayer.release()
        voiceLane.player.release()
        soundLane.player.release()
        releaseLayerPlayers()
    }
}
