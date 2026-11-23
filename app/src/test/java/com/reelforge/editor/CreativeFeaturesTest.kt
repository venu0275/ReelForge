package com.reelforge.editor

import com.reelforge.editor.data.ProjectJson
import com.reelforge.editor.engine.BeatFinder
import com.reelforge.editor.engine.Reverser
import com.reelforge.editor.model.AspectRatio
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
        assertTrue(Templates.all.all { it.photoMs >= 500 && it.motions.isNotEmpty() && (it.title.isNotBlank() || it.ending.isNotBlank()) })
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
    @Test fun `report has the title in the subject, the steps and the phone details`() {
        val r = com.reelforge.editor.ui.BugReportText(
            title = "App closes on Export",
            problem = "It closed when the video was long",
            steps = "1. Add a video\n2. Export",
            device = "App: CutMitra 1.1.0",
        )
        assertEquals("CutMitra – App closes on Export", r.subject)
        assertTrue(r.body.startsWith("Issue: App closes on Export"))
        assertTrue(r.body.contains("What went wrong:\nIt closed"))
        assertTrue(r.body.contains("Steps to reproduce the issue:\n1. Add a video"))
        assertTrue(r.body.contains("Phone and app:\nApp: CutMitra 1.1.0"))
        assertTrue(!r.body.contains("log", ignoreCase = true))
        val bare = com.reelforge.editor.ui.BugReportText("Crash", "", "", null)
        assertTrue(!bare.body.contains("Steps to reproduce") && !bare.body.contains("Phone and app"))
        // Long titles are cut in the subject; an empty one still gives a subject.
        assertEquals("CutMitra – ".length + 60, com.reelforge.editor.ui.BugReportText("x".repeat(200), "", "", null).subject.length)
        assertEquals("CutMitra – Bug report", com.reelforge.editor.ui.BugReportText(" ", "", "", null).subject)
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

class ExtractSoundTest {
    private fun video(id: String, ms: Long) =
        Clip(id = id, uri = "content://v/$id", type = ClipType.VIDEO, sourceDurationMs = ms)

    @Test fun `voice ranges are measured from the start of the sound`() {
        val voice = com.reelforge.editor.model.AudioClip(
            uri = "v", title = "Voice 1", startMs = 3_000, durationMs = 2_000, voice = true,
        )
        val p = Project(name = "p", clips = listOf(video("a", 10_000)), audio = listOf(voice), duckLevel = 0.3f)
        val ranges = com.reelforge.editor.model.voiceRangesIn(p, 2_000, 8_000)
        assertEquals(listOf(1_000_000L..3_000_000L), ranges)
        // Same answer as for the video clip itself, so the sound is lowered exactly as before.
        assertEquals(
            com.reelforge.editor.model.voiceRangesInClip(p, 0),
            com.reelforge.editor.model.voiceRangesIn(p, 0, 10_000),
        )
        assertTrue(com.reelforge.editor.model.voiceRangesIn(p.copy(duckLevel = 1f), 0, 10_000).isEmpty())
    }

    @Test fun `extracted sound settings are saved with the project`() {
        val sound = com.reelforge.editor.model.AudioClip(
            uri = "file:///sounds/s.m4a", title = "Sound: beach", startMs = 1_000, durationMs = 4_000,
            duck = true, pitch = 1.5f, fxAmount = 0.4f, audioFx = com.reelforge.editor.model.AudioFx.CAVE,
        )
        val saved = ProjectJson.decode(ProjectJson.encode(Project(name = "p", audio = listOf(sound)))).audio.single()
        assertEquals(sound, saved)
        val old = ProjectJson.decode(ProjectJson.encode(Project(name = "p", audio = listOf(sound.copy(duck = false)))))
        assertTrue(!old.audio.single().duck)
    }

    @Test fun `saved sound names are safe file names`() {
        assertEquals("Sound_beach_trip", com.reelforge.editor.engine.SoundExtractor.fileName("Sound: beach trip!"))
        assertEquals("Sound", com.reelforge.editor.engine.SoundExtractor.fileName("✈️:/"))
        assertTrue(com.reelforge.editor.engine.SoundExtractor.fileName("x".repeat(99)).length <= 40)
    }
}

class RatioTest {
    @Test fun `the closest frame shape is picked for common videos and photos`() {
        assertEquals(AspectRatio.LANDSCAPE, AspectRatio.closest(1920f / 1080f))
        assertEquals(AspectRatio.PORTRAIT, AspectRatio.closest(1080f / 1920f))
        assertEquals(AspectRatio.PORTRAIT, AspectRatio.closest(720f / 1600f))   // tall phone screen recording
        assertEquals(AspectRatio.SQUARE, AspectRatio.closest(1f))
        assertEquals(AspectRatio.FOUR_THREE, AspectRatio.closest(4000f / 3000f)) // phone photo
        assertEquals(AspectRatio.THREE_FOUR, AspectRatio.closest(3000f / 4000f))
        assertEquals(AspectRatio.CINEMA, AspectRatio.closest(2.4f))
    }
}

class BlackBarsTest {
    private val bars = com.reelforge.editor.util.BlackBars

    /** A 90 x 160 (9:16) frame with a grey 90 x 50 picture in the middle, black above and below. */
    private fun letterboxed(): IntArray {
        val w = 90
        val h = 160
        return IntArray(w * h) { i -> if (i / w in 55 until 105) 0xFF808080.toInt() else 0xFF000000.toInt() }
    }

    @Test fun `finds the picture between black bars`() {
        val box = bars.boxOf(letterboxed(), 90, 160)!!
        assertEquals(0f, box.left, 0.001f)
        assertEquals(1f, box.right, 0.001f)
        assertEquals(55f / 160, box.top, 0.001f)
        assertEquals(105f / 160, box.bottom, 0.001f)
        assertTrue(box.hasBars)
        // 90 x 50 picture: about 16:9.
        assertEquals(AspectRatio.LANDSCAPE, AspectRatio.closest(box.aspect(90f / 160f)))
    }

    @Test fun `noisy grey bars, text in a bar and a dark line in the picture are handled`() {
        val w = 90
        val h = 160
        var seed = 7
        fun rnd(n: Int): Int { seed = seed * 1_103_515_245 + 12_345; return ((seed ushr 16) and 0x7fff) % n }
        fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        val px = IntArray(w * h) { grey(20 + rnd(36)) }                       // "black" that is noisy grey
        for (y in 55 until 105) for (x in 0 until w) px[y * w + x] = grey(70 + rnd(150))
        for (x in 0 until w) px[80 * w + x] = grey(20)                         // dark line inside the picture
        for (y in 122 until 130) for (x in 20 until 60) if (rnd(2) == 0) px[y * w + x] = grey(240) // caption
        val box = bars.boxOf(px, w, h)!!
        assertEquals(55f / 160, box.top, 0.01f)
        assertEquals(105f / 160, box.bottom, 0.01f)
        assertEquals(AspectRatio.LANDSCAPE, AspectRatio.closest(box.aspect(9f / 16f)))
    }

    @Test fun `dark scenes do not make the picture look smaller`() {
        val bright = com.reelforge.editor.util.ContentBox(0f, 0.34f, 1f, 0.66f)
        val dark = com.reelforge.editor.util.ContentBox(0.1f, 0.4f, 0.85f, 0.6f)
        assertEquals(bright, bars.combine(listOf(dark, bright, dark, dark)))
    }

    @Test fun `the whole picture is kept unless filling a frame of another shape`() {
        // Picture a little wider than the frame: fit keeps all of it, cover fills the frame.
        val box = com.reelforge.editor.util.ContentBox(0f, 0.35f, 1f, 0.65f)
        val videoW = (9f / 16f) / (16f / 9f)
        val fit = bars.fillFraming(box, 9f / 16f, 16f / 9f, fill = false)
        assertTrue(box.width * videoW * fit.zoom <= 1.001f)
        assertTrue(box.height * fit.zoom <= 1.001f)
        val cover = bars.fillFraming(box, 9f / 16f, 16f / 9f, fill = false, cover = true)
        assertTrue(box.width * videoW * cover.zoom >= 0.999f && box.height * cover.zoom >= 0.999f)
    }

    @Test fun `a frame without bars or an all-black frame`() {
        val full = IntArray(90 * 160) { 0xFF707070.toInt() }
        assertTrue(!bars.boxOf(full, 90, 160)!!.hasBars)
        assertEquals(null, bars.boxOf(IntArray(90 * 160) { 0xFF000000.toInt() }, 90, 160))
    }

    @Test fun `zoom makes the picture fill the frame`() {
        val box = com.reelforge.editor.util.ContentBox(0f, 0.34375f, 1f, 0.65625f)
        // Frame changed to 16:9, 9:16 video fitted: the picture must grow to fill the frame.
        val wide = bars.fillFraming(box, 9f / 16f, 16f / 9f, fill = false, cover = true)
        val videoW = (9f / 16f) / (16f / 9f)
        assertTrue(box.width * videoW * wide.zoom >= 0.999f)
        assertTrue(box.height * wide.zoom >= 0.999f)
        assertEquals(0f, wide.panX, 0.001f)
        assertEquals(0f, wide.panY, 0.001f)
        // Kept at 9:16: the picture fills the height (sides cut off).
        val tall = bars.fillFraming(box, 9f / 16f, 9f / 16f, fill = false, cover = true)
        assertEquals(1f / box.height, tall.zoom, 0.01f)
        // A picture lower down is moved up into the middle.
        val low = bars.fillFraming(com.reelforge.editor.util.ContentBox(0f, 0.5f, 1f, 0.8f), 9f / 16f, 9f / 16f, false, true)
        assertTrue(low.panY < 0f)
    }
}

class ToastTest {
    private fun kind(t: String) = com.reelforge.editor.ui.Toast.kindOf(t)

    @Test fun `messages get the right look`() {
        assertEquals(com.reelforge.editor.ui.ToastKind.ERROR, kind("Could not reverse this clip on this phone"))
        assertEquals(com.reelforge.editor.ui.ToastKind.ERROR, kind("The preview had a problem on this phone"))
        assertEquals(com.reelforge.editor.ui.ToastKind.WARNING, kind("Move the red line onto the selected clip, away from its edges"))
        assertEquals(com.reelforge.editor.ui.ToastKind.WARNING, kind("Add your photos or videos first, then pick a template"))
        assertEquals(com.reelforge.editor.ui.ToastKind.SUCCESS, kind("Sound added at the red line"))
        assertEquals(com.reelforge.editor.ui.ToastKind.SUCCESS, kind("Saved to Music/CutMitra on your phone"))
        assertEquals(com.reelforge.editor.ui.ToastKind.INFO, kind("Auto captions stopped"))
    }

    @Test fun `toasts stay long enough to read`() {
        val short = com.reelforge.editor.ui.Toast("Undone", com.reelforge.editor.ui.ToastKind.INFO, false, 1)
        val long = com.reelforge.editor.ui.Toast("x".repeat(200), com.reelforge.editor.ui.ToastKind.ERROR, false, 2)
        assertTrue(com.reelforge.editor.ui.toastMillis(short) >= 2_200)
        assertTrue(com.reelforge.editor.ui.toastMillis(long) <= 6_500)
        assertTrue(
            com.reelforge.editor.ui.toastMillis(short.copy(undo = true)) > com.reelforge.editor.ui.toastMillis(short)
        )
    }
}
