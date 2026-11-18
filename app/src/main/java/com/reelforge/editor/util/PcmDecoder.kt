package com.reelforge.editor.util

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Changes the sample rate of a stream of mono samples by straight-line interpolation. Good enough
 * for speech recognition, which only needs 16 kHz. Plain Kotlin, so it can be tested.
 */
class Resampler(private val inRate: Int, private val outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private var position = 0.0   // where the next output sample falls, in input samples
    private var previous = 0f    // the last input sample of the previous block
    private var started = false

    /** Converts one block; output samples are added to [out]. */
    fun process(input: FloatArray, count: Int, out: MutableList<Float>) {
        if (count <= 0) return
        if (inRate == outRate) {
            for (i in 0 until count) out += input[i]
            return
        }
        if (!started) {
            previous = input[0]
            started = true
        }
        // Index -1 stands for [previous], so blocks join without a click.
        while (position < count - 1) {
            val i = kotlin.math.floor(position).toInt()
            val t = (position - i).toFloat()
            val a = if (i < 0) previous else input[i]
            val b = input[i + 1]
            out += a + (b - a) * t
            position += step
        }
        position -= count
        previous = input[count - 1]
    }
}

/** Reads the sound of a video or audio file as 16 kHz mono 16-bit samples, for speech recognition. */
object PcmDecoder {
    const val RATE = 16_000

    /**
     * Decodes the part [fromMs, toMs) of the file's first sound track. Samples are handed to
     * [onSamples] in blocks; [stop] is checked between blocks. Returns false when the file has no
     * sound track that can be decoded.
     */
    fun decode(
        context: Context,
        uri: Uri,
        fromMs: Long,
        toMs: Long,
        stop: () -> Boolean = { false },
        onSamples: (ShortArray, Int) -> Unit,
    ): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
            extractor.selectTrack(track)
            val fromUs = fromMs * 1000
            val toUs = toMs * 1000
            extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var float = false
            var resampler = Resampler(rate, RATE)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val mono = ArrayList<Float>()
            var block = FloatArray(0)

            while (!stop()) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        val time = extractor.sampleTime
                        if (size < 0 || time > toUs) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, time, 0)
                            extractor.advance()
                        }
                    }
                }
                val out = decoder.dequeueOutputBuffer(info, 10_000)
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = decoder.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    resampler = Resampler(rate, RATE)
                    continue
                }
                if (out < 0) continue
                val ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val data = decoder.getOutputBuffer(out)
                if (data != null && info.size > 0) {
                    data.position(info.offset)
                    data.limit(info.offset + info.size)
                    data.order(ByteOrder.LITTLE_ENDIAN)
                    val bytesPerSample = if (float) 4 else 2
                    val frames = info.size / (bytesPerSample * channels)
                    if (block.size < frames) block = FloatArray(frames)
                    // Keep only the samples inside [fromUs, toUs).
                    var kept = 0
                    for (frame in 0 until frames) {
                        var sum = 0f
                        for (c in 0 until channels) {
                            sum += if (float) data.float else data.short / 32768f
                        }
                        val timeUs = info.presentationTimeUs + frame * 1_000_000L / rate
                        if (timeUs in fromUs until toUs) block[kept++] = sum / channels
                    }
                    mono.clear()
                    resampler.process(block, kept, mono)
                    if (mono.isNotEmpty()) {
                        val shorts = ShortArray(mono.size) { (mono[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
                        onSamples(shorts, shorts.size)
                    }
                    if (info.presentationTimeUs > toUs) {
                        decoder.releaseOutputBuffer(out, false)
                        break
                    }
                }
                decoder.releaseOutputBuffer(out, false)
                if (ended) break
            }
            return true
        } catch (e: Exception) {
            return false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
