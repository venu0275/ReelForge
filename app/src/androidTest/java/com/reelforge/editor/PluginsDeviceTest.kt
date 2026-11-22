package com.reelforge.editor

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.foundation.verticalScroll
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reelforge.editor.engine.ExportManager
import com.reelforge.editor.engine.SceneEffect
import com.reelforge.editor.model.AspectRatio
import com.reelforge.editor.model.Clip
import com.reelforge.editor.model.ClipType
import com.reelforge.editor.model.ImageLayer
import com.reelforge.editor.model.NoiseRemoval
import com.reelforge.editor.model.Project
import com.reelforge.editor.model.TextPresets
import com.reelforge.editor.ui.EditorViewModel
import com.reelforge.editor.ui.ItemKind
import com.reelforge.editor.ui.PluginsPanel
import com.reelforge.editor.util.MediaUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The plug-in features driven the way the app drives them, through the editor's view model. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PluginsDeviceTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun <T> read(block: () -> T): T {
        var out: T? = null
        onMain { out = block() }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private fun waitUntil(timeoutMs: Long, label: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (read(check)) return
            Thread.sleep(200)
        }
        throw AssertionError("timed out: $label")
    }

    private fun newViewModel(): EditorViewModel = read { EditorViewModel(context.applicationContext as Application) }

    private fun picture(name: String, w: Int, h: Int, draw: (Canvas) -> Unit): Uri {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp))
        val file = File(context.cacheDir, "$name.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(file)
    }

    /** A patterned square, easy to follow. */
    private fun target(): Uri = picture("target", 120, 120) { c ->
        val p = Paint()
        for (y in 0 until 6) for (x in 0 until 6) {
            p.color = if ((x + y) % 2 == 0) Color.YELLOW else Color.MAGENTA
            c.drawRect(x * 20f, y * 20f, x * 20f + 20f, y * 20f + 20f, p)
        }
    }

    private fun export(project: Project): File {
        val output = File(context.cacheDir, "plugins_${System.nanoTime()}.mp4")
        val done = CountDownLatch(1)
        var error: Exception? = null
        // Built off the main thread (it decodes pictures), started on it, as the app does.
        val composition = ExportManager.buildComposition(context, project, 360)
        val (width, height) = ExportManager.outputSize(project.aspect, 360)
        onMain {
            val listener = object : androidx.media3.transformer.Transformer.Listener {
                override fun onCompleted(
                    composition: androidx.media3.transformer.Composition,
                    exportResult: androidx.media3.transformer.ExportResult,
                ) = done.countDown()

                override fun onError(
                    composition: androidx.media3.transformer.Composition,
                    exportResult: androidx.media3.transformer.ExportResult,
                    exportException: androidx.media3.transformer.ExportException,
                ) {
                    error = exportException
                    done.countDown()
                }
            }
            ExportManager.createTransformer(context, listener, ExportManager.bitrate(width, height, 1))
                .start(composition, output.absolutePath)
        }
        assertTrue("export timed out", done.await(240, TimeUnit.SECONDS))
        if (error != null) throw AssertionError("export failed: $error")
        return output
    }

    @Test
    fun pluginStore_everyPartShowsWithoutErrors() {
        val vm = newViewModel()
        rule.setContent {
            androidx.compose.foundation.layout.Column(
                androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())
            ) { PluginsPanel(vm) }
        }
        rule.waitForIdle()
        for (part in listOf("effects", "captions", "audio", "ai", "fonts", "text", "mine", "all")) {
            rule.onNodeWithTag("plugins_$part", useUnmergedTree = true).performScrollTo().performClick()
            rule.waitForIdle()
            Thread.sleep(300)
        }
        // Effects play their live preview; fonts list every language.
        rule.onNodeWithTag("plugins_effects", useUnmergedTree = true).performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithTag("effect_diwali").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithTag("effect_bubbles").fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag("plugins_fonts", useUnmergedTree = true).performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Telugu fonts").fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag("plugins_captions", useUnmergedTree = true).performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithTag("speech_te").fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag("plugins_audio", useUnmergedTree = true).performScrollTo().performClick()
        // AI Voice starts the phone's speech engine; it either finds voices or says there is none.
        waitUntil(20_000, "speech engine") { vm.voiceEngineReady != null }
        Log.i("ReelForgeTest", "speech engine ready=${read { vm.voiceEngineReady }} voices=${read { vm.aiVoices.size }}")
    }

    @Test
    fun effectsPresetsNoiseAndSharpen_changeTheProject() {
        val vm = newViewModel()
        val photo = picture("small_photo", 300, 200) { c ->
            c.drawColor(Color.rgb(30, 90, 160))
            c.drawCircle(150f, 100f, 60f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        }
        val video = export(
            Project(
                name = "noisy",
                clips = listOf(Clip(uri = photo.toString(), type = ClipType.IMAGE, sourceDurationMs = 1_500, hasAudio = false)),
            )
        )
        val videoInfo = MediaUtils.probe(context, Uri.fromFile(video))!!
        val project = Project(
            name = "plugins",
            clips = listOf(
                Clip(uri = photo.toString(), type = ClipType.IMAGE, sourceDurationMs = 2_000, hasAudio = false),
                Clip(uri = Uri.fromFile(video).toString(), type = ClipType.VIDEO, sourceDurationMs = videoInfo.durationMs, hasAudio = true),
            ),
        )
        onMain { vm.openProject(project) }

        // Scene effect: a full-frame layer drawn by the app.
        onMain { vm.addSceneEffect(SceneEffect.DIWALI) }
        waitUntil(10_000, "effect added") { vm.project!!.layers.any { it.button == "DIWALI" } }
        val effect = read { vm.project!!.layers.first { it.button == "DIWALI" } }
        assertEquals(1f, effect.scale, 0f)

        // Text preset: added even when its font cannot download.
        val before = read { vm.project!!.texts.size }
        onMain { vm.addTextPreset(TextPresets.ALL.first { it.group == "Telugu" }) }
        waitUntil(60_000, "preset added") { vm.project!!.texts.size == before + 1 }

        // Noise remover on every clip with sound.
        onMain { vm.setNoiseRemovalEverywhere(NoiseRemoval.MEDIUM) }
        assertEquals(NoiseRemoval.MEDIUM, read { vm.project!!.clips[1].denoise })
        assertEquals(NoiseRemoval.OFF, read { vm.project!!.clips[0].denoise }) // a photo has no sound

        // Super Resolution swaps the small photo for a sharper copy four times its size.
        onMain { vm.sharpenPhotos(listOf(read { vm.project!!.clips[0].id }), emptyList()) }
        waitUntil(180_000, "sharpen finished") { vm.enhanceWork == null && "/sharper/" in vm.project!!.clips[0].uri }
        val sharper = Uri.parse(read { vm.project!!.clips[0].uri })
        assertEquals(1200 to 800, MediaUtils.photoSize(context, sharper))

        // The whole project still exports, with noise removal on the video's sound.
        val out = export(read { vm.project!! })
        assertTrue(out.length() > 1_000)
    }

    @Test
    fun tracking_followsAMovingSquare() {
        val background = picture("track_bg", 640, 360) { c ->
            c.drawColor(Color.rgb(40, 70, 40))
            val p = Paint().apply { color = Color.rgb(60, 100, 60) }
            for (i in 0 until 20) c.drawCircle((i * 97 % 640).toFloat(), (i * 53 % 360).toFloat(), 18f, p)
        }
        val square = target()
        // A video of the square sliding from the left to the right.
        val moving = ImageLayer(
            uri = square.toString(), scale = 0.25f, x = -0.7f, y = 0f, startMs = 0, endMs = 3_000,
            moving = true, x2 = 0.7f, y2 = 0f,
        )
        val video = export(
            Project(
                name = "slide", aspect = AspectRatio.LANDSCAPE,
                clips = listOf(Clip(uri = background.toString(), type = ClipType.IMAGE, sourceDurationMs = 3_000, hasAudio = false)),
                layers = listOf(moving),
            )
        )
        val info = MediaUtils.probe(context, Uri.fromFile(video))
        assertNotNull(info)
        // A sticker the same size, placed on the square where it starts.
        val sticker = ImageLayer(uri = square.toString(), scale = 0.25f, x = -0.7f, y = 0f, startMs = 0, endMs = 2_900)
        val vm = newViewModel()
        onMain {
            vm.openProject(
                Project(
                    name = "track", aspect = AspectRatio.LANDSCAPE,
                    clips = listOf(Clip(uri = Uri.fromFile(video).toString(), type = ClipType.VIDEO,
                        sourceDurationMs = info!!.durationMs, hasAudio = false)),
                    layers = listOf(sticker),
                )
            )
            vm.trackItem(ItemKind.LAYER, sticker.id)
        }
        waitUntil(120_000, "tracking finished") { vm.trackWork == null }
        val keys = read { vm.project!!.layers.first { it.id == sticker.id }.keyframes }
        Log.i("ReelForgeTest", "tracking keyframes: ${keys.map { "${it.tMs}:${"%.2f".format(it.x)},${"%.2f".format(it.y)}" }}")
        assertTrue("no path made", keys.size >= 2)
        assertEquals("starts on the square", -0.7f, keys.first().x, 0.15f)
        assertTrue("moved right with the square", keys.last().x > 0.3f)
        assertTrue("stayed level", keys.all { kotlin.math.abs(it.y) < 0.2f })
        assertTrue("followed most of the way", keys.last().tMs >= 2_000)
    }
}
