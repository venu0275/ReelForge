package com.reelforge.editor.engine

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.reelforge.editor.data.AssetDownloader
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Vocal Separator: splits a song into the voice and the music (karaoke), on the phone.
 *
 * The model is Spleeter 2stems by Deezer Research (MIT licence), converted to LiteRT by
 * tools/models/convert_spleeter.py. It is downloaded once from the app's public assets repo.
 * It works the way Spleeter does: a 4096-point STFT at 44.1 kHz, 512 frames at a time, a mask
 * for the voice; the music is what is left (so voice + music is exactly the song).
 */
object VocalModel {
    const val FILE = "vocal_separator_2stems.tflite"
    const val URL = "https://raw.githubusercontent.com/venu0275/cutmitra-assets/main/models/$FILE"
    const val SIZE_MB = 20
    /** Longest piece that is split in one go (it is held in memory while it is worked on). */
    const val MAX_MS = 5 * 60_000L

    fun file(context: Context) = File(File(context.filesDir, "models").apply { mkdirs() }, FILE)

    fun isReady(context: Context) = file(context).length() > 1_000_000

    fun download(context: Context, onProgress: (Int) -> Unit) {
        val f = file(context)
        AssetDownloader.download(URL, f, onProgress)
        // A broken download must not stay: it would fail every time.
        runCatching { Interpreter(f).close() }.onFailure {
            f.delete()
            throw java.io.IOException("downloaded model is not usable", it)
        }
    }

    fun delete(context: Context) = file(context).delete()
}

/** The sound of a file as 44.1 kHz stereo 16-bit samples, interleaved (left, right, left, ...). */
class StereoSound(val samples: ShortArray, val frames: Int) {
    companion object {
        const val RATE = 44_100
    }
}

object StereoDecoder {

    /**
     * Decodes [fromMs, toMs) of the file's first sound track to 44.1 kHz stereo. Mono becomes the
     * same sound on both sides. Returns null when there is no sound that can be decoded.
     */
    fun decode(context: Context, uri: Uri, fromMs: Long, toMs: Long, stop: () -> Boolean = { false }): StereoSound? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
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
            val wanted = (((toMs - fromMs).coerceAtLeast(0) * StereoSound.RATE) / 1000).toInt() + StereoSound.RATE
            val out = ShortArray(wanted * 2)
            var written = 0
            var left = com.reelforge.editor.util.Resampler(rate, StereoSound.RATE)
            var right = com.reelforge.editor.util.Resampler(rate, StereoSound.RATE)
            val l = ArrayList<Float>()
            val r = ArrayList<Float>()
            var blockL = FloatArray(0)
            var blockR = FloatArray(0)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
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
                val o = decoder.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = decoder.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    left = com.reelforge.editor.util.Resampler(rate, StereoSound.RATE)
                    right = com.reelforge.editor.util.Resampler(rate, StereoSound.RATE)
                    continue
                }
                if (o < 0) continue
                val ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val data = decoder.getOutputBuffer(o)
                if (data != null && info.size > 0) {
                    data.position(info.offset)
                    data.limit(info.offset + info.size)
                    data.order(ByteOrder.LITTLE_ENDIAN)
                    val bytes = if (float) 4 else 2
                    val frames = info.size / (bytes * channels)
                    if (blockL.size < frames) {
                        blockL = FloatArray(frames); blockR = FloatArray(frames)
                    }
                    var kept = 0
                    for (frame in 0 until frames) {
                        var a = 0f
                        var b = 0f
                        for (c in 0 until channels) {
                            val v = if (float) data.float else data.short / 32768f
                            if (c == 0) a = v
                            if (c == 1) b = v
                        }
                        if (channels == 1) b = a
                        val timeUs = info.presentationTimeUs + frame * 1_000_000L / rate
                        if (timeUs in fromUs until toUs) {
                            blockL[kept] = a; blockR[kept] = b; kept++
                        }
                    }
                    l.clear(); r.clear()
                    left.process(blockL, kept, l)
                    right.process(blockR, kept, r)
                    val n = minOf(l.size, r.size)
                    for (i in 0 until n) {
                        if (written >= wanted) break
                        out[2 * written] = (l[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                        out[2 * written + 1] = (r[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                        written++
                    }
                    if (info.presentationTimeUs > toUs) {
                        decoder.releaseOutputBuffer(o, false)
                        break
                    }
                }
                decoder.releaseOutputBuffer(o, false)
                if (ended) break
            }
            if (written == 0) return null
            return StereoSound(out, written)
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}

/** Writes 16-bit stereo WAV files a piece at a time. */
class WavWriter(file: File, private val rate: Int = StereoSound.RATE, private val channels: Int = 2) : AutoCloseable {
    private val out = RandomAccessFile(file, "rw").apply { setLength(0) }
    private var dataBytes = 0L
    private var buffer = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)

    init {
        out.write(header(0))
    }

    fun write(samples: ShortArray, count: Int) {
        if (buffer.capacity() < count * 2) buffer = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.clear()
        for (i in 0 until count) buffer.putShort(samples[i])
        out.write(buffer.array(), 0, count * 2)
        dataBytes += count * 2
    }

    override fun close() {
        out.seek(0)
        out.write(header(dataBytes))
        out.close()
    }

    internal fun header(data: Long): ByteArray = Wav.header(data, rate, channels)
}

object Wav {
    fun header(dataBytes: Long, rate: Int, channels: Int): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dataBytes).toInt())
        b.put("WAVE".toByteArray()); b.put("fmt ".toByteArray())
        b.putInt(16); b.putShort(1); b.putShort(channels.toShort()); b.putInt(rate)
        b.putInt(rate * channels * 2); b.putShort((channels * 2).toShort()); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataBytes.toInt())
        return b.array()
    }
}

/**
 * The STFT Spleeter uses: 4096-point frames every 1024 samples with a periodic Hann window, and
 * its inverse with the matching window (so an unchanged spectrum gives back the same sound).
 */
object SpleeterStft {
    const val N = 4096
    const val HOP = 1024
    /** Frequency bins the model looks at (up to about 11 kHz). */
    const val F = 1024
    /** Frames the model takes at once (about 12 seconds). */
    const val T = 512
    const val BINS = N / 2 + 1

    val window = FloatArray(N) { (0.5 - 0.5 * cos(2.0 * PI * it / N)).toFloat() }

    /** Sum of window² over the frames covering any sample (the same everywhere for this hop): 1.5. */
    val windowPower: Float = run {
        var s = 0.0
        for (k in 0 until N / HOP) s += window[k * HOP + 7].toDouble().let { it * it }
        s.toFloat()
    }

    /** Frames for a signal [samples] long, after Spleeter's [N] samples of silence in front. */
    fun frames(samples: Int): Int = (samples + N + HOP - 1) / HOP

    /**
     * The voice's share of each frequency (0..1) from the model's two estimates, Spleeter's
     * way: v² / (v² + m²).
     */
    fun mask(voice: Float, music: Float): Float {
        val v = voice * voice
        val m = music * music
        return (v + 1e-10f / 2) / (v + m + 1e-10f)
    }
}

/** Runs the model over a whole piece of sound. Not thread-safe. */
class VocalSeparator(model: File) : AutoCloseable {
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))
    private val t = SpleeterStft.T
    private val f = SpleeterStft.F
    private val input = ByteBuffer.allocateDirect(t * f * 2 * 4).order(ByteOrder.nativeOrder())
    private val outA = ByteBuffer.allocateDirect(t * f * 2 * 4).order(ByteOrder.nativeOrder())
    private val outB = ByteBuffer.allocateDirect(t * f * 2 * 4).order(ByteOrder.nativeOrder())
    /**
     * Which of the model's two outputs is the voice (the other is the music). The converter names
     * them after the Keras model's outputs: ":0" is the voice, ":1" the music; in the file they
     * are stored the other way round.
     */
    private val voiceOutput: Int = run {
        val names = (0 until interpreter.outputTensorCount).map { interpreter.getOutputTensor(it).name() }
        names.indexOfFirst { it.endsWith(":0") }.takeIf { it >= 0 } ?: 1
    }

    /**
     * Splits [sound] and writes the voice to [voiceFile] and the music to [musicFile] (WAV).
     * [onProgress] gets 0..1; [stop] is checked between pieces.
     */
    fun separate(sound: StereoSound, voiceFile: File, musicFile: File, onProgress: (Float) -> Unit, stop: () -> Boolean) {
        val n = SpleeterStft.N
        val hop = SpleeterStft.HOP
        val bins = SpleeterStft.BINS
        val window = SpleeterStft.window
        val norm = 1f / SpleeterStft.windowPower
        val total = sound.frames
        val frames = SpleeterStft.frames(total)
        val segments = (frames + t - 1) / t
        val fft = Fft(n)
        val re = FloatArray(n)
        val im = FloatArray(n)
        // The mix's spectrum for one piece, kept to apply the mask: [channel][frame][bin].
        val specRe = Array(2) { Array(t) { FloatArray(bins) } }
        val specIm = Array(2) { Array(t) { FloatArray(bins) } }
        // Overlap-add of the voice for the piece, plus the tail that runs into the next piece.
        val span = t * hop + n
        val acc = Array(2) { FloatArray(span) }
        val voiceOut = ShortArray(t * hop * 2)
        val musicOut = ShortArray(t * hop * 2)
        // Samples of the padded signal (n of silence first) already written out.
        var done = 0L

        fun sample(ch: Int, padded: Long): Float {
            val i = padded - n
            return if (i < 0 || i >= total) 0f else sound.samples[(2 * i + ch).toInt()] / 32768f
        }

        WavWriter(voiceFile).use { voice ->
            WavWriter(musicFile).use { music ->
                for (s in 0 until segments) {
                    if (stop()) throw kotlinx.coroutines.CancellationException("stopped")
                    input.rewind()
                    // Forward STFT of each frame; the model's input is the magnitude, frame by frame.
                    for (k in 0 until t) {
                        val frame = s.toLong() * t + k
                        for (ch in 0 until 2) {
                            if (frame >= frames) {
                                java.util.Arrays.fill(specRe[ch][k], 0f); java.util.Arrays.fill(specIm[ch][k], 0f)
                                continue
                            }
                            val start = frame * hop
                            for (i in 0 until n) {
                                re[i] = sample(ch, start + i) * window[i]
                                im[i] = 0f
                            }
                            fft.transform(re, im, inverse = false)
                            System.arraycopy(re, 0, specRe[ch][k], 0, bins)
                            System.arraycopy(im, 0, specIm[ch][k], 0, bins)
                        }
                        for (b in 0 until f) for (ch in 0 until 2) {
                            val x = specRe[ch][k][b]
                            val y = specIm[ch][k][b]
                            input.putFloat(sqrt(x * x + y * y))
                        }
                    }
                    input.rewind(); outA.rewind(); outB.rewind()
                    interpreter.runForMultipleInputsOutputs(arrayOf<Any>(input), mapOf(0 to outA, 1 to outB))
                    val voiceEst = (if (voiceOutput == 0) outA else outB).also { it.rewind() }.asFloatBuffer()
                    val musicEst = (if (voiceOutput == 0) outB else outA).also { it.rewind() }.asFloatBuffer()

                    // Mask each frame and turn it back into sound.
                    for (k in 0 until t) {
                        val frame = s.toLong() * t + k
                        if (frame >= frames) break
                        for (ch in 0 until 2) {
                            var upper = 0f
                            for (b in 0 until f) {
                                val idx = (k * f + b) * 2 + ch
                                val m = SpleeterStft.mask(voiceEst.get(idx), musicEst.get(idx))
                                re[b] = specRe[ch][k][b] * m
                                im[b] = specIm[ch][k][b] * m
                                if (b >= f - 64) upper += m
                            }
                            // Above the model's range the voice keeps its average share near the top.
                            val high = upper / 64f
                            for (b in f until bins) {
                                re[b] = specRe[ch][k][b] * high
                                im[b] = specIm[ch][k][b] * high
                            }
                            for (b in 1 until n / 2) {
                                re[n - b] = re[b]
                                im[n - b] = -im[b]
                            }
                            fft.transform(re, im, inverse = true)
                            val offset = k * hop
                            val a = acc[ch]
                            for (i in 0 until n) a[offset + i] += re[i] * window[i] * norm
                        }
                    }
                    // The first t * hop samples of this piece are finished.
                    val ready = t * hop
                    var count = 0
                    for (i in 0 until ready) {
                        val padded = done + i
                        val real = padded - n
                        if (real < 0 || real >= total) continue
                        for (ch in 0 until 2) {
                            val v = acc[ch][i]
                            val mix = sound.samples[(2 * real + ch).toInt()] / 32768f
                            voiceOut[count] = (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                            musicOut[count] = ((mix - v) * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                            count++
                        }
                    }
                    voice.write(voiceOut, count)
                    music.write(musicOut, count)
                    for (ch in 0 until 2) {
                        val a = acc[ch]
                        System.arraycopy(a, ready, a, 0, span - ready)
                        java.util.Arrays.fill(a, span - ready, span, 0f)
                    }
                    done += ready
                    onProgress((s + 1) / segments.toFloat())
                }
            }
        }
    }

    override fun close() = interpreter.close()
}
