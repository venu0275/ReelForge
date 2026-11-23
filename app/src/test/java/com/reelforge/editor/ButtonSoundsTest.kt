package com.reelforge.editor

import com.reelforge.editor.engine.AnimatedButton
import com.reelforge.editor.engine.ButtonSounds
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ButtonSoundsTest {
    private fun loudness(s: ShortArray, fromMs: Long, toMs: Long): Double {
        val a = (fromMs * ButtonSounds.SAMPLE_RATE / 1000).toInt().coerceIn(0, s.size)
        val b = (toMs * ButtonSounds.SAMPLE_RATE / 1000).toInt().coerceIn(a, s.size)
        if (b <= a) return 0.0
        return (a until b).sumOf { abs(s[it].toDouble()) } / (b - a)
    }

    @Test fun `every button has sounds that fit and never clip`() {
        for (b in AnimatedButton.entries) {
            val cues = ButtonSounds.cues(b, b.defaultMs)
            assertTrue("$b has no sounds", cues.isNotEmpty())
            assertTrue(cues.all { it.atMs in 0 until b.defaultMs && it.gain in 0.0..1.0 })
            val track = ButtonSounds.track(b, b.defaultMs)
            assertEquals((b.defaultMs * ButtonSounds.SAMPLE_RATE / 1000).toInt(), track.size)
            val peak = track.maxOf { abs(it.toInt()) }
            assertTrue("$b peak $peak", peak in 20_000..32_767)
            // Same sound every time (the file is cached by name).
            assertArrayEquals(track, ButtonSounds.track(b, b.defaultMs))
        }
    }

    @Test fun `subscribe clicks and rings when the hand taps`() {
        val s = ButtonSounds.track(AnimatedButton.SUBSCRIBE, 4_000)
        val quiet = loudness(s, 600, 1_000)
        assertTrue(loudness(s, 1_100, 1_140) > quiet * 3)  // the click on Subscribe
        assertTrue(loudness(s, 1_860, 2_200) > quiet * 3)  // the bell
        assertTrue(loudness(s, 1_900, 2_300) > loudness(s, 3_600, 4_000)) // and it fades
    }

    @Test fun `sliding buttons whoosh out at their own end, even when made shorter`() {
        val cues = ButtonSounds.cues(AnimatedButton.FOLLOW, 2_000)
        assertEquals(1_500L, cues.last().atMs)
        assertTrue(ButtonSounds.cues(AnimatedButton.SUBSCRIBE, 1_000).all { it.atMs < 1_000 })
        assertTrue(ButtonSounds.Sound.entries.all { k -> ButtonSounds.sound(k).let { it.isNotEmpty() && it.all { v -> v.isFinite() } } })
    }
}
