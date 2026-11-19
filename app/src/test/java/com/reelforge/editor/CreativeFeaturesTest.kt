package com.reelforge.editor

import com.reelforge.editor.data.ProjectJson
import com.reelforge.editor.engine.BeatFinder
import com.reelforge.editor.engine.Reverser
import com.reelforge.editor.model.Clip
import com.reelforge.editor.model.ClipType
import com.reelforge.editor.model.CreativeOps
import com.reelforge.editor.model.MusicTrack
import com.reelforge.editor.model.PhotoMotion
import com.reelforge.editor.model.Project
import com.reelforge.editor.model.SpeedRamp
import com.reelforge.editor.model.TextItem
import com.reelforge.editor.model.Templates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

class PhotoZoomTest {
    @Test fun `zooms run from start to end and pans never show an edge`() {
        assertEquals(1f, PhotoMotion.ZOOM_IN.pose(0f).scale, 1e-4f)
        assertEquals(1f + PhotoMotion.ZOOM, PhotoMotion.ZOOM_IN.pose(1f).scale, 1e-4f)
        assertEquals(1f, PhotoMotion.ZOOM_OUT.pose(1f).scale, 1e-4f)
        for (m in PhotoMotion.entries) for (i in 0..20) {
            val pose = m.pose(i / 20f)
            assertTrue("$m shows an edge at ${i / 20f}", abs(pose.dx) <= pose.scale - 1f + 1e-4f)
            assertTrue("$m shows an edge at ${i / 20f}", abs(pose.dy) <= pose.scale - 1f + 1e-4f)
        }
        // Pans go the named way.
        assertTrue(PhotoMotion.PAN_LEFT.pose(1f).dx < PhotoMotion.PAN_LEFT.pose(0f).dx)
        assertTrue(PhotoMotion.PAN_UP.pose(1f).dy > PhotoMotion.PAN_UP.pose(0f).dy)
    }

    @Test fun `photo motion and beats survive saving`() {
        val photo = Clip(uri = "p", type = ClipType.IMAGE, sourceDurationMs = 3_000, motion = PhotoMotion.PAN_RIGHT)
        val music = MusicTrack(uri = "m", title = "Song", durationMs = 60_000, beats = listOf(0, 500, 1_000))
        val saved = ProjectJson.decode(ProjectJson.encode(Project(name = "p", clips = listOf(photo), music = music)))
        assertEquals(PhotoMotion.PAN_RIGHT, saved.clips.single().motion)
        assertEquals(listOf(0L, 500L, 1_000L), saved.music!!.beats)
    }
}

class SpeedRampTest {
    private val clip = Clip(
        id = "c", uri = "v", type = ClipType.VIDEO, sourceDurationMs = 10_000,
        trimStartMs = 1_000, trimEndMs = 9_000, fadeInMs = 500, fadeOutMs = 500,
    )

    @Test fun `ramp curves start and end on their points`() {
        assertEquals(0.5f, SpeedRamp.FLASH_IN.curve(0f), 1e-4f)
        assertEquals(3f, SpeedRamp.FLASH_IN.curve(1f), 1e-4f)
        for (r in SpeedRamp.entries) for (i in 0..10) assertTrue(r.curve(i / 10f) > 0f)
    }

    @Test fun `a ramp splits the clip into back-to-back pieces`() {
        val p = Project(name = "p", clips = listOf(clip))
        val ramped = CreativeOps.speedRamp(p, "c", SpeedRamp.FLASH_IN)
        val pieces = ramped.clips
        assertEquals(CreativeOps.RAMP_STEPS, pieces.size)
        assertEquals("c", pieces.first().id)
        assertEquals(1_000L, pieces.first().trimStartMs)
        assertEquals(9_000L, pieces.last().trimEndMs)
        for (i in 1 until pieces.size) assertEquals(pieces[i - 1].trimEndMs, pieces[i].trimStartMs)
        // Flash in speeds up all the way.
        for (i in 1 until pieces.size) assertTrue(pieces[i].speed >= pieces[i - 1].speed)
        // Fades stay only at the outer ends.
        assertTrue(pieces.first().fadeInMs > 0 && pieces.last().fadeOutMs > 0)
        assertTrue(pieces.drop(1).all { it.fadeInMs == 0L } && pieces.dropLast(1).all { it.fadeOutMs == 0L })
        assertEquals(pieces.map { it.id }.distinct().size, pieces.size)
    }

    @Test fun `photos and very short clips are not ramped`() {
        val photo = Clip(id = "p", uri = "i", type = ClipType.IMAGE, sourceDurationMs = 3_000)
        val short = clip.copy(id = "s", trimStartMs = 0, trimEndMs = 500)
        val p = Project(name = "p", clips = listOf(photo, short))
        assertEquals(p, CreativeOps.speedRamp(p, "p", SpeedRamp.HERO))
        assertEquals(p, CreativeOps.speedRamp(p, "s", SpeedRamp.HERO))
    }
}

class BeatSyncTest {
    /** Loudness steps of a made-up song: a kick every beat, a hi-hat between, some noise. */
    private fun song(bpm: Double, seconds: Int = 30): FloatArray {
        val random = java.util.Random(bpm.toLong())
        val period = 60.0 / bpm
        val steps = seconds * BeatFinder.STEPS_PER_SECOND
        return FloatArray(steps) { s ->
            val t = s.toDouble() / BeatFinder.STEPS_PER_SECOND
            val kick = exp(-((t + 0.13) % period) / 0.05) * 0.5
            val hat = 0.1 * exp(-((t + 0.13 + period / 2) % period) / 0.02)
            val noise = 0.05 * random.nextDouble()
            ((kick + hat) * (kick + hat) + noise * noise * 0.3).toFloat()
        }
    }

    private fun check(bpm: Double) {
        val beats = BeatFinder.beats(song(bpm))
        val found = CreativeOps.bpm(beats)
        assertTrue("$bpm BPM song read as $found", abs(found - bpm) <= 3)
        val period = 60_000.0 / bpm
        val errors = beats.map { b -> ((b + 130) % period).let { minOf(it, period - it) } }
        assertTrue("beats off by ${errors.average()} ms on average", errors.average() < 40)
    }

    @Test fun `finds the beat of steady songs`() {
        check(120.0)
        check(95.0)
        check(128.0)
        check(100.0)
    }

    @Test fun `silence has no beat`() {
        assertTrue(BeatFinder.beats(FloatArray(1_000)).isEmpty())
        assertTrue(BeatFinder.beats(FloatArray(10) { 0.1f }).isEmpty())
    }

    @Test fun `beats are placed where the music plays`() {
        val clips = listOf(Clip(uri = "p", type = ClipType.IMAGE, sourceDurationMs = 10_000))
        val once = MusicTrack(uri = "m", title = "s", durationMs = 3_000, loop = false, startMs = 1_000,
            beats = (0..3_000L step 500).toList())
        assertEquals((1_000..4_000L step 500).toList(), CreativeOps.beatTimes(Project(name = "p", clips = clips, music = once)))

        val looped = MusicTrack(uri = "m", title = "s", durationMs = 2_000, loop = true, offsetMs = 500,
            beats = listOf(0, 1_000))
        val short = listOf(Clip(uri = "p", type = ClipType.IMAGE, sourceDurationMs = 5_000))
        assertEquals(listOf(500L, 1_500L, 2_500L, 3_500L, 4_500L),
            CreativeOps.beatTimes(Project(name = "p", clips = short, music = looped)))
    }

    @Test fun `clips are cut so every change lands on a beat`() {
        val clips = listOf(
            Clip(uri = "a", type = ClipType.IMAGE, sourceDurationMs = 3_000),
            Clip(uri = "b", type = ClipType.VIDEO, sourceDurationMs = 10_000, trimEndMs = 5_000),
            Clip(uri = "c", type = ClipType.VIDEO, sourceDurationMs = 4_000),
        )
        val beats = (0..20_000L step 700).toList()
        val cut = CreativeOps.cutToBeats(Project(name = "p", clips = clips), beats)
        assertEquals(listOf(2_800L, 4_900L, 3_500L), cut.clips.map { it.durationMs })
        // Every cut is on a beat.
        var edge = 0L
        cut.clips.forEach { edge += it.durationMs; assertTrue(edge in beats) }
        // A video is never asked for more than its file has.
        assertTrue(cut.clips.all { it.trimEndMs <= it.sourceDurationMs })
        // Every 2nd beat gives longer steps.
        val two = CreativeOps.cutToBeats(Project(name = "p", clips = clips), beats, every = 2)
        var e2 = 0L
        two.clips.forEach { e2 += it.durationMs; assertEquals(0L, e2 % 1_400) }
    }
}

class TemplateTest {
    @Test fun `a template shapes the clips and adds its titles once`() {
        val clips = listOf(
            Clip(uri = "a", type = ClipType.IMAGE, sourceDurationMs = 5_000),
            Clip(uri = "b", type = ClipType.IMAGE, sourceDurationMs = 5_000),
            Clip(uri = "c", type = ClipType.VIDEO, sourceDurationMs = 20_000),
        )
        val mine = TextItem(id = "mine", text = "Hello", startMs = 0, endMs = 1_000)
        val travel = Templates.byId("travel")!!
        val p = Templates.apply(Project(name = "p", clips = clips, texts = listOf(mine)), travel, "Goa", "Bye")
        assertEquals(listOf(2_000L, 2_000L, 4_000L), p.clips.map { it.durationMs })
        assertTrue(p.clips.all { it.filter == travel.filter && it.transition == travel.transition })
        assertEquals(PhotoMotion.PAN_LEFT, p.clips[0].motion)
        assertEquals(PhotoMotion.PAN_RIGHT, p.clips[1].motion)
        assertEquals(400L, p.clips[0].fadeOutMs)
        assertEquals(400L, p.clips[1].fadeInMs)
        assertEquals(listOf("Hello", "Goa", "Bye"), p.texts.map { it.text })
        // Applying again replaces the template's own texts and keeps the user's.
        val again = Templates.apply(p, travel, "Goa 2", "")
        assertEquals(listOf("Hello", "Goa 2"), again.texts.map { it.text })
    }

    @Test fun `every template is complete`() {
        assertTrue(Templates.all.size >= 5)
        assertEquals(Templates.all.size, Templates.all.map { it.id }.distinct().size)
        assertTrue(Templates.all.all { it.photoMs >= 500 && it.motions.isNotEmpty() && it.title.isNotBlank() })
        // Nothing happens without clips.
        val empty = Project(name = "p")
        assertEquals(empty, Templates.apply(empty, Templates.all.first(), "x", "y"))
    }
}

class ReverseTest {
    @Test fun `output sizes suit every encoder`() {
        assertEquals(1280 to 720, Reverser.outputSize(1920, 1080))
        assertEquals(720 to 1280, Reverser.outputSize(1080, 1920))
        assertEquals(640 to 480, Reverser.outputSize(640, 480))
        val (w, h) = Reverser.outputSize(1000, 750)
        assertEquals(0, w % 16)
        assertEquals(0, h % 16)
    }

    @Test fun `frames are read in small groups, last first`() {
        assertEquals(5, Reverser.batchSize(1920, 1080))
        assertEquals(15, Reverser.batchSize(320, 240))
        assertEquals(3, Reverser.batchSize(3840, 2160))
        assertEquals(listOf(6, 5, 4, 3), Reverser.reversedFrames(3, 6).toList())
    }
}

class BugReportTest {
    @Test fun `report text has the problem, the steps and the phone details`() {
        val r = com.reelforge.editor.ui.BugReportText(
            problem = "App closed on Export\nwhen the video was long",
            steps = "1. Add a video\n2. Export",
            device = "App: CutMitra 1.1.0",
        )
        assertEquals("CutMitra bug: App closed on Export", r.subject)
        assertTrue(r.body.contains("What went wrong:\nApp closed on Export"))
        assertTrue(r.body.contains("How to make it happen:\n1. Add a video"))
        assertTrue(r.body.contains("Phone and app:\nApp: CutMitra 1.1.0"))
        val bare = com.reelforge.editor.ui.BugReportText("Crash", "", null)
        assertTrue(!bare.body.contains("How to make it happen") && !bare.body.contains("Phone and app"))
        // Long first lines are cut in the subject.
        assertEquals("CutMitra bug: ".length + 60, com.reelforge.editor.ui.BugReportText("x".repeat(200), "", null).subject.length)
    }
}

class PictureFramingTest {
    private val photo = Clip(uri = "p", type = ClipType.IMAGE, sourceDurationMs = 3_000)

    @Test fun `drag and pinch add to where the picture already is`() {
        val f = com.reelforge.editor.model.ClipFraming.after(photo.copy(zoom = 1.2f, panX = 0.1f), 0.2f, -0.1f, 1.5f)
        assertEquals(1.8f, f.zoom, 1e-4f)
        assertEquals(0.3f, f.panX, 1e-4f)
        assertEquals(-0.1f, f.panY, 1e-4f)
    }

    @Test fun `the picture can not be resized or pushed out of reach`() {
        val tiny = com.reelforge.editor.model.ClipFraming.after(photo, 5f, -5f, 0.01f)
        assertEquals(com.reelforge.editor.model.ClipFraming.MIN_ZOOM, tiny.zoom, 1e-4f)
        assertTrue(tiny.panX < 1f && tiny.panY > -1f)
        val huge = com.reelforge.editor.model.ClipFraming.after(photo, 0f, 0f, 100f)
        assertEquals(com.reelforge.editor.model.ClipFraming.MAX_ZOOM, huge.zoom, 1e-4f)
    }

    @Test fun `moving right and down is right and down in video units`() {
        val f = com.reelforge.editor.model.ClipFraming.of(photo.copy(panX = 0.25f, panY = 0.1f))
        assertEquals(0.5f, f.ndcX, 1e-4f)
        assertEquals(-0.2f, f.ndcY, 1e-4f)
    }

    @Test fun `size and position survive saving`() {
        val moved = photo.copy(zoom = 1.6f, panX = -0.2f, panY = 0.3f)
        val saved = ProjectJson.decode(ProjectJson.encode(Project(name = "p", clips = listOf(moved)))).clips.single()
        assertEquals(1.6f, saved.zoom, 1e-4f)
        assertEquals(-0.2f, saved.panX, 1e-4f)
        assertEquals(0.3f, saved.panY, 1e-4f)
    }
}
