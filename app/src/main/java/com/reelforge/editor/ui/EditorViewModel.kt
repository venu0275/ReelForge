package com.reelforge.editor.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
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
import com.reelforge.editor.data.ProjectRepository
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
import com.reelforge.editor.model.Project
import com.reelforge.editor.model.TextItem
import com.reelforge.editor.model.newId
import com.reelforge.editor.util.MediaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One finger stroke; points are fractions (0..1) of the frame width and height. */
class DrawStroke(val color: Int, val points: List<Offset>)

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
    private val undoStack = ArrayDeque<Project>()
    private val redoStack = ArrayDeque<Project>()
    private var lastMutationAt = 0L
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
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                message = "Playback error: ${error.errorCodeName}"
            }
        })
        refreshProjects()
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
        undoStack.clear()
        redoStack.clear()
        canUndo = false
        canRedo = false
        selectedClipId = p.clips.firstOrNull()?.id
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
        if (now - lastMutationAt > 700 || undoStack.isEmpty()) {
            undoStack.addLast(current)
            if (undoStack.size > 60) undoStack.removeFirst()
        }
        lastMutationAt = now
        redoStack.clear()
        commit(changed.copy(updatedAt = now))
    }

    private fun commit(next: Project) {
        project = next
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
        if (next.clips.none { it.id == selectedClipId }) selectedClipId = next.clips.firstOrNull()?.id
        playheadMs = playheadMs.coerceIn(0, next.durationMs)
        syncPlayer()
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(400)
            withContext(Dispatchers.IO) { repo.save(next) }
        }
    }

    fun undo() {
        val current = project ?: return
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(current)
        lastMutationAt = 0
        commit(previous)
    }

    fun redo() {
        val current = project ?: return
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(current)
        lastMutationAt = 0
        commit(next)
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
                if (selectedClipId == null) selectedClipId = clips.first().id
            }
        }
    }

    fun updateClip(id: String, block: (Clip) -> Clip) = mutate { p ->
        p.copy(clips = p.clips.map { if (it.id == id) block(it) else it })
    }

    fun deleteClip(id: String) {
        val p = project ?: return
        val index = p.clips.indexOfFirst { it.id == id }
        if (index < 0) return
        mutate { it.copy(clips = it.clips.filterNot { c -> c.id == id }) }
        selectedClipId = project?.clips?.getOrNull(index.coerceAtMost((project?.clips?.size ?: 1) - 1))?.id
    }

    fun duplicateClip(id: String) = mutate { p ->
        val index = p.clips.indexOfFirst { it.id == id }
        if (index < 0) p
        else p.copy(
            clips = p.clips.toMutableList().apply { add(index + 1, p.clips[index].copy(id = newId())) }
        )
    }

    fun moveClip(id: String, delta: Int) = mutate { p ->
        val from = p.clips.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in p.clips.indices) p
        else p.copy(clips = p.clips.toMutableList().apply { add(to, removeAt(from)) })
    }

    /** Splits the clip under the playhead into two clips. */
    fun splitAtPlayhead() {
        val p = project ?: return
        val (index, local) = p.locate(playheadMs) ?: return
        val clip = p.clips[index]
        val sourceOffset = (local * clip.effectiveSpeed).toLong()
        val length = clip.trimEndMs - clip.trimStartMs
        if (sourceOffset < 200 || length - sourceOffset < 200) {
            message = "Move the playhead further inside the clip to split"
            return
        }
        val cut = clip.trimStartMs + sourceOffset
        val first = clip.copy(trimEndMs = cut, fadeOutMs = 0)
        val second = clip.copy(id = newId(), trimStartMs = cut, fadeInMs = 0)
        mutate {
            it.copy(clips = it.clips.toMutableList().apply {
                set(index, first)
                add(index + 1, second)
            })
        }
        selectedClipId = second.id
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
        voicePlayer.volume = clip.volume.coerceIn(0f, 1f)
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
    fun setTransitionAfter(id: String, durationMs: Long) = mutate { p ->
        val index = p.clips.indexOfFirst { it.id == id }
        if (index < 0 || index >= p.clips.lastIndex) p
        else p.copy(clips = p.clips.mapIndexed { i, c ->
            when (i) {
                index -> c.copy(fadeOutMs = durationMs.coerceAtMost(c.durationMs / 2))
                index + 1 -> c.copy(fadeInMs = durationMs.coerceAtMost(c.durationMs / 2))
                else -> c
            }
        })
    }

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
        player.playbackParameters = PlaybackParameters(
            clip.effectiveSpeed,
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
        if (player.isPlaying) startMusic()
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
        syncVoice(p)
    }

    // -------------------------------------------------------------- thumbnails

    fun thumbKey(clip: Clip): String = "${clip.uri}@${clip.trimStartMs}"

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

    fun startExport(shortSide: Int, quality: Int) {
        val p = project ?: return
        if (p.clips.isEmpty() || export is ExportState.Running) return
        if (isRecording) stopRecording()
        player.pause()
        player.stop() // free hardware decoders for the export
        export = ExportState.Running(0)
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
                export = ExportState.Failed(
                    "${exportException.errorCodeName}: ${exportException.message ?: "export failed"}"
                )
                finishExport()
            }
        }

        viewModelScope.launch {
            try {
                val composition = withContext(Dispatchers.IO) {
                    ExportManager.buildComposition(ctx, p, shortSide)
                }
                if (export !is ExportState.Running) return@launch // cancelled while preparing
                val (outWidth, outHeight) = ExportManager.outputSize(p.aspect, shortSide)
                val t = ExportManager.createTransformer(
                    ctx, listener, ExportManager.bitrate(outWidth, outHeight, quality)
                )
                transformer = t
                t.start(composition, output.absolutePath)
                val holder = ProgressHolder()
                while (export is ExportState.Running) {
                    val active = transformer ?: break
                    if (active.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
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
        syncPlayer()
    }

    override fun onCleared() {
        transformer?.cancel()
        runCatching { recorder?.release() }
        player.release()
        musicPlayer.release()
        voicePlayer.release()
    }
}
