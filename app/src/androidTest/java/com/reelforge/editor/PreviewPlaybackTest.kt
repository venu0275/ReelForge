package com.reelforge.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reelforge.editor.engine.ExportManager
import com.reelforge.editor.model.AspectRatio
import com.reelforge.editor.model.AudioFx
import com.reelforge.editor.model.Background
import com.reelforge.editor.model.Clip
import com.reelforge.editor.model.ClipType
import com.reelforge.editor.model.CreativeOps
import com.reelforge.editor.model.FilterPreset
import com.reelforge.editor.model.PhotoMotion
import com.reelforge.editor.model.Project
import com.reelforge.editor.model.SpeedRamp
import com.reelforge.editor.model.Templates
import com.reelforge.editor.ui.EditorViewModel
import com.reelforge.editor.ui.MainActivity
import com.reelforge.editor.util.MediaUtils
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Plays projects in the real editor preview, start to end, the way users do, and fails if the
 * preview player reports an error (such as ERROR_CODE_FAILED_RUNTIME_CHECK). The full error is
 * written to the test log. Each case also logs how long playback took against its length.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PreviewPlaybackTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun log(text: String) = Log.i("ReelForgeTest", text)

    private fun photo(name: String, width: Int, height: Int, color: Int): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(color)
        val paint = Paint().apply { this.color = Color.WHITE }
        canvas.drawCircle(width * 0.3f, height * 0.4f, height * 0.2f, paint)
        val file = File(context.cacheDir, "$name.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return Uri.fromFile(file)
    }

    private fun photoClip(uri: Uri, ms: Long) =
        Clip(uri = uri.toString(), type = ClipType.IMAGE, sourceDurationMs = ms, hasAudio = false)

    /** A real 3 s video with a sound track, made by the app's own exporter. */
    private fun video(): Clip {
        val project = Project(
            name = "src", aspect = AspectRatio.LANDSCAPE,
            clips = listOf(photoClip(photo("v1", 640, 360, Color.rgb(200, 90, 40)), 3_000)),
        )
        val out = File(context.cacheDir, "preview_src.mp4")
        if (!out.exists() || out.length() < 1_000) {
            val latch = CountDownLatch(1)
            var error: Exception? = null
            val composition = ExportManager.buildComposition(context, project, 360)
            val (w, h) = ExportManager.outputSize(project.aspect, 360)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) = latch.countDown()
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        error = exportException
                        latch.countDown()
                    }
                }
                ExportManager.createTransformer(context, listener, ExportManager.bitrate(w, h, 1))
                    .start(composition, out.absolutePath)
            }
            assertTrue("source video was not made in time", latch.await(120, TimeUnit.SECONDS))
            error?.let { throw it }
        }
        val info = MediaUtils.probe(context, Uri.fromFile(out))!!
        return Clip(uri = Uri.fromFile(out).toString(), type = ClipType.VIDEO,
            sourceDurationMs = info.durationMs, hasAudio = info.hasAudio)
    }

    /** Opens [project], plays it to the end and fails on any preview player error. */
    private fun playThrough(label: String, project: Project) {
        val vm = ViewModelProvider(rule.activity)[EditorViewModel::class.java]
        val errors = mutableListOf<PlaybackException>()
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e("ReelForgeTest", "PREVIEW ERROR in $label: ${error.errorCodeName}", error)
                errors += error
            }
        }
        rule.runOnUiThread {
            vm.player.addListener(listener)
            vm.openProject(project)
            vm.seekTo(0)
        }
        Thread.sleep(1_500)
        val started = SystemClock.elapsedRealtime()
        rule.runOnUiThread { vm.togglePlay() }
        val total = project.durationMs
        // The emulator has no video hardware and plays several times slower than real time.
        val deadline = started + total * 10 + 20_000
        var ended = false
        var furthest = 0L
        var polls = 0
        while (SystemClock.elapsedRealtime() < deadline && errors.isEmpty()) {
            Thread.sleep(250)
            var position = 0L
            var playing = true
            var state = ""
            rule.runOnUiThread {
                position = vm.playheadMs
                playing = vm.isPlaying
                val pl = vm.player
                state = "state=${pl.playbackState} playWhenReady=${pl.playWhenReady} isPlaying=${pl.isPlaying} " +
                    "suppressed=${pl.playbackSuppressionReason} pos=${pl.currentPosition} items=${pl.mediaItemCount} " +
                    "error=${pl.playerError?.errorCodeName}"
            }
            if (++polls % 8 == 1) log("STATE $label: playhead=$position $state")
            furthest = maxOf(furthest, position)
            if (!playing && position >= total - 200) {
                ended = true
                break
            }
        }
        val took = SystemClock.elapsedRealtime() - started
        rule.runOnUiThread {
            if (vm.isPlaying) vm.togglePlay()
            vm.player.removeListener(listener)
        }
        log("PERF preview $label: ${total} ms of video, reached $furthest ms in $took ms (%.2fx)"
            .format(took / total.toFloat()))
        if (errors.isNotEmpty()) {
            val e = errors.first()
            fail("$label: preview error ${e.errorCodeName}: ${e.cause ?: e}")
        }
        assertTrue("$label: playback reached only $furthest of $total ms in $took ms", ended)
    }

    @Test
    fun photoThenVideoWithFilter() {
        // The user's report: a photo first, then a video, with a filter (as a template sets).
        val clips = listOf(photoClip(photo("p1", 1280, 720, Color.rgb(40, 80, 160)), 1_500), video())
        playThrough("photo+video+filter", Project(name = "t1", aspect = AspectRatio.PORTRAIT,
            clips = clips.map { it.copy(filter = FilterPreset.WARM) }))
    }

    @Test
    fun photoThenVideoPlain() {
        val clips = listOf(photoClip(photo("p2", 1280, 720, Color.rgb(40, 160, 80)), 1_500), video())
        playThrough("photo+video plain", Project(name = "t2", aspect = AspectRatio.PORTRAIT, clips = clips))
    }

    @Test
    fun blurBackgroundMovedPictureAndSoundEffect() {
        val clips = listOf(
            photoClip(photo("p3", 1280, 720, Color.rgb(160, 40, 80)), 1_500)
                .copy(motion = PhotoMotion.ZOOM_IN, zoom = 1.4f, panX = 0.1f),
            video().copy(audioFx = AudioFx.ROBOT, zoom = 0.8f, panY = -0.1f),
        )
        playThrough("blur+moved+robot", Project(name = "t3", aspect = AspectRatio.PORTRAIT,
            background = Background.BLUR, clips = clips))
    }

    @Test
    fun templateAndSpeedRamp() {
        val base = Project(
            name = "t4", aspect = AspectRatio.PORTRAIT,
            clips = listOf(
                photoClip(photo("p4", 1080, 1350, Color.rgb(90, 90, 30)), 3_000),
                video(),
                photoClip(photo("p5", 720, 1280, Color.rgb(30, 90, 90)), 3_000),
            ),
        )
        val templated = Templates.apply(base, Templates.byId("travel")!!, "Trip", "Bye")
        val ramped = CreativeOps.speedRamp(templated, templated.clips[1].id, SpeedRamp.MONTAGE)
        playThrough("template+ramp", ramped)
    }
}
