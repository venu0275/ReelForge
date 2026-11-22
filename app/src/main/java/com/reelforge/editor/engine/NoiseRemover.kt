package com.reelforge.editor.engine

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.reelforge.editor.model.NoiseRemoval
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Removes steady background noise (fans, AC, hiss, hum, traffic rumble) from one channel, the way
 * "noise reduction" in audio editors does: it splits the sound into frequencies, learns how loud
 * the noise is in each one during quiet moments, and turns each frequency down by how much of it
 * is noise. Plain Kotlin (no download, no native code), so it can be tested without a phone.
 *
 * The output is the input delayed by [latency] samples.
 */
class NoiseRemover(sampleRate: Int, level: NoiseRemoval) {
    /** Frame length: about 21 ms at 48 kHz. */
    private val n = if (sampleRate >= 32_000) 1024 else 512
    private val hop = n / 2

    /** How many samples the output lags behind the input. */
    val latency: Int = n - 1

    private val overSubtract = level.overSubtract
    private val floorGain = level.floorGain

    // sqrt-Hann windows before and after, with half-frame steps, add back up to exactly the input.
    private val window = FloatArray(n) { sqrt(0.5f - 0.5f * cos(2.0 * PI * it / n).toFloat()) }
    private val frame = FloatArray(n)
    private var filled = hop // starts with half a frame of silence in front
    private val acc = FloatArray(n)
    private val outQueue = FloatArray(n)
    private var outRead = 0
    private var outCount = 0

    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val bins = n / 2 + 1
    private val power = FloatArray(bins)
    private val noise = FloatArray(bins) { MIN_NOISE }
    /** Last frame's cleaned power per frequency, for the "decision-directed" estimate. */
    private val cleanPower = FloatArray(bins)
    private var frames = 0
    /** Frames with some sound in them (digital silence does not teach anything about the noise). */
    private var heard = 0

    // Where the sound is clearly louder than the noise, the noise estimate creeps up about 3 dB a
    // second (so a noise that grows is followed); elsewhere it follows the sound.
    private val noiseRise = 10f.pow(3f * hop / sampleRate / 10f)
    /** Bins below about 80 Hz are rumble, not speech, and are always lowered. */
    private val rumbleBins = (80f * n / sampleRate).toInt().coerceAtLeast(1)

    private val fft = Fft(n)

    private companion object {
        const val MIN_NOISE = 1e-6f
        const val LEARN_FRAMES = 12
    }

    fun process(x: Float): Float {
        frame[filled++] = x
        if (filled == n) {
            runFrame()
            System.arraycopy(frame, hop, frame, 0, n - hop)
            filled = n - hop
        }
        if (outCount == 0) return 0f
        val y = outQueue[outRead]
        outRead = (outRead + 1) % n
        outCount--
        return y
    }

    private fun runFrame() {
        for (i in 0 until n) {
            re[i] = frame[i] * window[i]
            im[i] = 0f
        }
        fft.transform(re, im, inverse = false)
        frames++
        var total = 0f
        for (b in 0 until bins) {
            power[b] = re[b] * re[b] + im[b] * im[b] + 1e-12f
            total += power[b]
        }
        val silent = total <= MIN_NOISE * bins
        if (!silent) heard++
        for (b in 0 until bins) {
            val p = power[b]
            // The first moments with sound teach the noise level (videos usually start before
            // anyone speaks); after that it follows quiet moments and creeps up where the sound
            // is clearly louder (so a noise that grows is followed).
            noise[b] = if (!silent && heard <= LEARN_FRAMES) {
                max(MIN_NOISE, if (heard == 1) p else 0.7f * noise[b] + 0.3f * p)
            } else {
                val q = max(p, MIN_NOISE)
                max(MIN_NOISE, if (q < 3f * noise[b]) 0.95f * noise[b] + 0.05f * q else noise[b] * noiseRise)
            }
            val g = if (overSubtract <= 0f) 1f else {
                // How much louder than the noise this frequency is, smoothed over time (this keeps
                // the "watery" sound of simple noise reduction away), then a Wiener-style gain.
                val nz = noise[b] * overSubtract
                val now = (p / nz - 1f).coerceAtLeast(0f)
                val snr = if (frames == 1) now else 0.98f * cleanPower[b] / nz + 0.02f * now
                if (b < rumbleBins) floorGain else (snr / (1f + snr)).coerceIn(floorGain, 1f)
            }
            cleanPower[b] = g * g * p
            re[b] *= g
            im[b] *= g
            if (b in 1 until n / 2) {
                re[n - b] = re[b]
                im[n - b] = -im[b]
            }
        }
        fft.transform(re, im, inverse = true)
        for (i in 0 until n) acc[i] += re[i] * window[i]
        // The first half is finished: both frames that cover it have been added.
        for (i in 0 until hop) {
            outQueue[(outRead + outCount) % n] = acc[i]
            outCount++
        }
        System.arraycopy(acc, hop, acc, 0, n - hop)
        java.util.Arrays.fill(acc, n - hop, n, 0f)
    }
}

/** A plain radix-2 FFT of a fixed size (a power of two). The inverse includes the 1/n. */
internal class Fft(private val n: Int) {
    private val levels = Integer.numberOfTrailingZeros(n)
    private val cosTable = FloatArray(n / 2) { cos(2.0 * PI * it / n).toFloat() }
    private val sinTable = FloatArray(n / 2) { sin(2.0 * PI * it / n).toFloat() }
    private val reversed = IntArray(n) { Integer.reverse(it) ushr (32 - levels) }

    init {
        require(n >= 2 && n and (n - 1) == 0) { "FFT size must be a power of two" }
    }

    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        for (i in 0 until n) {
            val j = reversed[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        val sign = if (inverse) 1f else -1f
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosTable[k]
                    val wi = sign * sinTable[k]
                    val l = j + half
                    val tr = re[l] * wr - im[l] * wi
                    val ti = re[l] * wi + im[l] * wr
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
        if (inverse) {
            val scale = 1f / n
            for (i in 0 until n) {
                re[i] *= scale
                im[i] *= scale
            }
        }
    }
}

/**
 * Noise remover for export. The remover's delay is taken out (its first output is dropped and
 * the end is flushed), so the cleaned sound stays exactly in time with the picture. 16-bit PCM.
 */
@OptIn(UnstableApi::class)
class NoiseRemoverProcessor(private val level: NoiseRemoval) : BaseAudioProcessor() {
    private var channels: Array<NoiseRemover> = emptyArray()
    private var toSkip = 0L     // output samples (per channel) still to drop
    private var inFrames = 0L
    private var outFrames = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return if (level == NoiseRemoval.OFF) AudioProcessor.AudioFormat.NOT_SET else inputAudioFormat
    }

    override fun onFlush() {
        val count = inputAudioFormat.channelCount.coerceAtLeast(1)
        val rate = inputAudioFormat.sampleRate.coerceAtLeast(8_000)
        channels = Array(count) { NoiseRemover(rate, level) }
        toSkip = channels[0].latency.toLong()
        inFrames = 0
        outFrames = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        if (channels.isEmpty()) onFlush()
        val count = channels.size
        val frames = inputBuffer.remaining() / (2 * count)
        val out = replaceOutputBuffer(frames * 2 * count)
        repeat(frames) {
            inFrames++
            val keep = toSkip == 0L
            for (channel in 0 until count) {
                val y = channels[channel].process(inputBuffer.short / 32768f)
                if (keep) out.putShort(toShort(y))
            }
            if (keep) outFrames++ else toSkip--
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    override fun onQueueEndOfStream() {
        if (channels.isEmpty()) return
        // Push silence through to get the last part of the sound out, then stop at its length.
        val count = channels.size
        val missing = (inFrames - outFrames).coerceAtLeast(0)
        val out = replaceOutputBuffer((missing * 2 * count).toInt())
        var pushed = 0L
        while (outFrames < inFrames && pushed < missing + channels[0].latency + 1) {
            pushed++
            val keep = toSkip == 0L
            for (channel in 0 until count) {
                val y = channels[channel].process(0f)
                if (keep) out.putShort(toShort(y))
            }
            if (keep) outFrames++ else toSkip--
        }
        out.flip()
    }

    override fun onReset() {
        channels = emptyArray()
    }

    private fun toShort(y: Float): Short = (y * 32767f).toInt().coerceIn(-32768, 32767).toShort()
}
