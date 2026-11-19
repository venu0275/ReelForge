package com.reelforge.editor.engine

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.reelforge.editor.model.AudioFx
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Cleans up one channel of a spoken recording:
 * 1. cuts low rumble (fans, traffic, handling noise) below about 90 Hz;
 * 2. turns down the quiet hiss between words (a gentle noise gate that learns the noise level);
 * 3. evens out loud and soft speech (a slow leveller);
 * 4. keeps peaks from clipping.
 * Plain Kotlin, so it can be tested without a phone.
 */
class VoiceCleaner(private val sampleRate: Int) {
    private val rumble1 = Biquad.highPass(90.0, sampleRate)
    private val rumble2 = Biquad.highPass(90.0, sampleRate)

    private fun coef(ms: Double) = exp(-1.0 / (ms * sampleRate / 1000.0)).toFloat()
    // A smoothed loudness, so single noisy samples do not count as speech.
    private val envAttack = coef(10.0)
    private val envRelease = coef(150.0)
    private val gateOpen = coef(5.0)
    private val gateClose = coef(150.0)
    private val levelSpeed = coef(400.0)
    // The noise level follows quiet moments down quickly and creeps up slowly (about 6 dB in 3 s).
    private val floorFall = coef(30.0)
    private val floorRise = (1.0 + 0.7 / (3.0 * sampleRate)).toFloat()

    private var envelope = 0f
    // Starts high and drops to the real noise level at the first quiet moment, so loud background
    // noise at the start is not mistaken for speech.
    private var floor = 0.05f
    private var gate = 1f
    private var power = TARGET * TARGET
    private var level = 1f

    fun process(x: Float): Float {
        val y = rumble2.process(rumble1.process(x))
        val a = abs(y)
        envelope = if (a > envelope) envAttack * envelope + (1 - envAttack) * a
        else envRelease * envelope + (1 - envRelease) * a
        floor = if (envelope < floor) (floorFall * floor + (1 - floorFall) * envelope).coerceAtLeast(MIN_FLOOR)
        else (floor * floorRise).coerceAtMost(0.05f)

        // Fully open well above the noise, down to GATE_FLOOR at the noise level.
        val ratio = envelope / floor
        val target = when {
            ratio >= OPEN_RATIO -> 1f
            ratio <= 1.5f -> GATE_FLOOR
            else -> GATE_FLOOR + (1 - GATE_FLOOR) * (ratio - 1.5f) / (OPEN_RATIO - 1.5f)
        }
        gate = if (target > gate) gateOpen * gate + (1 - gateOpen) * target
        else gateClose * gate + (1 - gateClose) * target
        val gated = y * gate

        // Only speech moves the leveller, so pauses are not pumped up into loud hiss.
        if (ratio >= OPEN_RATIO) {
            power = levelSpeed * power + (1 - levelSpeed) * gated * gated
            val wanted = (TARGET / (sqrt(power) + 1e-4f)).coerceIn(MIN_GAIN, MAX_GAIN)
            level = levelSpeed * level + (1 - levelSpeed) * wanted
        }
        val out = gated * level
        // Soft limit: unchanged below 0.8, rounded off above it, never past 1.
        return if (abs(out) <= 0.8f) out else (0.8f + 0.2f * tanh((abs(out) - 0.8f) / 0.2f)) * (if (out < 0) -1 else 1)
    }

    companion object {
        private const val TARGET = 0.12f      // about -18 dB: comfortable speech loudness
        private const val MIN_GAIN = 0.6f
        private const val MAX_GAIN = 3.5f
        private const val GATE_FLOOR = 0.2f   // noise between words is lowered by about 14 dB
        private const val OPEN_RATIO = 3f
        private const val MIN_FLOOR = 0.0005f
    }
}

/** Cleans up a voice recording during export. Expects 16-bit PCM. */
@OptIn(UnstableApi::class)
class VoiceCleanProcessor : BaseAudioProcessor() {
    private var channels: Array<VoiceCleaner> = emptyArray()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        val count = inputAudioFormat.channelCount.coerceAtLeast(1)
        val rate = inputAudioFormat.sampleRate.coerceAtLeast(8_000)
        channels = Array(count) { VoiceCleaner(rate) }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (channels.isEmpty()) onFlush()
        val count = channels.size
        val out = replaceOutputBuffer(inputBuffer.remaining())
        while (inputBuffer.remaining() >= 2 * count) {
            for (channel in 0 until count) {
                val y = channels[channel].process(inputBuffer.short / 32768f)
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

/**
 * The sound effect and clean-up a player applies while it plays, so the preview sounds like the
 * exported video. [fx] and [clean] can be changed at any time from the main thread; the audio
 * thread picks the change up on its next buffer. Expects 16-bit PCM (the player's default).
 */
@OptIn(UnstableApi::class)
class LiveAudioFx : BaseAudioProcessor() {
    @Volatile var fx: AudioFx = AudioFx.NONE
    @Volatile var clean: Boolean = false

    private var usedFx = AudioFx.NONE
    private var usedClean = false
    private var effects: Array<FxChannel> = emptyArray()
    private var cleaners: Array<VoiceCleaner> = emptyArray()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    private fun rebuild() {
        val count = inputAudioFormat.channelCount.coerceAtLeast(1)
        val rate = inputAudioFormat.sampleRate.coerceAtLeast(8_000)
        usedFx = fx
        usedClean = clean
        effects = Array(count) { FxChannel(usedFx, rate) }
        cleaners = if (usedClean) Array(count) { VoiceCleaner(rate) } else emptyArray()
    }

    override fun onFlush() = rebuild()

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (effects.isEmpty() || fx != usedFx || clean != usedClean) {
            // A problem setting up the effect must never stop playback: play the sound as it is.
            runCatching { rebuild() }.onFailure { usedFx = AudioFx.NONE; usedClean = false }
        }
        val count = effects.size.coerceAtLeast(1)
        val out = replaceOutputBuffer(inputBuffer.remaining())
        if ((usedFx.filterless && !usedClean) || effects.isEmpty()) {
            out.put(inputBuffer)
        } else {
            while (inputBuffer.remaining() >= 2 * count) {
                for (channel in 0 until count) {
                    var y = inputBuffer.short / 32768f
                    if (usedClean) y = cleaners[channel].process(y)
                    if (!usedFx.filterless) y = effects[channel].process(y)
                    out.putShort((y * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                }
            }
            inputBuffer.position(inputBuffer.limit())
        }
        out.flip()
    }

    override fun onReset() {
        effects = emptyArray()
        cleaners = emptyArray()
    }
}

/** A player set-up whose sound goes through [fx] before it is played. */
@OptIn(UnstableApi::class)
fun renderersWithFx(context: Context, fx: AudioProcessor): RenderersFactory =
    object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink = DefaultAudioSink.Builder(context)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(fx))
            .build()
    }
