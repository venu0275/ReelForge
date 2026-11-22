package com.reelforge.editor.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import kotlin.math.abs
import kotlin.math.min

/**
 * A picture the app draws itself, frame by frame: an animated channel button or a scene effect
 * (snow, Diwali lights, confetti, ...). The same drawing is used for the preview and the exported
 * video, so they always match. Stored on an image layer by [name].
 */
interface MovingArt {
    val name: String
    val title: String

    /** How long it is on the video when added (it can be changed like any layer). */
    val defaultMs: Long

    /** True when it covers the whole video (a scene effect) instead of sitting somewhere on it. */
    val fullFrame: Boolean get() = false

    /** Width / height of its picture on a video that is [frameAspect] (width / height). */
    fun aspect(frameAspect: Float): Float

    /** Draws it as it looks [tMs] after it appears, on a [w] x [h] canvas (transparent around). */
    fun draw(canvas: Canvas, tMs: Long, durationMs: Long, w: Float, h: Float)

    companion object {
        fun of(name: String?): MovingArt? = AnimatedButton.of(name) ?: SceneEffect.of(name)

        /** The picture of [art] at [tMs], [height] pixels high (a new bitmap). */
        fun render(art: MovingArt, tMs: Long, durationMs: Long, height: Int, frameAspect: Float): Bitmap {
            val hgt = height.coerceAtLeast(8)
            val wid = (hgt * art.aspect(frameAspect)).toInt().coerceAtLeast(8)
            val bitmap = Bitmap.createBitmap(wid, hgt, Bitmap.Config.ARGB_8888)
            art.draw(Canvas(bitmap), tMs.coerceAtLeast(0), durationMs, wid.toFloat(), hgt.toFloat())
            return bitmap
        }
    }

    /**
     * Draws frames for the exporter on demand, into two pictures used in turn (the video
     * pipeline uploads a picture again only when it gets a different one).
     */
    class Frames(private val art: MovingArt, private val durationMs: Long, height: Int, frameAspect: Float) {
        private val hgt = height.coerceAtLeast(8)
        private val wid = (hgt * art.aspect(frameAspect)).toInt().coerceAtLeast(8)
        private val buffers = Array(2) { Bitmap.createBitmap(wid, hgt, Bitmap.Config.ARGB_8888) }
        private var next = 0
        private var lastMs = Long.MIN_VALUE
        private var last: Bitmap = buffers[0]

        /** The picture [sinceMs] after it appears. */
        fun at(sinceMs: Long): Bitmap {
            // Frames close together in time (well under one frame at 60 fps) reuse the last picture.
            if (lastMs != Long.MIN_VALUE && abs(sinceMs - lastMs) < 8) return last
            val bmp = buffers[next]
            next = 1 - next
            bmp.eraseColor(0)
            art.draw(Canvas(bmp), sinceMs.coerceAtLeast(0), durationMs, wid.toFloat(), hgt.toFloat())
            lastMs = sinceMs
            last = bmp
            return bmp
        }

        /** A typical frame, for the overlay's first set-up. */
        val first: Bitmap get() = at(min(3_000L, durationMs / 2))
    }
}
