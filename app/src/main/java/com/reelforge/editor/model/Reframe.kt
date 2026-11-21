package com.reelforge.editor.model

/** How "Make it 9:16" (or another shape) fits wide clips into the new frame. */
enum class ReframeStyle(val title: String, val help: String) {
    BLUR("Blur background", "Looks the same: the whole picture is kept and a blurred copy fills the rest."),
    FILL("Fill the screen", "Zooms in to fill the frame; the edges are cut off."),
    FOLLOW("Smart follow", "Fills the frame and keeps the person, or the main action, in view."),
}

object Reframe {
    /**
     * The sideways / up-down shift that puts a point of the picture in the middle of a frame the
     * clip fills: [centreX] and [centreY] are where the person is (0..1 across and down the
     * picture). The shift never goes so far that an edge of the frame shows nothing.
     */
    fun followPan(videoAspect: Float, frameAspect: Float, centreX: Float, centreY: Float): Pair<Float, Float> {
        if (!(videoAspect > 0f) || !(frameAspect > 0f)) return 0f to 0f
        // Size of the filled picture in frame widths / heights.
        val wider = videoAspect > frameAspect
        val videoW = if (wider) videoAspect / frameAspect else 1f
        val videoH = if (wider) 1f else frameAspect / videoAspect
        val maxX = ((videoW - 1f) / 2f).coerceAtLeast(0f)
        val maxY = ((videoH - 1f) / 2f).coerceAtLeast(0f)
        val px = (-(centreX.coerceIn(0f, 1f) - 0.5f) * videoW).coerceIn(-maxX, maxX)
        val py = (-(centreY.coerceIn(0f, 1f) - 0.5f) * videoH).coerceIn(-maxY, maxY)
        return px to py
    }

    /**
     * Where the main action is when there is no person: the middle of the busiest, most detailed
     * part of a picture ([pixels] ARGB, [w] x [h]), pulled a third of the way back to the middle
     * so a busy corner does not take the frame too far. Flat areas (sky, walls, bars) do not count.
     * Null when the picture is (almost) flat.
     */
    fun busyCentre(pixels: IntArray, w: Int, h: Int): Pair<Float, Float>? {
        if (w < 3 || h < 3 || pixels.size < w * h) return null
        fun lum(c: Int) = ((c shr 16 and 0xff) * 3 + (c shr 8 and 0xff) * 6 + (c and 0xff)) / 10
        var sum = 0.0
        var sx = 0.0
        var sy = 0.0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val g = kotlin.math.abs(lum(pixels[i + 1]) - lum(pixels[i - 1])) +
                kotlin.math.abs(lum(pixels[i + w]) - lum(pixels[i - w]))
            if (g < 24) continue
            val weight = g.toDouble() * g
            sum += weight
            sx += weight * x
            sy += weight * y
        }
        if (sum <= 0.0) return null
        val fx = (sx / sum / w).toFloat()
        val fy = (sy / sum / h).toFloat()
        return (0.5f + (fx - 0.5f) * 0.67f) to (0.5f + (fy - 0.5f) * 0.67f)
    }

    /** The shapes to offer for "Make it …": the opposite of [current] first, then the others. */
    fun targetsFor(current: AspectRatio): List<AspectRatio> {
        val opposite = if (current.ratio < 1f) AspectRatio.LANDSCAPE else AspectRatio.PORTRAIT
        return (listOf(opposite) + listOf(AspectRatio.PORTRAIT, AspectRatio.LANDSCAPE, AspectRatio.SQUARE, AspectRatio.FOUR_FIVE))
            .distinct()
            .filter { it != current }
    }

    /**
     * Where the person is in a 256×256 (or [side]×[side]) mask of person chances: the middle of
     * the person-pixels, or null if hardly any of the picture shows a person.
     */
    fun personCentre(mask: FloatArray, side: Int): Pair<Float, Float>? {
        if (side <= 0 || mask.size < side * side) return null
        var sum = 0.0
        var sx = 0.0
        var sy = 0.0
        for (y in 0 until side) for (x in 0 until side) {
            val m = mask[y * side + x]
            if (m > 0.5f) {
                sum += m
                sx += m * x
                sy += m * y
            }
        }
        if (sum < side * side * 0.01) return null
        return ((sx / sum + 0.5) / side).toFloat() to ((sy / sum + 0.5) / side).toFloat()
    }
}
