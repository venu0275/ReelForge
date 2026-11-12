package com.reelforge.editor

import com.reelforge.editor.data.ProjectJson
import com.reelforge.editor.engine.ExportManager
import com.reelforge.editor.engine.fadeAlpha
import com.reelforge.editor.engine.overlayAnim
import com.reelforge.editor.model.AspectRatio
import com.reelforge.editor.model.AudioClip
import com.reelforge.editor.model.Background
import com.reelforge.editor.model.Clip
import com.reelforge.editor.model.ClipType
import com.reelforge.editor.model.FilterPreset
import com.reelforge.editor.model.History
import com.reelforge.editor.model.ImageLayer
import com.reelforge.editor.model.MusicTrack
import com.reelforge.editor.model.OverlayAnim
import com.reelforge.editor.model.Project
import com.reelforge.editor.model.ProjectOps
import com.reelforge.editor.model.TextAlignH
import com.reelforge.editor.model.TextFont
import com.reelforge.editor.model.TextItem
import com.reelforge.editor.model.TransitionStyle
import com.reelforge.editor.model.VideoLayer
import com.reelforge.editor.util.MediaUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private fun video(id: String, lengthMs: Long, speed: Float = 1f) =
    Clip(id = id, uri = "file:///$id.mp4", type = ClipType.VIDEO, sourceDurationMs = lengthMs, speed = speed)

private fun image(id: String, lengthMs: Long) =
    Clip(id = id, uri = "file:///$id.jpg", type = ClipType.IMAGE, sourceDurationMs = lengthMs, hasAudio = false)

private fun project(vararg clips: Clip) = Project(id = "p", name = "Test", clips = clips.toList())

// ---------------------------------------------------------------- timeline maths

class TimelineTest {

    @Test fun `clip duration follows trim and speed`() {
        val clip = video("a", 10_000).copy(trimStartMs = 2_000, trimEndMs = 6_000)
        assertEquals(4_000, clip.durationMs)
        assertEquals(2_000, clip.copy(speed = 2f).durationMs)
        assertEquals(8_000, clip.copy(speed = 0.5f).durationMs)
    }

    @Test fun `speed is ignored for photos`() {
        val photo = image("a", 3_000).copy(speed = 4f)
        assertEquals(3_000, photo.durationMs)
    }

    @Test fun `project duration is the sum of its clips`() {
        val p = project(video("a", 4_000), image("b", 3_000), video("c", 2_000, speed = 2f))
        assertEquals(8_000, p.durationMs)
        assertEquals(0, p.clipStartMs(0))
        assertEquals(4_000, p.clipStartMs(1))
        assertEquals(7_000, p.clipStartMs(2))
    }

    @Test fun `locate finds the clip and local time`() {
        val p = project(video("a", 4_000), image("b", 3_000))
        assertEquals(0 to 0L, p.locate(0))
        assertEquals(0 to 3_999L, p.locate(3_999))
        assertEquals(1 to 0L, p.locate(4_000))
        assertEquals(1 to 2_500L, p.locate(6_500))
    }

    @Test fun `locate past the end clamps to the last clip`() {
        val p = project(video("a", 4_000), image("b", 3_000))
        assertEquals(1 to 2_999L, p.locate(7_000))
        assertEquals(1 to 2_999L, p.locate(999_999))
    }

    @Test fun `locate handles negative time and an empty project`() {
        assertEquals(0 to 0L, project(video("a", 4_000)).locate(-500))
        assertNull(project().locate(0))
        assertEquals(0, project().durationMs)
    }
}

// ------------------------------------------------------------------ edit actions

class ProjectOpsTest {

    @Test fun `split cuts one clip into two and keeps total length`() {
        val p = project(video("a", 4_000), video("b", 2_000))
        val out = ProjectOps.split(p, 1_000, newClipId = "a2")!!
        assertEquals(listOf("a", "a2", "b"), out.clips.map { it.id })
        assertEquals(1_000, out.clips[0].durationMs)
        assertEquals(3_000, out.clips[1].durationMs)
        assertEquals(1_000, out.clips[1].trimStartMs)
        assertEquals(p.durationMs, out.durationMs)
    }

    @Test fun `split respects speed when finding the source position`() {
        val p = project(video("a", 4_000, speed = 2f)) // 2 s on the timeline
        val out = ProjectOps.split(p, 500, newClipId = "a2")!!
        assertEquals(1_000, out.clips[0].trimEndMs)
        assertEquals(1_000, out.clips[1].trimStartMs)
        assertEquals(2_000, out.durationMs)
    }

    @Test fun `split in the second clip uses the right clip`() {
        val p = project(video("a", 4_000), video("b", 2_000))
        val out = ProjectOps.split(p, 5_000, newClipId = "b2")!!
        assertEquals(listOf("a", "b", "b2"), out.clips.map { it.id })
        assertEquals(1_000, out.clips[1].durationMs)
    }

    @Test fun `split removes the fades at the cut`() {
        val p = project(video("a", 4_000).copy(fadeInMs = 500, fadeOutMs = 500))
        val out = ProjectOps.split(p, 2_000, newClipId = "a2")!!
        assertEquals(500, out.clips[0].fadeInMs)
        assertEquals(0, out.clips[0].fadeOutMs)
        assertEquals(0, out.clips[1].fadeInMs)
        assertEquals(500, out.clips[1].fadeOutMs)
    }

    @Test fun `split too close to an edge is refused`() {
        val p = project(video("a", 4_000))
        assertNull(ProjectOps.split(p, 0))
        assertNull(ProjectOps.split(p, 150))
        assertNull(ProjectOps.split(p, 3_900))
        assertNull(ProjectOps.split(p, 4_000))
    }

    @Test fun `split on an empty project is refused`() {
        assertNull(ProjectOps.split(project(), 0))
    }

    @Test fun `split works on photos`() {
        val out = ProjectOps.split(project(image("a", 3_000)), 1_000, newClipId = "a2")!!
        assertEquals(1_000, out.clips[0].durationMs)
        assertEquals(2_000, out.clips[1].durationMs)
    }

    @Test fun `duplicate inserts a copy right after the original`() {
        val p = project(video("a", 4_000), video("b", 2_000))
        val out = ProjectOps.duplicate(p, "a", newClipId = "copy")
        assertEquals(listOf("a", "copy", "b"), out.clips.map { it.id })
        assertEquals(out.clips[0].copy(id = "copy"), out.clips[1])
    }

    @Test fun `duplicate of an unknown clip changes nothing`() {
        val p = project(video("a", 4_000))
        assertSame(p, ProjectOps.duplicate(p, "missing"))
    }

    @Test fun `move reorders clips`() {
        val p = project(video("a", 1_000), video("b", 1_000), video("c", 1_000))
        assertEquals(listOf("b", "a", "c"), ProjectOps.move(p, "a", 1).clips.map { it.id })
        assertEquals(listOf("a", "c", "b"), ProjectOps.move(p, "c", -1).clips.map { it.id })
        assertEquals(listOf("b", "c", "a"), ProjectOps.move(p, "a", 2).clips.map { it.id })
    }

    @Test fun `move outside the track changes nothing`() {
        val p = project(video("a", 1_000), video("b", 1_000))
        assertSame(p, ProjectOps.move(p, "a", -1))
        assertSame(p, ProjectOps.move(p, "b", 1))
        assertSame(p, ProjectOps.move(p, "missing", 1))
    }

    @Test fun `delete removes only the named clip`() {
        val p = project(video("a", 1_000), video("b", 1_000))
        assertEquals(listOf("b"), ProjectOps.delete(p, "a").clips.map { it.id })
        assertEquals(p, ProjectOps.delete(p, "missing"))
        assertTrue(ProjectOps.delete(ProjectOps.delete(p, "a"), "b").clips.isEmpty())
    }

    @Test fun `transition fades this clip out and the next one in`() {
        val p = project(video("a", 4_000), video("b", 4_000), video("c", 4_000))
        val out = ProjectOps.transitionAfter(p, "a", 500)
        assertEquals(500, out.clips[0].fadeOutMs)
        assertEquals(500, out.clips[1].fadeInMs)
        assertEquals(0, out.clips[1].fadeOutMs)
        assertEquals(0, out.clips[2].fadeInMs)
    }

    @Test fun `transition is capped at half of a short clip`() {
        val p = project(video("a", 600), video("b", 400))
        val out = ProjectOps.transitionAfter(p, "a", 1_000)
        assertEquals(300, out.clips[0].fadeOutMs)
        assertEquals(200, out.clips[1].fadeInMs)
    }

    @Test fun `transition on the last or an unknown clip changes nothing`() {
        val p = project(video("a", 4_000), video("b", 4_000))
        assertSame(p, ProjectOps.transitionAfter(p, "b", 500))
        assertSame(p, ProjectOps.transitionAfter(p, "missing", 500))
    }

    @Test fun `negative transition length is treated as none`() {
        val p = project(video("a", 4_000), video("b", 4_000))
        val out = ProjectOps.transitionAfter(p, "a", -300)
        assertEquals(0, out.clips[0].fadeOutMs)
        assertEquals(0, out.clips[1].fadeInMs)
    }

    @Test fun `fades are clamped to half the clip`() {
        val clip = video("a", 1_000).copy(fadeInMs = 900, fadeOutMs = 2_000)
        val out = ProjectOps.clampFades(clip)
        assertEquals(500, out.fadeInMs)
        assertEquals(500, out.fadeOutMs)
        val negative = ProjectOps.clampFades(clip.copy(fadeInMs = -50))
        assertEquals(0, negative.fadeInMs)
    }
}

// ------------------------------------------------------- timeline edge drags

class EdgeTrimTest {

    @Test fun `dragging the start edge right cuts footage off the start`() {
        val out = ProjectOps.trimStart(project(video("a", 10_000)), "a", 2_000)
        assertEquals(2_000, out.clips[0].trimStartMs)
        assertEquals(8_000, out.clips[0].durationMs)
    }

    @Test fun `dragging the start edge left brings footage back but not before zero`() {
        val p = project(video("a", 10_000).copy(trimStartMs = 3_000))
        assertEquals(1_000, ProjectOps.trimStart(p, "a", -2_000).clips[0].trimStartMs)
        assertEquals(0, ProjectOps.trimStart(p, "a", -9_000).clips[0].trimStartMs)
    }

    @Test fun `dragging the end edge shortens and lengthens within the source`() {
        val p = project(video("a", 10_000).copy(trimEndMs = 6_000))
        assertEquals(4_000, ProjectOps.trimEnd(p, "a", -2_000).clips[0].trimEndMs)
        assertEquals(8_000, ProjectOps.trimEnd(p, "a", 2_000).clips[0].trimEndMs)
        assertEquals(10_000, ProjectOps.trimEnd(p, "a", 60_000).clips[0].trimEndMs)
    }

    @Test fun `an edge drag never makes a clip shorter than the minimum`() {
        val p = project(video("a", 10_000))
        assertEquals(10_000 - ProjectOps.MIN_CLIP_MS, ProjectOps.trimStart(p, "a", 60_000).clips[0].trimStartMs)
        assertEquals(ProjectOps.MIN_CLIP_MS, ProjectOps.trimEnd(p, "a", -60_000).clips[0].trimEndMs)
    }

    @Test fun `edge drags account for clip speed`() {
        val p = project(video("a", 10_000, speed = 2f)) // 5 s on the timeline
        val out = ProjectOps.trimStart(p, "a", 1_000)
        assertEquals(2_000, out.clips[0].trimStartMs)
        assertEquals(4_000, out.clips[0].durationMs)
    }

    @Test fun `photos stretch and shrink from either edge within limits`() {
        val p = project(image("a", 3_000))
        assertEquals(5_000, ProjectOps.trimEnd(p, "a", 2_000).clips[0].durationMs)
        assertEquals(2_000, ProjectOps.trimStart(p, "a", 1_000).clips[0].durationMs)
        assertEquals(ProjectOps.MIN_CLIP_MS, ProjectOps.trimEnd(p, "a", -99_000).clips[0].durationMs)
        assertEquals(ProjectOps.MAX_PHOTO_MS, ProjectOps.trimEnd(p, "a", 999_000).clips[0].durationMs)
    }

    @Test fun `trimming clamps fades and leaves other clips alone`() {
        val p = project(video("a", 10_000).copy(fadeInMs = 2_000, fadeOutMs = 2_000), video("b", 4_000))
        val out = ProjectOps.trimEnd(p, "a", -9_000)
        assertEquals(1_000, out.clips[0].durationMs)
        assertEquals(500, out.clips[0].fadeInMs)
        assertEquals(500, out.clips[0].fadeOutMs)
        assertEquals(p.clips[1], out.clips[1])
    }

    @Test fun `edge drags on unknown or tiny clips change nothing`() {
        val p = project(video("a", 200))
        assertSame(p, ProjectOps.trimStart(p, "missing", 500))
        assertSame(p, ProjectOps.trimEnd(p, "missing", 500))
        assertSame(p, ProjectOps.trimStart(p, "a", 100))
        assertSame(p, ProjectOps.trimEnd(p, "a", -100))
    }

    @Test fun `trim left removes what is before the playhead`() {
        val p = project(video("a", 4_000), video("b", 10_000), video("c", 2_000))
        val out = ProjectOps.trimLeftOfPlayhead(p, "b", 7_000)!! // 3 s into clip b
        assertEquals(3_000, out.clips[1].trimStartMs)
        assertEquals(10_000, out.clips[1].trimEndMs)
        assertEquals(7_000, out.clips[1].durationMs)
        assertEquals(p.durationMs - 3_000, out.durationMs)
        assertEquals(p.clips[0], out.clips[0])
        assertEquals(p.clips[2], out.clips[2])
    }

    @Test fun `trim right removes what is after the playhead`() {
        val p = project(video("a", 4_000), video("b", 10_000), video("c", 2_000))
        val out = ProjectOps.trimRightOfPlayhead(p, "b", 7_000)!!
        assertEquals(0, out.clips[1].trimStartMs)
        assertEquals(3_000, out.clips[1].trimEndMs)
        assertEquals(3_000, out.clips[1].durationMs)
        assertEquals(p.durationMs - 7_000, out.durationMs)
    }

    @Test fun `trim left then right keeps exactly the middle`() {
        val p = project(video("a", 10_000))
        val left = ProjectOps.trimLeftOfPlayhead(p, "a", 2_000)!!
        // After trimming left the clip starts at 0 again, so 5 s of it is the old 7 s mark.
        val both = ProjectOps.trimRightOfPlayhead(left, "a", 5_000)!!
        assertEquals(2_000, both.clips[0].trimStartMs)
        assertEquals(7_000, both.clips[0].trimEndMs)
    }

    @Test fun `playhead trims follow speed and work on photos`() {
        val fast = project(video("a", 10_000, speed = 2f)) // 5 s on the timeline
        assertEquals(4_000, ProjectOps.trimLeftOfPlayhead(fast, "a", 2_000)!!.clips[0].trimStartMs)
        assertEquals(4_000, ProjectOps.trimRightOfPlayhead(fast, "a", 2_000)!!.clips[0].trimEndMs)
        val photo = project(image("p", 3_000))
        assertEquals(2_000, ProjectOps.trimLeftOfPlayhead(photo, "p", 1_000)!!.clips[0].durationMs)
        assertEquals(1_000, ProjectOps.trimRightOfPlayhead(photo, "p", 1_000)!!.clips[0].durationMs)
    }

    @Test fun `playhead trims are refused outside the clip or at its edges`() {
        val p = project(video("a", 4_000), video("b", 10_000))
        assertNull(ProjectOps.trimLeftOfPlayhead(p, "b", 2_000))   // playhead is on clip a
        assertNull(ProjectOps.trimRightOfPlayhead(p, "a", 9_000))  // playhead is on clip b
        assertNull(ProjectOps.trimLeftOfPlayhead(p, "b", 4_000))   // exactly at b's start
        assertNull(ProjectOps.trimRightOfPlayhead(p, "b", 4_100))  // too close to b's start
        assertNull(ProjectOps.trimLeftOfPlayhead(p, "b", 13_950))  // too close to b's end
        assertNull(ProjectOps.trimLeftOfPlayhead(p, "missing", 5_000))
        assertNull(ProjectOps.cutPoint(project(), "a", 0))
    }

    @Test fun `replace keeps the edits and fits the new media`() {
        val styled = video("a", 10_000).copy(
            trimStartMs = 2_000, trimEndMs = 6_000, filter = FilterPreset.SEPIA, speed = 2f, fadeInMs = 300,
        )
        val p = project(styled, video("b", 4_000))
        val longer = ProjectOps.replaceMedia(p, "a", "file:///new.mp4", ClipType.VIDEO, 30_000, true).clips[0]
        assertEquals("file:///new.mp4", longer.uri)
        assertEquals(0, longer.trimStartMs)
        assertEquals(4_000, longer.trimEndMs)
        assertEquals(FilterPreset.SEPIA, longer.filter)
        assertEquals(2f, longer.speed, 0f)
        assertEquals(30_000, longer.sourceDurationMs)

        val shorter = ProjectOps.replaceMedia(p, "a", "file:///short.mp4", ClipType.VIDEO, 1_500, false).clips[0]
        assertEquals(1_500, shorter.trimEndMs)
        assertFalse(shorter.hasAudio)

        val photo = ProjectOps.replaceMedia(p, "a", "file:///pic.jpg", ClipType.IMAGE, 3_000, false).clips[0]
        assertEquals(ClipType.IMAGE, photo.type)
        assertEquals(styled.durationMs, photo.durationMs)

        assertSame(p, ProjectOps.replaceMedia(p, "missing", "x", ClipType.VIDEO, 1_000, true))
    }
}

// ----------------------------------------------------------- snapping, catalog

class SnapAndCatalogTest {

    @Test fun `snap pulls a value onto a nearby point only`() {
        val points = listOf(0L, 4_000L, 7_000L)
        assertEquals(4_000, ProjectOps.snap(3_900, points, 150))
        assertEquals(4_000, ProjectOps.snap(4_120, points, 150))
        assertEquals(3_700, ProjectOps.snap(3_700, points, 150))
        assertEquals(5_500, ProjectOps.snap(5_500, emptyList(), 150))
        assertEquals(3_900, ProjectOps.snap(3_900, points, 0))
    }

    @Test fun `a dropped item snaps by its start, else by its end, and never before zero`() {
        val points = listOf(0L, 4_000L, 7_000L)
        assertEquals(4_000, ProjectOps.snapStart(3_950, 2_000, points, 150))
        // Start is far from everything, but the end (5 050 + 2 000) is near 7 000.
        assertEquals(5_000, ProjectOps.snapStart(5_050, 2_000, points, 150))
        assertEquals(1_500, ProjectOps.snapStart(1_500, 500, points, 150))
        assertEquals(0, ProjectOps.snapStart(-800, 500, points, 150))
    }

    @Test fun `snap points cover clip edges and other items but not the moved one`() {
        val p = project(video("a", 4_000), video("b", 3_000)).copy(
            texts = listOf(
                TextItem(id = "t1", text = "x", startMs = 500, endMs = 1_500),
                TextItem(id = "t2", text = "y", startMs = 2_000, endMs = 2_600),
            ),
            audio = listOf(AudioClip("v", "u", "Voice", 5_000, 1_000)),
        )
        val points = ProjectOps.snapPoints(p, excludeId = "t1")
        assertTrue(points.containsAll(listOf(0L, 4_000L, 7_000L, 2_000L, 2_600L, 5_000L, 6_000L)))
        assertFalse(points.contains(500L))
        assertFalse(points.contains(1_500L))
        assertEquals(points.sorted(), points)
    }

    @Test fun `every aspect ratio is close to its label`() {
        val expected = mapOf(
            AspectRatio.LANDSCAPE to 16f / 9, AspectRatio.PORTRAIT to 9f / 16, AspectRatio.SQUARE to 1f,
            AspectRatio.FOUR_FIVE to 4f / 5, AspectRatio.THREE_FOUR to 3f / 4,
            AspectRatio.FOUR_THREE to 4f / 3, AspectRatio.CINEMA to 2.35f,
        )
        assertEquals(AspectRatio.entries.toSet(), expected.keys)
        for ((aspect, ratio) in expected) {
            val (w, h) = ExportManager.outputSize(aspect, 1080)
            assertEquals("$aspect", ratio, w.toFloat() / h, 0.01f)
        }
    }

    @Test fun `bitrate allows for frame rate and format`() {
        val base = ExportManager.bitrate(1920, 1080, 1)
        assertTrue(ExportManager.bitrate(1920, 1080, 1, fps = 60) > base)
        assertTrue(ExportManager.bitrate(1920, 1080, 1, hevc = true) < base)
        // 4K at the best quality must still fit in an Int.
        assertTrue(ExportManager.bitrate(3840, 2160, 2, fps = 60) > 0)
    }

    @Test fun `font catalog parses and rejects broken input`() {
        val fonts = com.reelforge.editor.data.AssetCatalog.parseFonts(
            """{"fonts":[{"id":"a","name":"A","group":"G","licence":"OFL-1.1","url":"https://x/a.ttf"},
               {"id":"b","name":"B","url":"https://x/b.ttf"}]}"""
        )
        assertEquals(2, fonts.size)
        assertEquals("G", fonts[0].group)
        assertEquals("Other", fonts[1].group)
        assertThrows(Exception::class.java) { com.reelforge.editor.data.AssetCatalog.parseFonts("{}") }
        assertThrows(Exception::class.java) {
            com.reelforge.editor.data.AssetCatalog.parseFonts("""{"fonts":[{"name":"no id"}]}""")
        }
    }

    @Test fun `the shipped font catalog is well formed`() {
        val file = listOf("src/main/assets/font_catalog.json", "app/src/main/assets/font_catalog.json")
            .map { java.io.File(it) }.first { it.exists() }
        val fonts = com.reelforge.editor.data.AssetCatalog.parseFonts(file.readText())
        assertTrue(fonts.size >= 20)
        assertEquals("ids must be unique", fonts.size, fonts.map { it.id }.toSet().size)
        for (font in fonts) {
            assertTrue(font.id, font.id.matches(Regex("[a-z0-9]+")))
            assertTrue(font.url, font.url.startsWith("https://") && font.url.endsWith(".ttf"))
            assertTrue(font.id, font.name.isNotBlank() && font.licence.isNotBlank())
        }
    }
}

// --------------------------------------------------------------------- undo redo

class HistoryTest {

    @Test fun `undo and redo walk through states`() {
        val h = History<String>()
        h.record("A", 1_000)   // A -> B
        h.record("B", 5_000)   // B -> C
        assertTrue(h.canUndo)
        assertFalse(h.canRedo)
        assertEquals("B", h.undo("C"))
        assertEquals("A", h.undo("B"))
        assertNull(h.undo("A"))
        assertEquals("B", h.redo("A"))
        assertEquals("C", h.redo("B"))
        assertNull(h.redo("C"))
    }

    @Test fun `rapid changes collapse into one undo step`() {
        val h = History<Int>(coalesceMs = 700)
        h.record(0, 1_000)
        h.record(1, 1_100)
        h.record(2, 1_200)
        assertEquals(0, h.undo(3))
        assertNull(h.undo(0))
    }

    @Test fun `a new change clears redo`() {
        val h = History<String>()
        h.record("A", 1_000)
        assertEquals("A", h.undo("B"))
        assertTrue(h.canRedo)
        h.record("A", 9_000)
        assertFalse(h.canRedo)
    }

    @Test fun `history keeps only the newest entries`() {
        val h = History<Int>(limit = 3, coalesceMs = 0)
        for (i in 0 until 10) h.record(i, i * 1_000L + 1)
        assertEquals(9, h.undo(10))
        assertEquals(8, h.undo(9))
        assertEquals(7, h.undo(8))
        assertNull(h.undo(7))
    }

    @Test fun `undo and redo on an empty history return nothing`() {
        val h = History<String>()
        assertNull(h.undo("x"))
        assertNull(h.redo("x"))
        h.record("a", 1_000)
        h.clear()
        assertFalse(h.canUndo)
    }
}

// ------------------------------------------------------------- fades, animations

class EffectMathTest {

    @Test fun `fade alpha ramps in and out`() {
        assertEquals(0f, fadeAlpha(0, 4_000, 1_000, 1_000), 0.001f)
        assertEquals(0.5f, fadeAlpha(500, 4_000, 1_000, 1_000), 0.001f)
        assertEquals(1f, fadeAlpha(2_000, 4_000, 1_000, 1_000), 0.001f)
        assertEquals(0.5f, fadeAlpha(3_500, 4_000, 1_000, 1_000), 0.001f)
        assertEquals(0f, fadeAlpha(4_000, 4_000, 1_000, 1_000), 0.001f)
    }

    @Test fun `no fade means full brightness everywhere`() {
        for (t in listOf(0L, 1L, 2_000L, 3_999L, 4_000L)) {
            assertEquals(1f, fadeAlpha(t, 4_000, 0, 0), 0f)
        }
    }

    @Test fun `fade alpha never leaves 0 to 1`() {
        for (t in -1_000L..6_000L step 250) {
            val a = fadeAlpha(t, 4_000, 800, 800)
            assertTrue("alpha $a at $t", a in 0f..1f)
        }
    }

    @Test fun `overlay animation starts hidden and ends hidden`() {
        val start = overlayAnim(0, 3_000, OverlayAnim.FADE, OverlayAnim.FADE)
        val middle = overlayAnim(1_500, 3_000, OverlayAnim.FADE, OverlayAnim.FADE)
        val end = overlayAnim(3_000, 3_000, OverlayAnim.FADE, OverlayAnim.FADE)
        assertEquals(0f, start.alpha, 0.001f)
        assertEquals(1f, middle.alpha, 0.001f)
        assertEquals(0f, end.alpha, 0.001f)
    }

    @Test fun `pop scales up and slide moves up into place`() {
        val pop = overlayAnim(0, 3_000, OverlayAnim.POP, OverlayAnim.NONE)
        assertTrue(pop.scale < 1f)
        val slide = overlayAnim(0, 3_000, OverlayAnim.SLIDE, OverlayAnim.NONE)
        assertTrue(slide.dy < 0f)
        val settled = overlayAnim(1_500, 3_000, OverlayAnim.SLIDE, OverlayAnim.POP)
        assertEquals(1f, settled.scale, 0.001f)
        assertEquals(0f, settled.dy, 0.001f)
    }

    @Test fun `sideways and turning animations start away and settle in place`() {
        val left = overlayAnim(0, 3_000, OverlayAnim.FROM_LEFT, OverlayAnim.NONE)
        val right = overlayAnim(0, 3_000, OverlayAnim.FROM_RIGHT, OverlayAnim.NONE)
        val down = overlayAnim(0, 3_000, OverlayAnim.SLIDE_DOWN, OverlayAnim.NONE)
        val spin = overlayAnim(0, 3_000, OverlayAnim.SPIN, OverlayAnim.NONE)
        val zoom = overlayAnim(0, 3_000, OverlayAnim.ZOOM, OverlayAnim.NONE)
        assertTrue(left.dx < 0f)
        assertTrue(right.dx > 0f)
        assertTrue(down.dy > 0f)
        assertEquals(180f, spin.rotation, 0.001f)
        assertTrue(zoom.scale > 1f)
        for (anim in OverlayAnim.entries) {
            val settled = overlayAnim(1_500, 3_000, anim, anim)
            assertEquals("$anim alpha", 1f, settled.alpha, 0.001f)
            assertEquals("$anim scale", 1f, settled.scale, 0.001f)
            assertEquals("$anim dx", 0f, settled.dx, 0.001f)
            assertEquals("$anim dy", 0f, settled.dy, 0.001f)
            assertEquals("$anim turn", 0f, settled.rotation, 0.001f)
            val leaving = overlayAnim(3_000, 3_000, OverlayAnim.NONE, anim)
            if (anim != OverlayAnim.NONE) assertEquals("$anim ends hidden", 0f, leaving.alpha, 0.001f)
        }
    }

    @Test fun `no animation leaves the overlay untouched`() {
        for (t in listOf(0L, 10L, 1_500L, 2_990L)) {
            val s = overlayAnim(t, 3_000, OverlayAnim.NONE, OverlayAnim.NONE)
            assertEquals(1f, s.alpha, 0f)
            assertEquals(1f, s.scale, 0f)
            assertEquals(0f, s.dy, 0f)
        }
    }

    @Test fun `animation on a zero length overlay does not divide by zero`() {
        val s = overlayAnim(0, 0, OverlayAnim.POP, OverlayAnim.SLIDE)
        assertFalse(s.alpha.isNaN())
        assertFalse(s.scale.isNaN())
        assertFalse(s.dy.isNaN())
    }
}

// ------------------------------------------------------------------ export sizes

class ExportSizeTest {

    @Test fun `output sizes match the aspect ratio`() {
        assertEquals(1920 to 1080, ExportManager.outputSize(AspectRatio.LANDSCAPE, 1080))
        assertEquals(1080 to 1920, ExportManager.outputSize(AspectRatio.PORTRAIT, 1080))
        assertEquals(720 to 720, ExportManager.outputSize(AspectRatio.SQUARE, 720))
        assertEquals(1080 to 1350, ExportManager.outputSize(AspectRatio.FOUR_FIVE, 1080))
        assertEquals(1280 to 720, ExportManager.outputSize(AspectRatio.LANDSCAPE, 720))
    }

    @Test fun `output sizes are always even numbers`() {
        for (aspect in AspectRatio.entries) {
            for (side in listOf(240, 360, 480, 540, 720, 1080, 481, 719)) {
                val (w, h) = ExportManager.outputSize(aspect, side)
                assertEquals("$aspect $side width", 0, w % 2)
                assertEquals("$aspect $side height", 0, h % 2)
                assertTrue(w > 0 && h > 0)
            }
        }
    }

    @Test fun `bitrate grows with quality and size`() {
        val low = ExportManager.bitrate(1280, 720, 0)
        val mid = ExportManager.bitrate(1280, 720, 1)
        val high = ExportManager.bitrate(1280, 720, 2)
        assertTrue(low < mid && mid < high)
        assertTrue(ExportManager.bitrate(1920, 1080, 1) > mid)
        // An unknown quality level falls back to the balanced one.
        assertEquals(mid, ExportManager.bitrate(1280, 720, 99))
    }

    @Test fun `time is formatted as minutes seconds tenths`() {
        assertEquals("00:00.0", MediaUtils.formatTime(0))
        assertEquals("00:01.5", MediaUtils.formatTime(1_500))
        assertEquals("01:05.2", MediaUtils.formatTime(65_250))
        assertEquals("00:00.0", MediaUtils.formatTime(-400))
    }
}

// ------------------------------------------------------------------ saved files

class ProjectJsonTest {

    private val full = Project(
        id = "p1",
        name = "Holiday \"cut\" ✈️",
        aspect = AspectRatio.PORTRAIT,
        clips = listOf(
            video("a", 9_000).copy(
                trimStartMs = 1_000, trimEndMs = 7_000, speed = 1.5f, volume = 0.4f, muted = true,
                rotation = 90, filter = FilterPreset.SEPIA, brightness = 0.2f, contrast = -0.3f,
                saturation = 40f, fadeInMs = 300, fadeOutMs = 500, opacity = 0.8f, zoom = 1.7f,
                flipH = true, fill = true, transition = TransitionStyle.SLIDE, pitch = 1.25f,
            ),
            image("b", 3_000),
        ),
        texts = listOf(
            TextItem(
                id = "t1", text = "Line one\nLine two", color = 0xFF112233.toInt(), size = 0.12f,
                x = -0.5f, y = 0.75f, startMs = 500, endMs = 2_500, font = TextFont.CURSIVE,
                background = true, outline = true, animIn = OverlayAnim.POP, animOut = OverlayAnim.SLIDE,
                bold = false, italic = true, align = TextAlignH.RIGHT, opacity = 0.6f, rotation = 15f,
                outlineColor = 0xFFFF0000.toInt(), boxColor = 0xCC00FF00.toInt(),
                fontPath = "/data/fonts/x.ttf", moving = true, x2 = 0.9f, y2 = -0.9f,
            )
        ),
        music = MusicTrack("content://music/1", "Song", 180_000, volume = 0.3f, loop = false),
        layers = listOf(
            ImageLayer(
                id = "l1", uri = "content://img/1", scale = 0.5f, x = 0.2f, y = -0.2f, opacity = 0.7f,
                rotation = -30f, startMs = 100, endMs = 900, animIn = OverlayAnim.FADE,
                animOut = OverlayAnim.FADE, moving = true, x2 = 1f, y2 = 1f,
            )
        ),
        audio = listOf(AudioClip("v1", "file:///voice.m4a", "Voice 1", 1_200, 3_400, 1.5f, 400, 600)),
        videoLayers = listOf(
            VideoLayer(
                id = "pip", uri = "content://video/9", sourceDurationMs = 9_000, trimStartMs = 1_000,
                trimEndMs = 4_000, startMs = 2_500, aspect = 0.5625f, scale = 0.3f, x = -0.7f, y = 0.6f,
                opacity = 0.9f, rotation = 12f, volume = 0.5f, muted = true, hasAudio = false,
                chromaKey = true, keyColor = 0xFF0000FF.toInt(), keyTolerance = 0.3f,
            )
        ),
        background = Background.BLUR,
        backgroundColor = 0xFF336699.toInt(),
        updatedAt = 1_700_000_000_000,
    )

    @Test fun `every field survives save and load`() {
        assertEquals(full, ProjectJson.decode(ProjectJson.encode(full)))
    }

    @Test fun `an empty project survives save and load`() {
        val empty = Project(id = "e", name = "", updatedAt = 5)
        assertEquals(empty, ProjectJson.decode(ProjectJson.encode(empty)))
    }

    @Test fun `a file saved by the first app version still loads with defaults`() {
        val old = """
            {"id":"old","name":"Old","aspect":"LANDSCAPE","updatedAt":1,
             "clips":[{"id":"c","uri":"u","type":"VIDEO","sourceDurationMs":5000,
                       "trimStartMs":0,"trimEndMs":5000}],
             "texts":[{"id":"t","text":"Hi"}]}
        """
        val p = ProjectJson.decode(old)
        assertEquals(1f, p.clips[0].speed, 0f)
        assertEquals(1f, p.clips[0].opacity, 0f)
        assertEquals(TransitionStyle.FADE, p.clips[0].transition)
        assertEquals(TextFont.SANS, p.texts[0].font)
        assertTrue(p.texts[0].bold)
        assertNull(p.texts[0].fontPath)
        assertTrue(p.layers.isEmpty())
        assertTrue(p.audio.isEmpty())
        assertTrue(p.videoLayers.isEmpty())
        assertNull(p.music)
        assertEquals(Background.BLACK, p.background)
    }

    @Test fun `unknown enum values fall back instead of failing`() {
        val odd = """
            {"id":"x","name":"X","aspect":"HEXAGON","background":"LAVA",
             "clips":[{"id":"c","uri":"u","type":"VIDEO","sourceDurationMs":1000,
                       "trimStartMs":0,"trimEndMs":1000,"filter":"XRAY","transition":"SPIN"}],
             "texts":[{"id":"t","text":"Hi","font":"COMIC","align":"DIAGONAL","animIn":"EXPLODE"}]}
        """
        val p = ProjectJson.decode(odd)
        assertEquals(AspectRatio.LANDSCAPE, p.aspect)
        assertEquals(Background.BLACK, p.background)
        assertEquals(FilterPreset.NONE, p.clips[0].filter)
        assertEquals(TransitionStyle.FADE, p.clips[0].transition)
        assertEquals(TextFont.SANS, p.texts[0].font)
        assertEquals(TextAlignH.CENTER, p.texts[0].align)
        assertEquals(OverlayAnim.NONE, p.texts[0].animIn)
    }

    @Test fun `broken files are rejected`() {
        assertThrows(Exception::class.java) { ProjectJson.decode("") }
        assertThrows(Exception::class.java) { ProjectJson.decode("not json at all") }
        assertThrows(Exception::class.java) { ProjectJson.decode("{\"name\":\"no id\"}") }
        assertThrows(Exception::class.java) {
            ProjectJson.decode("{\"id\":\"x\",\"name\":\"X\",\"clips\":[{\"id\":\"c\"}]}")
        }
    }

    @Test fun `a video layer lasts as long as its trimmed range`() {
        val layer = VideoLayer(uri = "u", sourceDurationMs = 9_000, trimStartMs = 1_000, trimEndMs = 4_000)
        assertEquals(3_000, layer.durationMs)
        assertEquals(9_000, VideoLayer(uri = "u", sourceDurationMs = 9_000).durationMs)
    }

    @Test fun `different projects produce different files`() {
        assertNotEquals(ProjectJson.encode(full), ProjectJson.encode(full.copy(name = "Other")))
    }
}

// -------------------------------------------------------------------- performance

class LogicPerformanceTest {

    private fun bigProject(clipCount: Int): Project = Project(
        id = "big",
        name = "Big",
        clips = (0 until clipCount).map { video("c$it", 2_000 + (it % 7) * 500L) },
        texts = (0 until clipCount / 2).map {
            TextItem(id = "t$it", text = "Caption $it", startMs = it * 1_000L, endMs = it * 1_000L + 900)
        },
    )

    private fun millis(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    @Test fun `finding the playhead in a 1000 clip project is fast`() {
        val p = bigProject(1_000)
        val total = p.durationMs
        repeat(200) { p.locate(total / 2) } // warm up
        val took = millis { repeat(10_000) { p.locate((it * 37L) % total) } }
        println("PERF locate: 10,000 lookups in a 1,000-clip project took $took ms")
        assertTrue("locate too slow: $took ms", took < 3_000)
    }

    @Test fun `saving and loading a 500 clip project is fast`() {
        val p = bigProject(500)
        repeat(3) { ProjectJson.decode(ProjectJson.encode(p)) } // warm up
        var text = ""
        val encode = millis { repeat(20) { text = ProjectJson.encode(p) } }
        val decode = millis { repeat(20) { ProjectJson.decode(text) } }
        println("PERF json: 500-clip project, ${text.length / 1024} KB, save ${encode / 20} ms, load ${decode / 20} ms")
        assertTrue("save too slow: $encode ms for 20", encode < 4_000)
        assertTrue("load too slow: $decode ms for 20", decode < 4_000)
        assertEquals(p, ProjectJson.decode(text))
    }

    @Test fun `200 splits in a row stay fast and keep the total length`() {
        var p = project(video("a", 600_000))
        val total = p.durationMs
        val took = millis {
            for (i in 1..200) {
                p = ProjectOps.split(p, i * 2_500L, newClipId = "s$i") ?: p
            }
        }
        println("PERF split: 200 splits took $took ms, ${p.clips.size} clips")
        assertEquals(201, p.clips.size)
        assertEquals(total, p.durationMs)
        assertTrue("splits too slow: $took ms", took < 2_000)
    }
}
