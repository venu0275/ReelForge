package com.reelforge.editor.engine

import androidx.media3.transformer.ExportException

/**
 * Notices a render that has stopped moving. Media3's own 10-second muxer timeout is turned off
 * (see ExportManager.transformerBuilder) because it stops healthy long exports, so this takes
 * its place with a much longer limit: only a render whose progress has not changed at all for
 * [limitMs] counts as stuck. Call [check] each time progress is polled.
 */
class StallGuard(
    private val limitMs: Long = DEFAULT_LIMIT_MS,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private var lastProgress = Int.MIN_VALUE
    private var lastChange = clock()

    /** [progress] is the percent done, or -1 when Media3 cannot say. True when stuck. */
    fun check(progress: Int): Boolean {
        val now = clock()
        if (progress != lastProgress) {
            lastProgress = progress
            lastChange = now
            return false
        }
        return now - lastChange > limitMs
    }

    companion object {
        // Long enough for 1% of a 45-minute 4K video on a slow phone.
        const val DEFAULT_LIMIT_MS = 5 * 60_000L
    }
}

/** Which export failures are worth another try, and how to explain a failure to the user. */
object ExportErrors {

    const val STALLED_MESSAGE =
        "The export stopped making progress. Try a lower resolution, or close other apps and try again.\n(stalled)"

    const val OUT_OF_MEMORY_MESSAGE =
        "The phone ran out of memory making this video. Try a lower resolution or fewer text and sticker layers.\n(out of memory)"

    fun notEnoughSpace(neededMb: Long, freeMb: Long): String =
        "Not enough free space. This export needs about $neededMb MB but only $freeMb MB is free. " +
            "Free up some space or choose a lower resolution."

    /**
     * Failures that come from the phone's encoders, decoders or muxer, or from frame processing.
     * These often pass on a second try, especially with simpler settings. Missing or unreadable
     * files and formats the phone cannot read are left out: trying again would fail the same way.
     */
    fun isRetryable(code: Int): Boolean = when (code) {
        ExportException.ERROR_CODE_MUXING_TIMEOUT,
        ExportException.ERROR_CODE_MUXING_FAILED,
        ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
        ExportException.ERROR_CODE_ENCODING_FAILED,
        ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
        ExportException.ERROR_CODE_DECODER_INIT_FAILED,
        ExportException.ERROR_CODE_DECODING_FAILED,
        ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
        ExportException.ERROR_CODE_AUDIO_PROCESSING_FAILED,
        ExportException.ERROR_CODE_UNSPECIFIED,
        ExportException.ERROR_CODE_FAILED_RUNTIME_CHECK -> true
        else -> false
    }

    /** A plain-language message, with the technical code kept at the end for bug reports. */
    fun message(e: ExportException): String {
        val text = when (e.errorCode) {
            ExportException.ERROR_CODE_MUXING_TIMEOUT,
            ExportException.ERROR_CODE_MUXING_FAILED ->
                "The video could not be written. Try a lower resolution, or close other apps and try again."
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
            ExportException.ERROR_CODE_ENCODING_FAILED,
            ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED ->
                "This phone could not encode the video at these settings. Try a lower resolution or 30 fps."
            ExportException.ERROR_CODE_DECODER_INIT_FAILED,
            ExportException.ERROR_CODE_DECODING_FAILED ->
                "One of the clips could not be read. Close other apps that play video and try again."
            ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                "One of the clips is in a format this phone cannot read. Replace that clip and try again."
            ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
            ExportException.ERROR_CODE_IO_NO_PERMISSION ->
                "A clip or photo is missing or no longer allowed. Replace it and try again."
            ExportException.ERROR_CODE_IO_UNSPECIFIED ->
                "A file could not be read or saved. Check that there is free space on the phone."
            else ->
                "Something went wrong while making the video. Please try again."
        }
        return "$text\n(${e.errorCodeName})"
    }
}
