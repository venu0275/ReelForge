package com.reelforge.editor

import com.reelforge.editor.data.ProjectJson
import com.reelforge.editor.model.Clip
import com.reelforge.editor.model.ClipType
import com.reelforge.editor.model.Project
import com.reelforge.editor.model.TextFrame
import com.reelforge.editor.model.TextItem
import com.reelforge.editor.model.TextStickers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextStickersTest {
    @Test fun `both groups have packs of real stickers`() {
        for (g in TextStickers.GROUPS) assertTrue("$g is empty", TextStickers.packs(g).size >= 5)
        assertEquals(TextStickers.PACKS.size, TextStickers.PACKS.map { it.id }.distinct().size)
        for (pack in TextStickers.PACKS) {
            assertTrue(pack.group in TextStickers.GROUPS)
            assertTrue("${pack.id} is empty", pack.stickers.isNotEmpty())
            for (s in pack.stickers) {
                assertTrue("${pack.id}: no shape", s.frame != TextFrame.NONE)
                assertTrue("${pack.id}: no words", s.text.isNotBlank())
                assertTrue("${pack.id}: size ${s.size}", s.size in 0.03f..0.15f)
            }
        }
        // Every shape is used somewhere.
        val used = TextStickers.PACKS.flatMap { p -> p.stickers.map { it.frame } }.toSet()
        assertEquals(TextFrame.entries.toSet() - TextFrame.NONE, used)
    }

    @Test fun `a sticker's shape and colours are saved with the project`() {
        val sticker = TextStickers.PACKS.first { it.id == "hacker" }.stickers.first()
        val p = Project(
            name = "p",
            clips = listOf(Clip(uri = "a", type = ClipType.VIDEO, sourceDurationMs = 5_000)),
            texts = listOf(sticker),
        )
        val back = ProjectJson.decode(ProjectJson.encode(p)).texts.single()
        assertEquals(TextFrame.TERMINAL, back.frame)
        assertEquals(sticker.frameColor, back.frameColor)
        assertEquals(sticker.accentColor, back.accentColor)
        // Old projects (no shape saved) load as plain text.
        assertEquals(TextFrame.NONE, ProjectJson.decode(ProjectJson.encode(p.copy(texts = listOf(TextItem(text = "Hi"))))).texts.single().frame)
    }

    @Test fun `copying a look carries the shape`() {
        val sticker = TextStickers.PACKS.first { it.id == "neon" }.stickers.first()
        val plain = TextItem(text = "Mine")
        val styled = plain.withLookOf(sticker)
        assertEquals(sticker.frame, styled.frame)
        assertEquals("Mine", styled.text)
    }
}
