package com.reelforge.editor.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import com.reelforge.editor.model.Clip
import com.reelforge.editor.model.FilterPreset
import com.reelforge.editor.model.TextItem
import kotlin.math.ceil
import kotlin.math.max

/** A fixed 4x4 colour matrix (column-major), e.g. sepia. */
@OptIn(UnstableApi::class)
class ConstantRgbMatrix(private val matrix: FloatArray) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = matrix
}

/**
 * Fades a clip from/to black. Time is measured from the first frame this effect sees, so it
 * does not depend on how the pipeline offsets timestamps.
 */
@OptIn(UnstableApi::class)
class FadeEffect(
    private val durationUs: Long,
    private val fadeInUs: Long,
    private val fadeOutUs: Long,
) : RgbMatrix {
    private var baseUs = C.TIME_UNSET

    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        if (baseUs == C.TIME_UNSET) baseUs = presentationTimeUs
        val t = presentationTimeUs - baseUs
        val a = fadeAlpha(t, durationUs, fadeInUs, fadeOutUs)
        return floatArrayOf(
            a, 0f, 0f, 0f,
            0f, a, 0f, 0f,
            0f, 0f, a, 0f,
            0f, 0f, 0f, 1f,
        )
    }
}

/** Brightness multiplier (0..1) at time [t] inside a clip. Units only need to be consistent. */
fun fadeAlpha(t: Long, duration: Long, fadeIn: Long, fadeOut: Long): Float {
    var a = 1f
    if (fadeIn > 0 && t < fadeIn) a = minOf(a, t.toFloat() / fadeIn)
    if (fadeOut > 0 && t > duration - fadeOut) a = minOf(a, (duration - t).toFloat() / fadeOut)
    return a.coerceIn(0f, 1f)
}

/** A text bitmap that is only visible in [startUs, endUs) measured from the clip's first frame. */
@OptIn(UnstableApi::class)
class TimedTextOverlay(
    private val bitmap: Bitmap,
    private val startUs: Long,
    private val endUs: Long,
    x: Float,
    y: Float,
) : BitmapOverlay() {
    private val visible: OverlaySettings = OverlaySettings.Builder()
        .setBackgroundFrameAnchor(x, y)
        .setOverlayFrameAnchor(x, y)
        .build()
    private val hidden: OverlaySettings = OverlaySettings.Builder().setAlphaScale(0f).build()
    private var baseUs = C.TIME_UNSET

    private fun relative(presentationTimeUs: Long): Long {
        if (baseUs == C.TIME_UNSET) baseUs = presentationTimeUs
        return presentationTimeUs - baseUs
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        relative(presentationTimeUs)
        return bitmap
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val t = relative(presentationTimeUs)
        return if (t in startUs until endUs) visible else hidden
    }
}

@OptIn(UnstableApi::class)
object EffectFactory {

    private val SEPIA = floatArrayOf(
        0.393f, 0.349f, 0.272f, 0f,
        0.769f, 0.686f, 0.534f, 0f,
        0.189f, 0.168f, 0.131f, 0f,
        0f, 0f, 0f, 1f,
    )

    fun rotation(clip: Clip): List<Effect> =
        if (clip.rotation % 360 == 0) emptyList()
        else listOf(
            ScaleAndRotateTransformation.Builder()
                .setRotationDegrees(-clip.rotation.toFloat()) // model is clockwise
                .build()
        )

    fun color(clip: Clip): List<Effect> {
        val out = mutableListOf<Effect>()
        when (clip.filter) {
            FilterPreset.NONE -> Unit
            FilterPreset.GRAYSCALE -> out += RgbFilter.createGrayscaleFilter()
            FilterPreset.INVERT -> out += RgbFilter.createInvertedFilter()
            FilterPreset.SEPIA -> out += ConstantRgbMatrix(SEPIA)
            FilterPreset.WARM -> out += RgbAdjustment.Builder()
                .setRedScale(1.15f).setBlueScale(0.85f).build()
            FilterPreset.COOL -> out += RgbAdjustment.Builder()
                .setRedScale(0.85f).setBlueScale(1.15f).build()
            FilterPreset.VIVID -> {
                out += HslAdjustment.Builder().adjustSaturation(40f).build()
                out += Contrast(0.15f)
            }
            FilterPreset.FADED -> {
                out += HslAdjustment.Builder().adjustSaturation(-35f).build()
                out += Contrast(-0.15f)
            }
        }
        if (clip.brightness != 0f) out += Brightness(clip.brightness.coerceIn(-1f, 1f))
        if (clip.contrast != 0f) out += Contrast(clip.contrast.coerceIn(-1f, 1f))
        if (clip.saturation != 0f) {
            out += HslAdjustment.Builder()
                .adjustSaturation(clip.saturation.coerceIn(-100f, 100f))
                .build()
        }
        return out
    }

    /** Effects used for the live preview player (rotation + colour only). */
    fun preview(clip: Clip): List<Effect> = rotation(clip) + color(clip)

    const val NO_EFFECTS = "0|NONE|0.0|0.0|0.0"

    /** A string that changes whenever [preview] would return something different. */
    fun previewSignature(clip: Clip): String =
        "${clip.rotation % 360}|${clip.filter}|${clip.brightness}|${clip.contrast}|${clip.saturation}"

    /** Renders a text item to a transparent bitmap sized for a frame [frameHeight] pixels tall. */
    fun renderText(item: TextItem, frameHeight: Int): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = item.color
            textSize = max(8f, item.size * frameHeight)
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            setShadowLayer(textSize * 0.08f, 0f, textSize * 0.04f, 0xB0000000.toInt())
        }
        val lines = item.text.ifBlank { " " }.split("\n")
        val pad = paint.textSize * 0.25f
        val lineHeight = paint.fontSpacing
        val width = ceil((lines.maxOf { paint.measureText(it) }) + pad * 2).toInt().coerceAtLeast(2)
        val height = ceil(lineHeight * lines.size + pad * 2).toInt().coerceAtLeast(2)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        var baseline = pad - paint.fontMetrics.ascent
        for (line in lines) {
            canvas.drawText(line, width / 2f, baseline, paint)
            baseline += lineHeight
        }
        return bitmap
    }
}
