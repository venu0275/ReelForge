package com.reelforge.editor.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.Crop
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import com.reelforge.editor.model.Background
import com.reelforge.editor.model.Clip
import com.reelforge.editor.model.FilterPreset
import com.reelforge.editor.model.OverlayAnim
import com.reelforge.editor.model.Project
import com.reelforge.editor.model.TextFont
import com.reelforge.editor.model.TextItem
import com.reelforge.editor.model.TransitionStyle
import java.nio.ByteBuffer
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
    private val opacity: Float = 1f,
) : RgbMatrix {
    private var baseUs = C.TIME_UNSET

    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        if (baseUs == C.TIME_UNSET) baseUs = presentationTimeUs
        val t = presentationTimeUs - baseUs
        val a = fadeAlpha(t, durationUs, fadeInUs, fadeOutUs) * opacity.coerceIn(0f, 1f)
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

/** Alpha, scale and vertical offset (in frame units, -1..1) of an overlay at one moment. */
data class AnimState(val alpha: Float, val scale: Float, val dy: Float)

private const val ANIM_MS = 400f

/** Entry / exit animation state at [tMs] into an overlay that lasts [durationMs]. */
fun overlayAnim(tMs: Long, durationMs: Long, animIn: OverlayAnim, animOut: OverlayAnim): AnimState {
    val window = minOf(ANIM_MS, durationMs / 2f).coerceAtLeast(1f)
    var alpha = 1f
    var scale = 1f
    var dy = 0f
    fun blend(anim: OverlayAnim, progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        when (anim) {
            OverlayAnim.NONE -> Unit
            OverlayAnim.FADE -> alpha *= p
            OverlayAnim.SLIDE -> {
                alpha *= p
                dy -= (1f - p) * 0.25f
            }
            OverlayAnim.POP -> {
                alpha *= p
                scale *= 0.3f + 0.7f * p
            }
        }
    }
    if (tMs < window) blend(animIn, tMs / window)
    val remaining = durationMs - tMs
    if (remaining < window) blend(animOut, remaining / window)
    return AnimState(alpha, scale, dy)
}

/**
 * A bitmap (text, sticker or image layer) shown during [startUs, endUs), measured from the first
 * frame of the clip it is attached to. The range may start before 0 or end after the clip.
 */
@OptIn(UnstableApi::class)
class TimedBitmapOverlay(
    private val bitmap: Bitmap,
    private val startUs: Long,
    private val endUs: Long,
    private val x: Float,
    private val y: Float,
    private val opacity: Float,
    private val animIn: OverlayAnim,
    private val animOut: OverlayAnim,
    private val x2: Float = x,
    private val y2: Float = y,
) : BitmapOverlay() {
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
        if (t < startUs || t >= endUs) return hidden
        val anim = overlayAnim((t - startUs) / 1000, (endUs - startUs) / 1000, animIn, animOut)
        val progress = ((t - startUs).toFloat() / (endUs - startUs).coerceAtLeast(1)).coerceIn(0f, 1f)
        val px = (x + (x2 - x) * progress).coerceIn(-1f, 1f)
        val py = (y + (y2 - y) * progress).coerceIn(-1f, 1f)
        return OverlaySettings.Builder()
            .setBackgroundFrameAnchor(px, (py + anim.dy).coerceIn(-1f, 1f))
            .setOverlayFrameAnchor(px, py)
            .setScale(anim.scale, anim.scale)
            .setAlphaScale((anim.alpha * opacity).coerceIn(0f, 1f))
            .build()
    }
}

/** Fades a clip's sound in and out, matching its picture fade. Expects 16-bit PCM. */
@OptIn(UnstableApi::class)
class AudioFadeProcessor(
    private val durationUs: Long,
    private val fadeInUs: Long,
    private val fadeOutUs: Long,
) : BaseAudioProcessor() {
    private var frames = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        val sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(1)
        val out = replaceOutputBuffer(inputBuffer.remaining())
        while (inputBuffer.remaining() >= 2 * channels) {
            val tUs = frames * 1_000_000L / sampleRate
            val gain = fadeAlpha(tUs, durationUs, fadeInUs, fadeOutUs)
            repeat(channels) {
                out.putShort((inputBuffer.short * gain).toInt().toShort())
            }
            frames++
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    override fun onFlush() {
        frames = 0
    }

    override fun onReset() {
        frames = 0
    }
}

/** Slide / zoom motion during a clip's fade-in and fade-out time (export only). */
@OptIn(UnstableApi::class)
class TransitionMotion(
    private val durationUs: Long,
    private val inUs: Long,
    private val outUs: Long,
    private val style: TransitionStyle,
) : MatrixTransformation {
    private var baseUs = C.TIME_UNSET

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        if (baseUs == C.TIME_UNSET) baseUs = presentationTimeUs
        val t = presentationTimeUs - baseUs
        val enter = if (inUs > 0 && t < inUs) (t.toFloat() / inUs).coerceIn(0f, 1f) else 1f
        val leave = if (outUs > 0 && t > durationUs - outUs) {
            ((durationUs - t).toFloat() / outUs).coerceIn(0f, 1f)
        } else 1f
        val matrix = Matrix()
        when (style) {
            TransitionStyle.FADE -> Unit
            // Comes in from the right, leaves to the left (frame is 2 units wide).
            TransitionStyle.SLIDE -> matrix.setTranslate((1f - enter) * 2f - (1f - leave) * 2f, 0f)
            TransitionStyle.ZOOM -> {
                val scale = 1f + (1f - enter) * 0.35f + (1f - leave) * 0.35f
                matrix.setScale(scale, scale)
            }
        }
        return matrix
    }
}

/**
 * Fits the clip inside the output frame and fills the bars around it with a colour or a blurred,
 * enlarged copy of the clip.
 */
@OptIn(UnstableApi::class)
class BackgroundFitEffect(
    private val outWidth: Int,
    private val outHeight: Int,
    private val blur: Boolean,
    private val color: Int,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        BackgroundFitProgram(outWidth, outHeight, blur, color)
}

@OptIn(UnstableApi::class)
private class BackgroundFitProgram(
    private val outWidth: Int,
    private val outHeight: Int,
    private val blur: Boolean,
    color: Int,
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 1) {

    private val program: GlProgram = try {
        GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }
    private val rgba = floatArrayOf(
        ((color shr 16) and 0xFF) / 255f,
        ((color shr 8) and 0xFF) / 255f,
        (color and 0xFF) / 255f,
        1f,
    )
    private val fg = floatArrayOf(1f, 1f)
    private val bg = floatArrayOf(1f, 1f)

    init {
        program.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val inAspect = inputWidth.toFloat() / inputHeight
        val outAspect = outWidth.toFloat() / outHeight
        if (inAspect > outAspect) {
            fg[0] = 1f; fg[1] = outAspect / inAspect
            bg[0] = outAspect / inAspect; bg[1] = 1f
        } else {
            fg[0] = inAspect / outAspect; fg[1] = 1f
            bg[0] = 1f; bg[1] = inAspect / outAspect
        }
        return Size(outWidth, outHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            program.setFloatsUniform("uFg", fg)
            program.setFloatsUniform("uBg", bg)
            program.setFloatsUniform("uColor", rgba)
            program.setFloatUniform("uBlur", if (blur) 1f else 0f)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private companion object {
        const val VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            varying vec2 vPos;
            void main() {
              gl_Position = aFramePosition;
              vPos = aFramePosition.xy;
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexSampler;
            uniform vec2 uFg;
            uniform vec2 uBg;
            uniform vec4 uColor;
            uniform float uBlur;
            varying vec2 vPos;
            void main() {
              vec2 p = vPos / uFg;
              if (abs(p.x) <= 1.0 && abs(p.y) <= 1.0) {
                gl_FragColor = texture2D(uTexSampler, p * 0.5 + 0.5);
              } else if (uBlur > 0.5) {
                vec2 uv = vPos * uBg * 0.5 + 0.5;
                vec3 sum = vec3(0.0);
                for (int i = -3; i <= 3; i++) {
                  for (int j = -3; j <= 3; j++) {
                    sum += texture2D(uTexSampler, uv + vec2(float(i), float(j)) * 0.012).rgb;
                  }
                }
                gl_FragColor = vec4(sum / 49.0 * 0.7, 1.0);
              } else {
                gl_FragColor = uColor;
              }
            }
        """
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

    /** Mirror, rotation and centre zoom. */
    fun geometry(clip: Clip): List<Effect> {
        val out = mutableListOf<Effect>()
        if (clip.rotation % 360 != 0 || clip.flipH) {
            out += ScaleAndRotateTransformation.Builder()
                .setScale(if (clip.flipH) -1f else 1f, 1f)
                .setRotationDegrees(-clip.rotation.toFloat()) // model is clockwise
                .build()
        }
        if (clip.zoom > 1.01f) {
            val edge = 1f / clip.zoom.coerceIn(1f, 4f)
            out += Crop(-edge, edge, -edge, edge)
        }
        return out
    }

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
    /** True when the bars around this clip are filled with a colour or blur. */
    fun usesBackground(clip: Clip, project: Project): Boolean =
        !clip.fill && project.background != Background.BLACK

    fun background(project: Project, width: Int, height: Int): Effect =
        BackgroundFitEffect(width, height, project.background == Background.BLUR, project.backgroundColor)

    fun preview(clip: Clip, project: Project): List<Effect> {
        val out = (geometry(clip) + color(clip)).toMutableList()
        if (usesBackground(clip, project)) {
            val (w, h) = ExportManager.outputSize(project.aspect, 540)
            out += background(project, w, h)
        }
        return out
    }

    /** A string that changes whenever [preview] would return something different. */
    fun previewSignature(clip: Clip, project: Project): String =
        "${clip.rotation % 360}|${clip.filter}|${clip.brightness}|${clip.contrast}|" +
            "${clip.saturation}|${clip.flipH}|${if (clip.zoom > 1.01f) clip.zoom else 1.0f}|" +
            if (usesBackground(clip, project)) {
                "${project.background}|${project.backgroundColor}|${project.aspect}"
            } else "-"

    /** Renders a text item to a transparent bitmap sized for a frame [frameHeight] pixels tall. */
    fun renderText(item: TextItem, frameHeight: Int): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = item.color
            textSize = max(8f, item.size * frameHeight)
            val custom = item.fontPath?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
            typeface = custom ?: Typeface.create(
                when (item.font) {
                    TextFont.SANS -> Typeface.SANS_SERIF
                    TextFont.SERIF -> Typeface.SERIF
                    TextFont.MONO -> Typeface.MONOSPACE
                },
                Typeface.BOLD,
            )
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
        if (item.background) {
            val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), pad, pad, box)
        }
        var baseline = pad - paint.fontMetrics.ascent
        val stroke = if (item.outline) {
            Paint(paint).apply {
                clearShadowLayer()
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
                strokeWidth = textSize * 0.12f
                color = 0xFF000000.toInt()
            }
        } else null
        for (line in lines) {
            if (stroke != null) canvas.drawText(line, width / 2f, baseline, stroke)
            canvas.drawText(line, width / 2f, baseline, paint)
            baseline += lineHeight
        }
        return bitmap
    }
}
