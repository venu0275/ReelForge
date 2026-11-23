package com.reelforge.editor

import com.reelforge.editor.util.MediaUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureFormatTest {
    private fun bytes(vararg parts: Any): ByteArray = parts.flatMap { p ->
        when (p) {
            is String -> p.toByteArray(Charsets.US_ASCII).toList()
            is Int -> listOf(p.toByte())
            else -> error("bad part")
        }
    }.toByteArray().copyOf(32)

    private fun format(head: ByteArray, n: Int = head.size) = MediaUtils.imageFormat(head, n)

    @Test fun `pictures are known by their first bytes, whatever their name`() {
        assertEquals("jpeg", format(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals("png", format(bytes(0x89, "PNG", 0x0D, 0x0A)))
        assertEquals("webp", format(bytes("RIFF", 0, 0, 0, 0, "WEBPVP8 ")))
        assertEquals("gif", format(bytes("GIF89a")))
        assertEquals("bmp", format(bytes("BM")))
        assertEquals("avif", format(bytes(0, 0, 0, 0x1C, "ftypavif")))
        assertEquals("heif", format(bytes(0, 0, 0, 0x18, "ftypheic")))
        assertEquals("heif", format(bytes(0, 0, 0, 0x18, "ftypmif1")))
        // A video and rubbish are not pictures.
        assertNull(format(bytes(0, 0, 0, 0x18, "ftypisom")))
        assertNull(format(bytes("hello world")))
        // Too short to tell.
        assertNull(format(bytes(0xFF, 0xD8), 2))
        assertNull(format(ByteArray(0), 0))
    }

    @Test fun `only JPEG and PNG go to the player as they are`() {
        assertTrue(MediaUtils.playableImage("jpeg") && MediaUtils.playableImage("png"))
        for (f in listOf("webp", "gif", "bmp", "heif", "avif", null)) assertFalse(MediaUtils.playableImage(f))
        assertEquals("image/png", MediaUtils.imageMime("png"))
        assertEquals("image/jpeg", MediaUtils.imageMime("jpeg"))
    }
}
