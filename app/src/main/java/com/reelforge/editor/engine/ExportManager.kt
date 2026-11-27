package com.reelforge.editor.engine

import android.content.Context
import android.media.MediaCodecList
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import com.reelforge.editor.model.AspectRatio
import com.reelforge.editor.model.AudioClip
import com.reelforge.editor.model.AudioFx
import com.reelforge.editor.model.Keyframe
import com.reelforge.editor.model.OverlayAnim
import com.reelforge.editor.model.Clip
import com.reelforge.editor.model.ClipType
import com.reelforge.editor.model.MusicTrack
import com.reelforge.editor.model.Project
import com.reelforge.editor.model.TransitionStyle
import com.reelforge.editor.model.VideoLayer
import com.reelforge.editor.util.MediaUtils
import kotlin.math.max
import kotlin.math.min

/** Turns a [Project] into a Media3 Transformer [Composition] and renders it to an MP4. */
@OptIn(UnstableApi::class)
object ExportManager {

    const val DEFAULT_FRAME_RATE = 30
    private const val MAX_MUSIC_REPEATS = 400
    private const val SILENCE_MS = 10_000L
    private const val MAX_OVERLAYS_PER_EFFECT = 15

    /** Each video layer needs its own decoder, so phones can only handle a few at once. */
    const val MAX_VIDEO_LAYERS = 3

    /** Gaps shorter than about one frame are not worth a blank filler. */
    private const val MIN_BLANK_MS = 40L

    /** Output frame size for an aspect ratio, where [shortSide] is e.g. 720 or 1080. */
    fun outputSize(aspect: AspectRatio, shortSide: Int): Pair<Int, Int> {
        fun even(v: Int) = v - (v % 2)
        return if (aspect.w >= aspect.h) {
            even(shortSide * aspect.w / aspect.h) to even(shortSide)
        } else {
            even(shortSide) to even(shortSide * aspect.h / aspect.w)
        }
    }

    /**
     * Target video bitrate. [quality] is 0 (small file), 1 or 2 (best). 60 fps needs more bits;
     * H.265 needs fewer for the same picture.
     */
    fun bitrate(width: Int, height: Int, quality: Int, fps: Int = 30, hevc: Boolean = false): Int {
        val bitsPerPixel = when (quality) {
            0 -> 0.07
            2 -> 0.20
            else -> 0.12
        }
        val rate = if (fps > 30) 1.5 else 1.0
        val codec = if (hevc) 0.65 else 1.0
        return (width.toLong() * height * 30 * bitsPerPixel * rate * codec).toInt()
    }

    /** Whether this phone has an encoder for that format, size and frame rate. */
    fun canEncode(hevc: Boolean, width: Int, height: Int, fps: Int): Boolean {
        val mime = if (hevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
        // Portrait video is encoded sideways, so check the landscape orientation.
        val long = max(width, height)
        val short = min(width, height)
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder &&
                    info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                    info.getCapabilitiesForType(mime).videoCapabilities
                        .areSizeAndRateSupported(long, short, fps.toDouble())
            }
        }.getOrDefault(false)
    }

    /**
     * Free space an export needs: the video and audio at their bitrates, twice over (the file is
     * written to the cache and then copied to the gallery), plus a 20% margin.
     */
    fun bytesNeeded(videoBitrate: Int, durationMs: Long): Long {
        val bitsPerSecond = videoBitrate.toLong() + AUDIO_BITRATE_ESTIMATE
        val fileBytes = bitsPerSecond * durationMs / 8 / 1000
        return fileBytes * 2 * 12 / 10
    }

    private const val AUDIO_BITRATE_ESTIMATE = 192_000L

    /**
     * A Transformer builder for every render in the app.
     *
     * Media3 stops a render with ERROR_CODE_MUXING_TIMEOUT when no audio or video sample reaches
     * the muxer for a while. Its default of 10 seconds stopped healthy long or heavy exports, but
     * turning the check off entirely made exports hang forever on chipsets where the hardware
     * codec gets stuck (Media3's own guide warns about this). So the limit is raised to
     * [MUXER_TIMEOUT_MS]: a stuck codec still ends in an error, which the export retries with
     * safer settings.
     */
    fun transformerBuilder(context: Context): Transformer.Builder =
        Transformer.Builder(context)
            .setMaxDelayBetweenMuxerSamplesMs(MUXER_TIMEOUT_MS)

    const val MUXER_TIMEOUT_MS = 60_000L

    fun createTransformer(
        context: Context,
        listener: Transformer.Listener,
        videoBitrate: Int,
        hevc: Boolean = false,
    ): Transformer =
        transformerBuilder(context)
            .setVideoMimeType(if (hevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(
                DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder().setBitrate(videoBitrate).build()
                    )
                    .build()
            )
            .addListener(listener)
            .build()

    private class OverlaySpec(
        val bitmap: Bitmap,
        val startMs: Long,
        val endMs: Long,
        val x: Float,
        val y: Float,
        val opacity: Float,
        val animIn: OverlayAnim,
        val animOut: OverlayAnim,
        val x2: Float,
        val y2: Float,
        val rotation: Float,
        val keyframes: List<Keyframe>,
        val loop: com.reelforge.editor.model.LoopFx = com.reelforge.editor.model.LoopFx.NONE,
        val reveal: List<Bitmap> = emptyList(),
        val revealMs: Long = 0,
        val frames: MovingArt.Frames? = null,
    )

    /** Builds the composition. Decodes images and writes files, so call it off the main thread. */
    fun buildComposition(
        context: Context,
        source: Project,
        shortSide: Int,
        fps: Int = DEFAULT_FRAME_RATE,
    ): Composition {
        val project = withExportablePhotos(context, source)
        val (width, height) = outputSize(project.aspect, shortSide)

        // Image layers sit below text and stickers.
        val specs = project.layers.mapNotNull { layer ->
            // Animated buttons and scene effects are drawn frame by frame at the size they appear.
            MovingArt.of(layer.button)?.let { kind ->
                val frames = MovingArt.Frames(
                    kind, layer.endMs - layer.startMs, (layer.scale * height).toInt().coerceAtLeast(8),
                    width.toFloat() / height,
                )
                return@mapNotNull OverlaySpec(
                    frames.first,
                    layer.startMs, layer.endMs, layer.x, layer.y, layer.opacity, layer.animIn, layer.animOut,
                    if (layer.moving) layer.x2 else layer.x, if (layer.moving) layer.y2 else layer.y,
                    layer.rotation, layer.keyframes, layer.loop,
                    frames = frames,
                )
            }
            val source = MediaUtils.decodeImage(context, Uri.parse(layer.uri), 1440) ?: return@mapNotNull null
            val targetHeight = (layer.scale * height).toInt().coerceAtLeast(2)
            val targetWidth = (source.width.toLong() * targetHeight / source.height).toInt().coerceAtLeast(2)
            OverlaySpec(
                Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true),
                layer.startMs, layer.endMs, layer.x, layer.y, layer.opacity, layer.animIn, layer.animOut,
                if (layer.moving) layer.x2 else layer.x, if (layer.moving) layer.y2 else layer.y,
                layer.rotation,
                layer.keyframes,
                layer.loop,
            )
        } + project.texts.map { text ->
            // A text that appears letter by letter is drawn once per step (fewer steps at large sizes).
            val steps = com.reelforge.editor.model.revealSteps(text.reveal, text.text, if (height > 1200) 12 else 24)
            OverlaySpec(
                EffectFactory.renderText(text, height),
                text.startMs, text.endMs, text.x, text.y, text.opacity, text.animIn, text.animOut,
                if (text.moving) text.x2 else text.x, if (text.moving) text.y2 else text.y,
                text.rotation,
                text.keyframes,
                text.loop,
                steps.map { EffectFactory.renderText(text, height, it) },
                com.reelforge.editor.model.revealDurationMs(text.endMs - text.startMs),
            )
        }

        val total = project.durationMs
        // Video layers that actually fall inside the video.
        val videoLayers = project.videoLayers
            .filter { it.durationMs >= 100 && it.startMs < total - 100 }
            .take(MAX_VIDEO_LAYERS)
        val layered = videoLayers.isNotEmpty()

        val items = project.clips.mapIndexed { index, clip ->
            val clipStart = project.clipStartMs(index)
            val clipEnd = clipStart + clip.durationMs
            // With video layers, text and pictures are drawn after the layers (see below) so they
            // stay on top; otherwise they are drawn on each clip.
            val overlays = (if (layered) emptyList() else specs)
                .filter { it.endMs > clipStart && it.startMs < clipEnd }
                .map { s ->
                    TimedBitmapOverlay(
                        bitmap = s.bitmap,
                        startUs = (s.startMs - clipStart) * 1000,
                        endUs = (s.endMs - clipStart) * 1000,
                        x = s.x,
                        y = s.y,
                        opacity = s.opacity,
                        animIn = s.animIn,
                        animOut = s.animOut,
                        x2 = s.x2,
                        y2 = s.y2,
                        rotation = s.rotation,
                        keyframes = s.keyframes,
                    loop = s.loop,
                    reveal = s.reveal,
                    revealUs = s.revealMs * 1000,
                    frameAt = s.frames?.let { f -> { ms: Long -> f.at(ms) } },
                    )
                }
            buildClipItem(
                context, project, clip, width, height, overlays, fps,
                capFrameRate = !layered, videoOnly = layered,
                duckRangesUs = com.reelforge.editor.model.voiceRangesInClip(project, index),
            )
        }

        // Video tracks go first, topmost first, with the main track underneath them all.
        val sequences = mutableListOf<EditedMediaItemSequence>()
        val placements = mutableListOf<LayerPlacement>()
        if (layered) {
            val blank = Uri.fromFile(MediaUtils.blankImage(context))
            // Track 0 is a hidden blank picture lasting the whole video. Media3 takes the output
            // frame timing and the audio set-up from track 0, and a track that starts with a
            // picture fails if a slower-starting video track comes before it, so a picture that
            // loads instantly goes first.
            sequences += EditedMediaItemSequence(
                listOf(
                    EditedMediaItem.Builder(
                        MediaItem.Builder().setUri(blank)
                            .setMimeType(MimeTypes.IMAGE_PNG)
                            .setImageDurationMs(total)
                            .build()
                    ).setDurationUs(total * 1000).setFrameRate(fps).build()
                )
            )
            // The layer added last is drawn on top.
            videoLayers.asReversed().forEach { layer ->
                val (sequence, placement) = buildVideoLayerSequence(layer, total, blank, height, fps)
                sequences += sequence
                placements += placement
            }
        }
        sequences += EditedMediaItemSequence(items)
        if (layered) {
            // In a layered export the video tracks carry no sound. Each one's sound goes on its
            // own audio-only track, with real silence wherever there is none. This avoids
            // Media3's generated-silence path, which fails when several video tracks are stacked.
            val silence = Uri.fromFile(MediaUtils.silenceFile(context, SILENCE_MS))
            val mainAudio = project.clips.flatMapIndexed { index, clip ->
                clipAudioItems(clip, silence, com.reelforge.editor.model.voiceRangesInClip(project, index), project.duckLevel)
            }
            if (mainAudio.isNotEmpty()) sequences += EditedMediaItemSequence(mainAudio)
            videoLayers.forEach { layer ->
                layerAudioItems(layer, total, silence)?.let { sequences += EditedMediaItemSequence(it) }
            }
        }
        project.music?.let { music ->
            buildMusicSequence(music, total) {
                Uri.fromFile(MediaUtils.silenceFile(context, SILENCE_MS))
            }?.let { sequences += it }
        }
        if (project.audio.isNotEmpty()) {
            val silence = Uri.fromFile(MediaUtils.silenceFile(context, SILENCE_MS))
            project.audio.forEach { clip ->
                buildAudioClipSequence(clip, total, silence, project)?.let { sequences += it }
            }
        }

        val builder = Composition.Builder(sequences).experimentalSetForceAudioTrack(!layered)
            // Many phones now record 10-bit HDR by default. Mixing an HDR clip with photos or SDR
            // clips, or drawing SDR text on it, is not supported when HDR is kept, and some
            // phones stall or fail on it. Exports are SDR anyway, so HDR clips are tone-mapped.
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        if (layered) {
            builder.setVideoCompositorSettings(LayerCompositorSettings(width, height, placements))
            val onTop = specs.map { s ->
                TimedBitmapOverlay(
                    bitmap = s.bitmap,
                    startUs = s.startMs * 1000,
                    endUs = s.endMs * 1000,
                    x = s.x,
                    y = s.y,
                    opacity = s.opacity,
                    animIn = s.animIn,
                    animOut = s.animOut,
                    x2 = s.x2,
                    y2 = s.y2,
                    rotation = s.rotation,
                    keyframes = s.keyframes,
                    loop = s.loop,
                    reveal = s.reveal,
                    revealUs = s.revealMs * 1000,
                    frameAt = s.frames?.let { f -> { ms: Long -> f.at(ms) } },
                )
            }
            if (onTop.isNotEmpty()) {
                val effects: List<Effect> = onTop.chunked(MAX_OVERLAYS_PER_EFFECT).map {
                    OverlayEffect(ImmutableList.copyOf<TextureOverlay>(it))
                }
                builder.setEffects(Effects(emptyList(), effects))
            }
        }
        return builder.build()
    }

    /**
     * One video layer as its own track: a blank picture up to its start, the video itself, then
     * a blank picture to the end, so the track always spans the whole video. The blanks are never
     * seen: the compositor only shows this track during the layer's own time range.
     */
    /**
     * Photos larger than [MAX_PHOTO_SIDE] are swapped for a smaller, upright copy. Phones now take
     * 50 to 200 megapixel photos; Media3 decodes a photo at full size, which can take seconds, run
     * out of memory, or exceed the GPU's largest texture on some phones and stall the export.
     */
    private fun withExportablePhotos(context: Context, project: Project): Project {
        if (project.clips.none { it.type == ClipType.IMAGE }) return project
        return project.copy(
            clips = project.clips.map { clip ->
                if (clip.type != ClipType.IMAGE) return@map clip
                val uri = Uri.parse(clip.uri)
                if (MediaUtils.imageLongSide(context, uri) <= MAX_PHOTO_SIDE) return@map clip
                MediaUtils.playableCopy(context, uri)
                    ?.let { clip.copy(uri = Uri.fromFile(it).toString()) }
                    ?: clip
            },
        )
    }

    /** The largest photo side sent to Media3; 4096 is a texture size every phone supports. */
    const val MAX_PHOTO_SIDE = 4096

    private fun buildVideoLayerSequence(
        layer: VideoLayer,
        totalMs: Long,
        blank: Uri,
        outHeight: Int,
        fps: Int,
    ): Pair<EditedMediaItemSequence, LayerPlacement> {
        val lead = if (layer.startMs >= MIN_BLANK_MS) layer.startMs else 0L
        val playMs = min(layer.durationMs, totalMs - lead)
        val tail = totalMs - lead - playMs
        val layerHeight = ((layer.scale * outHeight).toInt().coerceAtLeast(16) / 2) * 2

        fun blankItem(ms: Long): EditedMediaItem {
            val media = MediaItem.Builder().setUri(blank)
                .setMimeType(MimeTypes.IMAGE_PNG)
                .setImageDurationMs(ms)
                .build()
            return EditedMediaItem.Builder(media).setDurationUs(ms * 1000).setFrameRate(fps).build()
        }

        val items = mutableListOf<EditedMediaItem>()
        if (lead > 0) items += blankItem(lead)

        val media = MediaItem.Builder().setUri(Uri.parse(layer.uri))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(layer.trimStartMs)
                    .setEndPositionMs(layer.trimStartMs + playMs)
                    .build()
            )
            .build()
        val video = mutableListOf<Effect>(Presentation.createForHeight(layerHeight))
        if (layer.chromaKey) video += ChromaKeyEffect(layer.keyColor, layer.keyTolerance)
        items += EditedMediaItem.Builder(media)
            .setRemoveAudio(true) // its sound is on a separate audio track, see layerAudioItems
            .setEffects(Effects(emptyList(), video))
            .build()

        if (tail >= MIN_BLANK_MS) items += blankItem(tail)

        val placement = LayerPlacement(
            startUs = lead * 1000,
            endUs = (lead + playMs) * 1000,
            x = layer.x,
            y = layer.y,
            opacity = layer.opacity,
            rotation = layer.rotation,
            keyframes = layer.keyframes,
        )
        return EditedMediaItemSequence(items) to placement
    }

    /** Real silence lasting [ms], as one or more pieces of the silence file. */
    private fun silenceItems(silence: Uri, ms: Long): List<EditedMediaItem> {
        val items = mutableListOf<EditedMediaItem>()
        var left = ms
        while (left >= 20) {
            val part = min(left, SILENCE_MS)
            val media = MediaItem.Builder().setUri(silence).apply {
                if (part < SILENCE_MS) {
                    setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setEndPositionMs(part).build()
                    )
                }
            }.build()
            items += EditedMediaItem.Builder(media).build()
            left -= part
        }
        return items
    }

    /** The sound of one main-track clip as audio-only items: its own audio, or silence. */
    private fun clipAudioItems(
        clip: Clip,
        silence: Uri,
        duckRangesUs: List<LongRange> = emptyList(),
        duckLevel: Float = 1f,
    ): List<EditedMediaItem> {
        if (clip.type == ClipType.IMAGE || clip.muted || !clip.hasAudio) {
            return silenceItems(silence, clip.durationMs)
        }
        val media = MediaItem.Builder().setUri(Uri.parse(clip.uri))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.trimStartMs)
                    .setEndPositionMs(clip.trimEndMs)
                    .build()
            )
            .build()
        val audio = mutableListOf<AudioProcessor>()
        if (clip.speed != 1f || clip.pitch != 1f) {
            audio += SonicAudioProcessor().apply {
                setSpeed(clip.speed)
                setPitch(clip.pitch.coerceIn(0.5f, 2f))
            }
        }
        if (clip.volume != 1f) audio += volumeProcessor(clip.volume)
        if (duckRangesUs.isNotEmpty()) audio += DuckProcessor(duckRangesUs, duckLevel)
        audio += fxChain(clip.audioFx, denoise = clip.denoise)
        if (clip.fadeInMs > 0 || clip.fadeOutMs > 0) {
            audio += AudioFadeProcessor(clip.durationMs * 1000, clip.fadeInMs * 1000, clip.fadeOutMs * 1000)
        }
        return listOf(
            EditedMediaItem.Builder(media).setRemoveVideo(true).setEffects(Effects(audio, emptyList())).build()
        )
    }

    /** The sound of a video layer: silence up to its start, then its audio. Null when silent. */
    private fun layerAudioItems(layer: VideoLayer, totalMs: Long, silence: Uri): List<EditedMediaItem>? {
        if (layer.muted || !layer.hasAudio) return null
        val lead = if (layer.startMs >= MIN_BLANK_MS) layer.startMs else 0L
        val playMs = min(layer.durationMs, totalMs - lead)
        val media = MediaItem.Builder().setUri(Uri.parse(layer.uri))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(layer.trimStartMs)
                    .setEndPositionMs(layer.trimStartMs + playMs)
                    .build()
            )
            .build()
        val audio = if (layer.volume != 1f) listOf(volumeProcessor(layer.volume)) else emptyList()
        return silenceItems(silence, lead) +
            EditedMediaItem.Builder(media).setRemoveVideo(true).setEffects(Effects(audio, emptyList())).build()
    }

    /**
     * The processors for a sound effect, optionally after Clean voice. Pitch styles (Chipmunk,
     * Deep voice) change pitch without changing length; the others run a filter.
     */
    private fun fxChain(
        fx: AudioFx,
        clean: Boolean = false,
        pitch: Float = fx.pitch,
        amount: Float = 1f,
        denoise: com.reelforge.editor.model.NoiseRemoval = com.reelforge.editor.model.NoiseRemoval.OFF,
    ): List<AudioProcessor> = buildList {
        // Noise comes out first, so the voice clean-up and effects work on the clean sound.
        if (denoise != com.reelforge.editor.model.NoiseRemoval.OFF) add(NoiseRemoverProcessor(denoise))
        if (clean) add(VoiceCleanProcessor())
        if (pitch != 1f) add(SonicAudioProcessor().apply { setPitch(pitch) })
        if (!fx.filterless && amount > 0f) add(AudioFxProcessor(fx, amount))
    }

    /** A sound taken from a video is lowered under voice-overs, as it was inside the video. */
    private fun duckFor(clip: AudioClip, playMs: Long, project: Project?): List<AudioProcessor> {
        if (project == null || !clip.duck || clip.voice) return emptyList()
        val ranges = com.reelforge.editor.model.voiceRangesIn(project, clip.startMs, clip.startMs + playMs)
        return if (ranges.isEmpty()) emptyList() else listOf(DuckProcessor(ranges, project.duckLevel))
    }

    /** A voice-over / sound clip: silence up to its start, then the audio itself. */
    private fun buildAudioClipSequence(
        clip: AudioClip,
        videoDurationMs: Long,
        silence: Uri,
        project: Project? = null,
    ): EditedMediaItemSequence? {
        val available = videoDurationMs - clip.startMs
        if (available < 100) return null
        val items = mutableListOf<EditedMediaItem>()
        var gap = clip.startMs
        while (gap >= 50) {
            val part = min(gap, SILENCE_MS)
            val media = MediaItem.Builder().setUri(silence).apply {
                if (part < SILENCE_MS) {
                    setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setEndPositionMs(part).build()
                    )
                }
            }.build()
            items += EditedMediaItem.Builder(media).build()
            gap -= part
        }
        val playMs = if (clip.durationMs > 0) min(clip.durationMs, available) else available
        val media = MediaItem.Builder().setUri(Uri.parse(clip.uri))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.offsetMs)
                    .setEndPositionMs(clip.offsetMs + playMs)
                    .build()
            )
            .build()
        items += EditedMediaItem.Builder(media)
            .setRemoveVideo(true)
            .setEffects(
                Effects(
                    fxChain(clip.audioFx, clip.clean, clip.totalPitch, clip.fxAmount, clip.denoise) +
                        audioChain(clip.volume, playMs, clip.fadeInMs, clip.fadeOutMs) +
                        duckFor(clip, playMs, project),
                    emptyList(),
                )
            )
            .build()
        return EditedMediaItemSequence(items)
    }

    private fun buildClipItem(
        context: Context,
        project: Project,
        clip: Clip,
        width: Int,
        height: Int,
        overlays: List<TextureOverlay>,
        fps: Int,
        capFrameRate: Boolean,
        videoOnly: Boolean,
        duckRangesUs: List<LongRange> = emptyList(),
    ): EditedMediaItem {
        val isImage = clip.type == ClipType.IMAGE
        val uri = Uri.parse(clip.uri)
        val mediaItem = MediaItem.Builder().setUri(uri).apply {
            if (isImage) {
                setImageDurationMs(clip.durationMs)
                setMimeType(com.reelforge.editor.util.MediaUtils.imageMime(com.reelforge.editor.util.MediaUtils.imageFormat(context, uri)))
            } else {
                setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.trimStartMs)
                        .setEndPositionMs(clip.trimEndMs)
                        .build()
                )
            }
        }.build()

        val video = mutableListOf<Effect>()
        val audio = mutableListOf<AudioProcessor>()

        if (!isImage && clip.speed != 1f) {
            video += SpeedChangeEffect(clip.speed)
        }
        if (!isImage && capFrameRate) {
            // Caps the frame rate, which also keeps fast-forwarded clips light to encode. Left out
            // when video layers are stacked: Media3's frame dropping fails in that pipeline.
            video += FrameDropEffect.createDefaultFrameDropEffect(fps.toFloat())
        }
        if (!isImage && (clip.speed != 1f || clip.pitch != 1f)) {
            audio += SonicAudioProcessor().apply {
                setSpeed(clip.speed)
                setPitch(clip.pitch.coerceIn(0.5f, 2f))
            }
        }
        if (!isImage && !clip.muted && clip.volume != 1f) {
            audio += volumeProcessor(clip.volume)
        }
        // The video's own sound is lowered while a voice-over plays (times are after speed change).
        if (!isImage && !clip.muted && clip.hasAudio && duckRangesUs.isNotEmpty()) {
            audio += DuckProcessor(duckRangesUs, project.duckLevel)
        }

        video += EffectFactory.geometry(clip)
        if (isImage && clip.motion != com.reelforge.editor.model.PhotoMotion.NONE) {
            video += PhotoMotionEffect(clip.durationMs * 1000, clip.motion)
        }
        video += EffectFactory.color(clip)
        if (EffectFactory.usesBackground(clip, project)) {
            // The backdrop stays put while the picture is resized / moved over it.
            video += EffectFactory.background(project, width, height, clip)
        } else {
            video += Presentation.createForWidthAndHeight(
                width,
                height,
                if (clip.fill) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT,
            )
            EffectFactory.framing(clip)?.let { video += it }
        }
        val durationUs = clip.durationMs * 1000
        val inUs = clip.fadeInMs * 1000
        val outUs = clip.fadeOutMs * 1000
        if ((inUs > 0 || outUs > 0) && clip.transition != TransitionStyle.FADE) {
            video += TransitionMotion(durationUs, inUs, outUs, clip.transition)
        }
        // A slide moves the picture instead of dimming it.
        val dims = clip.transition != TransitionStyle.SLIDE
        if (clip.opacity < 1f || (dims && (inUs > 0 || outUs > 0))) {
            video += FadeEffect(
                durationUs, if (dims) inUs else 0L, if (dims) outUs else 0L, clip.opacity
            )
        }
        if (!isImage && !clip.muted && clip.hasAudio &&
            (clip.audioFx != AudioFx.NONE || clip.denoise != com.reelforge.editor.model.NoiseRemoval.OFF)
        ) {
            audio += fxChain(clip.audioFx, denoise = clip.denoise)
        }
        val hasFade = clip.fadeInMs > 0 || clip.fadeOutMs > 0
        if (!isImage && !clip.muted && clip.hasAudio && hasFade) {
            audio += AudioFadeProcessor(clip.durationMs * 1000, clip.fadeInMs * 1000, clip.fadeOutMs * 1000)
        }
        // Media3 draws at most 15 overlays per OverlayEffect, so larger sets are chained.
        overlays.chunked(MAX_OVERLAYS_PER_EFFECT).forEach { group ->
            video += OverlayEffect(ImmutableList.copyOf(group))
        }

        return EditedMediaItem.Builder(mediaItem).apply {
            if (isImage) {
                setDurationUs(clip.durationMs * 1000)
                setFrameRate(fps)
            }
            setRemoveAudio(videoOnly || isImage || clip.muted || !clip.hasAudio)
            setEffects(Effects(audio, video))
        }.build()
    }

    /**
     * Background music laid end to end until it covers the video (when looping), with the last
     * piece cut to fit. The fade-in goes on the first piece and the fade-out on the last.
     */
    /**
     * Background music: silence up to where it starts, then the song from its offset, repeated
     * when looping, until it is cut off or the video ends.
     */
    private fun buildMusicSequence(
        music: MusicTrack,
        videoDurationMs: Long,
        silence: () -> Uri,
    ): EditedMediaItemSequence? {
        val start = music.startMs.coerceIn(0, videoDurationMs)
        val span = music.windowEnd(videoDurationMs) - start
        if (span < 100) return null
        val known = music.durationMs > 0
        var from = if (known) music.offsetMs % music.durationMs else music.offsetMs
        val pieces = mutableListOf<Pair<Long, Long>>() // (position in the file, length)
        var remaining = span
        while (remaining > 0 && pieces.size < MAX_MUSIC_REPEATS) {
            val piece = if (known) min(music.durationMs - from, remaining) else remaining
            if (piece >= 20) pieces += from to piece // slivers shorter than a frame are skipped
            remaining -= piece
            from = 0
            if (!music.loop) break
        }
        if (pieces.isEmpty()) return null

        val uri = Uri.parse(music.uri)
        val items = mutableListOf<EditedMediaItem>()
        if (start >= 20) items += silenceItems(silence(), start)
        pieces.forEachIndexed { i, (position, length) ->
            val media = MediaItem.Builder().setUri(uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(position)
                        .setEndPositionMs(position + length)
                        .build()
                )
                .build()
            val fadeIn = if (i == 0) music.fadeInMs else 0L
            val fadeOut = if (i == pieces.lastIndex) music.fadeOutMs else 0L
            items += EditedMediaItem.Builder(media)
                .setRemoveVideo(true)
                .setEffects(Effects(audioChain(music.volume, length, fadeIn, fadeOut), emptyList()))
                .build()
        }
        return EditedMediaItemSequence(items)
    }

    /** Volume, plus a fade when one is asked for. Fades are kept inside the piece. */
    private fun audioChain(volume: Float, lengthMs: Long, fadeInMs: Long, fadeOutMs: Long): List<AudioProcessor> {
        val chain = mutableListOf(volumeProcessor(volume))
        var fadeIn = fadeInMs.coerceIn(0, lengthMs)
        var fadeOut = fadeOutMs.coerceIn(0, lengthMs)
        if (fadeIn + fadeOut > lengthMs) {
            fadeIn = fadeIn.coerceAtMost(lengthMs / 2)
            fadeOut = fadeOut.coerceAtMost(lengthMs / 2)
        }
        if (fadeIn > 0 || fadeOut > 0) {
            chain += AudioFadeProcessor(lengthMs * 1000, fadeIn * 1000, fadeOut * 1000)
        }
        return chain
    }

    /** Scales audio volume while keeping the channel layout. */
    private fun volumeProcessor(volume: Float): AudioProcessor {
        val processor = ChannelMixingAudioProcessor()
        for (channels in 1..6) {
            runCatching {
                processor.putChannelMixingMatrix(
                    ChannelMixingMatrix.create(channels, channels).scaleBy(volume.coerceIn(0f, 2f))
                )
            }
        }
        return processor
    }
}
