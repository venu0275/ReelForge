package com.reelforge.editor

import com.reelforge.editor.data.AssetCatalog
import com.reelforge.editor.data.FontPacks
import com.reelforge.editor.engine.AnimatedButton
import com.reelforge.editor.engine.MovingArt
import com.reelforge.editor.engine.SceneEffect
import com.reelforge.editor.engine.SceneEffects
import com.reelforge.editor.model.TextPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginsTest {

    private val fonts by lazy {
        val file = listOf("src/main/assets/font_catalog.json", "app/src/main/assets/font_catalog.json")
            .map { java.io.File(it) }.first { it.exists() }
        AssetCatalog.parseFonts(file.readText())
    }

    @Test fun `every language has a font pack with sizes`() {
        val packs = FontPacks.packs(fonts)
        for (language in FontPacks.LANGUAGES) {
            val pack = packs.firstOrNull { it.group == language }
            assertNotNull(language, pack)
            assertTrue(language, pack!!.fonts.isNotEmpty() && pack.bytes > 0)
            assertTrue(language, pack.sample.isNotBlank())
        }
        // Languages come first, then the English styles.
        assertEquals(FontPacks.LANGUAGES, packs.take(FontPacks.LANGUAGES.size).map { it.group })
        assertTrue(packs.first { it.group == "Telugu" }.fonts.size >= 15)
        assertTrue("every font has a size", fonts.all { it.bytes > 0 })
        assertTrue("only open-licence fonts", fonts.all { it.licence == "OFL-1.1" && "/ofl/" in it.url })
    }

    @Test fun `sizes read well`() {
        assertEquals("", FontPacks.size(0))
        assertEquals("1 KB", FontPacks.size(200))
        assertEquals("350 KB", FontPacks.size(350_400))
        assertEquals("1.8 MB", FontPacks.size(1_806_540))
    }

    @Test fun `text presets use fonts the store has`() {
        val ids = fonts.map { it.id }.toSet()
        assertEquals("ids unique", TextPresets.ALL.size, TextPresets.ALL.map { it.id }.toSet().size)
        for (preset in TextPresets.ALL) {
            assertTrue(preset.id, preset.group in TextPresets.GROUPS)
            preset.fontId?.let { assertTrue("${preset.id} uses $it", it in ids) }
            val item = preset.item("/fonts/x.ttf")
            assertEquals("/fonts/x.ttf", item.fontPath)
            assertTrue(preset.id, item.text.isNotBlank() && item.size > 0f)
        }
        // Each use is a new text.
        val p = TextPresets.ALL.first()
        assertFalse(p.item(null).id == p.item(null).id)
        for (group in TextPresets.GROUPS) assertTrue(group, TextPresets.ALL.any { it.group == group })
    }

    @Test fun `moving art is found by name and names never clash`() {
        assertEquals(SceneEffect.SNOWFALL, MovingArt.of("SNOWFALL"))
        assertEquals(AnimatedButton.LIKE, MovingArt.of("LIKE"))
        assertNull(MovingArt.of(null))
        assertNull(MovingArt.of("snowfall"))
        val names = AnimatedButton.entries.map { it.name } + SceneEffect.entries.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(SceneEffect.entries.all { it.fullFrame })
        assertTrue(AnimatedButton.entries.none { it.fullFrame })
        // A scene effect is the shape of the video, whatever that is.
        assertEquals(9f / 16f, SceneEffect.HEARTS.aspect(9f / 16f), 1e-6f)
        assertEquals(5f, SceneEffect.HEARTS.aspect(50f), 1e-6f)
    }

    @Test fun `scene effects fade in and out and their particles stay put`() {
        assertEquals(0f, SceneEffects.fade(0, 5_000), 1e-6f)
        assertEquals(1f, SceneEffects.fade(2_000, 5_000), 1e-6f)
        assertEquals(0f, SceneEffects.fade(5_000, 5_000), 1e-6f)
        assertEquals(0f, SceneEffects.fade(9_000, 5_000), 1e-6f)
        assertEquals(1f, SceneEffects.fade(1_000, 0), 1e-6f)
        for (i in 0 until 500) for (k in 0 until 30) {
            val v = SceneEffects.rnd(i, k)
            assertTrue(v in 0f..1f)
            assertEquals(v, SceneEffects.rnd(i, k), 0f)
        }
    }
}
