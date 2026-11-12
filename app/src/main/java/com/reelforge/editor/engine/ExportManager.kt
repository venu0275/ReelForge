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

    fun createTransformer(
        context: Context,
        listener: Transformer.Listener,
        videoBitrate: Int,
        hevc: Boolean = false,
    ): Transformer =
        Transformer.Builder(context)
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
    )

    /** Builds the composition. Decodes images and writes files, so call it off the main thread. */
    fun buildComposition(
        context: Context,
        project: Project,
        shortSide: Int,
        fps: Int = DEFAULT_FRAME_RATE,
    ): Composition {
        val (width, height) = outputSize(project.aspect, shortSide)

        // Image layers sit below text and stickers.
        val specs = project.layers.mapNotNull { layer ->
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
            OverlaySpec(
                EffectFactory.renderText(text, height),
                text.startMs, text.endMs, text.x, text.y, text.opacity, text.animIn, text.animOut,
                if (text.moving) text.x2 else text.x, if (text.moving) text.y2 else text.y,
                text.rotation,
                text.keyframes,
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
                    )
                }
            buildClipItem(
                context, project, clip, width, height, overlays, fps,
                capFrameRate = !layered, videoOnly = layered,
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
            val mainAudio = project.clips.flatMap { clipAudioItems(it, silence) }
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
                buildAudioClipSequence(clip, total, silence)?.let { sequences += it }
            }
        }

        val builder = Composition.Builder(sequences).experimentalSetForceAudioTrack(!layered)
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
    private fun clipAudioItems(clip: Clip, silence: Uri): List<EditedMediaItem> {
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
        if (clip.audioFx != AudioFx.NONE) audio += AudioFxProcessor(clip.audioFx)
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

    /** A voice-over / sound clip: silence up to its start, then the audio itself. */
    private fun buildAudioClipSequence(
        clip: AudioClip,
        videoDurationMs: Long,
        silence: Uri,
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
                    (if (clip.audioFx != AudioFx.NONE) listOf(AudioFxProcessor(clip.audioFx)) else emptyList()) +
                        audioChain(clip.volume, playMs, clip.fadeInMs, clip.fadeOutMs),
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
    ): EditedMediaItem {
        val isImage = clip.type == ClipType.IMAGE
        val uri = Uri.parse(clip.uri)
        val mediaItem = MediaItem.Builder().setUri(uri).apply {
            if (isImage) {
                setImageDurationMs(clip.durationMs)
                val mime = if (uri.scheme == "file") MimeTypes.IMAGE_JPEG
                else context.contentResolver.getType(uri)
                if (mime != null) setMimeType(mime)
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

        video += EffectFactory.geometry(clip)
        video += EffectFactory.color(clip)
        if (EffectFactory.usesBackground(clip, project)) {
            video += EffectFactory.background(project, width, height)
        } else {
            video += Presentation.createForWidthAndHeight(
                width,
                height,
                if (clip.fill) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT,
            )
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
        if (!isImage && !clip.muted && clip.hasAudio && clip.audioFx != AudioFx.NONE) {
            audio += AudioFxProcessor(clip.audioFx)
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
