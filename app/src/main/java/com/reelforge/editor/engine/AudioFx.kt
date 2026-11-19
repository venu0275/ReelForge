package com.reelforge.editor.engine

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.reelforge.editor.model.AudioFx
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

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
 * [amount] (0..1) mixes the effect with the untouched sound, so it can be made subtler; it can
 * be changed while playing. Plain Kotlin, so it can be tested without a phone.
 */
class FxChannel(private val fx: AudioFx, private val sampleRate: Int, amount: Float = 1f) {
    @Volatile var amount: Float = amount.coerceIn(0f, 1f)
        set(value) { field = value.coerceIn(0f, 1f) }

    private fun samples(ms: Double) = (ms * sampleRate / 1000).toInt()

    private val echo = Comb(samples(if (fx == AudioFx.CAVE) 190.0 else 280.0), if (fx == AudioFx.CAVE) 0.35f else 0.45f)
    private val combs = when (fx) {
        // A cave is bigger and harder than a hall: longer delays that die away more slowly.
        AudioFx.CAVE -> listOf(53.1, 61.7, 71.3, 79.9).map { Comb(samples(it), 0.86f) }
        AudioFx.REVERB, AudioFx.GHOST -> listOf(29.7, 37.1, 41.1, 43.7).map { Comb(samples(it), 0.78f) }
        else -> emptyList()
    }
    private val allPasses = listOf(5.0, 1.7).map { AllPass(samples(it), 0.7f) }
    private val filters: List<Biquad> = when (fx) {
        AudioFx.BASS -> listOf(Biquad.lowShelf(160.0, 9.0, sampleRate))
        AudioFx.TREBLE -> listOf(Biquad.highShelf(4_000.0, 9.0, sampleRate))
        AudioFx.PHONE -> listOf(
            Biquad.highPass(400.0, sampleRate), Biquad.highPass(400.0, sampleRate),
            Biquad.lowPass(3_000.0, sampleRate), Biquad.lowPass(3_000.0, sampleRate),
        )
        AudioFx.MONSTER -> listOf(Biquad.lowShelf(180.0, 8.0, sampleRate))
        AudioFx.MEGAPHONE -> listOf(
            Biquad.highPass(550.0, sampleRate), Biquad.highPass(550.0, sampleRate),
            Biquad.lowPass(3_800.0, sampleRate),
        )
        AudioFx.RADIO -> listOf(
            Biquad.highPass(300.0, sampleRate),
            Biquad.lowPass(2_600.0, sampleRate), Biquad.lowPass(2_600.0, sampleRate),
        )
        AudioFx.UNDERWATER -> listOf(Biquad.lowPass(480.0, sampleRate), Biquad.lowPass(480.0, sampleRate))
        else -> emptyList()
    }
    private var phase = 0.0
    private var noise = 22_222
    private var level = 0f

    private fun turn(hz: Double): Float {
        phase += 2 * PI * hz / sampleRate
        if (phase > 2 * PI) phase -= 2 * PI
        return sin(phase).toFloat()
    }

    private fun filtered(x: Float): Float {
        var y = x
        for (filter in filters) y = filter.process(y)
        return y
    }

    private fun hall(x: Float): Float {
        var wet = 0f
        for (comb in combs) wet += comb.process(x)
        wet /= combs.size
        for (allPass in allPasses) wet = allPass.process(wet)
        return wet
    }

    /** Gentle overdrive: loud parts are rounded off instead of clipped. */
    private fun drive(x: Float, gain: Float) = tanh(x * gain)

    /** Faint steady hiss, the same every time (no randomness, so exports are repeatable). */
    private fun hiss(): Float {
        noise = noise * 1_103_515_245 + 12_345
        return ((noise ushr 16) and 0x7fff) / 16_384f - 1f
    }

    fun process(x: Float): Float {
        val wet = effect(x)
        val mix = amount
        return if (mix >= 1f) wet else x + (wet - x) * mix
    }

    private fun effect(x: Float): Float = when (fx) {
        // Pitch-only effects are done by the player / exporter, not here.
        AudioFx.NONE, AudioFx.CHIPMUNK, AudioFx.KID, AudioFx.DEEP -> x
        AudioFx.ECHO -> x * 0.8f + echo.process(x) * 0.5f
        AudioFx.REVERB -> x * 0.75f + hall(x) * 0.45f
        AudioFx.CAVE -> x * 0.75f + hall(x) * 0.55f + echo.process(x) * 0.3f
        // Boosts are turned down a little overall so loud passages do not distort.
        AudioFx.BASS, AudioFx.TREBLE -> filters[0].process(x) * 0.6f
        AudioFx.PHONE -> filtered(x) * 1.6f
        AudioFx.ROBOT -> {
            // Multiplying by a steady low tone gives the metallic "robot" sound.
            x * (0.25f + 0.75f * turn(60.0))
        }
        // Lowered by the pitch; a bass lift and growl make it rough.
        AudioFx.MONSTER -> drive(filters[0].process(x) * 0.6f, 2.2f) * 0.7f
        // A fast ring tone mixed with the voice gives a buzzing, other-worldly sound.
        AudioFx.ALIEN -> x * 0.5f + x * turn(110.0) * 0.7f
        // A hall that slowly swells and fades.
        AudioFx.GHOST -> (x * 0.45f + hall(x) * 0.7f) * (0.75f + 0.25f * turn(4.5))
        AudioFx.MEGAPHONE -> drive(filtered(x) * 1.6f, 3.5f) * 0.55f
        AudioFx.RADIO -> {
            // The hiss follows the voice, so silence stays silent.
            level = maxOf(abs(x), level * 0.9995f)
            drive(filtered(x) * 1.4f, 2f) * 0.75f + hiss() * 0.006f * minOf(1f, level * 10f)
        }
        AudioFx.UNDERWATER -> filtered(x) * (1.3f + 0.25f * turn(2.5))
    }
}

/** Applies an [AudioFx] to 16-bit PCM audio during export. */
@OptIn(UnstableApi::class)
class AudioFxProcessor(private val fx: AudioFx, private val amount: Float = 1f) : BaseAudioProcessor() {
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
        channels = Array(count) { FxChannel(fx, rate, amount) }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
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
