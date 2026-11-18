package com.reelforge.editor.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import com.reelforge.editor.data.AssetDownloader
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * Background removal. It uses Google's "selfie multiclass" segmentation model (Apache 2.0, about
 * 16 MB, downloaded once on first use), run with the plain LiteRT runtime. Pictures and videos
 * never leave the phone.
 */
object CutOut {
    const val MODEL_URL =
        "https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite"
    const val MODEL_MB = 16

    /** The colour put behind the person in a video, then keyed out like a green screen. */
    const val KEY_GREEN = 0xFF00FF00.toInt()

    /** Longest stretch of video that is processed (it takes roughly real time or longer). */
    const val MAX_VIDEO_MS = 60_000L

    fun modelFile(context: Context) =
        File(File(context.filesDir, "models").apply { mkdirs() }, "selfie_multiclass_256x256.tflite")

    fun hasModel(context: Context) = modelFile(context).length() > 1_000_000

    fun downloadModel(context: Context, onProgress: (Int) -> Unit) =
        AssetDownloader.download(MODEL_URL, modelFile(context), onProgress)

    /** Smooth 0..1 step between [edge0] and [edge1]: firm edges with a little softness. */
    fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    /**
     * Turns the model's six class scores per pixel (background, hair, body, face, clothes, other)
     * into the chance that the pixel shows the person. Scores that are not yet probabilities
     * (that do not add up to 1) are turned into probabilities first.
     */
    fun personChance(scores: FloatArray): Float {
        val sum = scores.sum()
        if (scores.all { it in 0f..1f } && kotlin.math.abs(sum - 1f) < 0.05f) return (1f - scores[0]).coerceIn(0f, 1f)
        val top = scores.max()
        val e = scores.map { exp((it - top).toDouble()) }
        return (1.0 - e[0] / e.sum()).toFloat().coerceIn(0f, 1f)
    }

    /** A 3×3 blur of a [side]×[side] mask, which softens jagged edges. */
    fun soften(mask: FloatArray, side: Int): FloatArray {
        val out = FloatArray(mask.size)
        for (y in 0 until side) for (x in 0 until side) {
            var sum = 0f
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val yy = y + dy
                val xx = x + dx
                if (yy in 0 until side && xx in 0 until side) {
                    sum += mask[yy * side + xx]
                    n++
                }
            }
            out[y * side + x] = sum / n
        }
        return out
    }
}

/** Finds the person in a picture. Not thread-safe: use one per worker. */
class PersonSegmenter(model: File) : AutoCloseable {
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))
    private val input = ByteBuffer.allocateDirect(SIDE * SIDE * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(SIDE * SIDE * CLASSES * 4).order(ByteOrder.nativeOrder())
    private val pixels = IntArray(SIDE * SIDE)
    private val scores = FloatArray(CLASSES)

    /** For each pixel of a 256×256 grid over [picture], the chance (0..1) that it shows a person. */
    fun personMask(picture: Bitmap): FloatArray {
        val small = Bitmap.createScaledBitmap(picture, SIDE, SIDE, true)
        small.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE)
        if (small !== picture) small.recycle()
        input.rewind()
        for (p in pixels) {
            // The model expects RGB scaled to 0..1.
            input.putFloat(((p shr 16) and 0xFF) / 255f)
            input.putFloat(((p shr 8) and 0xFF) / 255f)
            input.putFloat((p and 0xFF) / 255f)
        }
        input.rewind()
        output.rewind()
        interpreter.run(input, output)
        output.rewind()
        val values = output.asFloatBuffer()
        val mask = FloatArray(SIDE * SIDE)
        for (i in mask.indices) {
            for (c in 0 until CLASSES) scores[c] = values.get(i * CLASSES + c)
            mask[i] = CutOut.personChance(scores)
        }
        return CutOut.soften(mask, SIDE)
    }

    /** A 256×256 picture whose alpha follows [keep] (1 = keep the pixel), in [color]. */
    fun maskBitmap(mask: FloatArray, color: Int, keepPerson: Boolean): Bitmap {
        val rgb = color and 0x00FFFFFF
        val colors = IntArray(mask.size) { i ->
            val person = CutOut.smoothStep(0.35f, 0.65f, mask[i])
            val alpha = ((if (keepPerson) person else 1f - person) * 255).toInt().coerceIn(0, 255)
            (alpha shl 24) or rgb
        }
        return Bitmap.createBitmap(colors, SIDE, SIDE, Bitmap.Config.ARGB_8888)
    }

    /** [picture] with everything but the person made transparent. */
    fun cutOut(picture: Bitmap): Bitmap {
        val mask = maskBitmap(personMask(picture), 0xFF000000.toInt(), keepPerson = true)
        val scaled = Bitmap.createScaledBitmap(mask, picture.width, picture.height, true)
        mask.recycle()
        val result = picture.copy(Bitmap.Config.ARGB_8888, true)
        result.setHasAlpha(true)
        Canvas(result).drawBitmap(scaled, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        scaled.recycle()
        return result
    }

    override fun close() = interpreter.close()

    companion object {
        const val SIDE = 256
        const val CLASSES = 6
    }
}

/**
 * Paints the background of each video frame bright green, leaving the person, so the video can
 * then be keyed like a green-screen video. The frame is read from the same file at the same time
 * and looked at by [segmenter].
 */
@OptIn(UnstableApi::class)
class GreenBackgroundOverlay(
    context: Context,
    uri: Uri,
    private val sourceStartMs: Long,
    private val segmenter: PersonSegmenter,
) : BitmapOverlay(), AutoCloseable {
    private val retriever = MediaMetadataRetriever().apply { setDataSource(context, uri) }
    private val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
        ?.toIntOrNull() ?: 0
    private var frameSize: Size? = null
    private var firstUs = Long.MIN_VALUE
    private var last: Bitmap? = null
    private var lastUs = Long.MIN_VALUE

    override fun configure(videoSize: Size) {
        frameSize = videoSize
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        if (presentationTimeUs == lastUs) last?.let { return it }
        lastUs = presentationTimeUs
        if (firstUs == Long.MIN_VALUE) firstUs = presentationTimeUs
        val sourceUs = sourceStartMs * 1000 + (presentationTimeUs - firstUs)
        val size = frameSize
        val frame = retriever.getScaledFrameAtTime(sourceUs, MediaMetadataRetriever.OPTION_CLOSEST, 512, 512)
            ?: return last ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        var mask = segmenter.maskBitmap(segmenter.personMask(frame), CutOut.KEY_GREEN, keepPerson = false)
        // The retriever gives the frame as it is shown; if the video pipeline still has it turned
        // the other way, turn the mask to match.
        if (size != null && rotation % 180 != 0 && (frame.width > frame.height) != (size.width > size.height)) {
            val turned = Bitmap.createBitmap(
                mask, 0, 0, mask.width, mask.height, Matrix().apply { postRotate(-rotation.toFloat()) }, true,
            )
            mask.recycle()
            mask = turned
        }
        frame.recycle()
        val w = size?.width ?: mask.width
        val h = size?.height ?: mask.height
        val full = Bitmap.createScaledBitmap(mask, w, h, true)
        if (full !== mask) mask.recycle()
        last = full
        return full
    }

    override fun close() {
        runCatching { retriever.release() }
    }
}
