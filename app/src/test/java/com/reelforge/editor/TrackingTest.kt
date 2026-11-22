package com.reelforge.editor

import com.reelforge.editor.model.ClipFraming
import com.reelforge.editor.model.ObjectTracker
import com.reelforge.editor.model.Placement
import com.reelforge.editor.model.TrackPath
import com.reelforge.editor.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class PlacementTest {

    @Test fun `a wide video fits a tall frame with bars, and fills it by cropping`() {
        assertEquals(1f to (9f / 16f) / (16f / 9f), Placement.fitted(16f / 9f, 9f / 16f, fill = false))
        val (w, h) = Placement.fitted(16f / 9f, 9f / 16f, fill = true)
        assertEquals(1f, h, 1e-6f)
        assertEquals((16f / 9f) / (9f / 16f), w, 1e-5f)
        assertEquals(1f to 1f, Placement.fitted(1f, 1f, fill = false))
    }

    @Test fun `picture and frame points convert both ways`() {
        val framings = listOf(ClipFraming.NONE, ClipFraming(1.6f, 0.1f, -0.2f), ClipFraming(0.5f, -0.3f, 0.25f))
        for (framing in framings) for (fill in listOf(false, true)) for (aspect in listOf(16f / 9f, 9f / 16f, 1f)) {
            for ((u, v) in listOf(0.5f to 0.5f, 0.1f to 0.9f, 0.73f to 0.2f)) {
                val (fx, fy) = Placement.toFrame(u, v, aspect, 9f / 16f, fill, framing)
                val (u2, v2) = Placement.toPicture(fx, fy, aspect, 9f / 16f, fill, framing)
                assertEquals(u, u2, 1e-4f)
                assertEquals(v, v2, 1e-4f)
            }
        }
        // The middle of an unmoved picture is the middle of the frame.
        assertEquals(0.5f to 0.5f, Placement.toFrame(0.5f, 0.5f, 16f / 9f, 9f / 16f, false, ClipFraming.NONE))
        // The left edge of a wide video in a tall frame (fill) is far outside the frame.
        assertTrue(Placement.toFrame(0f, 0.5f, 16f / 9f, 9f / 16f, true, ClipFraming.NONE).first < -1f)
    }

    @Test fun `bias and centre agree with how items are placed`() {
        assertEquals(0.5f, Placement.centre(0f, 0.3f), 1e-6f)
        // At bias 1 the item touches the right edge.
        assertEquals(1f - 0.15f, Placement.centre(1f, 0.3f), 1e-6f)
        for (b in listOf(-1f, -0.4f, 0f, 0.7f)) assertEquals(b, Placement.bias(Placement.centre(b, 0.2f), 0.2f), 1e-5f)
        // Past the edge it stays at the edge; a full-width item has nowhere to move.
        assertEquals(1f, Placement.bias(1.5f, 0.2f), 0f)
        assertEquals(0f, Placement.bias(0.9f, 1f), 0f)
    }
}

class TrackPathTest {

    @Test fun `smoothing takes out shake and simplifying keeps the shape`() {
        val wobbly = (0..40).map { i -> TrackPoint(i * 100L, 0.2f + i * 0.01f + if (i % 2 == 0) 0.004f else -0.004f, 0.5f) }
        val smooth = TrackPath.smooth(wobbly)
        assertEquals(wobbly.size, smooth.size)
        assertTrue(smooth.drop(1).dropLast(1).all { abs(it.x - (0.2f + it.tMs / 100f * 0.01f)) < 0.003f })
        // A straight line needs only its two ends.
        val line = (0..50).map { TrackPoint(it * 100L, it / 50f, 0.3f) }
        assertEquals(2, TrackPath.simplify(line).size)
        // A corner is kept.
        val corner = (0..20).map { TrackPoint(it * 100L, if (it <= 10) it / 10f else 1f, if (it <= 10) 0f else (it - 10) / 10f) }
        val kept = TrackPath.simplify(corner)
        assertTrue(kept.any { it.tMs == 1_000L })
        assertTrue(kept.size <= 4)
        assertEquals(emptyList<TrackPoint>(), TrackPath.simplify(emptyList()))
    }
}

class ObjectTrackerTest {

    private val w = 200
    private val h = 120

    /** A textured background with a bright patterned square whose top-left is at (x, y). */
    private fun frame(x: Int, y: Int, size: Int = 24): FloatArray = FloatArray(w * h) { i ->
        val px = i % w
        val py = i / w
        if (px in x until x + size && py in y until y + size) {
            if (((px - x) / 4 + (py - y) / 4) % 2 == 0) 1f else 0.6f
        } else {
            0.3f + 0.1f * sin(px * 0.31f) * sin(py * 0.17f)
        }
    }

    @Test fun `follows a square moving across the picture`() {
        val tracker = ObjectTracker(half = 10, radius = 18)
        assertTrue(tracker.start(frame(20, 40), w, h, 32f, 52f))
        var x = 20
        var y = 40
        repeat(20) {
            x += 6
            y += if (it < 10) 2 else -2
            assertTrue("step $it lost, score ${tracker.score}", tracker.step(frame(x, y), w, h))
            assertEquals(x + 12f, tracker.x, 1.5f)
            assertEquals(y + 12f, tracker.y, 1.5f)
        }
    }

    @Test fun `says it is lost when the object vanishes`() {
        val tracker = ObjectTracker(half = 10, radius = 18)
        assertTrue(tracker.start(frame(80, 50), w, h, 92f, 62f))
        val empty = FloatArray(w * h) { 0.3f + 0.1f * sin(it % w * 0.31f) * sin(it / w * 0.17f) }
        assertFalse(tracker.step(empty, w, h))
        // A flat picture has nothing to follow.
        assertFalse(ObjectTracker(10, 10).start(FloatArray(w * h) { 0.5f }, w, h, 100f, 60f))
        // Too small a picture.
        assertFalse(ObjectTracker(10, 10).start(FloatArray(100), 10, 10, 5f, 5f))
    }

    @Test fun `grey values follow brightness`() {
        val g = ObjectTracker.grey(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt()))
        assertEquals(0f, g[0], 1e-6f)
        assertEquals(1f, g[1], 1e-4f)
        assertEquals(0.299f, g[2], 1e-3f)
    }
}
