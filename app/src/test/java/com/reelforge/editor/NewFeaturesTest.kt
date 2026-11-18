package com.reelforge.editor

import com.reelforge.editor.data.ProjectJson
import com.reelforge.editor.engine.CaptionText
import com.reelforge.editor.engine.CutOut
import com.reelforge.editor.engine.FxChannel
import com.reelforge.editor.engine.SpokenWord
import com.reelforge.editor.engine.VoiceCleaner
import com.reelforge.editor.model.AudioClip
import com.reelforge.editor.model.AudioFx
import com.reelforge.editor.model.Project
import com.reelforge.editor.record.WavFile
import com.reelforge.editor.util.Resampler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** Clean voice: the clean-up keeps speech and lowers steady noise. */
class CleanVoiceTest {
    private val rate = 48_000

    private fun rms(samples: List<Float>) = sqrt(samples.sumOf { (it * it).toDouble() } / samples.size)

    @Test fun `silence stays silent and nothing goes past full scale`() {
        val c = VoiceCleaner(rate)
        repeat(rate) { assertEquals(0f, c.process(0f), 1e-6f) }
        val loud = VoiceCleaner(rate)
        for (n in 0 until rate) {
            val y = loud.process(sin(2 * PI * 300 * n / rate).toFloat()) // a full-scale tone
            assertFalse(y.isNaN())
            assertTrue("sample $y is past full scale", abs(y) <= 1f)
        }
    }

    @Test fun `steady low noise is turned down while speech-level sound stays`() {
        val random = java.util.Random(7)
        val c = VoiceCleaner(rate)
        val noiseOut = mutableListOf<Float>()
        // Two seconds of quiet hiss, then a second of a "voice" tone much louder than it.
        for (n in 0 until rate * 2) {
            val y = c.process((random.nextGaussian() * 0.004).toFloat())
            if (n > rate) noiseOut += y
        }
        val voiceOut = mutableListOf<Float>()
        for (n in 0 until rate) {
            val x = (0.1 * sin(2 * PI * 220 * n / rate) + random.nextGaussian() * 0.004).toFloat()
            val y = c.process(x)
            if (n > rate / 2) voiceOut += y
        }
        val noiseIn = 0.004
        assertTrue("noise should be lowered (was ${rms(noiseOut)})", rms(noiseOut) < noiseIn * 0.6)
        assertTrue("voice should stay clearly audible (was ${rms(voiceOut)})", rms(voiceOut) > 0.05)
    }

    @Test fun `low rumble is cut`() {
        val c = VoiceCleaner(rate)
        val out = mutableListOf<Float>()
        for (n in 0 until rate * 2) {
            val y = c.process((0.2 * sin(2 * PI * 30 * n / rate)).toFloat())
            if (n > rate) out += y
        }
        assertTrue("30 Hz rumble should be much quieter (was ${rms(out)})", rms(out) < 0.2 / sqrt(2.0) * 0.35)
    }

    @Test fun `pitch styles change pitch only, so the filter leaves them alone`() {
        assertTrue(AudioFx.CHIPMUNK.pitch > 1f)
        assertTrue(AudioFx.DEEP.pitch < 1f)
        assertTrue(AudioFx.CHIPMUNK.filterless && AudioFx.DEEP.filterless && AudioFx.NONE.filterless)
        assertFalse(AudioFx.ROBOT.filterless)
        for (fx in listOf(AudioFx.CHIPMUNK, AudioFx.DEEP)) {
            val ch = FxChannel(fx, rate)
            assertEquals(0.37f, ch.process(0.37f), 0f)
        }
    }

    @Test fun `the clean mark survives saving`() {
        val voice = AudioClip(uri = "v", title = "Voice 1", startMs = 0, durationMs = 1_000, voice = true, clean = true)
        val saved = ProjectJson.decode(ProjectJson.encode(Project(name = "p", audio = listOf(voice))))
        assertTrue(saved.audio.single().clean)
        val plain = ProjectJson.decode(ProjectJson.encode(Project(name = "p", audio = listOf(voice.copy(clean = false)))))
        assertFalse(plain.audio.single().clean)
    }

    @Test fun `wav header describes the recording`() {
        val h = ByteBuffer.wrap(WavFile.header(48_000, 1, 96_000)).order(ByteOrder.LITTLE_ENDIAN)
        assertArrayEquals("RIFF".toByteArray(), ByteArray(4) { h.get(it) })
        assertEquals(36 + 96_000, h.getInt(4))
        assertEquals(1, h.getShort(20).toInt())      // PCM
        assertEquals(1, h.getShort(22).toInt())      // mono
        assertEquals(48_000, h.getInt(24))
        assertEquals(96_000, h.getInt(28))           // bytes per second
        assertEquals(16, h.getShort(34).toInt())     // bits
        assertEquals(96_000, h.getInt(40))
        assertEquals(44, WavFile.header(16_000, 2, 0).size)
    }
}

/** Auto captions: sound conversion and caption lines. */
class AutoCaptionsTest {

    @Test fun `resampling keeps the length and joins blocks smoothly`() {
        val r = Resampler(48_000, 16_000)
        val out = mutableListOf<Float>()
        // One second of a slow ramp, fed in uneven blocks.
        val input = FloatArray(48_000) { it / 48_000f }
        var at = 0
        for (size in generateSequence(1_000) { if (it == 1_000) 333 else 1_000 }) {
            if (at >= input.size) break
            val n = minOf(size, input.size - at)
            r.process(input.copyOfRange(at, at + n), n, out)
            at += n
        }
        assertTrue("got ${out.size} samples", abs(out.size - 16_000) <= 2)
        // A ramp in gives a ramp out: every step is about the same and never goes backwards.
        for (i in 1 until out.size) assertTrue(out[i] >= out[i - 1] - 1e-6f)
        assertEquals(0.5f, out[8_000], 0.001f)
    }

    @Test fun `same rate passes samples through`() {
        val out = mutableListOf<Float>()
        Resampler(16_000, 16_000).process(floatArrayOf(0.1f, -0.2f, 0.3f), 3, out)
        assertEquals(listOf(0.1f, -0.2f, 0.3f), out)
    }

    @Test fun `reads words and times from a speech result`() {
        val json = """{"result":[{"conf":1.0,"end":0.9,"start":0.5,"word":"hello"},""" +
            """{"conf":0.9,"end":1.4,"start":1.0,"word":"[unk]"},{"conf":1.0,"end":1.8,"start":1.5,"word":"world"}],"text":"hello world"}"""
        val words = CaptionText.parseWords(json, offsetMs = 10_000)
        assertEquals(listOf("hello", "world"), words.map { it.text })
        assertEquals(10_500L, words[0].startMs)
        assertEquals(11_800L, words[1].endMs)
        assertTrue(CaptionText.parseWords("""{"text":""}""").isEmpty())
        assertTrue(CaptionText.parseWords("not json").isEmpty())
    }

    @Test fun `words are grouped into short lines that break at pauses`() {
        fun w(t: String, s: Long) = SpokenWord(t, s, s + 250)
        val words = listOf(
            w("one", 0), w("two", 300), w("three", 600), w("four", 900), w("five", 1_200), w("six", 1_500),
            // A long pause, then more speech.
            w("later", 4_000), w("on", 4_300),
        )
        val lines = CaptionText.group(words)
        assertEquals(listOf("one two three four five", "six", "later on"), lines.map { it.text })
        // Lines never overlap, and the line before a pause stays up a little after its last word.
        for (i in 1 until lines.size) assertTrue(lines[i - 1].endMs <= lines[i].startMs)
        assertEquals(1_750L + 400, lines[1].endMs)
        assertTrue(lines.all { it.endMs > it.startMs })
    }

    @Test fun `long words start a new line before it gets too wide`() {
        val words = listOf("internationalisation", "considerations", "everywhere").mapIndexed { i, t ->
            SpokenWord(t, i * 300L, i * 300L + 250)
        }
        assertTrue(CaptionText.group(words, maxChars = 28).all { it.text.length <= 28 })
        assertEquals("Hello there", CaptionText.tidy("hello there"))
        assertEquals("नमस्ते", CaptionText.tidy("नमस्ते"))
    }
}

/** Remove background: turning the model's scores into a clean mask. */
class RemoveBackgroundTest {

    @Test fun `person chance from probabilities and from raw scores`() {
        // Already probabilities: background 0.1, so the person is 0.9.
        assertEquals(0.9f, CutOut.personChance(floatArrayOf(0.1f, 0.2f, 0.3f, 0.2f, 0.1f, 0.1f)), 1e-4f)
        // Raw scores: a big background score means almost surely background.
        assertTrue(CutOut.personChance(floatArrayOf(9f, 0f, 0f, 0f, 0f, 0f)) < 0.01f)
        assertTrue(CutOut.personChance(floatArrayOf(-5f, 4f, 0f, 0f, 0f, 0f)) > 0.99f)
    }

    @Test fun `smooth step is firm at the ends and soft in between`() {
        assertEquals(0f, CutOut.smoothStep(0.35f, 0.65f, 0.1f), 0f)
        assertEquals(1f, CutOut.smoothStep(0.35f, 0.65f, 0.9f), 0f)
        assertEquals(0.5f, CutOut.smoothStep(0.35f, 0.65f, 0.5f), 1e-4f)
    }

    @Test fun `softening blurs a hard edge but keeps flat areas`() {
        val side = 4
        val mask = FloatArray(side * side) { if (it % side < 2) 1f else 0f } // left half is the person
        val soft = CutOut.soften(mask, side)
        assertEquals(1f, soft[0], 1e-6f)            // well inside stays 1
        assertEquals(0f, soft[side - 1], 1e-6f)     // well outside stays 0
        assertTrue(soft[1] in 0.5f..0.9f)           // the edge is softened
    }
}
