package com.reelforge.editor.model

/** How "Make it 9:16" (or another shape) fits wide clips into the new frame. */
enum class ReframeStyle(val title: String, val help: String) {
    BLUR("Blur background", "Whole picture kept; a blurred copy fills the rest."),
    FILL("Fill the screen", "Zooms in to fill the frame; the sides are cut off."),
    FOLLOW("Follow the person", "Fills the frame and keeps the person in view (finds them automatically)."),
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
