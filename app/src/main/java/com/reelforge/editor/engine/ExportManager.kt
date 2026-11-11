package com.reelforge.editor.engine

import android.content.Context
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
import com.reelforge.editor.model.OverlayAnim
import com.reelforge.editor.model.Clip
import com.reelforge.editor.model.ClipType
import com.reelforge.editor.model.MusicTrack
import com.reelforge.editor.model.Project
import com.reelforge.editor.model.TransitionStyle
import com.reelforge.editor.util.MediaUtils
import kotlin.math.min

/** Turns a [Project] into a Media3 Transformer [Composition] and renders it to an MP4. */
@OptIn(UnstableApi::class)
object ExportManager {

    private const val IMAGE_FRAME_RATE = 30
    private const val SILENCE_MS = 10_000L
    private const val MAX_OVERLAYS_PER_EFFECT = 15

    /** Output frame size for an aspect ratio, where [shortSide] is e.g. 720 or 1080. */
    fun outputSize(aspect: AspectRatio, shortSide: Int): Pair<Int, Int> {
        fun even(v: Int) = v - (v % 2)
        return if (aspect.w >= aspect.h) {
            even(shortSide * aspect.w / aspect.h) to even(shortSide)
        } else {
            even(shortSide) to even(shortSide * aspect.h / aspect.w)
        }
    }

    /** Target video bitrate for a frame size; [quality] is 0 (small file), 1 or 2 (best). */
    fun bitrate(width: Int, height: Int, quality: Int): Int {
        val bitsPerPixel = when (quality) {
            0 -> 0.07
            2 -> 0.20
            else -> 0.12
        }
        return (width.toLong() * height * IMAGE_FRAME_RATE * bitsPerPixel).toInt()
    }

    fun createTransformer(
        context: Context,
        listener: Transformer.Listener,
        videoBitrate: Int,
    ): Transformer =
        Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
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
    )

    /** Builds the composition. Decodes images and writes files, so call it off the main thread. */
    fun buildComposition(context: Context, project: Project, shortSide: Int): Composition {
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
            )
        } + project.texts.map { text ->
            OverlaySpec(
                EffectFactory.renderText(text, height),
                text.startMs, text.endMs, text.x, text.y, text.opacity, text.animIn, text.animOut,
                if (text.moving) text.x2 else text.x, if (text.moving) text.y2 else text.y,
                text.rotation,
            )
        }

        val items = project.clips.mapIndexed { index, clip ->
            val clipStart = project.clipStartMs(index)
            val clipEnd = clipStart + clip.durationMs
            val overlays = specs
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
                    )
                }
            buildClipItem(context, project, clip, width, height, overlays)
        }

        val total = project.durationMs
        val sequences = mutableListOf(EditedMediaItemSequence(items))
        project.music?.let { music ->
            buildMusicSequence(music, total)?.let { sequences += it }
        }
        if (project.audio.isNotEmpty()) {
            val silence = Uri.fromFile(MediaUtils.silenceFile(context, SILENCE_MS))
            project.audio.forEach { clip ->
                buildAudioClipSequence(clip, total, silence)?.let { sequences += it }
            }
        }

        return Composition.Builder(sequences)
            .experimentalSetForceAudioTrack(true)
            .build()
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
                MediaItem.ClippingConfiguration.Builder().setEndPositionMs(playMs).build()
            )
            .build()
        items += EditedMediaItem.Builder(media)
            .setRemoveVideo(true)
            .setEffects(Effects(listOf(volumeProcessor(clip.volume)), emptyList()))
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
                setFrameRate(IMAGE_FRAME_RATE)
            }
            setRemoveAudio(isImage || clip.muted || !clip.hasAudio)
            setEffects(Effects(audio, video))
        }.build()
    }

    private fun buildMusicSequence(music: MusicTrack, videoDurationMs: Long): EditedMediaItemSequence? {
        if (videoDurationMs <= 0) return null
        val longerThanVideo = music.durationMs <= 0 || music.durationMs >= videoDurationMs
        val mediaItem = MediaItem.Builder().setUri(Uri.parse(music.uri)).apply {
            if (longerThanVideo) {
                setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setEndPositionMs(videoDurationMs)
                        .build()
                )
            }
        }.build()
        val item = EditedMediaItem.Builder(mediaItem)
            .setRemoveVideo(true)
            .setEffects(Effects(listOf(volumeProcessor(music.volume)), emptyList()))
            .build()
        val loop = music.loop && !longerThanVideo
        return EditedMediaItemSequence(listOf(item), loop)
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
