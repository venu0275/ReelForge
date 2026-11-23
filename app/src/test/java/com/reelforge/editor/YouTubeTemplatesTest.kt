package com.reelforge.editor

import com.reelforge.editor.engine.MovingArt
import com.reelforge.editor.model.AspectRatio
import com.reelforge.editor.model.At
import com.reelforge.editor.model.Clip
import com.reelforge.editor.model.ClipType
import com.reelforge.editor.model.ImageLayer
import com.reelforge.editor.model.Project
import com.reelforge.editor.model.TemplatePart
import com.reelforge.editor.model.Templates
import com.reelforge.editor.model.TextItem
import com.reelforge.editor.model.YouTubeTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeTemplatesTest {
    private fun clips() = listOf(
        Clip(uri = "a", type = ClipType.VIDEO, sourceDurationMs = 30_000),
        Clip(uri = "b", type = ClipType.VIDEO, sourceDurationMs = 30_000),
    )

    @Test fun `every YouTube template is in the list and uses only real effects and buttons`() {
        assertTrue(YouTubeTemplates.all.size >= 10)
        assertTrue(YouTubeTemplates.all.all { it.group == "YouTube" && it in Templates.all })
        assertTrue(Templates.all.all { it.group in Templates.groups })
        for (t in YouTubeTemplates.all) {
            for (name in Templates.movingArt(t)) assertTrue("${t.id}: $name", MovingArt.of(name) != null)
            for (part in t.parts) if (part is TemplatePart.Words) {
                assertTrue("${t.id}: ${part.source}", part.source in setOf(null, "title", "ending", "channel"))
                if (part.source == null) assertTrue(part.words.isNotBlank())
                assertTrue(part.look.size in 0.02f..0.2f)
                assertTrue(part.look.x in -1f..1f && part.look.y in -1f..1f)
            }
            if (t.usesChannel) assertTrue("${t.id} asks for a channel it never shows",
                t.parts.any { it is TemplatePart.Words && it.source == "channel" })
        }
        assertEquals(AspectRatio.PORTRAIT, Templates.byId("yt_shorts")!!.aspect)
        assertTrue(Templates.byId("yt_shorts")!!.suggestCaptions)
    }

    @Test fun `parts are placed from the start or the end and cut to the video`() {
        val fromEnd = TemplatePart.Button("LIKE", At.END, 5_000, 600_000)
        assertEquals(25_000L to 30_000L, Templates.timing(fromEnd, 30_000))
        // Longer than the video: starts at the beginning.
        assertEquals(0L to 3_000L, Templates.timing(fromEnd, 3_000))
        val fromStart = TemplatePart.Effect("CONFETTI", At.START, 1_000, 2_000)
        assertEquals(1_000L to 3_000L, Templates.timing(fromStart, 10_000))
        // Too short to see, or no video.
        assertNull(Templates.timing(fromStart, 1_100))
        assertNull(Templates.timing(fromStart, 0))
    }

    @Test fun `applying adds the parts, then replaces them and keeps the user's own`() {
        val mineText = TextItem(id = "mine", text = "Hello", startMs = 0, endMs = 1_000)
        val mineLayer = ImageLayer(id = "sticker", uri = "s", startMs = 0, endMs = 1_000)
        val outro = Templates.byId("yt_outro")!!
        val stills = Templates.movingArt(outro).associateWith { "file://$it.png" }
        val start = Project(name = "p", clips = clips(), texts = listOf(mineText), layers = listOf(mineLayer), aspect = AspectRatio.PORTRAIT)
        val p = Templates.apply(start, outro, "", "Thanks!", "Venu Vlogs", stills)
        assertEquals(AspectRatio.LANDSCAPE, p.aspect)
        assertTrue(p.texts.any { it.text == "Thanks!" })
        assertTrue(p.texts.any { it.text == "Venu Vlogs" })
        assertTrue(p.texts.any { it.text == "Watch next →" })
        assertEquals(setOf("SUBSCRIBE", "LIKE"), p.layers.mapNotNull { it.button }.toSet())
        val total = p.durationMs
        assertTrue(p.layers.filter { it.button != null }.all { it.endMs == total && it.startMs < total })
        assertTrue(p.texts.all { it.endMs <= total })

        // Again with another template: the outro's parts go, the user's stay.
        val intro = Templates.byId("yt_intro")!!
        val again = Templates.apply(p, intro, "Welcome", "", "Venu Vlogs", Templates.movingArt(intro).associateWith { "x" })
        assertTrue(again.texts.none { it.text == "Thanks!" })
        assertTrue(again.texts.any { it.id == "mine" } && again.layers.any { it.id == "sticker" })
        assertEquals(setOf("SPARKLES", "SUBSCRIBE"), again.layers.mapNotNull { it.button }.toSet())
        // A blank channel leaves its text out; a missing picture leaves its layer out.
        val bare = Templates.apply(start, intro, "Welcome", "", "", emptyMap())
        assertTrue(bare.texts.none { it.id.startsWith(Templates.TEXT_PREFIX) && it.text.isBlank() })
        assertTrue(bare.layers.none { it.id.startsWith(Templates.TEXT_PREFIX) })
        assertEquals(listOf("Hello", "Welcome"), bare.texts.map { it.text })
    }
}
