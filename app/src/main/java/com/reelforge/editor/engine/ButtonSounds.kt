package com.reelforge.editor.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Sounds for the animated buttons, made by the app itself (no download, no licence): a pop when
 * a button appears, a mouse click when the hand taps, a ringing bell, a sparkle on Like, and
 * whooshes for the sliding buttons. Each sound is placed on the moment it matches in the
 * animation, so "with sound" buttons click and ring exactly when they are seen to.
 */
object ButtonSounds {
    const val SAMPLE_RATE = 44_100

    enum class Sound { POP, CLICK, BELL, SPARKLE, WHOOSH, BLIP, CHIME }

    /** One sound, [atMs] after the button appears, at [gain] (0..1). */
    data class Cue(val atMs: Long, val sound: Sound, val gain: Double = 1.0)

    /** When each sound plays for [b] shown for [durationMs] (times match [AnimatedButtons.draw]). */
    fun cues(b: AnimatedButton, durationMs: Long): List<Cue> = (when (b) {
        AnimatedButton.SUBSCRIBE -> listOf(
            Cue(0, Sound.POP),
            Cue(1_100, Sound.CLICK),          // the hand taps Subscribe
            Cue(1_250, Sound.POP, 0.45),      // the bell pops in
            Cue(1_800, Sound.CLICK),          // the hand taps the bell
            Cue(1_850, Sound.BELL, 0.9),      // and it rings
        )
        AnimatedButton.LIKE -> listOf(
            Cue(0, Sound.POP),
            Cue(450, Sound.BLIP, 0.8),        // the thumb jumps up
            Cue(850, Sound.SPARKLE, 0.7),     // sparks fly
        )
        AnimatedButton.BELL -> listOf(
            Cue(0, Sound.POP),
            Cue(500, Sound.BELL, 0.9),
            Cue(560, Sound.CHIME, 0.5),       // the red badge
            Cue(900, Sound.WHOOSH, 0.35),     // the label slides out
        )
        else -> listOf(
            Cue(0, Sound.WHOOSH, 0.6),
            Cue(380, Sound.POP, 0.7),
            Cue((durationMs - 500).coerceAtLeast(500), Sound.WHOOSH, 0.5),
        )
    }).filter { it.atMs < durationMs }

    /** Mono 16-bit samples for [b] shown for [durationMs]: every cue mixed in at its time. */
    fun track(b: AnimatedButton, durationMs: Long): ShortArray {
        val n = (durationMs.coerceAtLeast(1) * SAMPLE_RATE / 1000).toInt()
        val mix = DoubleArray(n)
        for (cue in cues(b, durationMs)) {
            val s = sound(cue.sound)
            val at = (cue.atMs * SAMPLE_RATE / 1000).toInt()
            for (i in s.indices) {
                val j = at + i
                if (j >= n) break
                mix[j] += s[i] * cue.gain
            }
        }
        // Even loudness, never clipping.
        val peak = mix.maxOfOrNull { abs(it) } ?: 0.0
        val scale = if (peak > 0) 0.85 / peak else 0.0
        return ShortArray(n) { (mix[it] * scale * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort() }
    }

    /** One sound, -1..1, always the same for the same [kind]. */
    fun sound(kind: Sound): DoubleArray {
        val rate = SAMPLE_RATE.toDouble()
        val random = java.util.Random(11L + kind.ordinal)
        fun make(ms: Int, f: (t: Double, u: Double) -> Double): DoubleArray {
            val n = (rate * ms / 1000).toInt()
            return DoubleArray(n) { i ->
                val u = i.toDouble() / n
                // Short ramps at both ends so nothing clicks by accident.
                val edge = minOf(1.0, i / (rate * 0.002), (n - i) / (rate * 0.01)).coerceAtLeast(0.0)
                f(i / rate, u) * edge
            }
        }
        var phase = 0.0
        fun sweep(from: Double, to: Double, u: Double): Double {
            phase += 2 * PI * (from + (to - from) * u) / rate
            return sin(phase)
        }
        fun noise() = random.nextDouble() * 2 - 1
        return when (kind) {
            // A soft bubble pop: a quick falling tone with a tiny tick at the front.
            Sound.POP -> make(140) { t, u -> sweep(900.0, 180.0, u) * exp(-t * 30) + noise() * exp(-t * 900) * 0.3 }
            // A mouse click: a press and a quieter release.
            Sound.CLICK -> make(110) { t, _ ->
                fun tick(at: Double, level: Double): Double {
                    val d = t - at
                    return if (d < 0) 0.0 else (noise() * exp(-d * 1_100) + sin(2 * PI * 3_200 * d) * exp(-d * 450)) * level
                }
                tick(0.0, 1.0) + tick(0.065, 0.55)
            }
            // A small hand bell ringing a few times as it swings (matches the drawn swing).
            Sound.BELL -> {
                val ratios = doubleArrayOf(1.0, 2.0, 2.76, 5.40, 8.93)
                val levels = doubleArrayOf(1.0, 0.55, 0.4, 0.22, 0.1)
                val decays = doubleArrayOf(2.4, 3.2, 4.5, 7.0, 10.0)
                val strikes = doubleArrayOf(0.0, 0.111, 0.222, 0.333, 0.444)
                val strength = doubleArrayOf(1.0, 0.75, 0.55, 0.4, 0.28)
                val f = 1_175.0 // D6
                make(1_900) { t, _ ->
                    var v = 0.0
                    for (s in strikes.indices) {
                        val d = t - strikes[s]
                        if (d < 0) continue
                        var ring = 0.0
                        for (p in ratios.indices) ring += sin(2 * PI * f * ratios[p] * d) * levels[p] * exp(-d * decays[p])
                        v += (ring + noise() * exp(-d * 2_000) * 0.4) * strength[s]
                    }
                    v * 0.4
                }
            }
            // Rising glassy notes: C6 E6 G6 C7.
            Sound.SPARKLE -> {
                val notes = doubleArrayOf(1_046.5, 1_318.5, 1_568.0, 2_093.0)
                make(800) { t, _ ->
                    var v = 0.0
                    for (k in notes.indices) {
                        val d = t - k * 0.07
                        if (d < 0) continue
                        v += (sin(2 * PI * notes[k] * d) + 0.3 * sin(2 * PI * notes[k] * 3 * d)) * exp(-d * 7)
                    }
                    v * 0.35
                }
            }
            // Air rushing past: noise through a filter that opens and closes.
            Sound.WHOOSH -> {
                var low = 0.0
                make(450) { _, u ->
                    val k = 0.03 + 0.45 * sin(PI * u)
                    low += k * (noise() - low)
                    low * sin(PI * u) * 1.8
                }
            }
            // A cheerful upward blip.
            Sound.BLIP -> make(160) { t, u -> sweep(420.0, 1_250.0, u) * exp(-t * 14) }
            // A two-note notification chime.
            Sound.CHIME -> make(700) { t, _ ->
                fun note(at: Double, f: Double): Double {
                    val d = t - at
                    return if (d < 0) 0.0 else (sin(2 * PI * f * d) + 0.25 * sin(2 * PI * f * 2 * d)) * exp(-d * 6)
                }
                (note(0.0, 1_568.0) + note(0.09, 2_093.0)) * 0.5
            }
        }
    }
}
