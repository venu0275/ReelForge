package com.reelforge.editor

import com.reelforge.editor.data.AppSettings
import com.reelforge.editor.data.ProjectJson
import com.reelforge.editor.engine.FxChannel
import com.reelforge.editor.model.AspectRatio
import com.reelforge.editor.model.AudioClip
import com.reelforge.editor.model.AudioFx
import com.reelforge.editor.model.ClipFraming
import com.reelforge.editor.model.CreativeOps
import com.reelforge.editor.model.Project
import com.reelforge.editor.model.pitchOfSemitones
import com.reelforge.editor.model.semitonesOfPitch
import com.reelforge.editor.ui.BugReportText
import com.reelforge.editor.ui.Toast
import com.reelforge.editor.ui.ToastKind
import com.reelforge.editor.util.BlackBars
import com.reelforge.editor.util.ContentBox
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Positive and negative cases for the newer features: odd, empty, broken or extreme input must
 * give a sensible answer and never crash.
 */
class EdgeCasesTest {

    // ---------------------------------------------------------------- ratio

    @Test fun `ratio - common phone shapes, including tall 20 by 9 screens`() {
        assertEquals(AspectRatio.PORTRAIT, AspectRatio.closest(1080f / 2400f))
        assertEquals(AspectRatio.LANDSCAPE, AspectRatio.closest(2400f / 1080f * 0.8f))
        assertEquals(AspectRatio.FOUR_FIVE, AspectRatio.closest(0.8f))
    }

    @Test fun `ratio - zero, negative, infinite and not-a-number shapes do not crash`() {
        for (bad in listOf(0f, -1f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN, 1e9f, 1e-9f)) {
            AspectRatio.closest(bad) // any answer is fine; it must not throw
        }
        assertEquals(AspectRatio.PORTRAIT, AspectRatio.closest(0.0001f))
        assertEquals(AspectRatio.CINEMA, AspectRatio.closest(1000f))
    }

    // ---------------------------------------------------------------- black bars

    @Test fun `black bars - empty, too small or mismatched frames give no answer`() {
        assertNull(BlackBars.boxOf(IntArray(0), 0, 0))
        assertNull(BlackBars.boxOf(IntArray(10), 90, 160))
        assertNull(BlackBars.boxOf(IntArray(4) { -1 }, -2, -2))
        assertNull(BlackBars.combine(emptyList()))
    }

    @Test fun `black bars - a tiny speck of light is not a picture`() {
        val w = 90
        val h = 160
        val px = IntArray(w * h) { 0xFF000000.toInt() }
        px[80 * w + 45] = -1
        assertNull(BlackBars.boxOf(px, w, h))
    }

    @Test fun `black bars - a zero-size box never gives an endless zoom`() {
        val f = BlackBars.fillFraming(ContentBox(0.5f, 0.5f, 0.5f, 0.5f), 9f / 16f, 16f / 9f, fill = false, cover = true)
        assertTrue(f.zoom.isFinite() && f.zoom <= ClipFraming.MAX_ZOOM)
        assertTrue(f.panX.isFinite() && f.panY.isFinite())
        assertTrue(f.panX in -1f..1f && f.panY in -1f..1f)
    }

    @Test fun `black bars - no bars means nothing to remove`() {
        assertTrue(!ContentBox(0f, 0f, 1f, 1f).hasBars)
        assertTrue(!ContentBox(0.02f, 0.02f, 0.98f, 0.98f).hasBars)
        assertTrue(ContentBox(0f, 0.2f, 1f, 0.8f).hasBars)
    }

    // ---------------------------------------------------------------- voice and pitch

    @Test fun `pitch - semitones round-trip and stay finite at the ends`() {
        for (st in -12..12) assertEquals(st.toFloat(), semitonesOfPitch(pitchOfSemitones(st.toFloat())), 0.001f)
        assertTrue(semitonesOfPitch(0f).isFinite())
        assertTrue(semitonesOfPitch(-5f).isFinite())
    }

    @Test fun `pitch - style and user pitch together stay in the allowed range`() {
        val high = AudioClip(uri = "u", title = "v", startMs = 0, durationMs = 1, audioFx = AudioFx.CHIPMUNK, pitch = 4f)
        val low = AudioClip(uri = "u", title = "v", startMs = 0, durationMs = 1, audioFx = AudioFx.MONSTER, pitch = 0.1f)
        assertEquals(AudioClip.MAX_PITCH, high.totalPitch, 0f)
        assertEquals(AudioClip.MIN_PITCH, low.totalPitch, 0f)
    }

    @Test fun `effects - strength is kept between 0 and 1 and odd samples do not break them`() {
        val fx = FxChannel(AudioFx.CAVE, 44_100, 5f)
        assertEquals(1f, fx.amount, 0f)
        fx.amount = -2f
        assertEquals(0f, fx.amount, 0f)
        for (e in AudioFx.entries) {
            val ch = FxChannel(e, 8_000)
            listOf(0f, 1f, -1f, 3f, -3f).forEach { ch.process(it) }
            val y = ch.process(0.5f)
            assertTrue("$e gave $y", y.isFinite())
        }
    }

    @Test fun `effects - every style has a name and pitch-only styles need no filter`() {
        AudioFx.entries.forEach { assertTrue(it.label.isNotBlank()) }
        assertTrue(AudioFx.CHIPMUNK.filterless && AudioFx.KID.filterless && AudioFx.DEEP.filterless)
        assertTrue(!AudioFx.MONSTER.filterless && AudioFx.MONSTER.adjustable)
    }

    // ---------------------------------------------------------------- saved projects

    @Test fun `saved sound settings out of range are put back in range`() {
        val sound = AudioClip(uri = "u", title = "s", startMs = 0, durationMs = 1_000)
        val json = JSONObject(ProjectJson.encode(Project(name = "p", audio = listOf(sound))))
        val a = json.getJSONArray("audio").getJSONObject(0)
        a.put("pitch", 99.0)
        a.put("fxAmount", -3.0)
        a.put("audioFx", "NOT_A_STYLE")
        val back = ProjectJson.decode(json.toString()).audio.single()
        assertEquals(AudioClip.MAX_PITCH, back.pitch, 0f)
        assertEquals(0f, back.fxAmount, 0f)
        assertEquals(AudioFx.NONE, back.audioFx)
    }

    @Test fun `projects saved before the new sound settings still open`() {
        val sound = AudioClip(uri = "u", title = "s", startMs = 0, durationMs = 1_000)
        val json = JSONObject(ProjectJson.encode(Project(name = "p", audio = listOf(sound))))
        val a = json.getJSONArray("audio").getJSONObject(0)
        a.remove("pitch")
        a.remove("fxAmount")
        a.remove("duck")
        val back = ProjectJson.decode(json.toString()).audio.single()
        assertEquals(1f, back.pitch, 0f)
        assertEquals(1f, back.fxAmount, 0f)
        assertTrue(!back.duck)
    }

    // ---------------------------------------------------------------- settings

    @Test fun `settings - new options have safe defaults and are kept in range`() {
        val d = AppSettings()
        assertTrue(!d.ratioTourSeen)
        assertEquals(AudioFx.NONE, d.recordFx)
        assertEquals(0, d.recordSemitones)
        assertEquals(12, d.copy(recordSemitones = 40).sanitized().recordSemitones)
        assertEquals(-12, d.copy(recordSemitones = -40).sanitized().recordSemitones)
    }

    // ---------------------------------------------------------------- messages

    @Test fun `toasts - empty or odd text still gets a look`() {
        assertEquals(ToastKind.INFO, Toast.kindOf(""))
        assertEquals(ToastKind.INFO, Toast.kindOf("   "))
        assertEquals(ToastKind.ERROR, Toast.kindOf("COULD NOT SAVE"))
    }

    @Test fun `bug report - a title on several lines uses only the first in the subject`() {
        val r = BugReportText("First line\nsecond line", "", "", null)
        assertEquals("CutMitra – First line", r.subject)
        assertTrue(!r.subject.contains("\n"))
        // Unusual characters are kept as typed (the email app shows them).
        assertEquals("CutMitra – Export ✗ & crash?", BugReportText("Export ✗ & crash?", "", "", null).subject)
    }

    // ---------------------------------------------------------------- beat sync

    @Test fun `beats - too few or identical beat times give no tempo`() {
        assertEquals(0, CreativeOps.bpm(emptyList()))
        assertEquals(0, CreativeOps.bpm(listOf(100L, 200L, 300L)))
        assertEquals(0, CreativeOps.bpm(listOf(500L, 500L, 500L, 500L)))
        assertEquals(120, CreativeOps.bpm(listOf(0L, 500L, 1_000L, 1_500L, 2_000L)))
    }
}

class AnimatedButtonSaveTest {
    @Test fun `an animated button layer is saved and opened again`() {
        val layer = com.reelforge.editor.model.ImageLayer(uri = "file:///s.png", button = "SUBSCRIBE")
        val back = ProjectJson.decode(ProjectJson.encode(Project(name = "p", layers = listOf(layer)))).layers.single()
        assertEquals("SUBSCRIBE", back.button)
        assertEquals(com.reelforge.editor.engine.AnimatedButton.SUBSCRIBE, com.reelforge.editor.engine.AnimatedButton.of(back.button))
    }

    @Test fun `unknown or missing button names fall back to a plain picture`() {
        val layer = com.reelforge.editor.model.ImageLayer(uri = "file:///s.png", button = "SUBSCRIBE")
        val json = JSONObject(ProjectJson.encode(Project(name = "p", layers = listOf(layer))))
        json.getJSONArray("layers").getJSONObject(0).put("button", "DANCE_PARTY")
        assertNull(ProjectJson.decode(json.toString()).layers.single().button)
        json.getJSONArray("layers").getJSONObject(0).remove("button")
        assertNull(ProjectJson.decode(json.toString()).layers.single().button)
        assertNull(com.reelforge.editor.engine.AnimatedButton.of(null))
        assertTrue(com.reelforge.editor.engine.AnimatedButton.entries.all { it.defaultMs in 2_000..6_000 })
    }
}

class TimelineLanesTest {
    private fun lanes(vararg r: Pair<Long, Long>) = com.reelforge.editor.model.ProjectOps.lanes(r.toList())

    @Test fun `two texts at the same time go on separate lines`() {
        assertEquals(listOf(0, 1), lanes(0L to 3_000L, 0L to 3_000L))
    }

    @Test fun `items one after another share a line, overlapping ones do not`() {
        assertEquals(listOf(0, 0, 0), lanes(0L to 1_000L, 1_000L to 2_000L, 2_000L to 3_000L))
        assertEquals(listOf(0, 1, 0), lanes(0L to 2_000L, 1_000L to 3_000L, 2_500L to 4_000L))
    }

    @Test fun `a free line is reused before a new one is opened`() {
        // Given out of time order, each still takes the first line where it fits.
        assertEquals(listOf(1, 0, 2, 0), lanes(500L to 1_500L, 0L to 1_000L, 600L to 900L, 1_000L to 2_000L))
    }

    @Test fun `no items, one item, and odd ranges`() {
        assertEquals(emptyList<Int>(), lanes())
        assertEquals(listOf(0), lanes(5L to 5L))
        assertEquals(listOf(0, 1), lanes(-100L to 100L, 0L to 50L))
    }
}

class ReframeTest {
    private val wide = 16f / 9f
    private val tall = 9f / 16f

    @Test fun `a person in the middle needs no shift`() {
        val (x, y) = com.reelforge.editor.model.Reframe.followPan(wide, tall, 0.5f, 0.5f)
        assertEquals(0f, x, 0.0001f)
        assertEquals(0f, y, 0.0001f)
    }

    @Test fun `a person on the left moves the picture right, never past its edge`() {
        val (x, _) = com.reelforge.editor.model.Reframe.followPan(wide, tall, 0.3f, 0.5f)
        assertTrue(x > 0f)
        // 16:9 filling 9:16 is about 3.16 frames wide, so it can move at most about 1.08 frames.
        val (far, _) = com.reelforge.editor.model.Reframe.followPan(wide, tall, 0f, 0.5f)
        val videoW = wide / tall
        assertEquals((videoW - 1f) / 2f, far, 0.001f)
        val (other, _) = com.reelforge.editor.model.Reframe.followPan(wide, tall, 1f, 0.5f)
        assertEquals(-(videoW - 1f) / 2f, other, 0.001f)
    }

    @Test fun `same shape or bad input means no shift`() {
        assertEquals(0f to 0f, com.reelforge.editor.model.Reframe.followPan(tall, tall, 0.1f, 0.9f))
        assertEquals(0f to 0f, com.reelforge.editor.model.Reframe.followPan(0f, tall, 0.1f, 0.5f))
        assertEquals(0f to 0f, com.reelforge.editor.model.Reframe.followPan(Float.NaN, tall, 0.1f, 0.5f))
    }

    @Test fun `the person's centre is found in a mask, and none when there is hardly anyone`() {
        val side = 100
        val mask = FloatArray(side * side)
        for (y in 40 until 80) for (x in 10 until 30) mask[y * side + x] = 0.9f
        val (cx, cy) = com.reelforge.editor.model.Reframe.personCentre(mask, side)!!
        assertEquals(0.2f, cx, 0.01f)
        assertEquals(0.6f, cy, 0.01f)
        assertNull(com.reelforge.editor.model.Reframe.personCentre(FloatArray(side * side), side))
        assertNull(com.reelforge.editor.model.Reframe.personCentre(FloatArray(3), side))
    }
}
