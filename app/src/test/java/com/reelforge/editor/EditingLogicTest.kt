package com.reelforge.editor

import com.reelforge.editor.data.ProjectJson
import com.reelforge.editor.engine.ExportManager
import com.reelforge.editor.engine.FxChannel
import com.reelforge.editor.engine.fadeAlpha
import com.reelforge.editor.engine.overlayAnim
import com.reelforge.editor.model.AspectRatio
import com.reelforge.editor.model.AudioClip
import com.reelforge.editor.model.AudioFx
import com.reelforge.editor.model.Keyframe
import com.reelforge.editor.model.Background
import com.reelforge.editor.model.Clip
import com.reelforge.editor.model.ClipType
import com.reelforge.editor.model.Cut
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

    @Test fun `text and image layers trim and split at the playhead`() {
        val p = project(video("a", 10_000)).copy(
            texts = listOf(TextItem(id = "t", text = "Hi", startMs = 1_000, endMs = 5_000,
                animIn = OverlayAnim.FADE, animOut = OverlayAnim.POP)),
            layers = listOf(ImageLayer(id = "l", uri = "u", startMs = 1_000, endMs = 5_000)),
        )
        assertEquals(3_000L to 5_000L, ProjectOps.cutText(p, "t", 3_000, Cut.LEFT)!!.texts[0].let { it.startMs to it.endMs })
        assertEquals(1_000L to 3_000L, ProjectOps.cutText(p, "t", 3_000, Cut.RIGHT)!!.texts[0].let { it.startMs to it.endMs })
        val split = ProjectOps.cutText(p, "t", 3_000, Cut.SPLIT, newItemId = "t2")!!.texts
        assertEquals(listOf("t", "t2"), split.map { it.id })
        assertEquals(listOf(1_000L to 3_000L, 3_000L to 5_000L), split.map { it.startMs to it.endMs })
        // The join is seamless: no exit animation on the first half, no entry on the second.
        assertEquals(OverlayAnim.FADE to OverlayAnim.NONE, split[0].animIn to split[0].animOut)
        assertEquals(OverlayAnim.NONE to OverlayAnim.POP, split[1].animIn to split[1].animOut)

        val layers = ProjectOps.cutLayer(p, "l", 2_000, Cut.SPLIT, newItemId = "l2")!!.layers
        assertEquals(listOf(1_000L to 2_000L, 2_000L to 5_000L), layers.map { it.startMs to it.endMs })
        assertEquals(2_000, ProjectOps.cutLayer(p, "l", 2_000, Cut.LEFT)!!.layers[0].startMs)
    }

    @Test fun `video layers keep their picture in step when cut`() {
        val layer = VideoLayer(id = "v", uri = "u", sourceDurationMs = 20_000, trimStartMs = 2_000,
            trimEndMs = 10_000, startMs = 1_000) // plays 1 s – 9 s
        val p = project(video("a", 12_000)).copy(videoLayers = listOf(layer))
        val left = ProjectOps.cutVideoLayer(p, "v", 4_000, Cut.LEFT)!!.videoLayers[0]
        assertEquals(4_000, left.startMs)
        assertEquals(5_000, left.trimStartMs)   // 3 s further into the source
        assertEquals(10_000, left.trimEndMs)
        val right = ProjectOps.cutVideoLayer(p, "v", 4_000, Cut.RIGHT)!!.videoLayers[0]
        assertEquals(1_000, right.startMs)
        assertEquals(2_000 to 5_000L, right.trimStartMs.toInt() to right.trimEndMs)
        val parts = ProjectOps.cutVideoLayer(p, "v", 4_000, Cut.SPLIT, newItemId = "v2")!!.videoLayers
        assertEquals(listOf("v", "v2"), parts.map { it.id })
        assertEquals(layer.durationMs, parts.sumOf { it.durationMs })
        assertEquals(parts[0].startMs + parts[0].durationMs, parts[1].startMs)
        assertEquals(parts[0].trimEndMs, parts[1].trimStartMs)
    }

    @Test fun `sounds keep playing from the right place when cut`() {
        val clip = AudioClip("s", "u", "Voice", startMs = 2_000, durationMs = 6_000, fadeInMs = 300,
            fadeOutMs = 400, offsetMs = 500)
        val p = project(video("a", 12_000)).copy(audio = listOf(clip))
        val left = ProjectOps.cutAudio(p, "s", 5_000, Cut.LEFT)!!.audio[0]
        assertEquals(5_000, left.startMs)
        assertEquals(3_000, left.durationMs)
        assertEquals(3_500, left.offsetMs)
        assertEquals(300, left.fadeInMs)
        val right = ProjectOps.cutAudio(p, "s", 5_000, Cut.RIGHT)!!.audio[0]
        assertEquals(3_000, right.durationMs)
        assertEquals(500, right.offsetMs)
        assertEquals(400, right.fadeOutMs)
        val parts = ProjectOps.cutAudio(p, "s", 5_000, Cut.SPLIT, newItemId = "s2")!!.audio
        assertEquals(clip.durationMs, parts.sumOf { it.durationMs })
        assertEquals(parts[0].offsetMs + parts[0].durationMs, parts[1].offsetMs)
        // No fade is added at the join.
        assertEquals(0, parts[0].fadeOutMs)
        assertEquals(0, parts[1].fadeInMs)
    }

    @Test fun `cuts on items are refused outside them or at their edges`() {
        val p = project(video("a", 10_000)).copy(
            texts = listOf(TextItem(id = "t", text = "Hi", startMs = 1_000, endMs = 5_000)),
            audio = listOf(AudioClip("s", "u", "Voice", 2_000, 6_000)),
            videoLayers = listOf(VideoLayer(id = "v", uri = "u", sourceDurationMs = 4_000, startMs = 3_000)),
        )
        for (cut in Cut.entries) {
            assertNull(ProjectOps.cutText(p, "t", 500, cut))
            assertNull(ProjectOps.cutText(p, "t", 1_100, cut))
            assertNull(ProjectOps.cutText(p, "t", 4_950, cut))
            assertNull(ProjectOps.cutText(p, "missing", 3_000, cut))
            assertNull(ProjectOps.cutAudio(p, "s", 9_000, cut))
            assertNull(ProjectOps.cutVideoLayer(p, "v", 2_000, cut))
            assertNull(ProjectOps.cutLayer(p, "none", 3_000, cut))
        }
    }

    @Test fun `music can start later, stop earlier and carries on from the same point`() {
        val song = MusicTrack("u", "Song", durationMs = 4_000, loop = true)
        val p = project(video("a", 10_000)).copy(music = song)
        assertEquals(10_000, song.windowEnd(10_000))

        // Start here: the music begins at 3 s, from 3 s into the song.
        val left = ProjectOps.cutMusic(p, 3_000, Cut.LEFT)!!.music!!
        assertEquals(3_000, left.startMs)
        assertEquals(3_000, left.offsetMs)
        assertEquals(3_000, left.filePosition(3_000))
        assertEquals(0, left.filePosition(4_000)) // loops back to the start of the song

        // Stop here.
        val right = ProjectOps.cutMusic(p, 6_000, Cut.RIGHT)!!.music!!
        assertEquals(6_000, right.windowEnd(10_000))
        assertEquals(0, right.startMs)

        // Both, then slide it: the stretch moves, the part of the song does not change.
        val both = ProjectOps.cutMusic(p.copy(music = left), 6_000, Cut.RIGHT)!!
        val moved = ProjectOps.shiftMusic(both, 1_500).music!!
        assertEquals(4_500, moved.startMs)
        assertEquals(7_500, moved.stopMs)
        assertEquals(3_000, moved.offsetMs)
        assertEquals(0, ProjectOps.shiftMusic(both, -99_000).music!!.startMs)
    }

    @Test fun `music that does not loop ends when the song ends`() {
        val song = MusicTrack("u", "Song", durationMs = 4_000, loop = false)
        assertEquals(4_000, song.windowEnd(10_000))
        assertEquals(6_500, song.copy(startMs = 3_000, offsetMs = 500).windowEnd(10_000))
        assertEquals(10_000, song.copy(startMs = 8_000).windowEnd(10_000))
        // An unknown song length is treated as covering the video.
        assertEquals(10_000, song.copy(durationMs = 0).windowEnd(10_000))
    }

    @Test fun `music cuts are refused for split, outside the music, or with no music`() {
        val song = MusicTrack("u", "Song", durationMs = 4_000, loop = false) // covers 0–4 s
        val p = project(video("a", 10_000)).copy(music = song)
        assertNull(ProjectOps.cutMusic(p, 2_000, Cut.SPLIT))
        assertNull(ProjectOps.cutMusic(p, 6_000, Cut.LEFT))
        assertNull(ProjectOps.cutMusic(p, 100, Cut.RIGHT))
        assertNull(ProjectOps.cutMusic(project(video("a", 10_000)), 2_000, Cut.LEFT))
        val noMusic = project(video("a", 10_000))
        assertSame(noMusic, ProjectOps.shiftMusic(noMusic, 500))
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

// ------------------------------------------- keyframes, clip order, slip, sound fx

class KeyframeAndFxTest {

    private val base = Keyframe(0, x = 0.1f, y = 0.2f, scale = 1f, rotation = 5f, opacity = 0.9f)
    private val keys = listOf(
        Keyframe(1_000, x = -1f, y = 0f, scale = 1f, rotation = 0f, opacity = 1f),
        Keyframe(3_000, x = 1f, y = 0.5f, scale = 2f, rotation = 90f, opacity = 0f),
    )

    @Test fun `without keyframes the item keeps its resting pose`() {
        val pose = ProjectOps.poseAt(emptyList(), 2_500, base)
        assertEquals(base.copy(tMs = 2_500), pose)
    }

    @Test fun `between keyframes every value glides evenly`() {
        val half = ProjectOps.poseAt(keys, 2_000, base)
        assertEquals(0f, half.x, 0.001f)
        assertEquals(0.25f, half.y, 0.001f)
        assertEquals(1.5f, half.scale, 0.001f)
        assertEquals(45f, half.rotation, 0.001f)
        assertEquals(0.5f, half.opacity, 0.001f)
        val quarter = ProjectOps.poseAt(keys, 1_500, base)
        assertEquals(-0.5f, quarter.x, 0.001f)
    }

    @Test fun `before the first and after the last keyframe the item holds still`() {
        assertEquals(-1f, ProjectOps.poseAt(keys, 0, base).x, 0f)
        assertEquals(-1f, ProjectOps.poseAt(keys, -5_000, base).x, 0f)
        assertEquals(1f, ProjectOps.poseAt(keys, 9_000, base).x, 0f)
        assertEquals(90f, ProjectOps.poseAt(keys, 3_000, base).rotation, 0f)
        // A single keyframe simply pins the pose.
        assertEquals(1f, ProjectOps.poseAt(keys.takeLast(1), 100, base).x, 0f)
    }

    @Test fun `saving a keyframe adds one in order or updates a nearby one`() {
        val added = ProjectOps.upsertKeyframe(keys, Keyframe(2_000, 0.3f, 0.3f))
        assertEquals(listOf(1_000L, 2_000L, 3_000L), added.map { it.tMs })
        val updated = ProjectOps.upsertKeyframe(keys, Keyframe(1_050, 0.7f, 0.7f))
        assertEquals(listOf(1_000L, 3_000L), updated.map { it.tMs })
        assertEquals(0.7f, updated[0].x, 0f)
        val first = ProjectOps.upsertKeyframe(emptyList(), Keyframe(500, 0f, 0f))
        assertEquals(1, first.size)
    }

    @Test fun `cutting an animated item keeps the motion in step`() {
        val item = TextItem(id = "t", text = "Go", startMs = 1_000, endMs = 5_000, keyframes = keys)
        val p = project(video("a", 10_000)).copy(texts = listOf(item))
        val parts = ProjectOps.cutText(p, "t", 3_000, Cut.SPLIT, newItemId = "t2")!!.texts
        // The second half starts 2 s into the animation, so its keyframes move 2 s earlier.
        assertEquals(listOf(-1_000L, 1_000L), parts[1].keyframes.map { it.tMs })
        val before = ProjectOps.poseAt(item.keyframes, 2_000, base)
        val after = ProjectOps.poseAt(parts[1].keyframes, 0, base)
        assertEquals(before.x, after.x, 0.001f)
        assertEquals(before.rotation, after.rotation, 0.001f)
        assertEquals(item.keyframes, parts[0].keyframes)
    }

    @Test fun `a dragged clip lands at the chosen place`() {
        val p = project(video("a", 1_000), video("b", 1_000), video("c", 1_000), video("d", 1_000))
        assertEquals(listOf("b", "c", "a", "d"), ProjectOps.moveTo(p, "a", 2).clips.map { it.id })
        assertEquals(listOf("d", "a", "b", "c"), ProjectOps.moveTo(p, "d", 0).clips.map { it.id })
        assertEquals(listOf("a", "c", "d", "b"), ProjectOps.moveTo(p, "b", 99).clips.map { it.id })
        assertSame(p, ProjectOps.moveTo(p, "b", 1))
        assertSame(p, ProjectOps.moveTo(p, "missing", 0))
    }

    @Test fun `slip shows another part of the video without changing the clip`() {
        val clip = video("a", 10_000).copy(trimStartMs = 2_000, trimEndMs = 5_000)
        val p = project(clip, video("b", 4_000))
        val out = ProjectOps.slip(p, "a", 6_000).clips[0]
        assertEquals(6_000, out.trimStartMs)
        assertEquals(9_000, out.trimEndMs)
        assertEquals(clip.durationMs, out.durationMs)
        // It cannot slip past either end of the source.
        assertEquals(7_000, ProjectOps.slip(p, "a", 99_000).clips[0].trimStartMs)
        assertEquals(0, ProjectOps.slip(p, "a", -500).clips[0].trimStartMs)
        assertSame(p, ProjectOps.slip(p, "a", 2_000))
        val photos = project(image("p", 3_000))
        assertSame(photos, ProjectOps.slip(photos, "p", 500))
    }

    // ---- sound effects, checked by measuring how loud a pure tone comes out

    private fun loudness(fx: AudioFx, hz: Double, rate: Int = 44_100): Double {
        val channel = FxChannel(fx, rate)
        var sum = 0.0
        var count = 0
        for (n in 0 until rate) {
            val y = channel.process((0.5 * kotlin.math.sin(2 * Math.PI * hz * n / rate)).toFloat())
            assertFalse("$fx produced a non-number", y.isNaN() || y.isInfinite())
            if (n > rate / 2) { // skip the start while the effect settles
                sum += y * y
                count++
            }
        }
        return kotlin.math.sqrt(sum / count)
    }

    @Test fun `no effect leaves the sound untouched`() {
        val channel = FxChannel(AudioFx.NONE, 44_100)
        for (x in listOf(-1f, -0.3f, 0f, 0.42f, 1f)) assertEquals(x, channel.process(x), 0f)
    }

    @Test fun `bass boost lifts low notes more than high notes`() {
        val low = loudness(AudioFx.BASS, 60.0) / loudness(AudioFx.NONE, 60.0)
        val high = loudness(AudioFx.BASS, 5_000.0) / loudness(AudioFx.NONE, 5_000.0)
        assertTrue("low $low high $high", low > high * 2)
    }

    @Test fun `treble boost lifts high notes more than low notes`() {
        val low = loudness(AudioFx.TREBLE, 100.0) / loudness(AudioFx.NONE, 100.0)
        val high = loudness(AudioFx.TREBLE, 9_000.0) / loudness(AudioFx.NONE, 9_000.0)
        assertTrue("low $low high $high", high > low * 2)
    }

    @Test fun `telephone keeps the voice range and removes rumble and hiss`() {
        val voice = loudness(AudioFx.PHONE, 1_200.0)
        assertTrue(voice > loudness(AudioFx.PHONE, 60.0) * 8)
        assertTrue(voice > loudness(AudioFx.PHONE, 12_000.0) * 8)
    }

    @Test fun `echo repeats a sound after a short delay`() {
        val rate = 44_100
        val channel = FxChannel(AudioFx.ECHO, rate)
        val out = FloatArray(rate) { n -> channel.process(if (n == 0) 1f else 0f) }
        val delay = (0.28 * rate).toInt()
        assertTrue("the original click is kept", out[0] > 0.5f)
        assertTrue("an echo arrives after 0.28 s", out[delay] > 0.2f)
        assertTrue("a quieter second echo follows", out[2 * delay] > 0.05f && out[2 * delay] < out[delay])
        assertEquals("silence between the echoes", 0f, out[delay / 2], 0.0001f)
    }

    @Test fun `hall and robot change the sound but stay within range`() {
        for (fx in listOf(AudioFx.REVERB, AudioFx.ROBOT, AudioFx.ECHO)) {
            val channel = FxChannel(fx, 48_000)
            var peak = 0f
            var different = false
            for (n in 0 until 48_000) {
                val x = (0.5 * kotlin.math.sin(2 * Math.PI * 440.0 * n / 48_000)).toFloat()
                val y = channel.process(x)
                peak = maxOf(peak, kotlin.math.abs(y))
                if (kotlin.math.abs(y - x) > 0.05f) different = true
            }
            assertTrue("$fx should change the sound", different)
            assertTrue("$fx is too loud: $peak", peak < 1.5f)
        }
    }

    @Test fun `every effect works at common sample rates and on silence`() {
        for (fx in AudioFx.entries) for (rate in listOf(8_000, 22_050, 44_100, 48_000)) {
            val channel = FxChannel(fx, rate)
            repeat(2_000) { assertEquals("$fx at $rate", 0f, channel.process(0f), 0.0001f) }
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
                audioFx = AudioFx.REVERB,
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
                keyframes = listOf(Keyframe(0, -1f, 0.5f), Keyframe(1_500, 1f, -0.5f, 2f, 45f, 0.25f)),
            )
        ),
        music = MusicTrack(
            "content://music/1", "Song", 180_000, volume = 0.3f, loop = false,
            fadeInMs = 500, fadeOutMs = 900, startMs = 2_000, stopMs = 9_000, offsetMs = 30_000,
        ),
        layers = listOf(
            ImageLayer(
                id = "l1", uri = "content://img/1", scale = 0.5f, x = 0.2f, y = -0.2f, opacity = 0.7f,
                rotation = -30f, startMs = 100, endMs = 900, animIn = OverlayAnim.FADE,
                animOut = OverlayAnim.FADE, moving = true, x2 = 1f, y2 = 1f,
            )
        ),
        audio = listOf(AudioClip("v1", "file:///voice.m4a", "Voice 1", 1_200, 3_400, 1.5f, 400, 600, 250, AudioFx.ECHO)),
        videoLayers = listOf(
            VideoLayer(
                id = "pip", uri = "content://video/9", sourceDurationMs = 9_000, trimStartMs = 1_000,
                trimEndMs = 4_000, startMs = 2_500, aspect = 0.5625f, scale = 0.3f, x = -0.7f, y = 0.6f,
                opacity = 0.9f, rotation = 12f, volume = 0.5f, muted = true, hasAudio = false,
                chromaKey = true, keyColor = 0xFF0000FF.toInt(), keyTolerance = 0.3f,
                keyframes = listOf(Keyframe(200, 0f, 0f, 0.5f, -10f, 0.75f)),
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

class StoreTest {

    @Test fun `the shipped sticker catalog is well formed`() {
        val file = listOf("src/main/assets/sticker_catalog.json", "app/src/main/assets/sticker_catalog.json")
            .map { java.io.File(it) }.first { it.exists() }
        val stickers = com.reelforge.editor.data.StickerCatalog.parse(file.readText())
        assertTrue(stickers.size >= 60)
        assertEquals("codes must be unique", stickers.size, stickers.map { it.code }.toSet().size)
        for (s in stickers) {
            assertTrue(s.code, s.code.matches(Regex("[0-9a-f]{4,5}")))
            assertTrue(s.url, s.url.startsWith("https://") && s.url.endsWith("emoji_u${s.code}.png"))
            assertTrue(s.code, s.label.isNotEmpty() && s.group.isNotBlank())
        }
    }

    @Test fun `every sound effect is audible, short and repeatable`() {
        for (effect in com.reelforge.editor.data.SoundEffect.entries) {
            val a = com.reelforge.editor.data.SoundMaker.samples(effect)
            val b = com.reelforge.editor.data.SoundMaker.samples(effect)
            assertTrue(effect.name, a.contentEquals(b))
            assertTrue(effect.name, a.size in 1_000..90_000)
            val peak = a.maxOf { kotlin.math.abs(it.toInt()) }
            assertTrue("${effect.name} peak $peak", peak in 6_000..Short.MAX_VALUE.toInt())
            val wav = com.reelforge.editor.data.SoundMaker.wav(a)
            assertEquals(44 + a.size * 2, wav.size)
            assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        }
    }
}

class MusicSearchTest {
    private val search = com.reelforge.editor.data.MusicSearch

    @Test fun `every search asks only for public domain audio`() {
        val urls = search.searchUrls(" happy piano ", soundEffects = false) + search.searchUrls("rain", soundEffects = true)
        assertEquals(3, urls.size)
        assertTrue(urls[0], urls[0].startsWith("https://api.openverse.org/v1/audio/?q=happy+piano+music&"))
        assertTrue(urls[1], "?q=happy+piano&" in urls[1])
        for (url in urls) assertTrue(url, "license=cc0,pdm&" in url && "by" !in url.substringAfter("license="))
    }

    @Test fun `results that are not free or not playable are dropped`() {
        val json = """{"results":[
          {"id":"a-1","title":"Good","creator":"Me","url":"https://x.org/a.mp3","duration":61000,"license":"cc0","filetype":"mp3"},
          {"id":"a-2","title":"No remix","url":"https://x.org/b.mp3","license":"by-nd","filetype":"mp3"},
          {"id":"a-6","title":"Credit me","creator":"Asha","url":"https://x.org/f?format=mp32","license":"by","filetype":"mp32"},
          {"id":"a-3","title":"Not secure","url":"http://x.org/c.mp3","license":"cc0","filetype":"mp3"},
          {"id":"a-4","title":"Odd file","url":"https://x.org/d.exe","license":"cc0","filetype":"exe"},
          {"id":"a-5","title":"","creator":null,"url":"https://x.org/e.ogg?download=1","duration":null,"license":"pdm"}
        ]}"""
        val found = search.parse(json)
        assertEquals("credit-required results must be dropped", listOf("a-1", "a-5"), found.map { it.id })
        assertEquals(61_000L, found[0].durationMs)
        assertEquals("Untitled", found[1].title)
        assertEquals("", found[1].creator)
        assertEquals("ogg", found[1].fileType)
        assertEquals("Public domain", found[1].licence)
        assertTrue(search.parse("{}").isEmpty())
    }
}

class LoopFxTest {
    private fun pose(fx: com.reelforge.editor.model.LoopFx, t: Long) = com.reelforge.editor.model.loopPose(fx, t)

    @Test fun `no loop leaves the item alone and every loop moves it`() {
        assertEquals(com.reelforge.editor.model.LoopPose(), pose(com.reelforge.editor.model.LoopFx.NONE, 777))
        for (fx in com.reelforge.editor.model.LoopFx.entries.drop(1)) {
            val poses = (0L..3000L step 50).map { pose(fx, it) }
            assertTrue("${fx.name} never moves", poses.toSet().size > 1)
            for (p in poses) {
                assertTrue(fx.name, kotlin.math.abs(p.dx) <= 0.1f && kotlin.math.abs(p.dy) <= 0.1f)
                assertTrue(fx.name, p.scale in 0.8f..1.2f && p.alpha in 0.2f..1f && p.rotation in -360f..360f)
            }
        }
    }

    @Test fun `loops repeat and tolerate odd times`() {
        assertEquals(pose(com.reelforge.editor.model.LoopFx.SPIN, 500).rotation, pose(com.reelforge.editor.model.LoopFx.SPIN, 2500).rotation, 0.01f)
        assertEquals(pose(com.reelforge.editor.model.LoopFx.BLINK, 100), pose(com.reelforge.editor.model.LoopFx.BLINK, 900))
        assertEquals(pose(com.reelforge.editor.model.LoopFx.BOUNCE, 0), pose(com.reelforge.editor.model.LoopFx.BOUNCE, -50))
        assertTrue(pose(com.reelforge.editor.model.LoopFx.BOUNCE, 300).dy > 0f)
    }

    @Test fun `the loop setting survives saving`() {
        val layer = ImageLayer(uri = "file:///x.png", loop = com.reelforge.editor.model.LoopFx.WOBBLE)
        val saved = com.reelforge.editor.data.ProjectJson.decode(
            com.reelforge.editor.data.ProjectJson.encode(Project(name = "p", layers = listOf(layer)))
        )
        assertEquals(com.reelforge.editor.model.LoopFx.WOBBLE, saved.layers.single().loop)
    }
}

class SettingsTest {
    private val base = com.reelforge.editor.data.AppSettings()

    @Test fun `defaults are sensible`() {
        assertEquals(com.reelforge.editor.data.ScreenMode.AUTO, base.screen)
        assertEquals(base, base.sanitized())
        assertTrue(base.exportSide in com.reelforge.editor.data.AppSettings.SIDES)
        assertTrue(base.exportFps in com.reelforge.editor.data.AppSettings.FPS)
    }

    @Test fun `bad stored values are put back in range`() {
        val fixed = base.copy(photoMs = -5, itemMs = 9_999_999, exportSide = 999, exportFps = 7, exportQuality = 9).sanitized()
        assertEquals(500L, fixed.photoMs)
        assertEquals(20_000L, fixed.itemMs)
        assertEquals(720, fixed.exportSide)
        assertEquals(30, fixed.exportFps)
        assertEquals(2, fixed.exportQuality)
    }
}

class DragOnPreviewTest {
    @Test fun `dragging moves an item by the right share of the free space`() {
        // Frame 1000 px, item 200 px: 400 px of travel each side of the centre.
        assertEquals(0.5f, ProjectOps.movedBias(0f, 200f, 1000f, 200f), 0.001f)
        assertEquals(-0.25f, ProjectOps.movedBias(0.25f, -200f, 1000f, 200f), 0.001f)
    }

    @Test fun `dragging stops at the edges and lands on the centre when close`() {
        assertEquals(1f, ProjectOps.movedBias(0.8f, 5000f, 1000f, 200f), 0f)
        assertEquals(-1f, ProjectOps.movedBias(0f, -5000f, 1000f, 200f), 0f)
        assertEquals(0f, ProjectOps.movedBias(0.5f, -195f, 1000f, 200f), 0f)
    }

    @Test fun `an item wider than the frame can still be moved`() {
        val moved = ProjectOps.movedBias(0f, 25f, 1000f, 3000f)
        assertTrue(moved > 0.4f && moved <= 1f)
    }
}

class MusicLengthTest {
    private fun track(ms: Long, loop: Boolean = true) =
        com.reelforge.editor.model.MusicTrack(uri = "file:///m.mp3", title = "m", durationMs = ms, loop = loop)

    @Test fun `music longer than the video is heard to the end of the video and shown to its own end`() {
        val music = track(180_000)
        assertEquals(120_000L, music.windowEnd(120_000))
        assertEquals(180_000L, music.fileEnd())
        assertEquals(60_000L, music.filePosition(60_000))
    }

    @Test fun `start, offset and cut move the end of the file on the timeline`() {
        assertEquals(190_000L, track(180_000).copy(startMs = 10_000).fileEnd())
        assertEquals(150_000L, track(180_000).copy(offsetMs = 30_000).fileEnd())
        assertEquals(90_000L, track(180_000).copy(stopMs = 90_000).fileEnd())
        assertEquals(0L, track(0).fileEnd())
    }
}

class TextStyleTest {
    @Test fun `a ready-made style changes the look and nothing else`() {
        val text = com.reelforge.editor.model.TextItem(
            text = "Hello", x = 0.3f, y = -0.4f, startMs = 1_000, endMs = 4_000, rotation = 15f,
            fontPath = "/x/custom.ttf", italic = true,
        )
        for (style in com.reelforge.editor.model.TextStyle.entries) {
            val out = style.applyTo(text)
            assertEquals(style.name, text.id, out.id)
            assertEquals("Hello", out.text)
            assertEquals(0.3f, out.x, 0f)
            assertEquals(-0.4f, out.y, 0f)
            assertEquals(1_000L, out.startMs)
            assertEquals(4_000L, out.endMs)
            assertEquals(15f, out.rotation, 0f)
            assertEquals(style.name, null, out.fontPath)
            assertTrue(style.name, out.size in 0.03f..0.2f)
            assertTrue(style.name, style.label.isNotBlank() && style.hint.isNotBlank())
        }
    }

    @Test fun `styles differ from each other`() {
        val text = com.reelforge.editor.model.TextItem(text = "Hi")
        val looks = com.reelforge.editor.model.TextStyle.entries.map { it.applyTo(text) }
        assertEquals(looks.size, looks.toSet().size)
    }
}

class TextOptionsTest {
    private fun steps(reveal: com.reelforge.editor.model.TextReveal, text: String, max: Int = 24) =
        com.reelforge.editor.model.revealSteps(reveal, text, max)

    @Test fun `letter by letter shows one more letter each step and ends with everything`() {
        val letters = steps(com.reelforge.editor.model.TextReveal.LETTERS, "Hi yo")
        assertEquals(listOf(1, 2, 4, 5), letters)
        assertEquals(listOf(2, 5), steps(com.reelforge.editor.model.TextReveal.WORDS, "Hi yo"))
        assertTrue(steps(com.reelforge.editor.model.TextReveal.NONE, "Hi yo").isEmpty())
        assertTrue(steps(com.reelforge.editor.model.TextReveal.LETTERS, "   ").isEmpty())
    }

    @Test fun `long texts are grouped into a limited number of steps`() {
        val text = "abcdefghij".repeat(10)
        val out = steps(com.reelforge.editor.model.TextReveal.LETTERS, text, 8)
        assertEquals(8, out.size)
        assertEquals(text.length, out.last())
        assertEquals(out.sorted(), out)
    }

    @Test fun `the reveal runs at the start and then shows everything`() {
        val s = steps(com.reelforge.editor.model.TextReveal.LETTERS, "abcd")
        assertEquals(1, com.reelforge.editor.model.revealCount(s, 0, 3_000))
        assertEquals(1, com.reelforge.editor.model.revealCount(s, -50, 3_000))
        assertEquals(3, com.reelforge.editor.model.revealCount(s, 800, 3_000))
        assertEquals(null, com.reelforge.editor.model.revealCount(s, 1_500, 3_000))
        assertEquals(null, com.reelforge.editor.model.revealCount(emptyList(), 10, 3_000))
        assertEquals(600L, com.reelforge.editor.model.revealDurationMs(1_000))
    }

    @Test fun `copying a look or a motion leaves words, place and timing alone`() {
        val a = com.reelforge.editor.model.TextItem(text = "A", x = 0.2f, startMs = 100, endMs = 900)
        val b = com.reelforge.editor.model.TextItem(
            text = "B", color = 0xFFFF0000.toInt(), underline = true, glow = true, letterSpacing = 0.2f,
            loop = com.reelforge.editor.model.LoopFx.PULSE, reveal = com.reelforge.editor.model.TextReveal.WORDS,
        )
        val look = a.withLookOf(b)
        assertEquals("A", look.text)
        assertEquals(0.2f, look.x, 0f)
        assertEquals(100L, look.startMs)
        assertTrue(look.underline && look.glow && look.color == b.color && look.letterSpacing == 0.2f)
        assertEquals(com.reelforge.editor.model.LoopFx.NONE, look.loop)
        val motion = a.withMotionOf(b)
        assertEquals(com.reelforge.editor.model.LoopFx.PULSE, motion.loop)
        assertEquals(com.reelforge.editor.model.TextReveal.WORDS, motion.reveal)
        assertTrue(!motion.underline && motion.color == a.color)
    }

    @Test fun `the new text options survive saving`() {
        val text = com.reelforge.editor.model.TextItem(
            text = "Saved", underline = true, letterSpacing = 0.15f, lineSpacing = 1.4f, shadow = false,
            shadowStrength = 2f, glow = true, glowColor = 0xFF00FF00.toInt(), outlineWidth = 2.5f,
            loop = com.reelforge.editor.model.LoopFx.SHAKE, reveal = com.reelforge.editor.model.TextReveal.LETTERS,
        )
        val saved = com.reelforge.editor.data.ProjectJson.decode(
            com.reelforge.editor.data.ProjectJson.encode(Project(name = "p", texts = listOf(text)))
        ).texts.single()
        assertEquals(text, saved)
    }
}

class PlaybackGroupingTest {
    private fun video(uri: String, start: Long, end: Long, speed: Float = 1f) =
        Clip(uri = uri, type = ClipType.VIDEO, sourceDurationMs = 60_000, trimStartMs = start, trimEndMs = end, speed = speed)

    @Test fun `clips that were only split apart play as one piece`() {
        val clips = listOf(video("a", 0, 4_000), video("a", 4_000, 9_000), video("a", 9_000, 12_000), video("b", 0, 3_000))
        assertEquals(listOf(0..2, 3..3), ProjectOps.playGroups(clips))
        assertTrue(ProjectOps.playGroups(emptyList()).isEmpty())
    }

    @Test fun `a trim, another file or another speed starts a new piece`() {
        val trimmed = listOf(video("a", 0, 4_000), video("a", 5_000, 9_000))
        assertEquals(listOf(0..0, 1..1), ProjectOps.playGroups(trimmed))
        val otherFile = listOf(video("a", 0, 4_000), video("b", 4_000, 9_000))
        assertEquals(2, ProjectOps.playGroups(otherFile).size)
        val otherSpeed = listOf(video("a", 0, 4_000), video("a", 4_000, 9_000, speed = 2f))
        assertEquals(2, ProjectOps.playGroups(otherSpeed).size)
    }

    @Test fun `positions map into a piece and back again`() {
        val clips = listOf(video("a", 2_000, 4_000), video("a", 4_000, 9_000), video("b", 0, 3_000))
        val groups = ProjectOps.playGroups(clips)
        assertEquals(0 to 500L, ProjectOps.toPlayPosition(clips, groups, 0, 500))
        assertEquals(0 to 3_000L, ProjectOps.toPlayPosition(clips, groups, 1, 1_000))
        assertEquals(1 to 700L, ProjectOps.toPlayPosition(clips, groups, 2, 700))
        assertEquals(0 to 500L, ProjectOps.fromPlayPosition(clips, groups, 0, 500))
        assertEquals(1 to 0L, ProjectOps.fromPlayPosition(clips, groups, 0, 2_000))
        assertEquals(1 to 1_000L, ProjectOps.fromPlayPosition(clips, groups, 0, 3_000))
        assertEquals(1 to 5_500L, ProjectOps.fromPlayPosition(clips, groups, 0, 7_500))
        assertEquals(2 to 700L, ProjectOps.fromPlayPosition(clips, groups, 1, 700))
        assertEquals(null, ProjectOps.fromPlayPosition(clips, groups, 5, 0))
    }
}

class ClipNameTest {
    @Test fun `a clip's file name survives saving and old projects load without one`() {
        val clip = Clip(uri = "content://x/1", name = "Wedding day.mp4", type = ClipType.VIDEO, sourceDurationMs = 5_000)
        val saved = com.reelforge.editor.data.ProjectJson.decode(
            com.reelforge.editor.data.ProjectJson.encode(Project(name = "p", clips = listOf(clip)))
        )
        assertEquals("Wedding day.mp4", saved.clips.single().name)
        val old = Clip(uri = "content://x/2", type = ClipType.IMAGE, sourceDurationMs = 3_000)
        assertEquals("", old.name)
    }
}

class MediaLibraryTest {
    private fun item(uri: String, album: String, modified: Long, name: String = "") =
        com.reelforge.editor.data.LibraryItem(uri, name, isVideo = true, durationMs = 1_000, albumId = album, albumName = name, modified = modified)

    @Test fun `items are grouped into folders, newest folder and newest item first`() {
        val items = listOf(item("a1", "A", 10, "Camera"), item("b1", "B", 30, "WhatsApp"), item("a2", "A", 20, "Camera"))
        val albums = com.reelforge.editor.data.MediaLibrary.albums(items)
        assertEquals(listOf("B", "A"), albums.map { it.id })
        assertEquals(listOf("a2", "a1"), albums[1].items.map { it.uri })
        assertEquals("Camera", albums[1].name)
        assertEquals("Other", com.reelforge.editor.data.MediaLibrary.albums(listOf(item("x", "Z", 1))).single().name)
        assertTrue(com.reelforge.editor.data.MediaLibrary.albums(emptyList()).isEmpty())
    }
}

class ScreenRecordingMathTest {
    @Test fun `recording size keeps the shape, never enlarges and is even`() {
        assertEquals(1080 to 2400, com.reelforge.editor.record.recordingSize(1080, 2400, 1080))
        assertEquals(720 to 1600, com.reelforge.editor.record.recordingSize(1080, 2400, 720))
        assertEquals(1600 to 720, com.reelforge.editor.record.recordingSize(2400, 1080, 720))
        assertEquals(720 to 1280, com.reelforge.editor.record.recordingSize(720, 1280, 1080))
        val (w, h) = com.reelforge.editor.record.recordingSize(1081, 2341, 480)
        assertTrue(w % 2 == 0 && h % 2 == 0 && w == 480)
    }

    @Test fun `bitrate stays within sensible limits`() {
        assertEquals(2_000_000, com.reelforge.editor.record.recordingBitrate(100, 100, 30))
        assertEquals(16_000_000, com.reelforge.editor.record.recordingBitrate(2160, 3840, 60))
    }

    @Test fun `the preview box turns into crop coordinates and an even output size`() {
        val box = com.reelforge.editor.record.CropBox(0.25f, 0.1f, 0.75f, 0.4f)
        val ndc = box.toNdc()
        assertEquals(-0.5f, ndc[0], 0.0001f)
        assertEquals(0.5f, ndc[1], 0.0001f)
        assertEquals(0.2f, ndc[2], 0.0001f)
        assertEquals(0.8f, ndc[3], 0.0001f)
        assertEquals(540 to 720, box.outputSize(1080, 2400))
        assertTrue(box.isValid)
        assertTrue(!com.reelforge.editor.record.CropBox(0f, 0f, 0.01f, 1f).isValid)
    }
}

class StretchBarTest {
    @Test fun `dragging the right end makes a bar longer or shorter`() {
        assertEquals(1_000L to 6_000L, ProjectOps.resizeEdge(1_000, 4_000, false, 2_000, emptyList(), 0))
        assertEquals(1_000L to 2_500L, ProjectOps.resizeEdge(1_000, 4_000, false, -1_500, emptyList(), 0))
    }

    @Test fun `dragging the left end moves the start and keeps the end`() {
        assertEquals(500L to 4_000L, ProjectOps.resizeEdge(1_000, 4_000, true, -500, emptyList(), 0))
        assertEquals(0L to 4_000L, ProjectOps.resizeEdge(1_000, 4_000, true, -5_000, emptyList(), 0))
    }

    @Test fun `a bar never gets shorter than the minimum or past its limits`() {
        val min = ProjectOps.MIN_BAR_MS
        assertEquals(1_000L to 1_000L + min, ProjectOps.resizeEdge(1_000, 4_000, false, -9_000, emptyList(), 0))
        assertEquals(4_000L - min to 4_000L, ProjectOps.resizeEdge(1_000, 4_000, true, 9_000, emptyList(), 0))
        assertEquals(1_000L to 5_000L, ProjectOps.resizeEdge(1_000, 4_000, false, 9_000, emptyList(), 0, maxEnd = 5_000))
        assertEquals(800L to 4_000L, ProjectOps.resizeEdge(1_000, 4_000, true, -900, emptyList(), 0, minStart = 800))
    }

    @Test fun `the dragged end sticks to nearby points`() {
        assertEquals(1_000L to 6_000L, ProjectOps.resizeEdge(1_000, 4_000, false, 1_950, listOf(6_000), 100))
        assertEquals(1_000L to 5_800L, ProjectOps.resizeEdge(1_000, 4_000, false, 1_800, listOf(6_000), 100))
    }

    @Test fun `stretching to the clip end finds the clip under a time`() {
        val clips = listOf(
            Clip(uri = "a", type = ClipType.IMAGE, sourceDurationMs = 3_000),
            Clip(uri = "b", type = ClipType.IMAGE, sourceDurationMs = 5_000),
        )
        val project = Project(name = "p", clips = clips)
        assertEquals(3_000L, ProjectOps.clipEndAt(project, 1_000))
        assertEquals(8_000L, ProjectOps.clipEndAt(project, 4_000))
    }
}

class VoiceOverTest {
    @Test fun `video sound is lowered inside a voice-over and ramps at its edges`() {
        val ranges = listOf(1_000_000L..3_000_000L)
        assertEquals(1f, com.reelforge.editor.model.duckGain(0, ranges, 0.25f), 0.001f)
        assertEquals(0.25f, com.reelforge.editor.model.duckGain(2_000_000, ranges, 0.25f), 0.001f)
        val edge = com.reelforge.editor.model.duckGain(925_000, ranges, 0.25f)
        assertTrue(edge > 0.25f && edge < 1f)
        assertEquals(1f, com.reelforge.editor.model.duckGain(2_000_000, ranges, 1f), 0.001f)
        assertEquals(0f, com.reelforge.editor.model.duckGain(2_000_000, ranges, 0f), 0.001f)
    }

    @Test fun `voice-over times are found inside each clip`() {
        val clips = listOf(
            Clip(uri = "a", type = ClipType.VIDEO, sourceDurationMs = 4_000),
            Clip(uri = "b", type = ClipType.VIDEO, sourceDurationMs = 4_000),
        )
        val voice = AudioClip(uri = "v", title = "Voice 1", startMs = 3_000, durationMs = 2_000, voice = true)
        val music = AudioClip(uri = "m", title = "Pop", startMs = 0, durationMs = 8_000)
        val p = Project(name = "p", clips = clips, audio = listOf(voice, music), duckLevel = 0.3f)
        assertEquals(listOf(3_000_000L..4_000_000L), com.reelforge.editor.model.voiceRangesInClip(p, 0))
        assertEquals(listOf(0L..1_000_000L), com.reelforge.editor.model.voiceRangesInClip(p, 1))
        assertTrue(com.reelforge.editor.model.voiceRangesInClip(p.copy(duckLevel = 1f), 0).isEmpty())
    }

    @Test fun `voice marks and the lowering level survive saving`() {
        val voice = AudioClip(uri = "v", title = "Voice 1", startMs = 0, durationMs = 1_000, voice = true)
        val p = Project(name = "p", audio = listOf(voice), duckLevel = 0.25f)
        val saved = com.reelforge.editor.data.ProjectJson.decode(com.reelforge.editor.data.ProjectJson.encode(p))
        assertTrue(saved.audio.single().voice)
        assertEquals(0.25f, saved.duckLevel, 0.001f)
    }
}

class ReplaceVoiceTest {
    private fun voice(start: Long, length: Long, offset: Long = 0) =
        AudioClip(uri = "v", title = "Voice", startMs = start, durationMs = length, offsetMs = offset, voice = true)

    @Test fun `a new voice-over cuts the old one into the parts before and after it`() {
        val old = voice(1_000, 8_000)
        val out = ProjectOps.cutOutVoice(listOf(old), 3_000, 5_000)
        assertEquals(2, out.size)
        assertEquals(1_000L, out[0].startMs)
        assertEquals(2_000L, out[0].durationMs)
        assertEquals(5_000L, out[1].startMs)
        assertEquals(4_000L, out[1].durationMs)
        assertEquals(4_000L, out[1].offsetMs)
        assertTrue(out[0].id != out[1].id)
    }

    @Test fun `fully covered voice-overs go, other sounds stay, tiny leftovers are dropped`() {
        val covered = voice(3_000, 1_000)
        val sound = AudioClip(uri = "s", title = "Pop", startMs = 3_000, durationMs = 1_000)
        val edge = voice(2_900, 2_000)
        val out = ProjectOps.cutOutVoice(listOf(covered, sound, edge), 3_000, 5_000)
        assertEquals(listOf(sound), out)
        assertEquals(listOf(covered), ProjectOps.cutOutVoice(listOf(covered), 5_000, 5_000))
    }
}

class MusicLibraryTest {
    private fun song(uri: String, title: String, artist: String, album: String, folder: String, modified: Long) =
        com.reelforge.editor.data.SongItem(uri, title, artist, album, album, 180_000, folder, folder, modified)

    private val songs = listOf(
        song("1", "Kesariya", "Arijit Singh", "Brahmastra", "Music", 10),
        song("2", "Voice note", "", "", "Recordings", 30),
        song("3", "Deva Deva", "Arijit Singh", "Brahmastra", "Music", 20),
    )

    @Test fun `songs group into albums and folders, newest first`() {
        val albums = com.reelforge.editor.data.MusicLibrary.albums(songs)
        assertEquals(listOf("Unknown album", "Brahmastra"), albums.map { it.name })
        assertEquals(listOf("3", "1"), albums[1].songs.map { it.uri })
        val folders = com.reelforge.editor.data.MusicLibrary.folders(songs)
        assertEquals(listOf("Recordings", "Music"), folders.map { it.name })
    }

    @Test fun `search matches title, artist or album in any letter case`() {
        val lib = com.reelforge.editor.data.MusicLibrary
        assertEquals(listOf("1", "3"), lib.search(songs, "arijit").map { it.uri })
        assertEquals(listOf("3"), lib.search(songs, "DEVA").map { it.uri })
        assertEquals(listOf("1", "3"), lib.search(songs, "brahma").map { it.uri })
        assertEquals(songs, lib.search(songs, "  "))
        assertTrue(lib.search(songs, "zzz").isEmpty())
    }
}
