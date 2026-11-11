package com.reelforge.editor.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.reelforge.editor.model.AspectRatio
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
    var export by mutableStateOf<ExportState>(ExportState.Idle)
        private set

    /** Timeline thumbnails keyed by [thumbKey]; full-size stills for image clips keyed by uri. */
    val thumbs = mutableStateMapOf<String, ImageBitmap>()
    val previews = mutableStateMapOf<String, ImageBitmap>()

    val player: ExoPlayer = ExoPlayer.Builder(app).build()
    private val musicPlayer: ExoPlayer = ExoPlayer.Builder(app).build()

    private var playlistSignature = ""
    private var effectSignature = ""
    private var musicSignature = ""
    private var effectsEverSet = false
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
                if (playing) startMusic() else musicPlayer.pause()
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
        selectedClipId = p.clips.firstOrNull()?.id
        playheadMs = 0
        playlistSignature = ""
        effectSignature = ""
        musicSignature = ""
        syncPlayer()
    }

    fun closeProject() {
        player.pause()
        player.clearMediaItems()
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

    fun deleteProject(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.delete(id) }
            projects = withContext(Dispatchers.IO) { repo.list() }
        }
    }

    private fun mutate(block: (Project) -> Project) {
        val current = project ?: return
        val next = block(current).copy(updatedAt = System.currentTimeMillis())
        project = next
        playheadMs = playheadMs.coerceIn(0, next.durationMs)
        syncPlayer()
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(400)
            withContext(Dispatchers.IO) { repo.save(next) }
        }
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

    fun addText(): String {
        val p = project ?: return ""
        val start = playheadMs
        val end = (start + 3000).coerceAtMost(maxOf(p.durationMs, start + 500))
        val item = TextItem(text = "Your text", startMs = start, endMs = end)
        mutate { it.copy(texts = it.texts + item) }
        return item.id
    }

    fun updateText(id: String, block: (TextItem) -> TextItem) = mutate { p ->
        p.copy(texts = p.texts.map { if (it.id == id) block(it) else it })
    }

    fun deleteText(id: String) = mutate { p -> p.copy(texts = p.texts.filterNot { it.id == id }) }

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

    // ---------------------------------------------------------------- playback

    private fun toMediaItem(clip: Clip): MediaItem {
        val uri = Uri.parse(clip.uri)
        val builder = MediaItem.Builder().setUri(uri).setMediaId(clip.id)
        if (clip.type == ClipType.IMAGE) {
            builder.setImageDurationMs(clip.durationMs)
            ctx.contentResolver.getType(uri)?.let { builder.setMimeType(it) }
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
        val clip = project?.clips?.getOrNull(player.currentMediaItemIndex) ?: return
        player.playbackParameters = PlaybackParameters(clip.effectiveSpeed)
        player.volume = if (clip.muted) 0f else clip.volume.coerceIn(0f, 1f)
        val signature = EffectFactory.previewSignature(clip)
        if (signature != effectSignature) {
            effectSignature = signature
            // Only switch the player to the effects pipeline once a clip actually needs it.
            if (effectsEverSet || signature != EffectFactory.NO_EFFECTS) {
                effectsEverSet = true
                player.setVideoEffects(EffectFactory.preview(clip))
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
        if (clip.type != ClipType.IMAGE) return
        if (!requested.add("p:${clip.uri}")) return
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                MediaUtils.decodeImage(ctx, Uri.parse(clip.uri), 1280)
            }
            if (bitmap != null) previews[clip.uri] = bitmap.asImageBitmap()
        }
    }

    // ------------------------------------------------------------------ export

    fun startExport(shortSide: Int) {
        val p = project ?: return
        if (p.clips.isEmpty() || export is ExportState.Running) return
        player.pause()
        player.stop() // free hardware decoders for the export
        val output = File(ctx.cacheDir, "export_${System.currentTimeMillis()}.mp4")
        try {
            val composition = ExportManager.buildComposition(ctx, p, shortSide)
            val t = ExportManager.createTransformer(ctx, object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    viewModelScope.launch {
                        export = try {
                            val name = "${p.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}_${System.currentTimeMillis()}.mp4"
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
            })
            transformer = t
            export = ExportState.Running(0)
            t.start(composition, output.absolutePath)
            viewModelScope.launch {
                val holder = ProgressHolder()
                while (export is ExportState.Running) {
                    val active = transformer ?: break
                    if (active.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        export = ExportState.Running(holder.progress)
                    }
                    delay(300)
                }
            }
        } catch (e: Exception) {
            export = ExportState.Failed(e.message ?: "Could not start export")
            finishExport()
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
        player.release()
        musicPlayer.release()
    }
}
