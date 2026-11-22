package com.reelforge.editor.engine

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Super Resolution: makes a small or blurry photo four times bigger and sharper, on the phone.
 *
 * The model is Real-ESRGAN realesr-general-x4v3 (BSD-3-Clause, Copyright (c) 2021 Xintao Wang),
 * converted to LiteRT by tools/models/convert_sr.py and shipped in the app (about 5 MB), so it
 * works offline and nothing has to be downloaded. The licence notice is in the open-source
 * licences screen; nothing needs to be shown on the video.
 */
object SuperResolution {
    const val ASSET = "super_resolution_x4.tflite"
    const val TILE = 128
    const val SCALE = 4
    /** Pixels each tile shares with its neighbours, so no seams show where tiles meet. */
    const val OVERLAP = 12
    /** Photos are made at most this many pixels on the long side before being enlarged. */
    const val MAX_INPUT = 640
    /** Photos already this big (long side) are sharp enough; enlarging them would only be slow. */
    const val ALREADY_SHARP = 1600

    fun isBundled(context: Context): Boolean =
        runCatching { context.assets.list("")?.contains(ASSET) == true }.getOrDefault(false)

    /** Whether enlarging a photo of this size is worthwhile. */
    fun worthIt(width: Int, height: Int): Boolean = maxOf(width, height) in 1 until ALREADY_SHARP

    /** The size the photo is brought to before enlarging (keeps its shape, never bigger). */
    fun inputSize(width: Int, height: Int): Pair<Int, Int> {
        val long = maxOf(width, height)
        if (long <= MAX_INPUT) return width to height
        val f = MAX_INPUT.toFloat() / long
        return (width * f).toInt().coerceAtLeast(1) to (height * f).toInt().coerceAtLeast(1)
    }

    /**
     * Where the tiles start along one side [length] long. Tiles are [TILE] long and overlap by at
     * least [OVERLAP] on each side; the last one is pulled back to end exactly at the edge.
     */
    fun starts(length: Int, tile: Int = TILE, overlap: Int = OVERLAP): List<Int> {
        if (length <= tile) return listOf(0)
        val step = tile - 2 * overlap
        val out = ArrayList<Int>()
        var s = 0
        while (true) {
            if (s + tile >= length) {
                out += length - tile
                break
            }
            out += s
            s += step
        }
        return out.distinct()
    }

    /**
     * Which part of a tile starting at [start] is kept: from the middle of the overlap with the
     * tile before to the middle of the overlap with the tile after. Returns [from, to) in
     * picture pixels.
     */
    fun keep(starts: List<Int>, index: Int, length: Int, tile: Int = TILE): Pair<Int, Int> {
        val s = starts[index]
        val from = if (index == 0) 0 else (starts[index - 1] + tile + s) / 2
        val to = if (index == starts.lastIndex) minOf(length, s + tile) else (s + tile + starts[index + 1]) / 2
        return from to to
    }

    /** Runs the model. Not thread-safe: use one at a time. */
    class Upscaler(context: Context) : AutoCloseable {
        private val interpreter: Interpreter
        private val input = ByteBuffer.allocateDirect(TILE * TILE * 3 * 4).order(ByteOrder.nativeOrder())
        private val output = ByteBuffer.allocateDirect(TILE * SCALE * TILE * SCALE * 3 * 4).order(ByteOrder.nativeOrder())

        init {
            val bytes = context.assets.open(ASSET).use { it.readBytes() }
            val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
            model.rewind()
            interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))
        }

        /**
         * Returns [photo] four times bigger. [onProgress] gets 0..1; [stop] is checked between
         * tiles (returning true stops with an exception).
         */
        fun upscale(photo: Bitmap, onProgress: (Float) -> Unit = {}, stop: () -> Boolean = { false }): Bitmap {
            val (w0, h0) = inputSize(photo.width, photo.height)
            val src = if (w0 != photo.width || h0 != photo.height) Bitmap.createScaledBitmap(photo, w0, h0, true) else photo
            // Pictures smaller than a tile are padded by repeating their edge.
            val w = maxOf(w0, TILE)
            val h = maxOf(h0, TILE)
            val pixels = IntArray(w * h)
            val row = IntArray(w0)
            for (y in 0 until h) {
                src.getPixels(row, 0, w0, 0, minOf(y, h0 - 1), w0, 1)
                for (x in 0 until w) pixels[y * w + x] = row[minOf(x, w0 - 1)]
            }
            val outW = w * SCALE
            val result = IntArray(outW * h * SCALE)
            val xs = starts(w)
            val ys = starts(h)
            val total = xs.size * ys.size
            var done = 0
            val tileOut = TILE * SCALE
            for ((yi, ty) in ys.withIndex()) {
                val (keepY0, keepY1) = keep(ys, yi, h)
                for ((xi, tx) in xs.withIndex()) {
                    if (stop()) throw kotlinx.coroutines.CancellationException("stopped")
                    input.rewind()
                    for (y in 0 until TILE) for (x in 0 until TILE) {
                        val p = pixels[(ty + y) * w + tx + x]
                        input.putFloat(((p shr 16) and 0xFF) / 255f)
                        input.putFloat(((p shr 8) and 0xFF) / 255f)
                        input.putFloat((p and 0xFF) / 255f)
                    }
                    input.rewind()
                    output.rewind()
                    interpreter.run(input, output)
                    val values = output.rewind().let { output.asFloatBuffer() }
                    val (keepX0, keepX1) = keep(xs, xi, w)
                    for (y in (keepY0 - ty) * SCALE until (keepY1 - ty) * SCALE) {
                        val outRow = (ty * SCALE + y) * outW
                        for (x in (keepX0 - tx) * SCALE until (keepX1 - tx) * SCALE) {
                            val i = (y * tileOut + x) * 3
                            val r = (values.get(i) * 255f + 0.5f).toInt().coerceIn(0, 255)
                            val g = (values.get(i + 1) * 255f + 0.5f).toInt().coerceIn(0, 255)
                            val b = (values.get(i + 2) * 255f + 0.5f).toInt().coerceIn(0, 255)
                            result[outRow + tx * SCALE + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                    done++
                    onProgress(done / total.toFloat())
                }
            }
            if (src !== photo) src.recycle()
            val full = Bitmap.createBitmap(result, outW, h * SCALE, Bitmap.Config.ARGB_8888)
            // Cut the padding back off.
            val out = if (w == w0 && h == h0) full
            else Bitmap.createBitmap(full, 0, 0, w0 * SCALE, h0 * SCALE).also { if (it !== full) full.recycle() }
            if (!photo.hasAlpha()) return out
            // See-through parts (a cut-out person, a sticker) stay see-through.
            val alpha = Bitmap.createScaledBitmap(photo, out.width, out.height, true)
            val a = IntArray(out.width)
            val c = IntArray(out.width)
            val mutable = if (out.isMutable) out else out.copy(Bitmap.Config.ARGB_8888, true).also { out.recycle() }
            for (y in 0 until mutable.height) {
                alpha.getPixels(a, 0, mutable.width, 0, y, mutable.width, 1)
                mutable.getPixels(c, 0, mutable.width, 0, y, mutable.width, 1)
                for (x in c.indices) c[x] = (a[x] and 0xFF000000.toInt()) or (c[x] and 0xFFFFFF)
                mutable.setPixels(c, 0, mutable.width, 0, y, mutable.width, 1)
            }
            alpha.recycle()
            return mutable
        }

        override fun close() = interpreter.close()
    }
}
