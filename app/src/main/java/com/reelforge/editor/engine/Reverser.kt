package com.reelforge.editor.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.view.Surface
import java.io.File
import kotlin.math.roundToInt

/**
 * Makes a copy of part of a video that plays backwards. Frames are read from the end in small
 * groups and fed to the phone's video encoder in reverse order. The copy has no sound.
 */
object Reverser {
    /** Longest part that can be reversed; every frame has to be decoded and encoded again. */
    const val MAX_MS = 30_000L

    /** Output sides are a multiple of 16, which every phone encoder accepts. */
    fun outputSize(width: Int, height: Int, maxSide: Int = 1280): Pair<Int, Int> {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(width, height))
        fun even16(v: Float) = ((v / 16f).roundToInt() * 16).coerceAtLeast(16)
        return even16(width * scale) to even16(height * scale)
    }

    /** Frames decoded at once: as many as fit in about 48 MB, between 3 and 15. */
    fun batchSize(width: Int, height: Int): Int =
        (48_000_000L / (width.toLong() * height * 4).coerceAtLeast(1)).toInt().coerceIn(3, 15)

    /** The frame numbers to write, last first. */
    fun reversedFrames(first: Int, last: Int): IntProgression = last downTo first

    /**
     * Writes the part [fromMs]..[toMs] of [uri], backwards, to [out]. [progress] gets 0..1 and
     * [stop] is checked between frames. Returns the length of the new video in milliseconds.
     * Throws if the video cannot be read or encoded.
     */
    fun reverse(
        context: Context,
        uri: Uri,
        fromMs: Long,
        toMs: Long,
        out: File,
        stop: () -> Boolean,
        progress: (Float) -> Unit,
    ): Long {
        val retriever = MediaMetadataRetriever()
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var surface: Surface? = null
        var muxerStarted = false
        try {
            retriever.setDataSource(context, uri)
            val frameCount = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toIntOrNull() ?: error("frame count unknown")
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: error("length unknown")
            if (frameCount <= 1 || duration <= 0) error("no frames")
            val fps = frameCount * 1000.0 / duration
            val first = (fromMs * fps / 1000).toInt().coerceIn(0, frameCount - 1)
            val last = ((toMs * fps / 1000).toInt() - 1).coerceIn(first, frameCount - 1)
            val total = last - first + 1

            val sample = retriever.getFrameAtIndex(first) ?: error("unreadable frame")
            val (w, h) = outputSize(sample.width, sample.height)
            val batch = batchSize(sample.width, sample.height)
            sample.recycle()

            val rate = fps.roundToInt().coerceIn(1, 60)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, (w * h * rate * 0.15).toInt().coerceIn(1_000_000, 12_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, rate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder = codec
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val input = codec.createInputSurface()
            surface = input
            codec.start()
            val mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux

            val frameUs = 1_000_000L / rate
            var written = 0
            var track = -1
            val info = MediaCodec.BufferInfo()

            // Takes whatever the encoder has finished. Times are set here, one frame apart,
            // because the times the encoder sees are just when each frame was drawn.
            fun drain(endOfStream: Boolean) {
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = mux.addTrack(codec.outputFormat)
                            mux.start()
                            muxerStarted = true
                        }
                        index >= 0 -> {
                            val data = codec.getOutputBuffer(index)!!
                            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (!config && info.size > 0 && muxerStarted) {
                                info.presentationTimeUs = written * frameUs
                                data.position(info.offset)
                                data.limit(info.offset + info.size)
                                mux.writeSampleData(track, data, info)
                                written++
                            }
                            codec.releaseOutputBuffer(index, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }

            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val target = Rect(0, 0, w, h)
            var done = 0
            var end = last
            while (end >= first) {
                if (stop()) throw InterruptedException("stopped")
                val start = maxOf(first, end - batch + 1)
                val frames: List<Bitmap> = retriever.getFramesAtIndex(start, end - start + 1)
                for (frame in frames.asReversed()) {
                    val canvas = input.lockHardwareCanvas()
                    try {
                        canvas.drawColor(android.graphics.Color.BLACK)
                        canvas.drawBitmap(frame, null, target, paint)
                    } finally {
                        input.unlockCanvasAndPost(canvas)
                    }
                    frame.recycle()
                    drain(false)
                    done++
                    progress(done.toFloat() / total)
                }
                end = start - 1
            }
            codec.signalEndOfInputStream()
            drain(true)
            return written * frameUs / 1000
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { surface?.release() }
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { retriever.release() }
        }
    }
}
