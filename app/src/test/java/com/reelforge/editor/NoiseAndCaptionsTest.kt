package com.reelforge.editor

import com.reelforge.editor.data.ProjectJson
import com.reelforge.editor.engine.CaptionLanguage
import com.reelforge.editor.engine.CaptionText
import com.reelforge.editor.engine.Fft
import com.reelforge.editor.engine.Hinglish
import com.reelforge.editor.engine.NoiseRemover
import com.reelforge.editor.engine.SpokenWord
import com.reelforge.editor.model.AudioClip
import com.reelforge.editor.model.Clip
import com.reelforge.editor.model.ClipType
import com.reelforge.editor.model.NoiseRemoval
import com.reelforge.editor.model.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class NoiseRemoverTest {

    private fun rms(a: FloatArray, from: Int = 0, to: Int = a.size): Double {
        var s = 0.0
        for (i in from until to) s += a[i] * a[i]
        return sqrt(s / (to - from).coerceAtLeast(1))
    }

    private fun run(level: NoiseRemoval, input: FloatArray, rate: Int = 48_000): Pair<FloatArray, Int> {
        val r = NoiseRemover(rate, level)
        return FloatArray(input.size) { r.process(input[it]) } to r.latency
    }

    @Test fun `fft forward then back gives the same signal`() {
        val n = 256
        val fft = Fft(n)
        val re = FloatArray(n) { sin(it * 0.3f) + 0.5f * sin(it * 1.7f) }
        val im = FloatArray(n)
        val orig = re.copyOf()
        fft.transform(re, im, inverse = false)
        // A pure tone shows up in its own frequency bin.
        fft.transform(re, im, inverse = true)
        for (i in 0 until n) assertEquals(orig[i], re[i], 1e-4f)
    }

    @Test fun `switched off, it passes the sound through exactly, only delayed`() {
        val random = java.util.Random(1)
        val input = FloatArray(20_000) { random.nextFloat() * 2 - 1 }
        val (out, latency) = run(NoiseRemoval.OFF, input)
        assertEquals(1023, latency)
        for (i in latency until input.size) assertEquals("sample $i", input[i - latency], out[i], 1e-4f)
        for (i in 0 until latency) assertEquals(0f, out[i], 0f)
    }

    @Test fun `steady hiss is lowered a lot while a voice-like tone stays`() {
        val rate = 48_000
        val random = java.util.Random(7)
        val total = rate * 4
        val speechStart = rate * 2
        val noise = FloatArray(total) { (random.nextFloat() * 2 - 1) * 0.05f }
        val tone = FloatArray(total) { i -> if (i >= speechStart) (0.3 * sin(2 * PI * 300 * i / rate)).toFloat() else 0f }
        val input = FloatArray(total) { noise[it] + tone[it] }
        val most = mapOf(NoiseRemoval.LOW to 0.55, NoiseRemoval.MEDIUM to 0.4, NoiseRemoval.HIGH to 0.32)
        for ((level, limit) in most) {
            val (out, latency) = run(level, input)
            // During the first two seconds there is only noise: it should be well down (after learning).
            val noiseOut = rms(out, latency + rate / 2, speechStart)
            val noiseIn = rms(input, rate / 2, speechStart - latency)
            assertTrue("$level noise ${noiseOut / noiseIn}", noiseOut < noiseIn * limit)
            // The tone keeps most of its strength.
            val toneOut = rms(out, speechStart + latency + rate / 4, total)
            assertTrue("$level tone $toneOut", toneOut > 0.3 / sqrt(2.0) * 0.8)
        }
        // Stronger settings take out more.
        val low = rms(run(NoiseRemoval.LOW, input).first, rate, speechStart)
        val high = rms(run(NoiseRemoval.HIGH, input).first, rate, speechStart)
        assertTrue(high < low)
    }

    @Test fun `odd input never breaks it`() {
        for (rate in listOf(8_000, 16_000, 22_050, 44_100, 48_000, 96_000)) {
            val r = NoiseRemover(rate, NoiseRemoval.HIGH)
            repeat(5_000) { i ->
                val y = r.process(if (i % 2 == 0) 1f else -1f)
                assertTrue(!y.isNaN() && abs(y) < 4f)
            }
            repeat(5_000) { assertTrue(!r.process(0f).isNaN()) }
        }
    }

    @Test fun `noise level is saved with the project`() {
        val clip = Clip(uri = "content://v", type = ClipType.VIDEO, sourceDurationMs = 1_000, denoise = NoiseRemoval.HIGH)
        val voice = AudioClip(uri = "file://a", title = "Voice 1", startMs = 0, durationMs = 500, voice = true, denoise = NoiseRemoval.LOW)
        val back = ProjectJson.decode(ProjectJson.encode(Project(name = "p", clips = listOf(clip), audio = listOf(voice))))
        assertEquals(NoiseRemoval.HIGH, back.clips.single().denoise)
        assertEquals(NoiseRemoval.LOW, back.audio.single().denoise)
        // Older projects have no level: off.
        val old = ProjectJson.encode(Project(name = "p", clips = listOf(clip.copy(denoise = NoiseRemoval.OFF))))
            .replace("\"denoise\":\"OFF\"", "\"denoise\":\"LOUD\"")
        assertEquals(NoiseRemoval.OFF, ProjectJson.decode(old).clips.single().denoise)
    }
}

class CaptionLanguagesTest {

    @Test fun `hindi is written the way people type it in chats`() {
        val cases = mapOf(
            "क्या हाल है" to "kya haal hai",
            "आप कैसे हैं" to "aap kaise hain",
            "मुझे नहीं पता" to "mujhe nahin pata",
            "करना" to "karna",
            "समझना" to "samajhna",
            "ठीक है" to "theek hai",
            "बहुत अच्छा" to "bahut achchha",
            "ज़िंदगी" to "zindagi",
            "ज़िंदगी" to "zindagi",
            "फ़िल्म" to "film",
            "धन्यवाद" to "dhanyavad",
            "हाँ" to "haan",
            "मैं" to "main",
            "हिंदी" to "hindi",
            "दोस्तों" to "doston",
            "प्यार" to "pyaar",
            "अपना" to "apna",
            "जी" to "ji",
        )
        for ((hindi, roman) in cases) assertEquals(hindi, roman, Hinglish.convert(hindi))
        // English words and numbers stay as they are.
        assertEquals("ok 2 बार".let { Hinglish.convert(it) }, "ok 2 baar")
        assertEquals("", Hinglish.convert(""))
        assertEquals("Kya haal hai", CaptionLanguage.HINGLISH.shown("क्या हाल है"))
        assertEquals("क्या हाल है", CaptionLanguage.HI.shown("क्या हाल है"))
    }

    @Test fun `languages are complete and hinglish shares the hindi download`() {
        assertEquals(CaptionLanguage.HI.model, CaptionLanguage.HINGLISH.model)
        assertEquals(
            "one entry per model, apart from Hinglish",
            CaptionLanguage.entries.size - 1, CaptionLanguage.entries.map { it.model }.toSet().size,
        )
        for (l in CaptionLanguage.entries) {
            assertTrue(l.name, l.model.startsWith("vosk-model-small-") && l.sizeMb in 20..200)
            assertTrue(l.url.startsWith("https://alphacephei.com/vosk/models/") && l.url.endsWith(".zip"))
            assertTrue(l.group == "India" || l.group == "World")
        }
        assertTrue(CaptionLanguage.TE.group == "India")
    }

    @Test fun `chinese and japanese lines have no spaces`() {
        val words = listOf(SpokenWord("你好", 0, 300), SpokenWord("世界", 320, 600))
        assertEquals("你好世界", CaptionText.group(words, spaced = false).single().text)
        assertEquals("你好 世界", CaptionText.group(words).single().text)
    }
}
