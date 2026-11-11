package com.reelforge.editor

import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reelforge.editor.data.ProjectRepository
import com.reelforge.editor.engine.ExportManager
import com.reelforge.editor.model.AspectRatio
import com.reelforge.editor.model.Clip
import com.reelforge.editor.model.ClipType
import com.reelforge.editor.model.FilterPreset
import com.reelforge.editor.model.Project
import com.reelforge.editor.model.TextItem
import com.reelforge.editor.ui.EditorViewModel
import com.reelforge.editor.ui.MainActivity
import com.reelforge.editor.util.MediaUtils
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Captures screenshots of the main screens (portrait and landscape) so the layout can be reviewed
 * without a phone. Saved to Pictures/ReelForgeTest on the device.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** A simple landscape picture: sky gradient, sun and two mountains. */
    private fun scene(name: String, top: Int, bottom: Int, hills: Int): Uri {
        val w = 1280
        val h = 720
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
        paint.color = Color.rgb(255, 230, 150)
        canvas.drawCircle(w * 0.72f, h * 0.3f, h * 0.11f, paint)
        paint.color = hills
        canvas.drawPath(Path().apply {
            moveTo(0f, h.toFloat()); lineTo(w * 0.3f, h * 0.45f); lineTo(w * 0.62f, h.toFloat()); close()
        }, paint)
        paint.color = Color.rgb(Color.red(hills) / 2, Color.green(hills) / 2, Color.blue(hills) / 2)
        canvas.drawPath(Path().apply {
            moveTo(w * 0.4f, h.toFloat()); lineTo(w * 0.75f, h * 0.55f); lineTo(w.toFloat(), h * 0.8f)
            lineTo(w.toFloat(), h.toFloat()); close()
        }, paint)
        val file = File(context.cacheDir, "$name.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return Uri.fromFile(file)
    }

    private fun photo(uri: Uri, ms: Long) =
        Clip(uri = uri.toString(), type = ClipType.IMAGE, sourceDurationMs = ms, hasAudio = false)

    /** Makes a real video file by exporting two pictures. */
    private fun makeVideo(a: Uri, b: Uri, ms: Long): File {
        val project = Project(name = "src", clips = listOf(photo(a, ms / 2), photo(b, ms - ms / 2)))
        val output = File(context.cacheDir, "screens_src_${System.nanoTime()}.mp4")
        val composition = ExportManager.buildComposition(context, project, 480)
        val latch = CountDownLatch(1)
        var error: Exception? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) = latch.countDown()
                override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                    error = e
                    latch.countDown()
                }
            }
            ExportManager.createTransformer(context, listener, 2_000_000).start(composition, output.absolutePath)
        }
        latch.await(180, TimeUnit.SECONDS)
        if (error != null) throw error!!
        return output
    }

    private fun save(name: String) {
        Thread.sleep(1_500)
        rule.waitForIdle()
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertNotNull("screenshot failed", shot)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ReelForgeTest")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { shot!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i("ReelForgeTest", "SCREENSHOT saved $name.png ${shot!!.width}x${shot.height}")
    }

    private fun tap(text: String, scroll: Boolean = false) {
        val node = rule.onNodeWithText(text, useUnmergedTree = true)
        if (scroll) node.performScrollTo()
        node.performClick()
    }

    private fun waitForText(text: String) {
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun rotate(orientation: Int) {
        rule.activity.runOnUiThread { rule.activity.requestedOrientation = orientation }
        Thread.sleep(2_500)
        rule.waitForIdle()
    }

    @Test
    fun captureMainScreens() {
        try {
            captureAll()
        } catch (e: Throwable) {
            // Keep a picture of whatever was on screen when a step failed.
            runCatching { save("99_failed_step") }
            throw e
        }
    }

    private fun captureAll() {
        val sunset = scene("sunset", Color.rgb(255, 120, 80), Color.rgb(120, 40, 110), Color.rgb(60, 40, 90))
        val day = scene("day", Color.rgb(90, 170, 255), Color.rgb(200, 230, 255), Color.rgb(60, 140, 80))
        val night = scene("night", Color.rgb(15, 20, 60), Color.rgb(60, 70, 140), Color.rgb(30, 30, 50))
        val snow = scene("snow", Color.rgb(170, 190, 210), Color.rgb(240, 245, 250), Color.rgb(200, 210, 225))

        val video = makeVideo(day, sunset, 6_000)
        val info = MediaUtils.probe(context, Uri.fromFile(video))!!
        val videoClip = Clip(
            uri = Uri.fromFile(video).toString(), type = ClipType.VIDEO,
            sourceDurationMs = info.durationMs, hasAudio = info.hasAudio,
        )
        val project = Project(
            name = "Trip to the hills",
            aspect = AspectRatio.LANDSCAPE,
            clips = listOf(
                videoClip,
                photo(night, 3_000),
                photo(snow, 3_000).copy(filter = FilterPreset.COOL),
                videoClip.copy(id = "second", trimStartMs = 1_000, trimEndMs = 5_000),
            ),
            texts = listOf(
                TextItem(text = "Day 1", size = 0.12f, y = 0.6f, startMs = 0, endMs = 20_000, outline = true),
            ),
        )
        ProjectRepository(context).save(project)
        ProjectRepository(context).save(
            Project(name = "Birthday reel", aspect = AspectRatio.PORTRAIT, clips = listOf(photo(day, 3_000)))
        )

        val vm = ViewModelProvider(rule.activity)[EditorViewModel::class.java]

        // 1. Home screen.
        rule.runOnUiThread { vm.refreshProjects() }
        waitForText("Trip to the hills")
        save("01_home_portrait")

        // 2. Editor, portrait, nothing selected: main menu.
        rule.runOnUiThread {
            vm.openProject(project)
            vm.seekTo(7_000)
        }
        waitForText("Add to your video")
        Thread.sleep(4_000) // timeline frames load in the background
        save("02_editor_portrait")

        // 3. A clip selected: its own actions and trim handles.
        rule.runOnUiThread { vm.toggleClipSelection(project.clips[1].id) }
        waitForText("Split")
        save("03_clip_selected_portrait")

        // 4. A tool open in portrait (bottom sheet).
        tap("Adjust", scroll = true)
        waitForText("Brightness")
        save("04_tool_open_portrait")
        // A real Back key press goes to the sheet's own window and closes only the sheet.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        Thread.sleep(1_500)
        waitForText("Split")

        // 5. Landscape with the clip still selected.
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        waitForText("Split")
        Thread.sleep(2_000)
        save("05_clip_selected_landscape")

        // 6. A tool open in landscape (side pane).
        tap("Adjust", scroll = true)
        waitForText("Brightness")
        save("06_tool_open_landscape")
        rule.onNodeWithContentDescription("Close tool").performClick()

        // 7. Landscape main menu.
        rule.runOnUiThread { vm.selectedClipId = null }
        waitForText("Add to your video")
        save("07_editor_landscape")

        // 8. Text editor in landscape.
        tap("Text", scroll = true)
        waitForText("Add text at playhead")
        tap("Edit")
        waitForText("Colour")
        save("08_text_editor_landscape")

        // 9-11. The other text tabs.
        tap("Background")
        waitForText("Background colour")
        save("09_text_background_landscape")
        tap("Animation")
        waitForText("In animation (how it appears)")
        save("10_text_animation_landscape")
        tap("Place", scroll = true)
        waitForText("Turn left 90°")
        save("11_text_turn_landscape")
    }
}
