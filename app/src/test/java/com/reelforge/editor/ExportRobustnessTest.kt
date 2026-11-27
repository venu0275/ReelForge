package com.reelforge.editor

import androidx.media3.transformer.ExportException
import com.reelforge.editor.engine.ExportErrors
import com.reelforge.editor.engine.ExportManager
import com.reelforge.editor.engine.StallGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Retry rules, stall detection and the free-space estimate used by video export. */
class ExportRobustnessTest {

    @Test
    fun muxerEncoderAndDecoderFailures_areRetried() {
        listOf(
            ExportException.ERROR_CODE_MUXING_TIMEOUT,
            ExportException.ERROR_CODE_MUXING_FAILED,
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
            ExportException.ERROR_CODE_ENCODING_FAILED,
            ExportException.ERROR_CODE_DECODER_INIT_FAILED,
            ExportException.ERROR_CODE_DECODING_FAILED,
            ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
        ).forEach { assertTrue("code $it should be retried", ExportErrors.isRetryable(it)) }
    }

    @Test
    fun missingFilesAndUnreadableFormats_areNotRetried() {
        listOf(
            ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
            ExportException.ERROR_CODE_IO_NO_PERMISSION,
            ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        ).forEach { assertFalse("code $it should not be retried", ExportErrors.isRetryable(it)) }
    }

    @Test
    fun stallGuard_onlyTripsAfterTheLimitWithNoProgress() {
        var now = 0L
        val guard = StallGuard(limitMs = 1_000, clock = { now })
        assertFalse(guard.check(0))
        now = 900; assertFalse("not stuck yet", guard.check(0))
        now = 1_200; assertFalse("progress moved, so the timer restarts", guard.check(1))
        now = 2_100; assertFalse("within the limit of the last change", guard.check(1))
        now = 2_300; assertTrue("no progress for longer than the limit", guard.check(1))
    }

    @Test
    fun stallGuard_countsUnknownProgressAsNoProgress() {
        var now = 0L
        val guard = StallGuard(limitMs = 1_000, clock = { now })
        assertFalse(guard.check(-1))
        now = 1_500
        assertTrue("an export that never reports progress is stuck too", guard.check(-1))
    }

    @Test
    fun stallGuard_slowButMovingExportIsNeverStuck() {
        var now = 0L
        val guard = StallGuard(limitMs = 1_000, clock = { now })
        for (percent in 0..100) {
            now += 900 // one percent just inside the limit each time
            assertFalse("percent $percent", guard.check(percent))
        }
    }

    @Test
    fun bytesNeeded_coversTheFileTwiceWithMargin() {
        val bitrate = 8_000_000
        val oneMinute = ExportManager.bytesNeeded(bitrate, 60_000)
        val fileOnly = (bitrate + 192_000L) * 60 / 8
        assertTrue("needs room for the cache file and the gallery copy", oneMinute >= fileOnly * 2)
        assertTrue("about double the size, not wildly more", oneMinute < fileOnly * 3)
        assertTrue("longer videos need more", ExportManager.bytesNeeded(bitrate, 120_000) > oneMinute)
    }

    @Test
    fun muxerTimeout_isLongerThanMedia3sDefaultButNeverOff() {
        // Off (C.TIME_UNSET) makes exports hang forever on chipsets where the codec gets stuck.
        assertTrue(ExportManager.MUXER_TIMEOUT_MS > 10_000)
        assertTrue(ExportManager.MUXER_TIMEOUT_MS <= 120_000)
    }
}
