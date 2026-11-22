package com.reelforge.editor.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Where things are on the screen. Points are fractions of the frame (or of a clip's picture):
 * x from the left, y from the top, 0..1.
 */
object Placement {

    /** The part of the frame (width, height as fractions) the clip's picture covers before zoom / pan. */
    fun fitted(pictureAspect: Float, frameAspect: Float, fill: Boolean): Pair<Float, Float> {
        val wide = pictureAspect > frameAspect
        // Fit: the picture touches the frame's sides on its long side. Fill: on its short side.
        return if (wide != fill) 1f to frameAspect / pictureAspect else pictureAspect / frameAspect to 1f
    }

    /** Where point ([u], [v]) of the clip's picture shows in the frame. */
    fun toFrame(u: Float, v: Float, pictureAspect: Float, frameAspect: Float, fill: Boolean, framing: ClipFraming): Pair<Float, Float> {
        val (rw, rh) = fitted(pictureAspect, frameAspect, fill)
        val fx = 0.5f + (u - 0.5f) * rw
        val fy = 0.5f + (v - 0.5f) * rh
        return (0.5f + (fx - 0.5f) * framing.zoom + framing.panX) to (0.5f + (fy - 0.5f) * framing.zoom + framing.panY)
    }

    /** Which point of the clip's picture shows at ([fx], [fy]) in the frame. */
    fun toPicture(fx: Float, fy: Float, pictureAspect: Float, frameAspect: Float, fill: Boolean, framing: ClipFraming): Pair<Float, Float> {
        val (rw, rh) = fitted(pictureAspect, frameAspect, fill)
        val x = 0.5f + (fx - framing.panX - 0.5f) / framing.zoom
        val y = 0.5f + (fy - framing.panY - 0.5f) / framing.zoom
        return (0.5f + (x - 0.5f) / rw) to (0.5f + (y - 0.5f) / rh)
    }

    /**
     * An item's x / y "bias" (-1..1, as stored on texts, layers and keyframes) to where its
     * middle is. [size] is the item's width (or height) as a fraction of the frame. For y the
     * bias counts upwards, so pass `-bias`.
     */
    fun centre(bias: Float, size: Float): Float = 0.5f + bias * (1f - size.coerceIn(0f, 1f)) / 2f

    /** The bias that puts an item's middle at [centre]. */
    fun bias(centre: Float, size: Float): Float {
        val free = 1f - size.coerceIn(0f, 1f)
        if (free < 1e-3f) return 0f
        return ((centre - 0.5f) * 2f / free).coerceIn(-1f, 1f)
    }
}

/** One tracked position: [tMs] on the timeline, the object's middle in the frame. */
data class TrackPoint(val tMs: Long, val x: Float, val y: Float)

object TrackPath {
    /** Takes out the small shake of tracking (an average with the neighbours). */
    fun smooth(points: List<TrackPoint>, reach: Int = 1): List<TrackPoint> {
        if (points.size < 3 || reach < 1) return points
        return points.mapIndexed { i, p ->
            val from = max(0, i - reach)
            val to = min(points.lastIndex, i + reach)
            val part = points.subList(from, to + 1)
            p.copy(x = part.map { it.x }.average().toFloat(), y = part.map { it.y }.average().toFloat())
        }
    }

    /**
     * Keeps only the points needed to follow the path within [tolerance] (fractions of the frame),
     * so the timeline does not fill up with hundreds of keyframes.
     */
    fun simplify(points: List<TrackPoint>, tolerance: Float = 0.004f): List<TrackPoint> {
        if (points.size <= 2) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        fun walk(a: Int, b: Int) {
            if (b - a < 2) return
            var worst = -1f
            var at = -1
            val pa = points[a]
            val pb = points[b]
            for (i in a + 1 until b) {
                val p = points[i]
                val f = (p.tMs - pa.tMs).toFloat() / (pb.tMs - pa.tMs).coerceAtLeast(1)
                val ex = pa.x + (pb.x - pa.x) * f
                val ey = pa.y + (pb.y - pa.y) * f
                val d = max(abs(p.x - ex), abs(p.y - ey))
                if (d > worst) {
                    worst = d
                    at = i
                }
            }
            if (worst > tolerance) {
                keep[at] = true
                walk(a, at)
                walk(at, b)
            }
        }
        walk(0, points.lastIndex)
        return points.filterIndexed { i, _ -> keep[i] }
    }
}

/**
 * Follows a patch of a video from frame to frame (template matching with normalised
 * correlation). Works on grey pictures, so it is plain Kotlin and testable without a phone.
 * Fast movement, things passing in front, or the object leaving the picture end the track.
 */
class ObjectTracker(
    /** Half the size of the patch followed, in pixels. */
    private val half: Int,
    /** How far (pixels) the object may move between two frames. */
    private val radius: Int,
) {
    private val side = 2 * half + 1
    private var template = FloatArray(side * side)
    private var original = FloatArray(side * side)
    var x = 0f
        private set
    var y = 0f
        private set
    /** How alike the last match was (1 = the same, below about 0.4 = lost). */
    var score = 1f
        private set

    /** Starts following the patch around ([cx], [cy]) of [grey] ([w] x [h]). */
    fun start(grey: FloatArray, w: Int, h: Int, cx: Float, cy: Float): Boolean {
        if (w < side || h < side || grey.size < w * h) return false
        x = cx.coerceIn(half.toFloat(), (w - 1 - half).toFloat())
        y = cy.coerceIn(half.toFloat(), (h - 1 - half).toFloat())
        val patch = patch(grey, w, x.toInt(), y.toInt()) ?: return false
        template = patch
        original = patch.copyOf()
        score = 1f
        return true
    }

    /** Finds the object in the next frame. Returns false when it is lost (the position stays). */
    fun step(grey: FloatArray, w: Int, h: Int): Boolean {
        if (w < side || h < side) return false
        val cx = x.toInt()
        val cy = y.toInt()
        var best = -2f
        var bx = cx
        var by = cy
        // Coarse search every other pixel, then the neighbours of the best one.
        var dy = -radius
        while (dy <= radius) {
            var dx = -radius
            while (dx <= radius) {
                val s = scoreAt(grey, w, h, cx + dx, cy + dy)
                if (s > best) {
                    best = s; bx = cx + dx; by = cy + dy
                }
                dx += 2
            }
            dy += 2
        }
        val coarseX = bx
        val coarseY = by
        for (ry in -1..1) for (rx in -1..1) {
            if (rx == 0 && ry == 0) continue
            val s = scoreAt(grey, w, h, coarseX + rx, coarseY + ry)
            if (s > best) {
                best = s; bx = coarseX + rx; by = coarseY + ry
            }
        }
        score = best
        if (best < LOST) return false
        // Half-pixel accuracy from the scores either side of the best one.
        fun refine(minus: Float, mid: Float, plus: Float): Float {
            val d = minus - 2 * mid + plus
            return if (d < -1e-6f) ((minus - plus) / (2 * d)).coerceIn(-0.5f, 0.5f) else 0f
        }
        val sx = refine(scoreAt(grey, w, h, bx - 1, by), best, scoreAt(grey, w, h, bx + 1, by))
        val sy = refine(scoreAt(grey, w, h, bx, by - 1), best, scoreAt(grey, w, h, bx, by + 1))
        x = bx + sx
        y = by + sy
        // The look of the object changes slowly (turning, light); keep a little of the first look
        // so the patch does not drift onto the background.
        patch(grey, w, bx, by)?.let { now ->
            for (i in template.indices) template[i] = 0.75f * template[i] + 0.15f * now[i] + 0.1f * original[i]
            normalise(template)
        }
        return true
    }

    private fun scoreAt(grey: FloatArray, w: Int, h: Int, px: Int, py: Int): Float {
        if (px < half || py < half || px >= w - half || py >= h - half) return -2f
        var sum = 0f
        var sumSq = 0f
        var dot = 0f
        var k = 0
        for (yy in py - half..py + half) {
            val row = yy * w
            for (xx in px - half..px + half) {
                val v = grey[row + xx]
                sum += v
                sumSq += v * v
                dot += v * template[k++]
            }
        }
        val n = (side * side).toFloat()
        val variance = sumSq - sum * sum / n
        if (variance < 1e-6f) return 0f
        // The template has mean 0 and length 1, so this is the correlation.
        return dot / sqrt(variance)
    }

    private fun patch(grey: FloatArray, w: Int, px: Int, py: Int): FloatArray? {
        val out = FloatArray(side * side)
        var k = 0
        for (yy in py - half..py + half) for (xx in px - half..px + half) {
            val i = yy * w + xx
            if (i < 0 || i >= grey.size) return null
            out[k++] = grey[i]
        }
        return if (normalise(out)) out else null
    }

    /** Mean 0, length 1. False for a flat patch (nothing to follow). */
    private fun normalise(a: FloatArray): Boolean {
        val mean = a.average().toFloat()
        var len = 0f
        for (i in a.indices) {
            a[i] -= mean
            len += a[i] * a[i]
        }
        if (len < 1e-6f) return false
        val inv = 1f / sqrt(len)
        for (i in a.indices) a[i] *= inv
        return true
    }

    companion object {
        const val LOST = 0.4f

        /** Grey values (0..1) of ARGB pixels. */
        fun grey(pixels: IntArray): FloatArray = FloatArray(pixels.size) { i ->
            val p = pixels[i]
            (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)) / 255f
        }
    }
}
