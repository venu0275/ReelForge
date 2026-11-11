package com.reelforge.editor.engine

import android.content.Context
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
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import com.reelforge.editor.model.AspectRatio
import com.reelforge.editor.model.Clip
import com.reelforge.editor.model.ClipType
import com.reelforge.editor.model.MusicTrack
import com.reelforge.editor.model.Project
import kotlin.math.max
import kotlin.math.min

/** Turns a [Project] into a Media3 Transformer [Composition] and renders it to an MP4. */
@OptIn(UnstableApi::class)
object ExportManager {

    private const val IMAGE_FRAME_RATE = 30

    /** Output frame size for an aspect ratio, where [shortSide] is e.g. 720 or 1080. */
    fun outputSize(aspect: AspectRatio, shortSide: Int): Pair<Int, Int> {
        fun even(v: Int) = v - (v % 2)
        return if (aspect.w >= aspect.h) {
            even(shortSide * aspect.w / aspect.h) to even(shortSide)
        } else {
            even(shortSide) to even(shortSide * aspect.h / aspect.w)
        }
    }

    fun createTransformer(context: Context, listener: Transformer.Listener): Transformer =
        Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(listener)
            .build()

    fun buildComposition(context: Context, project: Project, shortSide: Int): Composition {
        val (width, height) = outputSize(project.aspect, shortSide)
        val textBitmaps = project.texts.associate { it.id to EffectFactory.renderText(it, height) }

        val items = project.clips.mapIndexed { index, clip ->
            val clipStart = project.clipStartMs(index)
            val clipEnd = clipStart + clip.durationMs
            val overlays = project.texts
                .filter { it.endMs > clipStart && it.startMs < clipEnd }
                .map { text ->
                    TimedTextOverlay(
                        bitmap = textBitmaps.getValue(text.id),
                        startUs = max(0L, text.startMs - clipStart) * 1000,
                        endUs = min(clip.durationMs, text.endMs - clipStart) * 1000,
                        x = text.x,
                        y = text.y,
                    )
                }
            buildClipItem(clip, width, height, overlays)
        }

        val sequences = mutableListOf(EditedMediaItemSequence(items))
        project.music?.let { music ->
            buildMusicSequence(music, project.durationMs)?.let { sequences += it }
        }

        return Composition.Builder(sequences)
            .experimentalSetForceAudioTrack(true)
            .build()
    }

    private fun buildClipItem(
        clip: Clip,
        width: Int,
        height: Int,
        overlays: List<TextureOverlay>,
    ): EditedMediaItem {
        val isImage = clip.type == ClipType.IMAGE
        val mediaItem = MediaItem.Builder().setUri(Uri.parse(clip.uri)).apply {
            if (isImage) {
                setImageDurationMs(clip.durationMs)
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
            audio += SonicAudioProcessor().apply { setSpeed(clip.speed) }
        }
        if (!isImage && !clip.muted && clip.volume != 1f) {
            audio += volumeProcessor(clip.volume)
        }

        video += EffectFactory.rotation(clip)
        video += EffectFactory.color(clip)
        video += Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT)
        if (clip.fadeInMs > 0 || clip.fadeOutMs > 0) {
            video += FadeEffect(clip.durationMs * 1000, clip.fadeInMs * 1000, clip.fadeOutMs * 1000)
        }
        if (overlays.isNotEmpty()) {
            video += OverlayEffect(ImmutableList.copyOf(overlays))
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
