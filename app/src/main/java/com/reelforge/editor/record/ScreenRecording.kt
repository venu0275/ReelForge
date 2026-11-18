package com.reelforge.editor.record

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What part of the screen goes into the recording. */
enum class RecordArea(val label: String) { SCREEN("Whole screen"), PREVIEW("Preview only") }

/** Where a finished recording goes in the project. */
enum class RecordTarget(val label: String) { CLIP("Add as clip"), LAYER("Add as video layer"), NONE("Don't add") }

/** The choices on the Record screen settings. */
data class RecordSettings(
    val area: RecordArea = RecordArea.SCREEN,
    val microphone: Boolean = false,
    val shortSide: Int = 1080,
    val fps: Int = 30,
    val countdownSec: Int = 3,
    val target: RecordTarget = RecordTarget.CLIP,
    val saveToGallery: Boolean = true,
) {
    companion object {
        val SIDES = listOf(480, 720, 1080)
        val FPS = listOf(30, 60)
        val COUNTDOWNS = listOf(0, 3, 5)
    }
}

/**
 * A part of the screen as fractions of its width and height (0..1, top-left origin), used to crop
 * a "preview only" recording.
 */
data class CropBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val isValid: Boolean get() = right - left > 0.05f && bottom - top > 0.05f

    /** The same box in OpenGL coordinates (-1..1, y up), as left, right, bottom, top. */
    fun toNdc(): FloatArray = floatArrayOf(left * 2 - 1, right * 2 - 1, 1 - bottom * 2, 1 - top * 2)

    /** The size in pixels of this box cut from a [width] x [height] video, rounded to even numbers. */
    fun outputSize(width: Int, height: Int): Pair<Int, Int> =
        even(width * (right - left)) to even(height * (bottom - top))
}

/** Rounds to the nearest even number of at least 2 (video encoders need even sizes). */
fun even(value: Float): Int = max(2, (value / 2f).roundToInt() * 2)

/**
 * The recording size for a [screenWidth] x [screenHeight] screen whose shorter side is scaled to
 * [shortSide] (never enlarged), keeping its shape. Both sides are even.
 */
fun recordingSize(screenWidth: Int, screenHeight: Int, shortSide: Int): Pair<Int, Int> {
    val short = min(screenWidth, screenHeight).coerceAtLeast(1)
    val k = min(1f, shortSide.toFloat() / short)
    return even(screenWidth * k) to even(screenHeight * k)
}

/** A sensible encoder bitrate for the size and frame rate, in bits per second. */
fun recordingBitrate(width: Int, height: Int, fps: Int): Int =
    (width.toLong() * height * fps / 10).toInt().coerceIn(2_000_000, 16_000_000)

/**
 * The shared state of screen recording, read by the screen and written by the recording service.
 * Changes always happen on the main thread.
 */
object ScreenRecording {
    enum class Status { IDLE, COUNTDOWN, RECORDING, PAUSED, SAVING }

    var status by mutableStateOf(Status.IDLE)
    var countdown by mutableIntStateOf(0)
    var elapsedMs by mutableLongStateOf(0L)
    var error by mutableStateOf<String?>(null)

    /** A finished recording waiting to be put into the project, with its size and crop. */
    var finished by mutableStateOf<File?>(null)
    var finishedWidth = 0
    var finishedHeight = 0
    var crop: CropBox? = null

    /** The full screen size in pixels and its density, measured by the screen just before starting. */
    var screenWidth = 0
    var screenHeight = 0
    var screenDpi = 0

    var settings by mutableStateOf(RecordSettings())
        private set

    val busy: Boolean get() = status != Status.IDLE

    fun load(context: Context) {
        val p = context.getSharedPreferences("record", Context.MODE_PRIVATE)
        val d = RecordSettings()
        settings = RecordSettings(
            area = runCatching { RecordArea.valueOf(p.getString("area", d.area.name)!!) }.getOrDefault(d.area),
            microphone = p.getBoolean("microphone", d.microphone),
            shortSide = p.getInt("shortSide", d.shortSide).takeIf { it in RecordSettings.SIDES } ?: d.shortSide,
            fps = p.getInt("fps", d.fps).takeIf { it in RecordSettings.FPS } ?: d.fps,
            countdownSec = p.getInt("countdown", d.countdownSec).takeIf { it in RecordSettings.COUNTDOWNS } ?: d.countdownSec,
            target = runCatching { RecordTarget.valueOf(p.getString("target", d.target.name)!!) }.getOrDefault(d.target),
            saveToGallery = p.getBoolean("saveToGallery", d.saveToGallery),
        )
    }

    fun update(context: Context, block: (RecordSettings) -> RecordSettings) {
        settings = block(settings)
        context.getSharedPreferences("record", Context.MODE_PRIVATE).edit()
            .putString("area", settings.area.name)
            .putBoolean("microphone", settings.microphone)
            .putInt("shortSide", settings.shortSide)
            .putInt("fps", settings.fps)
            .putInt("countdown", settings.countdownSec)
            .putString("target", settings.target.name)
            .putBoolean("saveToGallery", settings.saveToGallery)
            .apply()
    }
}
