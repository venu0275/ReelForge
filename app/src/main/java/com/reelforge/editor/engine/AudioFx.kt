package com.reelforge.editor.engine

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.reelforge.editor.model.AudioFx
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A standard two-pole filter (RBJ "audio EQ cookbook" forms). */
internal class Biquad(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
    private val c0 = (b0 / a0).toFloat()
    private val c1 = (b1 / a0).toFloat()
    private val c2 = (b2 / a0).toFloat()
    private val d1 = (a1 / a0).toFloat()
    private val d2 = (a2 / a0).toFloat()
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    fun process(x: Float): Float {
        val y = c0 * x + c1 * x1 + c2 * x2 - d1 * y1 - d2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }

    companion object {
        private const val Q = 0.7071

        fun lowPass(freq: Double, rate: Int): Biquad {
            val w = 2 * PI * freq / rate
            val alpha = sin(w) / (2 * Q)
            val k = cos(w)
            return Biquad((1 - k) / 2, 1 - k, (1 - k) / 2, 1 + alpha, -2 * k, 1 - alpha)
        }

        fun highPass(freq: Double, rate: Int): Biquad {
            val w = 2 * PI * freq / rate
            val alpha = sin(w) / (2 * Q)
            val k = cos(w)
            return Biquad((1 + k) / 2, -(1 + k), (1 + k) / 2, 1 + alpha, -2 * k, 1 - alpha)
        }

        fun lowShelf(freq: Double, gainDb: Double, rate: Int): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * freq / rate
            val k = cos(w)
            val beta = 2 * sqrt(a) * (sin(w) / 2 * sqrt(2.0))
            return Biquad(
                a * ((a + 1) - (a - 1) * k + beta),
                2 * a * ((a - 1) - (a + 1) * k),
                a * ((a + 1) - (a - 1) * k - beta),
                (a + 1) + (a - 1) * k + beta,
                -2 * ((a - 1) + (a + 1) * k),
                (a + 1) + (a - 1) * k - beta,
            )
        }

        fun highShelf(freq: Double, gainDb: Double, rate: Int): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * freq / rate
            val k = cos(w)
            val beta = 2 * sqrt(a) * (sin(w) / 2 * sqrt(2.0))
            return Biquad(
                a * ((a + 1) + (a - 1) * k + beta),
                -2 * a * ((a - 1) + (a + 1) * k),
                a * ((a + 1) + (a - 1) * k - beta),
                (a + 1) - (a - 1) * k + beta,
                2 * ((a - 1) - (a + 1) * k),
                (a + 1) - (a - 1) * k - beta,
            )
        }
    }
}

/** A delay line that feeds part of its output back in. */
internal class Comb(delaySamples: Int, private val feedback: Float) {
    private val buffer = FloatArray(delaySamples.coerceAtLeast(1))
    private var index = 0

    /** Returns the delayed sound and stores the new input plus feedback. */
    fun process(x: Float): Float {
        val delayed = buffer[index]
        buffer[index] = x + delayed * feedback
        index = (index + 1) % buffer.size
        return delayed
    }
}

/** A delay that smears sound in time without colouring it (used to thicken reverb). */
internal class AllPass(delaySamples: Int, private val gain: Float) {
    private val buffer = FloatArray(delaySamples.coerceAtLeast(1))
    private var index = 0

    fun process(x: Float): Float {
        val delayed = buffer[index]
        val y = -gain * x + delayed
        buffer[index] = x + gain * y
        index = (index + 1) % buffer.size
        return y
    }
}

/**
 * One channel of a sound effect. Samples go in and come out as numbers around -1..1.
 * Plain Kotlin, so it can be tested without a phone.
 */
class FxChannel(private val fx: AudioFx, private val sampleRate: Int) {
    private fun samples(ms: Double) = (ms * sampleRate / 1000).toInt()

    private val echo = Comb(samples(280.0), 0.45f)
    private val combs = listOf(29.7, 37.1, 41.1, 43.7).map { Comb(samples(it), 0.78f) }
    private val allPasses = listOf(5.0, 1.7).map { AllPass(samples(it), 0.7f) }
    private val filters: List<Biquad> = when (fx) {
        AudioFx.BASS -> listOf(Biquad.lowShelf(160.0, 9.0, sampleRate))
        AudioFx.TREBLE -> listOf(Biquad.highShelf(4_000.0, 9.0, sampleRate))
        AudioFx.PHONE -> listOf(
            Biquad.highPass(400.0, sampleRate), Biquad.highPass(400.0, sampleRate),
            Biquad.lowPass(3_000.0, sampleRate), Biquad.lowPass(3_000.0, sampleRate),
        )
        else -> emptyList()
    }
    private var phase = 0.0

    fun process(x: Float): Float = when (fx) {
        // Pitch-only effects are done by the player / exporter, not here.
        AudioFx.NONE, AudioFx.CHIPMUNK, AudioFx.DEEP -> x
        AudioFx.ECHO -> x * 0.8f + echo.process(x) * 0.5f
        AudioFx.REVERB -> {
            var wet = 0f
            for (comb in combs) wet += comb.process(x)
            wet /= combs.size
            for (allPass in allPasses) wet = allPass.process(wet)
            x * 0.75f + wet * 0.45f
        }
        // Boosts are turned down a little overall so loud passages do not distort.
        AudioFx.BASS, AudioFx.TREBLE -> filters[0].process(x) * 0.6f
        AudioFx.PHONE -> {
            var y = x
            for (filter in filters) y = filter.process(y)
            y * 1.6f
        }
        AudioFx.ROBOT -> {
            // Multiplying by a steady low tone gives the metallic "robot" sound.
            phase += 2 * PI * 60.0 / sampleRate
            if (phase > 2 * PI) phase -= 2 * PI
            x * (0.25f + 0.75f * sin(phase).toFloat())
        }
    }
}

/** Applies an [AudioFx] to 16-bit PCM audio during export. */
@OptIn(UnstableApi::class)
class AudioFxProcessor(private val fx: AudioFx) : BaseAudioProcessor() {
    private var channels: Array<FxChannel> = emptyArray()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        val count = inputAudioFormat.channelCount.coerceAtLeast(1)
        val rate = inputAudioFormat.sampleRate.coerceAtLeast(8_000)
        channels = Array(count) { FxChannel(fx, rate) }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (channels.isEmpty()) onFlush()
        val count = channels.size
        val out = replaceOutputBuffer(inputBuffer.remaining())
        while (inputBuffer.remaining() >= 2 * count) {
            for (channel in 0 until count) {
                val x = inputBuffer.short / 32768f
                val y = channels[channel].process(x)
                out.putShort((y * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    override fun onReset() {
        channels = emptyArray()
    }
}
