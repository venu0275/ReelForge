package com.reelforge.editor.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.reelforge.editor.model.ClipFraming

/**
 * Where the picture is inside a video that has black bars baked into it (for example a wide video
 * saved as 9:16 with black above and below). Edges are fractions 0..1 of the video's width/height.
 */
data class ContentBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** True when there are clear bars on at least one side. */
    val hasBars: Boolean get() = width < 0.93f || height < 0.93f

    /** Width / height of the picture inside, for a video that is [videoAspect] wide per 1 high. */
    fun aspect(videoAspect: Float) = videoAspect * width / height

    fun union(o: ContentBox) =
        ContentBox(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))
}

object BlackBars {
    /** Brightness (0..255) above which a pixel counts as picture rather than bar. */
    private const val LIGHT = 36

    /**
     * The picture's box in one frame, from its ARGB [pixels] ([w] x [h]); null if the frame is
     * (almost) all dark, so it says nothing about the bars. A row or column belongs to the picture
     * when more than 3 % of it is lighter than a bar.
     */
    fun boxOf(pixels: IntArray, w: Int, h: Int): ContentBox? {
        if (w <= 0 || h <= 0 || pixels.size < w * h) return null
        val rowLight = IntArray(h)
        val colLight = IntArray(w)
        for (y in 0 until h) for (x in 0 until w) {
            val c = pixels[y * w + x]
            val lum = (((c shr 16) and 0xff) * 3 + ((c shr 8) and 0xff) * 6 + (c and 0xff)) / 10
            if (lum > LIGHT) {
                rowLight[y]++
                colLight[x]++
            }
        }
        val rowMin = maxOf(1, w * 3 / 100)
        val colMin = maxOf(1, h * 3 / 100)
        val top = rowLight.indexOfFirst { it >= rowMin }
        val bottom = rowLight.indexOfLast { it >= rowMin }
        val left = colLight.indexOfFirst { it >= colMin }
        val right = colLight.indexOfLast { it >= colMin }
        if (top < 0 || left < 0 || bottom - top < h / 10 || right - left < w / 10) return null
        return ContentBox(left / w.toFloat(), top / h.toFloat(), (right + 1) / w.toFloat(), (bottom + 1) / h.toFloat())
    }

    /**
     * Looks at a few frames spread through the video and returns the area the picture uses in any
     * of them (so dark scenes do not count as bars), or null if it cannot tell.
     */
    fun find(context: Context, uri: Uri, durationMs: Long): ContentBox? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val boxes = listOf(0.1, 0.3, 0.5, 0.7, 0.9).mapNotNull { at ->
                val frame = r.getScaledFrameAtTime(
                    (durationMs * at * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160,
                ) ?: return@mapNotNull null
                val box = boxOf(frame)
                frame.recycle()
                box
            }
            if (boxes.size < 2) null else boxes.reduce { a, b -> a.union(b) }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun boxOf(frame: Bitmap): ContentBox? {
        val w = frame.width
        val h = frame.height
        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, 0, 0, w, h)
        return boxOf(pixels, w, h)
    }

    /**
     * The zoom and position that make the picture inside [box] fill a frame [frameAspect] wide
     * per 1 high, for a video [videoAspect] wide per 1 high that is fitted ([fill] = false) or
     * filled into the frame. Matches how the preview and export place a clip.
     */
    fun fillFraming(box: ContentBox, videoAspect: Float, frameAspect: Float, fill: Boolean): ClipFraming {
        // The video's size in the frame before zooming, as fractions of the frame's width/height.
        val wider = videoAspect > frameAspect
        val (videoW, videoH) = if (wider != fill) 1f to frameAspect / videoAspect else videoAspect / frameAspect to 1f
        val pictureW = box.width * videoW
        val pictureH = box.height * videoH
        val zoom = maxOf(1f / pictureW, 1f / pictureH).coerceIn(ClipFraming.MIN_ZOOM, ClipFraming.MAX_ZOOM)
        val centreX = ((box.left + box.right) / 2f - 0.5f) * videoW
        val centreY = ((box.top + box.bottom) / 2f - 0.5f) * videoH
        return ClipFraming(zoom, (-centreX * zoom).coerceIn(-1f, 1f), (-centreY * zoom).coerceIn(-1f, 1f))
    }
}
