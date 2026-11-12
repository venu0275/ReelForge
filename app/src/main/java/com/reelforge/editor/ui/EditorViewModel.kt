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
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.reelforge.editor.data.AssetCatalog
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
import com.reelforge.editor.model.Clip
import com.reelforge.editor.model.ClipType
import com.reelforge.editor.model.MusicTrack
import com.reelforge.editor.model.History
import com.reelforge.editor.model.Project
import com.reelforge.editor.model.ProjectOps
import com.reelforge.editor.model.TextItem
import com.reelforge.editor.model.VideoLayer
import com.reelforge.editor.model.newId
import com.reelforge.editor.util.MediaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One finger stroke; points are fractions (0..1) of the frame width and height. */
class DrawStroke(val color: Int, val points: List<Offset>)

private const val SCRUB_INTERVAL_MS = 90L
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

    var projects by mutableStateOf(emptyList<Project>())
        private set
    var project by mutableStateOf<Project?>(null)
        private set
    var selectedClipId by mutableStateOf<String?>(null)
    var playheadMs by mutableLongStateOf(0L)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var export by mutableStateOf<ExportState>(ExportState.Idle)
        private set

    /** Timeline thumbnails keyed by [thumbKey]; full-size stills for image clips keyed by uri. */
    val thumbs = mutableStateMapOf<String, ImageBitmap>()
    val previews = mutableStateMapOf<String, ImageBitmap>()

    /** Frames shown along video clips on the timeline, keyed by [frameKey]. */
    val frames = mutableStateMapOf<String, ImageBitmap>()
    private val frameQueue = Channel<Pair<String, Long>>(Channel.UNLIMITED)
    private var scrubJob: Job? = null
    private var lastScrubSeekAt = 0L

    val player: ExoPlayer = ExoPlayer.Builder(app).build()
    private val musicPlayer: ExoPlayer = ExoPlayer.Builder(app).build()
    private val voicePlayer: ExoPlayer = ExoPlayer.Builder(app).build()
    private var voiceClipId: String? = null

    var isRecording by mutableStateOf(false)
        private set
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var recordStartMs = 0L

    private var playlistSignature = ""
    private var effectSignature = ""
    private var musicSignature = ""
    private var effectsEverSet = false
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

            override fun onPlayerError(error: PlaybackException) {
                message = "Playback error: ${error.errorCodeName}"
            }
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
        val p = Project(name = name.ifBlank { "Untitled" }, aspect = aspect)
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
        if (name.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.save(p.copy(name = name.trim())) }
            projects = withContext(Dispatchers.IO) { repo.list() }
        }
    }

    fun deleteProject(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.delete(id) }
            projects = withContext(Dispatchers.IO) { repo.list() }
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
        commit(history.undo(current) ?: return)
    }

    fun redo() {
        val current = project ?: return
        commit(history.redo(current) ?: return)
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
                            type = info.type,
                            sourceDurationMs = info.durationMs,
                            hasAudio = info.hasAudio,
                        )
                    }
                }
            }
            if (clips.size < uris.size) message = "Some files could not be read"
            if (clips.isNotEmpty()) {
                mutate { it.copy(clips = it.clips + clips) }
            }
        }
    }

    fun updateClip(id: String, block: (Clip) -> Clip) = mutate { p ->
        p.copy(clips = p.clips.map { if (it.id == id) block(it) else it })
    }

    fun deleteClip(id: String) {
        mutate { ProjectOps.delete(it, id) }
        if (selectedClipId == id) selectedClipId = null
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
    }

    /** Edge drags on the timeline. [deltaMs] is timeline time; see [ProjectOps.trimStart]. */
    fun trimClipStart(id: String, deltaMs: Long) = mutate { ProjectOps.trimStart(it, id, deltaMs) }

    fun trimClipEnd(id: String, deltaMs: Long) = mutate { ProjectOps.trimEnd(it, id, deltaMs) }

    /** Swaps the clip's video or photo for another one, keeping its edits. */
    fun replaceClip(id: String, uri: Uri) {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                MediaUtils.probe(ctx, uri)
            }
            if (info == null) {
                message = "That file could not be read"
                return@launch
            }
            mutate {
                ProjectOps.replaceMedia(it, id, uri.toString(), info.type, info.durationMs, info.hasAudio)
            }
        }
    }

    fun duplicateClip(id: String) = mutate { ProjectOps.duplicate(it, id) }

    fun moveClip(id: String, delta: Int) = mutate { ProjectOps.move(it, id, delta) }

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
    }

    // ------------------------------------------------------------ text + music

    fun addText(text: String = "Your text", size: Float = 0.08f): String {
        val p = project ?: return ""
        val start = playheadMs
        val end = (start + 3000).coerceAtMost(maxOf(p.durationMs, start + 500))
        val item = TextItem(text = text, size = size, startMs = start, endMs = end)
        mutate { it.copy(texts = it.texts + item) }
        return item.id
    }

    fun updateText(id: String, block: (TextItem) -> TextItem) = mutate { p ->
        p.copy(texts = p.texts.map { if (it.id == id) block(it) else it })
    }

    fun deleteText(id: String) = mutate { p -> p.copy(texts = p.texts.filterNot { it.id == id }) }

    fun duplicateText(id: String): String {
        val source = project?.texts?.firstOrNull { it.id == id } ?: return ""
        val copy = source.copy(id = newId(), y = (source.y - 0.2f).coerceIn(-1f, 1f))
        mutate { it.copy(texts = it.texts + copy) }
        return copy.id
    }

    fun deleteAllTexts() = mutate { it.copy(texts = emptyList()) }

    fun deleteAllLayers() = mutate { it.copy(layers = emptyList()) }

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
            mutate { it.copy(music = track) }
        }
    }

    fun updateMusic(block: (MusicTrack) -> MusicTrack) = mutate { p ->
        p.copy(music = p.music?.let(block))
    }

    fun removeMusic() = mutate { it.copy(music = null) }

    // ------------------------------------------------------------ image layers

    fun addImageLayer(uri: Uri): String {
        val p = project ?: return ""
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val start = playheadMs
        val end = (start + 3000).coerceAtMost(maxOf(p.durationMs, start + 500))
        val layer = ImageLayer(uri = uri.toString(), startMs = start, endMs = end)
        mutate { it.copy(layers = it.layers + layer) }
        return layer.id
    }

    fun updateLayer(id: String, block: (ImageLayer) -> ImageLayer) = mutate { p ->
        p.copy(layers = p.layers.map { if (it.id == id) block(it) else it })
    }

    fun deleteLayer(id: String) = mutate { p -> p.copy(layers = p.layers.filterNot { it.id == id }) }

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
            val end = (start + 3000).coerceAtMost(maxOf(p.durationMs, start + 500))
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
        val file = AssetCatalog.fontFile(ctx, asset)
        if (file.length() > 0) {
            if (textId != null) updateText(textId) { it.copy(fontPath = file.absolutePath) }
            return
        }
        if (fontProgress.containsKey(asset.id)) return
        fontProgress[asset.id] = 0
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    AssetDownloader.download(asset.url, file) { percent ->
                        viewModelScope.launch { if (fontProgress.containsKey(asset.id)) fontProgress[asset.id] = percent }
                    }
                    // Throws if what arrived is not a usable font.
                    Typeface.createFromFile(file)
                }.onFailure { file.delete() }.isSuccess
            }
            fontProgress.remove(asset.id)
            refreshInstalledFonts()
            if (!ok) {
                message = "Could not download ${asset.name}. Check the internet connection."
            } else if (textId != null) {
                updateText(textId) { it.copy(fontPath = file.absolutePath) }
            }
        }
    }

    fun removeStoreFont(asset: FontAsset) {
        AssetCatalog.fontFile(ctx, asset).delete()
        refreshInstalledFonts()
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
            layerPlayer.volume = if (layer.muted) 0f else layer.volume.coerceIn(0f, 1f)
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

    fun updateAudioClip(id: String, block: (AudioClip) -> AudioClip) = mutate { p ->
        p.copy(audio = p.audio.map { if (it.id == id) block(it) else it })
    }

    fun deleteAudioClip(id: String) = mutate { p -> p.copy(audio = p.audio.filterNot { it.id == id }) }

    /** Starts recording from the microphone at the playhead while the video plays. */
    fun startRecording() {
        val p = project ?: return
        if (isRecording || p.clips.isEmpty()) return
        val dir = File(ctx.filesDir, "voice").apply { mkdirs() }
        val file = File(dir, "voice_${System.currentTimeMillis()}.m4a")
        try {
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(128000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            recordFile = file
            if (playheadMs >= p.durationMs - 200) seekTo(0)
            recordStartMs = playheadMs
            isRecording = true
            stopVoice()
            player.play()
        } catch (e: Exception) {
            file.delete()
            message = "Could not start recording"
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        player.pause()
        val file = recordFile
        val ok = runCatching { recorder?.stop() }.isSuccess
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
                uri = uri.toString(), title = "Voice $number", startMs = start, durationMs = duration,
            )
            mutate { it.copy(audio = it.audio + clip) }
        }
    }

    private fun stopVoice() {
        if (voiceClipId != null) {
            voicePlayer.pause()
            voiceClipId = null
        }
    }

    /** Plays the voice / sound clip under the playhead in the preview. */
    private fun syncVoice(p: Project) {
        val clip = p.audio.firstOrNull { playheadMs >= it.startMs && playheadMs < it.startMs + it.durationMs }
        if (clip == null || isRecording) {
            stopVoice()
            return
        }
        if (clip.id != voiceClipId) {
            voiceClipId = clip.id
            voicePlayer.setMediaItem(MediaItem.fromUri(Uri.parse(clip.uri)))
            voicePlayer.prepare()
            voicePlayer.seekTo(playheadMs - clip.startMs)
            voicePlayer.play()
        }
        val length = minOf(clip.durationMs, p.durationMs - clip.startMs)
        voicePlayer.volume = clip.volume.coerceIn(0f, 1f) *
            fadeAlpha(playheadMs - clip.startMs, length, clip.fadeInMs, clip.fadeOutMs)
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
            selectedClipId = still.id
        }
    }

    /** Fade the clip out and the next one in, i.e. a dip-to-black transition. 0 removes it. */
    fun setTransitionAfter(id: String, durationMs: Long) =
        mutate { ProjectOps.transitionAfter(it, id, durationMs) }

    // ---------------------------------------------------------------- playback

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
        val signature = p.clips.joinToString("|") {
            "${it.id}:${it.trimStartMs}:${it.trimEndMs}"
        }
        if (signature != playlistSignature) {
            playlistSignature = signature
            val wasPlaying = player.isPlaying
            if (p.clips.isEmpty()) {
                player.clearMediaItems()
            } else {
                val (index, local) = p.locate(playheadMs) ?: (0 to 0L)
                val position = (local * p.clips[index].effectiveSpeed).toLong()
                player.setMediaItems(p.clips.map(::toMediaItem), index, position)
                player.prepare()
                player.playWhenReady = wasPlaying
            }
        }
        applyClipParams()
        syncMusic()
    }

    private fun applyClipParams() {
        val p = project ?: return
        val clip = p.clips.getOrNull(player.currentMediaItemIndex) ?: return
        // The preview player tops out at 8x; faster clips still export at their full speed.
        player.playbackParameters = PlaybackParameters(
            clip.effectiveSpeed.coerceIn(0.1f, 8f),
            if (clip.type == ClipType.VIDEO) clip.pitch.coerceIn(0.5f, 2f) else 1f,
        )
        player.volume = if (clip.muted) 0f else clip.volume.coerceIn(0f, 1f)
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
        val music = project?.music ?: return
        val position = when {
            music.durationMs <= 0 -> playheadMs
            music.loop -> playheadMs % music.durationMs
            else -> playheadMs
        }
        if (!music.loop && music.durationMs in 1..position) {
            musicPlayer.pause()
            return
        }
        if (kotlin.math.abs(musicPlayer.currentPosition - position) > 300) {
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
        player.seekTo(index, (local * p.clips[index].effectiveSpeed).toLong())
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

    /** Called from the UI a few times per second to move the playhead while playing. */
    fun tick() {
        val p = project ?: return
        if (!player.isPlaying) return
        val index = player.currentMediaItemIndex
        val clip = p.clips.getOrNull(index) ?: return
        val local = (player.currentPosition / clip.effectiveSpeed).toLong()
        playheadMs = (p.clipStartMs(index) + local).coerceIn(0, p.durationMs)
        // Sound follows the picture fade.
        val gain = fadeAlpha(local, clip.durationMs, clip.fadeInMs, clip.fadeOutMs)
        player.volume = (if (clip.muted) 0f else clip.volume.coerceIn(0f, 1f)) * gain
        p.music?.let { music ->
            // Music fades in at the start and out where it (or the video) ends.
            val end = if (music.loop || music.durationMs <= 0) p.durationMs
            else minOf(music.durationMs, p.durationMs)
            musicPlayer.volume = music.volume.coerceIn(0f, 1f) *
                fadeAlpha(playheadMs, end, music.fadeInMs, music.fadeOutMs)
        }
        syncVoice(p)
        syncLayers(p, true)
    }

    // -------------------------------------------------------------- thumbnails

    fun thumbKey(clip: Clip): String = "${clip.uri}@${clip.trimStartMs}"

    /** Key into [frames] for the timeline frame of [uri] nearest to [sourceMs] (one per second). */
    fun frameKey(uri: String, sourceMs: Long): String = "$uri#${sourceMs / 1000}"

    /** Asks for a timeline frame; it appears in [frames] when ready. Safe to call repeatedly. */
    fun requestFrame(uri: String, sourceMs: Long) {
        val second = sourceMs / 1000
        if (requested.add("f:$uri#$second")) frameQueue.trySend(uri to second)
    }

    /** Loads timeline frames one at a time, reusing the open video while the uri stays the same. */
    private fun startFrameLoader() {
        viewModelScope.launch(Dispatchers.IO) {
            var openUri: String? = null
            var retriever: MediaMetadataRetriever? = null
            try {
                for ((uri, second) in frameQueue) {
                    try {
                        if (uri != openUri) {
                            runCatching { retriever?.release() }
                            retriever = MediaMetadataRetriever().also { it.setDataSource(ctx, Uri.parse(uri)) }
                            openUri = uri
                        }
                        val bitmap = retriever?.getScaledFrameAtTime(
                            second * 1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160,
                        )
                        if (bitmap != null) {
                            val image = bitmap.asImageBitmap()
                            withContext(Dispatchers.Main) { frames["$uri#$second"] = image }
                        }
                    } catch (e: Exception) {
                        runCatching { retriever?.release() }
                        retriever = null
                        openUri = null
                    }
                }
            } finally {
                runCatching { retriever?.release() }
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
            if (bitmap != null) previews[uri] = bitmap.asImageBitmap()
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
                        ExportState.Done(uri)
                    } catch (e: Exception) {
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
        transformer?.cancel()
        runCatching { recorder?.release() }
        player.release()
        musicPlayer.release()
        voicePlayer.release()
        releaseLayerPlayers()
    }
}
