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
    /**
     * Brightness (0..255) above which a pixel counts as picture. Kept well above black, because
     * shared videos often have "black" bars that are really dark grey and full of noise.
     */
    private const val LIGHT = 60

    /** A row or column is picture when at least this share of it is lighter than a bar. */
    private const val SHARE = 0.15f

    /** Gaps up to this share of the frame (a dark line inside the picture) do not split it. */
    private const val GAP = 0.04f

    private fun lum(c: Int) = (((c shr 16) and 0xff) * 3 + ((c shr 8) and 0xff) * 6 + (c and 0xff)) / 10

    /**
     * The longest stretch of [isPicture] lines, joining stretches split by short gaps. Text or a
     * logo in a bar sits apart from the picture, so it is left out.
     */
    private fun longestRun(count: Int, isPicture: (Int) -> Boolean): IntRange? {
        val gap = maxOf(1, (count * GAP).toInt())
        var best: IntRange? = null
        var start = -1
        var end = -1
        fun close() {
            if (start >= 0 && (best == null || end - start > best!!.last - best!!.first)) best = start..end
        }
        for (i in 0 until count) {
            if (!isPicture(i)) continue
            if (start >= 0 && i - end - 1 <= gap) {
                end = i
            } else {
                close()
                start = i
                end = i
            }
        }
        close()
        return best
    }

    /**
     * The picture's box in one frame, from its ARGB [pixels] ([w] x [h]); null if the frame is
     * (almost) all dark, so it says nothing about the bars.
     */
    fun boxOf(pixels: IntArray, w: Int, h: Int): ContentBox? {
        if (w <= 0 || h <= 0 || pixels.size < w * h) return null
        val light = BooleanArray(w * h) { lum(pixels[it]) > LIGHT }
        val rows = longestRun(h) { y ->
            var n = 0
            for (x in 0 until w) if (light[y * w + x]) n++
            n >= w * SHARE
        } ?: return null
        // Columns are judged only inside the picture's rows.
        val tall = rows.last - rows.first + 1
        val cols = longestRun(w) { x ->
            var n = 0
            for (y in rows) if (light[y * w + x]) n++
            n >= tall * SHARE
        } ?: return null
        if (tall < h / 10 || cols.last - cols.first + 1 < w / 10) return null
        return ContentBox(
            cols.first / w.toFloat(), rows.first / h.toFloat(),
            (cols.last + 1) / w.toFloat(), (rows.last + 1) / h.toFloat(),
        )
    }

    /** The middle value of each edge, so one odd frame (a dark scene, a title) does not decide. */
    fun combine(boxes: List<ContentBox>): ContentBox? {
        if (boxes.isEmpty()) return null
        fun mid(pick: (ContentBox) -> Float) = boxes.map(pick).sorted()[boxes.size / 2]
        return ContentBox(mid { it.left }, mid { it.top }, mid { it.right }, mid { it.bottom })
    }

    /**
     * Looks at a few frames spread through the video and returns where the picture is in most of
     * them, or null if it cannot tell.
     */
    fun find(context: Context, uri: Uri, durationMs: Long): ContentBox? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val boxes = listOf(0.1, 0.25, 0.4, 0.55, 0.7, 0.85).mapNotNull { at ->
                val frame = r.getScaledFrameAtTime(
                    (durationMs * at * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 240,
                ) ?: return@mapNotNull null
                val box = boxOf(frame)
                frame.recycle()
                box
            }
            if (boxes.size < 3) null else combine(boxes)
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
