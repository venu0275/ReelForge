package com.reelforge.editor.engine

import android.content.Context
import android.net.Uri
import com.reelforge.editor.util.PcmDecoder
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Finds the beat of a song. The loudness is measured in short steps; sudden rises in loudness
 * (drum hits) are found; the most likely steady tempo between 70 and 180 beats per minute is
 * picked; and the beats are placed on that grid where the hits line up best. Plain Kotlin apart
 * from [fromFile], so it can be tested.
 */
object BeatFinder {
    /** Loudness steps per second (20 ms each). */
    const val STEPS_PER_SECOND = 50

    /** Collects loudness steps from a stream of samples. */
    class Meter(sampleRate: Int) {
        private val stepSize = (sampleRate / STEPS_PER_SECOND).coerceAtLeast(1)
        private var sum = 0.0
        private var count = 0
        val steps = ArrayList<Float>()

        fun add(samples: ShortArray, n: Int) {
            for (i in 0 until n) {
                val x = samples[i] / 32768.0
                sum += x * x
                if (++count == stepSize) {
                    steps += (sum / count).toFloat()
                    sum = 0.0
                    count = 0
                }
            }
        }
    }

    /** How strongly each step starts a new sound: the rise in (log) loudness, minus the local average. */
    fun onsets(energy: FloatArray): FloatArray {
        if (energy.size < 3) return FloatArray(energy.size)
        val logs = FloatArray(energy.size) { ln(energy[it] + 1e-7f) }
        val rise = FloatArray(energy.size) { i -> if (i == 0) 0f else max(0f, logs[i] - logs[i - 1]) }
        // Remove the slowly changing part so loud and soft sections count alike.
        val out = FloatArray(rise.size)
        val half = 8
        for (i in rise.indices) {
            var s = 0f
            var n = 0
            for (j in maxOf(0, i - half)..minOf(rise.lastIndex, i + half)) {
                s += rise[j]
                n++
            }
            out[i] = max(0f, rise[i] - s / n)
        }
        return out
    }

    /** [o] read between steps, so beat lengths need not be whole steps. */
    private fun at(o: FloatArray, x: Float): Float {
        if (x < 0 || x > o.lastIndex) return 0f
        val i = x.toInt()
        val f = x - i
        return o[i] * (1 - f) + (if (i + 1 < o.size) o[i + 1] else 0f) * f
    }

    /**
     * The beat length in steps (between 70 and 180 BPM, to a quarter step) whose pattern repeats
     * best. Most songs sit near 120 BPM, so a gentle preference there settles half/double ties.
     */
    fun beatLength(onset: FloatArray): Float {
        var best = 0f
        var bestScore = -1.0
        var lag = 60f * STEPS_PER_SECOND / 180f
        val longest = 60f * STEPS_PER_SECOND / 70f
        while (lag <= longest) {
            var score = 0.0
            for (i in kotlin.math.ceil(lag).toInt() until onset.size) score += onset[i] * at(onset, i - lag)
            score /= (onset.size - lag).coerceAtLeast(1f)
            val bpm = 60.0 * STEPS_PER_SECOND / lag
            score *= 1.0 / (1.0 + abs(ln(bpm / 120.0)) * 0.8)
            if (score > bestScore) {
                bestScore = score
                best = lag
            }
            lag += 0.25f
        }
        return best
    }

    /** Beat times in milliseconds from loudness [energy] steps. */
    fun beats(energy: FloatArray): List<Long> {
        val onset = onsets(energy)
        if (onset.size < STEPS_PER_SECOND * 4 || onset.all { it == 0f }) return emptyList()
        val rough = beatLength(onset)
        if (rough <= 0f) return emptyList()

        // Fine-tune the beat length and find where the grid lines up with the hits best.
        var lag = rough
        var phase = 0f
        var bestSum = -1f
        var l = rough - 0.3f
        while (l <= rough + 0.3f) {
            var p = 0f
            while (p < l) {
                var sum = 0f
                var k = 0
                while (p + k * l < onset.lastIndex) {
                    sum += at(onset, p + k * l)
                    k++
                }
                if (sum > bestSum) {
                    bestSum = sum
                    lag = l
                    phase = p
                }
                p += 0.5f
            }
            l += 0.05f
        }

        // Draw a straight line through the actual hits near the grid, so a small error in the
        // beat length does not add up over a long song.
        repeat(3) {
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            var k = 0
            while (phase + k * lag < onset.size) {
                val i = (phase + k * lag).roundToInt().coerceIn(0, onset.lastIndex)
                var peak = i
                for (j in maxOf(0, i - 2)..minOf(onset.lastIndex, i + 2)) if (onset[j] > onset[peak]) peak = j
                if (onset[peak] > 0f) {
                    xs += k.toFloat()
                    ys += peak.toFloat()
                }
                k++
            }
            if (xs.size >= 4) {
                val mx = xs.average().toFloat()
                val my = ys.average().toFloat()
                var sxx = 0f
                var sxy = 0f
                for (n in xs.indices) {
                    sxx += (xs[n] - mx) * (xs[n] - mx)
                    sxy += (xs[n] - mx) * (ys[n] - my)
                }
                if (sxx > 0f) {
                    val fitted = sxy / sxx
                    if (abs(fitted - lag) < 1f) {
                        lag = fitted
                        phase = my - lag * mx
                        while (phase < 0) phase += lag
                    }
                }
            }
        }

        val out = mutableListOf<Long>()
        var k = 0
        while (phase + k * lag < onset.size) {
            val i = (phase + k * lag).roundToInt().coerceIn(0, onset.lastIndex)
            var peak = i
            for (j in maxOf(0, i - 1)..minOf(onset.lastIndex, i + 1)) if (onset[j] > onset[peak]) peak = j
            out += peak * 1000L / STEPS_PER_SECOND
            k++
        }
        return out.distinct()
    }

    /** Beat times in the music file, or an empty list if none could be found. */
    fun fromFile(context: Context, uri: Uri, durationMs: Long, stop: () -> Boolean, progress: (Float) -> Unit): List<Long> {
        val meter = Meter(PcmDecoder.RATE)
        var heard = 0L
        // Up to 10 minutes is enough for any reel.
        val until = if (durationMs > 0) minOf(durationMs, 600_000) else 600_000
        val ok = PcmDecoder.decode(context, uri, 0, until, stop) { samples, n ->
            meter.add(samples, n)
            heard += n
            progress((heard * 1000f / PcmDecoder.RATE / until).coerceIn(0f, 1f))
        }
        if (!ok) return emptyList()
        return beats(meter.steps.toFloatArray())
    }
}
