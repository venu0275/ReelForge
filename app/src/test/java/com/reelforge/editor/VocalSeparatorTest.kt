package com.reelforge.editor

import com.reelforge.editor.engine.Fft
import com.reelforge.editor.engine.SpleeterStft
import com.reelforge.editor.engine.Wav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VocalSeparatorTest {

    @Test fun `window and frames match Spleeter`() {
        assertEquals(1.5f, SpleeterStft.windowPower, 1e-4f)
        assertEquals(0f, SpleeterStft.window[0], 0f)
        assertEquals(1f, SpleeterStft.window[SpleeterStft.N / 2], 1e-6f)
        // 4096 samples of silence go in front, then frames every 1024 samples to cover the end.
        assertEquals(4, SpleeterStft.frames(0))
        assertEquals(5, SpleeterStft.frames(1))
        assertEquals(4 + 44_100 / 1024 + 1, SpleeterStft.frames(44_100))
    }

    @Test fun `the voice mask is the voice's share`() {
        assertEquals(1f, SpleeterStft.mask(1f, 0f), 1e-6f)
        assertEquals(0f, SpleeterStft.mask(0f, 1f), 1e-6f)
        assertEquals(0.5f, SpleeterStft.mask(0.3f, 0.3f), 1e-6f)
        assertEquals(0.8f, SpleeterStft.mask(2f, 1f), 1e-6f)
        // Silence splits evenly instead of dividing by zero.
        assertEquals(0.5f, SpleeterStft.mask(0f, 0f), 1e-3f)
    }

    @Test fun `an unchanged spectrum gives back the same sound`() {
        val n = SpleeterStft.N
        val hop = SpleeterStft.HOP
        val random = java.util.Random(3)
        val signal = FloatArray(30_000) { random.nextFloat() * 2 - 1 }
        val frames = SpleeterStft.frames(signal.size)
        val out = FloatArray(frames * hop + n)
        val fft = Fft(n)
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (k in 0 until frames) {
            for (i in 0 until n) {
                val idx = k * hop + i - n
                re[i] = (if (idx in signal.indices) signal[idx] else 0f) * SpleeterStft.window[i]
                im[i] = 0f
            }
            fft.transform(re, im, inverse = false)
            fft.transform(re, im, inverse = true)
            for (i in 0 until n) out[k * hop + i] += re[i] * SpleeterStft.window[i] / SpleeterStft.windowPower
        }
        for (i in signal.indices) assertEquals("sample $i", signal[i], out[i + n], 1e-3f)
    }

    @Test fun `wav header describes the sound`() {
        val h = ByteBuffer.wrap(Wav.header(44_100L * 4, 44_100, 2)).order(ByteOrder.LITTLE_ENDIAN)
        val bytes = h.array()
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals("data", String(bytes, 36, 4))
        assertEquals(36 + 44_100 * 4, h.getInt(4))
        assertEquals(2, h.getShort(22).toInt())
        assertEquals(44_100, h.getInt(24))
        assertEquals(44_100 * 4, h.getInt(28))
        assertEquals(16, h.getShort(34).toInt())
        assertEquals(44_100 * 4, h.getInt(40))
        assertTrue(bytes.size == 44)
    }
}
