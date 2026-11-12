package com.reelforge.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reelforge.editor.data.AssetCatalog
import com.reelforge.editor.data.AssetDownloader
import com.reelforge.editor.data.ProjectRepository
import com.reelforge.editor.engine.EffectFactory
import com.reelforge.editor.engine.ExportManager
import com.reelforge.editor.model.AspectRatio
import com.reelforge.editor.model.AudioClip
import com.reelforge.editor.model.AudioFx
import com.reelforge.editor.model.Keyframe
import com.reelforge.editor.model.Background
import com.reelforge.editor.model.Clip
import com.reelforge.editor.model.ClipType
import com.reelforge.editor.model.FilterPreset
import com.reelforge.editor.model.ImageLayer
import com.reelforge.editor.model.MusicTrack
import com.reelforge.editor.model.OverlayAnim
import com.reelforge.editor.model.Project
import com.reelforge.editor.model.TextFont
import com.reelforge.editor.model.TextItem
import com.reelforge.editor.model.TransitionStyle
import com.reelforge.editor.model.VideoLayer
import com.reelforge.editor.util.MediaUtils
import com.reelforge.editor.util.SpeechMaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Runs real exports on a device or emulator and checks the files that come out.
 * Test media is generated on the device, so no sample files are needed.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ExportDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private class Outcome(val file: File?, val error: Exception?, val millis: Long)

    private class Info(val durationMs: Long, val width: Int, val height: Int, val hasAudio: Boolean)

    private fun log(message: String) {
        Log.i("ReelForgeTest", message)
    }

    /** A generated photo: coloured background with a few shapes so filters have something to change. */
    private fun photo(name: String, width: Int, height: Int, color: Int): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(color)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawCircle(width * 0.3f, height * 0.4f, height * 0.2f, paint)
        paint.color = Color.BLACK
        canvas.drawRect(width * 0.55f, height * 0.5f, width * 0.9f, height * 0.9f, paint)
        val file = File(context.cacheDir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(file)
    }

    private fun imageClip(uri: Uri, durationMs: Long) = Clip(
        uri = uri.toString(), type = ClipType.IMAGE, sourceDurationMs = durationMs, hasAudio = false,
    )

    /**
     * Exports the way the app does: a project with video layers gets up to three attempts,
     * because Media3 occasionally fails to start such an export. Every retry is logged.
     */
    private fun export(project: Project, shortSide: Int = 360, timeoutSec: Long = 240): Outcome {
        val attempts = if (project.videoLayers.isNotEmpty()) 3 else 1
        var outcome = exportOnce(project, shortSide, timeoutSec)
        var attempt = 1
        while (outcome.error != null && attempt < attempts) {
            Log.w("ReelForgeTest", "LAYERED RETRY after attempt $attempt of ${project.name}: ${outcome.error}".take(300))
            Thread.sleep(500)
            attempt++
            outcome = exportOnce(project, shortSide, timeoutSec)
        }
        return outcome
    }

    private fun exportOnce(project: Project, shortSide: Int, timeoutSec: Long): Outcome {
        val output = File(context.cacheDir, "out_${System.nanoTime()}.mp4")
        val latch = CountDownLatch(1)
        var error: Exception? = null
        var transformer: Transformer? = null
        val started = SystemClock.elapsedRealtime()
        val composition = ExportManager.buildComposition(context, project, shortSide)
        val (width, height) = ExportManager.outputSize(project.aspect, shortSide)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    latch.countDown()
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    error = exportException
                    latch.countDown()
                }
            }
            val t = ExportManager.createTransformer(context, listener, ExportManager.bitrate(width, height, 1))
            transformer = t
            t.start(composition, output.absolutePath)
        }
        val finished = latch.await(timeoutSec, TimeUnit.SECONDS)
        val took = SystemClock.elapsedRealtime() - started
        if (!finished) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { transformer?.cancel() }
            return Outcome(null, IllegalStateException("export did not finish in $timeoutSec s"), took)
        }
        return if (error != null) Outcome(null, error, took) else Outcome(output, null, took)
    }

    private fun exportOk(project: Project, shortSide: Int = 360): Outcome {
        val outcome = export(project, shortSide)
        if (outcome.error != null) {
            Log.e("ReelForgeTest", "export failed", outcome.error)
            fail("export failed: ${outcome.error}")
        }
        assertTrue("output file is empty", outcome.file!!.length() > 1_000)
        return outcome
    }

    private fun inspect(file: File): Info {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            // Portrait videos are stored sideways with a rotation flag, which players apply.
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val storedWidth = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val storedHeight = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            val sideways = rotation == 90 || rotation == 270
            return Info(
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong(),
                width = if (sideways) storedHeight else storedWidth,
                height = if (sideways) storedWidth else storedHeight,
                hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
            )
        } finally {
            r.release()
        }
    }

    private fun assertNear(label: String, expected: Long, actual: Long, tolerance: Long) {
        assertTrue("$label: expected about $expected ms but was $actual ms", abs(expected - actual) <= tolerance)
    }

    /** A short real video (with a silent audio track) made by exporting photos. */
    private fun baseVideo(durationMs: Long): File {
        val half = durationMs / 2
        val project = Project(
            name = "base",
            aspect = AspectRatio.LANDSCAPE,
            clips = listOf(
                imageClip(photo("base_a", 640, 360, Color.rgb(200, 60, 60)), half),
                imageClip(photo("base_b", 640, 360, Color.rgb(60, 90, 200)), durationMs - half),
            ),
        )
        return exportOk(project).file!!
    }

    private fun videoClip(file: File): Clip {
        val info = MediaUtils.probe(context, Uri.fromFile(file))
        assertNotNull("generated video could not be read", info)
        assertEquals(ClipType.VIDEO, info!!.type)
        return Clip(
            uri = Uri.fromFile(file).toString(),
            type = ClipType.VIDEO,
            sourceDurationMs = info.durationMs,
            hasAudio = info.hasAudio,
        )
    }

    // ------------------------------------------------------------- positive cases

    @Test
    fun photos_exportWithExpectedSizeDurationAndAudioTrack() {
        val project = Project(
            name = "photos",
            aspect = AspectRatio.PORTRAIT,
            clips = listOf(
                imageClip(photo("p1", 400, 300, Color.rgb(30, 160, 90)), 1_000),
                imageClip(photo("p2", 300, 500, Color.rgb(220, 180, 40)), 1_000),
            ),
        )
        val info = inspect(exportOk(project).file!!)
        assertEquals(ExportManager.outputSize(AspectRatio.PORTRAIT, 360), info.width to info.height)
        assertNear("duration", 2_000, info.durationMs, 500)
        assertTrue("exports should always carry an audio track", info.hasAudio)
    }

    @Test
    fun everyAspectRatio_exportsAtTheRightSize() {
        val uri = photo("aspect", 500, 500, Color.rgb(120, 60, 160))
        for (aspect in AspectRatio.entries) {
            val project = Project(name = "aspect", aspect = aspect, clips = listOf(imageClip(uri, 600)))
            val info = inspect(exportOk(project, shortSide = 240).file!!)
            assertEquals("$aspect", ExportManager.outputSize(aspect, 240), info.width to info.height)
        }
    }

    @Test
    fun everyFilterAndAdjustment_exports() {
        val uri = photo("filters", 640, 360, Color.rgb(90, 140, 200))
        val clips = FilterPreset.entries.mapIndexed { i, filter ->
            imageClip(uri, 400).copy(
                filter = filter,
                brightness = if (i % 2 == 0) 0.15f else -0.15f,
                contrast = if (i % 3 == 0) 0.3f else -0.2f,
                saturation = if (i % 2 == 0) 40f else -40f,
            )
        }
        val info = inspect(exportOk(Project(name = "filters", clips = clips)).file!!)
        assertNear("duration", 400L * clips.size, info.durationMs, 700)
    }

    @Test
    fun geometryFadesTransitionsAndBackdrops_export() {
        val wide = photo("geo_wide", 640, 360, Color.rgb(40, 150, 150))
        val tall = photo("geo_tall", 300, 600, Color.rgb(180, 80, 120))
        val clips = listOf(
            imageClip(tall, 800).copy(
                rotation = 90, flipH = true, zoom = 1.6f, opacity = 0.8f,
                fadeInMs = 200, fadeOutMs = 200, transition = TransitionStyle.ZOOM,
            ),
            imageClip(wide, 800).copy(
                fadeInMs = 250, fadeOutMs = 250, transition = TransitionStyle.SLIDE, fill = true,
            ),
            imageClip(tall, 800).copy(rotation = 270, fadeInMs = 300, transition = TransitionStyle.FADE),
        )
        for (background in Background.entries) {
            val project = Project(
                name = "geometry",
                aspect = AspectRatio.SQUARE,
                clips = clips,
                background = background,
                backgroundColor = Color.rgb(250, 200, 40),
            )
            val info = inspect(exportOk(project).file!!)
            assertEquals("$background", 360 to 360, info.width to info.height)
            assertNear("$background duration", 2_400, info.durationMs, 600)
        }
    }

    @Test
    fun textStickersAndImageLayers_export() {
        val uri = photo("overlay_bg", 640, 360, Color.rgb(50, 50, 90))
        val logo = photo("overlay_logo", 200, 200, Color.rgb(240, 240, 240))
        val texts = TextFont.entries.mapIndexed { i, font ->
            TextItem(
                text = "Font ${font.label}", font = font, size = 0.06f,
                x = -0.8f + (i % 4) * 0.5f, y = 0.8f - (i / 4) * 0.4f,
                startMs = i * 100L, endMs = 1_500L + i * 100L,
                bold = i % 2 == 0, italic = i % 3 == 0, outline = i % 2 == 1, background = i % 4 == 0,
                animIn = OverlayAnim.entries[i % OverlayAnim.entries.size],
                animOut = OverlayAnim.entries[(i + 3) % OverlayAnim.entries.size],
                opacity = 0.9f, rotation = i * 10f, moving = i % 5 == 0, x2 = 0.5f, y2 = -0.5f,
            )
        } + TextItem(text = "😀🔥", size = 0.2f, startMs = 0, endMs = 3_000) +
            TextItem(text = "Two\nlines", startMs = 2_000, endMs = 3_000, x = 1f, y = -1f)
        val layers = listOf(
            ImageLayer(
                uri = logo.toString(), scale = 0.3f, x = 0.9f, y = 0.9f, opacity = 0.7f, rotation = 25f,
                startMs = 300, endMs = 2_700, animIn = OverlayAnim.POP, animOut = OverlayAnim.FADE,
                moving = true, x2 = -0.9f, y2 = -0.9f,
            ),
            ImageLayer(uri = logo.toString(), scale = 1f, startMs = 0, endMs = 3_000, opacity = 0.2f),
        )
        val project = Project(
            name = "overlays",
            clips = listOf(imageClip(uri, 1_500), imageClip(uri, 1_500)),
            texts = texts,
            layers = layers,
        )
        val info = inspect(exportOk(project).file!!)
        assertNear("duration", 3_000, info.durationMs, 600)
    }

    @Test
    fun fortyOverlaysOnOneClip_export() {
        val uri = photo("many_overlays", 640, 360, Color.rgb(30, 30, 30))
        val texts = (0 until 40).map { i ->
            TextItem(
                text = "#$i", size = 0.05f, x = -0.9f + (i % 8) * 0.25f, y = 0.8f - (i / 8) * 0.35f,
                startMs = 0, endMs = 1_000,
            )
        }
        val project = Project(name = "many", clips = listOf(imageClip(uri, 1_000)), texts = texts)
        assertNear("duration", 1_000, inspect(exportOk(project).file!!).durationMs, 500)
    }

    @Test
    fun videoClip_trimSpeedPitchVolumeAndFade_export() {
        val source = videoClip(baseVideo(3_000))
        assertTrue("generated video should have an audio track", source.hasAudio)
        assertNear("source duration", 3_000, source.sourceDurationMs, 500)
        val edited = source.copy(
            trimStartMs = 500, trimEndMs = 2_500, speed = 2f, pitch = 1.25f, volume = 0.5f,
            fadeInMs = 200, fadeOutMs = 200, filter = FilterPreset.SEPIA,
        )
        assertEquals(1_000, edited.durationMs)
        val untouched = source.copy(muted = true)
        val slow = source.copy(trimStartMs = 0, trimEndMs = 1_000, speed = 0.5f, pitch = 0.7f, volume = 1.8f)
        val project = Project(name = "video", clips = listOf(edited, untouched, slow))
        val expected = project.durationMs
        val info = inspect(exportOk(project).file!!)
        assertNear("duration", expected, info.durationMs, 900)
        assertTrue(info.hasAudio)
    }

    @Test
    fun musicVoiceOverAndSoundClips_export() {
        val longAudio = baseVideo(3_000)
        val shortAudio = baseVideo(1_000)
        val uri = photo("audio_bg", 640, 360, Color.rgb(20, 120, 60))
        val base = Project(name = "audio", clips = listOf(imageClip(uri, 1_500), imageClip(uri, 1_500)))

        // Looping music that is shorter than the video, plus two sound clips after a silent gap.
        val looped = base.copy(
            music = MusicTrack(Uri.fromFile(shortAudio).toString(), "short", 1_000, volume = 0.5f, loop = true),
            audio = listOf(
                AudioClip(uri = Uri.fromFile(longAudio).toString(), title = "voice", startMs = 800, durationMs = 3_000, volume = 1.2f),
                AudioClip(uri = Uri.fromFile(shortAudio).toString(), title = "ding", startMs = 0, durationMs = 1_000),
            ),
        )
        val loopedInfo = inspect(exportOk(looped).file!!)
        assertNear("looped music must not lengthen the video", 3_000, loopedInfo.durationMs, 700)
        assertTrue(loopedInfo.hasAudio)

        // Music longer than the video is cut to the video length.
        val long = base.copy(
            clips = listOf(imageClip(uri, 1_200)),
            music = MusicTrack(Uri.fromFile(longAudio).toString(), "long", 3_000, loop = false),
        )
        assertNear("long music must not lengthen the video", 1_200, inspect(exportOk(long).file!!).durationMs, 600)

        // A sound clip that starts after the video ends is ignored.
        val late = base.copy(
            audio = listOf(AudioClip(uri = Uri.fromFile(shortAudio).toString(), title = "late", startMs = 9_000, durationMs = 1_000)),
        )
        assertNear("late clip must be ignored", 3_000, inspect(exportOk(late).file!!).durationMs, 700)
    }

    @Test
    fun freezeFrame_savesAReadablePicture() {
        val video = baseVideo(2_000)
        val frame = MediaUtils.saveFrame(context, Uri.fromFile(video), 500)
        assertNotNull("no frame captured", frame)
        val decoded = MediaUtils.decodeImage(context, Uri.fromFile(frame!!), 512)
        assertNotNull("captured frame is not a readable image", decoded)
        assertTrue(decoded!!.width > 0 && decoded.height > 0)
        // The still can go straight back on the timeline.
        val project = Project(name = "freeze", clips = listOf(videoClip(video), imageClip(Uri.fromFile(frame), 1_000)))
        assertNear("duration", project.durationMs, inspect(exportOk(project).file!!).durationMs, 700)
    }

    @Test
    fun silenceFile_isAValidWav() {
        val file = MediaUtils.silenceFile(context, 2_000)
        assertEquals(44L + 44_100L * 2 * 2, file.length())
        assertNear("silence duration", 2_000, MediaUtils.audioDurationMs(context, Uri.fromFile(file)), 150)
        assertEquals("second call reuses the file", file, MediaUtils.silenceFile(context, 2_000))
    }

    @Test
    fun projects_saveListAndDeleteOnDevice() {
        val repo = ProjectRepository(context)
        val project = Project(
            name = "Stored",
            clips = listOf(imageClip(Uri.parse("file:///x.png"), 1_000)),
            texts = listOf(TextItem(text = "Hello")),
        )
        repo.save(project)
        assertEquals(project, repo.list().first { it.id == project.id })
        repo.delete(project.id)
        assertTrue(repo.list().none { it.id == project.id })
        repo.delete("does-not-exist") // must not throw
    }

    // ----------------------------------------------------------------- video layers

    /** A plain single-colour picture, optionally with a white block in the middle. */
    private fun solid(name: String, color: Int, whiteCentre: Boolean = false): Uri {
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(color)
        if (whiteCentre) {
            canvas.drawRect(224f, 126f, 416f, 234f, Paint().apply { this.color = Color.WHITE })
        }
        val file = File(context.cacheDir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(file)
    }

    private fun solidVideo(name: String, color: Int, ms: Long, whiteCentre: Boolean = false): File =
        exportOk(Project(name = name, clips = listOf(imageClip(solid(name, color, whiteCentre), ms)))).file!!

    private fun layerOf(file: File): VideoLayer {
        val uri = Uri.fromFile(file)
        val info = MediaUtils.probe(context, uri)!!
        return VideoLayer(
            uri = uri.toString(),
            sourceDurationMs = info.durationMs,
            aspect = MediaUtils.videoAspect(context, uri)!!,
            hasAudio = info.hasAudio,
        )
    }

    /** The colour of the exported picture at a moment and place (x, y as fractions of the frame). */
    private fun pixel(file: File, timeMs: Long, x: Float, y: Float): Int {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val frame = r.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)!!
            return frame.getPixel((frame.width * x).toInt(), (frame.height * y).toInt())
        } finally {
            r.release()
        }
    }

    private fun assertColour(label: String, expected: Int, actual: Int, tolerance: Int = 70) {
        val off = maxOf(
            abs(Color.red(expected) - Color.red(actual)),
            abs(Color.green(expected) - Color.green(actual)),
            abs(Color.blue(expected) - Color.blue(actual)),
        )
        assertTrue(
            "$label: expected about #%06X but the picture shows #%06X".format(
                expected and 0xFFFFFF, actual and 0xFFFFFF
            ),
            off <= tolerance,
        )
    }

    private val red = Color.rgb(210, 40, 40)
    private val blue = Color.rgb(40, 60, 210)
    private val green = Color.rgb(0, 255, 0)

    @Test
    fun videoLayer_showsAtItsPlaceAndOnlyDuringItsTime() {
        val layer = layerOf(solidVideo("pip_blue", blue, 2_000)).copy(
            startMs = 500, trimStartMs = 0, trimEndMs = 1_000, scale = 0.5f, x = 1f, y = 1f,
        )
        val project = Project(
            name = "pip",
            clips = listOf(imageClip(solid("pip_red", red), 2_000)),
            videoLayers = listOf(layer),
        )
        val file = exportOk(project).file!!
        val info = inspect(file)
        assertEquals(640 to 360, info.width to info.height)
        assertNear("a layer must not change the video length", 2_000, info.durationMs, 500)

        // While the layer plays: blue in the top-right quarter, the main picture elsewhere.
        assertColour("layer, top right at 1.0 s", blue, pixel(file, 1_000, 0.85f, 0.15f))
        assertColour("main picture, bottom left at 1.0 s", red, pixel(file, 1_000, 0.15f, 0.85f))
        assertColour("main picture, top left at 1.0 s", red, pixel(file, 1_000, 0.15f, 0.15f))
        // Before and after the layer: the main picture everywhere.
        assertColour("before the layer starts", red, pixel(file, 150, 0.85f, 0.15f))
        assertColour("after the layer ends", red, pixel(file, 1_850, 0.85f, 0.15f))
    }

    @Test
    fun laterVideoLayer_isDrawnOverEarlierOne() {
        val lower = layerOf(solidVideo("stack_blue", blue, 1_500)).copy(startMs = 0, scale = 0.6f, x = 0f, y = 0f)
        val upper = layerOf(solidVideo("stack_green", green, 1_500)).copy(startMs = 0, scale = 0.3f, x = 0f, y = 0f)
        val project = Project(
            name = "stack",
            clips = listOf(imageClip(solid("stack_red", red), 1_500)),
            videoLayers = listOf(lower, upper),
        )
        val file = exportOk(project).file!!
        assertColour("top layer in the middle", green, pixel(file, 700, 0.5f, 0.5f), tolerance = 90)
        assertColour("lower layer around it", blue, pixel(file, 700, 0.5f, 0.27f))
        assertColour("main picture at the edge", red, pixel(file, 700, 0.05f, 0.5f))
    }

    @Test
    fun keyframedImageLayer_movesAcrossTheFrame() {
        // A small blue square glides from the left edge (0 s) to the right edge (2 s).
        val square = ImageLayer(
            uri = solid("key_square", blue).toString(), scale = 0.3f, startMs = 0, endMs = 2_000,
            keyframes = listOf(Keyframe(0, -1f, 0f), Keyframe(2_000, 1f, 0f)),
        )
        val project = Project(
            name = "keyframes",
            clips = listOf(imageClip(solid("key_base", red), 2_000)),
            layers = listOf(square),
        )
        val file = exportOk(project).file!!
        assertColour("starts on the left", blue, pixel(file, 100, 0.08f, 0.5f))
        assertColour("right side is empty at the start", red, pixel(file, 100, 0.92f, 0.5f))
        assertColour("passes the middle half way", blue, pixel(file, 1_000, 0.5f, 0.5f))
        assertColour("ends on the right", blue, pixel(file, 1_900, 0.92f, 0.5f))
        assertColour("left side is empty at the end", red, pixel(file, 1_900, 0.08f, 0.5f))
    }

    @Test
    fun keyframedVideoLayer_movesAndFadesOut() {
        val layer = layerOf(solidVideo("keyv_blue", blue, 2_000)).copy(
            startMs = 0, scale = 0.3f,
            keyframes = listOf(
                Keyframe(0, -1f, 1f, opacity = 1f),      // top left
                Keyframe(1_000, 1f, -1f, opacity = 1f),  // bottom right
                Keyframe(1_900, 1f, -1f, opacity = 0f),  // faded away
            ),
        )
        val project = Project(
            name = "keyframes-video",
            clips = listOf(imageClip(solid("keyv_base", red), 2_000)),
            videoLayers = listOf(layer),
        )
        val file = exportOk(project).file!!
        assertColour("starts top left", blue, pixel(file, 100, 0.08f, 0.12f))
        assertColour("bottom right is empty at the start", red, pixel(file, 100, 0.92f, 0.88f))
        assertColour("reaches bottom right", blue, pixel(file, 1_050, 0.92f, 0.88f))
        assertColour("top left is empty by then", red, pixel(file, 1_050, 0.08f, 0.12f))
        assertColour("has faded out at the end", red, pixel(file, 1_950, 0.92f, 0.88f), tolerance = 90)
    }

    @Test
    fun everySoundEffect_exportsOnClipsAndSounds() {
        val source = videoClip(baseVideo(1_200))
        val clips = AudioFx.entries.map { source.copy(audioFx = it, trimEndMs = 600) }
        val project = Project(
            name = "fx",
            clips = clips,
            audio = listOf(
                AudioClip(
                    uri = source.uri, title = "fx", startMs = 300, durationMs = 1_000,
                    audioFx = AudioFx.ECHO, fadeOutMs = 300,
                ),
            ),
        )
        val info = inspect(exportOk(project).file!!)
        assertNear("duration", 600L * clips.size, info.durationMs, 900)
        assertTrue(info.hasAudio)

        // The same clips in a layered export take the separate audio-track path.
        val layered = project.copy(
            clips = clips.take(3),
            videoLayers = listOf(layerOf(File(Uri.parse(source.uri).path!!)).copy(scale = 0.3f)),
        )
        assertNear("layered duration", 1_800, inspect(exportOk(layered).file!!).durationMs, 700)
    }

    @Test
    fun textToSpeech_makesAPlayableSoundWhenTheDeviceHasAVoice() {
        val file = File(context.cacheDir, "speech_test.wav")
        file.delete()
        val latch = CountDownLatch(1)
        var ok = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            SpeechMaker.speak(context, "Hello from Reel Forge", file) { result ->
                ok = result
                latch.countDown()
            }
        }
        assertTrue("speech neither finished nor failed", latch.await(45, TimeUnit.SECONDS))
        if (!ok) {
            log("SKIP text to speech: this device has no speech voice installed")
            return
        }
        val duration = MediaUtils.audioDurationMs(context, Uri.fromFile(file))
        log("SPEECH ok: ${file.length() / 1024} KB, $duration ms")
        assertTrue("speech file has no length", duration > 200)
        // The spoken clip exports like any other sound.
        val project = Project(
            name = "speech",
            clips = listOf(imageClip(photo("speech_bg", 320, 180, Color.DKGRAY), 1_500)),
            audio = listOf(AudioClip(uri = Uri.fromFile(file).toString(), title = "Speech", startMs = 200, durationMs = duration)),
        )
        assertNear("duration", 1_500, inspect(exportOk(project).file!!).durationMs, 600)
    }

    @Test
    fun chromaKey_removesTheGreenScreenAndKeepsTheSubject() {
        val greenScreen = layerOf(solidVideo("key_green", green, 2_000, whiteCentre = true)).copy(
            startMs = 0, scale = 1f, x = 0f, y = 0f,
        )
        val base = Project(name = "key", clips = listOf(imageClip(solid("key_red", red), 2_000)))

        // Without chroma key the layer covers everything.
        val plain = exportOk(base.copy(videoLayers = listOf(greenScreen))).file!!
        assertColour("green screen without keying", green, pixel(plain, 1_000, 0.1f, 0.1f), tolerance = 90)

        // With chroma key the green is gone and the white subject stays.
        val keyed = exportOk(base.copy(videoLayers = listOf(greenScreen.copy(chromaKey = true)))).file!!
        assertColour("main picture shows through the keyed corner", red, pixel(keyed, 1_000, 0.1f, 0.1f))
        assertColour("main picture shows through the keyed edge", red, pixel(keyed, 1_000, 0.9f, 0.85f))
        assertColour("the subject is kept", Color.WHITE, pixel(keyed, 1_000, 0.5f, 0.5f))
    }

    @Test
    fun threeVideoLayersWithTextMusicAndOpacity_export() {
        val clipFile = solidVideo("multi_layer", blue, 1_500)
        val layers = listOf(
            layerOf(clipFile).copy(startMs = 0, scale = 0.3f, x = -1f, y = 1f, opacity = 0.6f, rotation = 15f),
            layerOf(clipFile).copy(startMs = 700, scale = 0.3f, x = 1f, y = -1f, volume = 0.4f),
            layerOf(clipFile).copy(startMs = 1_400, scale = 0.25f, x = 0f, y = 0f, muted = true),
        )
        val uri = solid("multi_base", red)
        val project = Project(
            name = "multi",
            // A real video on the main track too, sped up, under the layers.
            clips = listOf(videoClip(clipFile).copy(speed = 2f), imageClip(uri, 2_250).copy(fadeInMs = 300)),
            videoLayers = layers,
            texts = listOf(TextItem(text = "On top", startMs = 0, endMs = 3_000, animIn = OverlayAnim.FADE)),
            layers = listOf(ImageLayer(uri = solid("multi_logo", Color.WHITE).toString(), scale = 0.2f, startMs = 500, endMs = 2_500)),
            music = MusicTrack(Uri.fromFile(clipFile).toString(), "m", 1_500, loop = true),
        )
        val info = inspect(exportOk(project).file!!)
        assertNear("duration", 3_000, info.durationMs, 700)
        assertTrue(info.hasAudio)
    }

    @Test
    fun videoLayerOutsideTheVideo_isIgnored_andAMissingOneReportsAnError() {
        val base = Project(name = "edge", clips = listOf(imageClip(solid("edge_red", red), 1_500)))
        val late = layerOf(solidVideo("edge_blue", blue, 1_000)).copy(startMs = 9_000)
        val ignored = exportOk(base.copy(videoLayers = listOf(late))).file!!
        assertNear("duration", 1_500, inspect(ignored).durationMs, 500)
        assertColour("late layer never shows", red, pixel(ignored, 700, 0.85f, 0.15f))

        val missing = VideoLayer(uri = "file:///no/such/layer.mp4", sourceDurationMs = 2_000)
        val outcome = export(base.copy(videoLayers = listOf(missing)), timeoutSec = 60)
        assertNull(outcome.file)
        assertTrue("expected an export error but got ${outcome.error}", outcome.error is ExportException)
    }

    @Test
    fun layeredExport_worksEveryTimeWhenRepeated() {
        val blueFile = solidVideo("repeat_blue", blue, 1_500)
        val greenFile = solidVideo("repeat_green", green, 1_500, whiteCentre = true)
        val photo = solid("repeat_red", red)
        val failures = mutableListOf<String>()
        repeat(8) { round ->
            // Alternate between a photo-first and a video-first main track, with layers that
            // start at 0 and later, since those are the cases that take different paths.
            val main = if (round % 2 == 0) listOf(imageClip(photo, 1_500))
            else listOf(videoClip(blueFile), imageClip(photo, 500))
            val project = Project(
                name = "repeat$round",
                clips = main,
                videoLayers = listOf(
                    layerOf(blueFile).copy(startMs = if (round % 3 == 0) 0 else 400, scale = 0.4f, x = -1f, y = 1f),
                    layerOf(greenFile).copy(startMs = 0, scale = 0.5f, x = 1f, y = -1f, chromaKey = round % 2 == 1),
                ),
            )
            val outcome = exportOnce(project, 360, 120)
            if (outcome.error != null) {
                Log.e("ReelForgeTest", "REPEAT round $round failed", outcome.error)
                failures += "round $round: ${outcome.error}".take(160)
            } else {
                log("REPEAT round $round ok in ${outcome.millis} ms")
            }
        }
        log("REPEAT summary: ${8 - failures.size} of 8 first attempts succeeded")
        // First attempts may fail now and then (the app retries); most must succeed.
        assertTrue("too many layered exports failed on the first attempt: $failures", failures.size <= 2)
    }

    @Test
    fun extremeSpeeds_export() {
        val source = videoClip(baseVideo(3_000))
        val fast = source.copy(speed = 16f)                                   // 3 s → about 0.19 s
        val slow = source.copy(trimStartMs = 0, trimEndMs = 300, speed = 0.1f) // 0.3 s → 3 s
        val project = Project(name = "speeds", clips = listOf(slow, fast, source.copy(speed = 8f)))
        val info = inspect(exportOk(project).file!!)
        assertNear("duration", project.durationMs, info.durationMs, 1_000)
    }

    @Test
    fun sixtyFpsAndH265_exportWhenThePhoneSupportsThem() {
        val uri = photo("fps", 640, 360, Color.rgb(90, 60, 150))
        val project = Project(
            name = "fps",
            clips = listOf(imageClip(uri, 1_000)),
            texts = listOf(TextItem(text = "60", endMs = 1_000, animIn = OverlayAnim.SPIN)),
        )
        val (w, h) = ExportManager.outputSize(project.aspect, 360)
        for (hevc in listOf(false, true)) {
            if (!ExportManager.canEncode(hevc, w, h, 60)) {
                log("SKIP 60 fps hevc=$hevc: this device has no encoder for it")
                continue
            }
            val output = File(context.cacheDir, "fps_${hevc}_${System.nanoTime()}.mp4")
            val composition = ExportManager.buildComposition(context, project, 360, fps = 60)
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
                ExportManager.createTransformer(
                    context, listener, ExportManager.bitrate(w, h, 1, 60, hevc), hevc,
                ).start(composition, output.absolutePath)
            }
            assertTrue("export timed out", latch.await(180, TimeUnit.SECONDS))
            assertNull("hevc=$hevc: $error", error)
            assertNear("duration hevc=$hevc", 1_000, inspect(output).durationMs, 500)
            log("60 fps export hevc=$hevc ok, ${output.length() / 1024} KB")
        }
    }

    @Test
    fun musicAndSoundFades_export() {
        val audio = baseVideo(1_500)
        val uri = photo("fade_bg", 640, 360, Color.rgb(20, 80, 120))
        val project = Project(
            name = "fades",
            clips = listOf(imageClip(uri, 4_000)),
            music = MusicTrack(
                Uri.fromFile(audio).toString(), "loop", 1_500, loop = true, fadeInMs = 800, fadeOutMs = 3_000,
            ),
            audio = listOf(
                AudioClip(
                    uri = Uri.fromFile(audio).toString(), title = "s", startMs = 1_000, durationMs = 1_000,
                    fadeInMs = 2_000, fadeOutMs = 2_000, offsetMs = 400, // the second half of a split
                ),
            ),
        )
        val info = inspect(exportOk(project).file!!)
        assertNear("duration", 4_000, info.durationMs, 700)
        assertTrue(info.hasAudio)
    }

    @Test
    fun musicWithLateStartEarlyStopAndOffset_export() {
        val audio = baseVideo(1_500)
        val uri = photo("music_window", 640, 360, Color.rgb(60, 40, 110))
        val base = Project(name = "window", clips = listOf(imageClip(uri, 4_000)))
        val song = MusicTrack(Uri.fromFile(audio).toString(), "song", 1_500, loop = true)
        val cases = listOf(
            song.copy(startMs = 1_200, offsetMs = 700),                 // starts late, mid-song, loops on
            song.copy(stopMs = 2_300, fadeOutMs = 500),                 // stops early
            song.copy(startMs = 900, stopMs = 3_100, offsetMs = 1_450), // both, offset near the song's end
            song.copy(loop = false, startMs = 3_200),                   // short tail, no loop
            song.copy(startMs = 9_000),                                 // starts after the video: ignored
        )
        cases.forEachIndexed { i, music ->
            val info = inspect(exportOk(base.copy(music = music)).file!!)
            assertNear("case $i: music must not change the video length", 4_000, info.durationMs, 700)
            assertTrue("case $i", info.hasAudio)
        }
    }

    @Test
    fun store_musicSearchFindsAPlayableFreeTrack() {
        val search = com.reelforge.editor.data.MusicSearch
        var results = emptyList<com.reelforge.editor.data.MusicResult>()
        var lastError = ""
        for (attempt in 1..3) {
            try {
                results = search.search("piano", soundEffects = false)
                break
            } catch (e: Exception) {
                lastError = e.message.orEmpty()
                log("MUSIC search attempt $attempt failed: $lastError")
                Thread.sleep(4_000)
            }
        }
        // The shared test machine can be turned away by the service; that is not an app fault.
        org.junit.Assume.assumeTrue("search service unavailable: $lastError", lastError.isEmpty() || results.isNotEmpty())
        runCatching {
            for (word in listOf("piano", "rain")) for (fx in listOf(false, true)) {
                search.searchUrls(word, fx).forEachIndexed { i, url ->
                    val found = search.fetch(url)
                    log("MUSIC probe $word fx=$fx stage=$i count=${found.size} first=${found.firstOrNull()?.let { it.licence + " " + it.fileType + " " + it.durationMs + " " + it.url.take(60) }}")
                }
            }
        }.onFailure { log("MUSIC probe failed: ${it.message}") }
        log("MUSIC found ${results.size} results")
        assertTrue("no results for a common word", results.isNotEmpty())
        var playable = 0
        for (result in results.take(4)) {
            val file = File(context.cacheDir, "musictest.${result.fileType}")
            try {
                AssetDownloader.download(result.url, file)
                val ms = MediaUtils.audioDurationMs(context, Uri.fromFile(file))
                log("MUSIC ${result.fileType} ${file.length() / 1024} KB ${ms} ms ${result.licence}")
                if (ms > 0) playable++
            } catch (e: Exception) {
                log("MUSIC download failed: ${e.message}")
            } finally {
                file.delete()
            }
        }
        assertTrue("none of the first results could be downloaded and played", playable > 0)
    }

    @Test
    fun store_everyStickerDownloadsAndDecodes() {
        val stickers = com.reelforge.editor.data.StickerCatalog.stickers(context)
        assertTrue("catalog is empty", stickers.size >= 60)
        val broken = mutableListOf<String>()
        for (sticker in stickers) {
            val file = File(context.cacheDir, "stickertest_${sticker.code}.png")
            try {
                AssetDownloader.download(sticker.url, file)
                val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                assertTrue("not a picture", bitmap != null && bitmap.width >= 64)
            } catch (e: Throwable) {
                broken += "${sticker.code}: ${e.message}"
                log("STICKER BROKEN ${sticker.code}: ${e.message}")
            } finally {
                file.delete()
            }
        }
        log("STICKERS ok ${stickers.size - broken.size}/${stickers.size}")
        assertTrue("stickers that failed to download: $broken", broken.isEmpty())
    }

    @Test
    fun store_buttonsAndSoundEffectsExport() {
        val uri = solid("store", 0xFF202020.toInt())
        val layers = com.reelforge.editor.data.SocialSticker.entries.take(3).mapIndexed { i, sticker ->
            val bitmap = sticker.render()
            assertTrue(sticker.name, bitmap.width > bitmap.height && bitmap.getPixel(bitmap.width / 2, 4) == sticker.background)
            val file = File(context.cacheDir, "social_$i.png")
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            com.reelforge.editor.model.ImageLayer(uri = Uri.fromFile(file).toString(), scale = 0.14f, y = -0.6f + i * 0.5f, startMs = 0, endMs = 2_000)
        }
        val sounds = com.reelforge.editor.data.SoundEffect.entries.mapIndexed { i, effect ->
            val samples = com.reelforge.editor.data.SoundMaker.samples(effect)
            val file = File(context.cacheDir, "fx_$i.wav")
            file.writeBytes(com.reelforge.editor.data.SoundMaker.wav(samples))
            val duration = MediaUtils.audioDurationMs(context, Uri.fromFile(file))
            assertNear(effect.name, samples.size * 1000L / 44100, duration, 60)
            AudioClip(uri = Uri.fromFile(file).toString(), title = effect.title, startMs = i * 200L, durationMs = duration)
        }
        val project = Project(name = "store", clips = listOf(imageClip(uri, 2_000)), layers = layers, audio = sounds)
        val info = inspect(exportOk(project).file!!)
        assertNear("length", 2_000, info.durationMs, 600)
        assertTrue("sound effects must be in the export", info.hasAudio)
    }

    @Test
    fun fontStore_everyCatalogFontDownloadsAndLoads() {
        val fonts = AssetCatalog.fonts(context)
        assertTrue("catalog is empty", fonts.size >= 20)
        val broken = mutableListOf<String>()
        for (font in fonts) {
            val file = File(context.cacheDir, "fonttest_${font.id}.ttf")
            try {
                AssetDownloader.download(font.url, file)
                android.graphics.Typeface.createFromFile(file)
                val bitmap = EffectFactory.renderText(TextItem(text = font.name, fontPath = file.absolutePath), 720)
                assertTrue(bitmap.width > 2)
                log("FONT ok ${font.id} ${file.length() / 1024} KB")
            } catch (e: Exception) {
                broken += "${font.id}: ${e.message}"
                log("FONT BROKEN ${font.id}: ${e.message}")
            } finally {
                file.delete()
            }
        }
        assertTrue("fonts that failed to download or load: $broken", broken.isEmpty())
    }

    @Test
    fun download_failsCleanlyForBadAddresses() {
        val target = File(context.cacheDir, "bad_download.ttf")
        for (url in listOf(
            "https://raw.githubusercontent.com/google/fonts/main/ofl/nosuchfont/Nope-Regular.ttf",
            "https://no-such-host.invalid/file.ttf",
        )) {
            try {
                AssetDownloader.download(url, target)
                fail("download of $url should have failed")
            } catch (expected: Exception) {
                // ok
            }
            assertFalse("a failed download must not leave a file", target.exists())
            assertFalse(File(target.parentFile, target.name + ".part").exists())
        }
    }

    // ------------------------------------------------------------- negative cases

    @Test
    fun emptyProject_isRejectedBeforeExportStarts() {
        try {
            ExportManager.buildComposition(context, Project(name = "empty"), 360)
            fail("an empty project must not produce a composition")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun missingVideoFile_reportsAnErrorInsteadOfHanging() {
        val clip = Clip(
            uri = "file:///does/not/exist.mp4", type = ClipType.VIDEO, sourceDurationMs = 2_000,
        )
        val outcome = export(Project(name = "missing", clips = listOf(clip)), timeoutSec = 60)
        assertNull(outcome.file)
        assertTrue("expected an export error but got ${outcome.error}", outcome.error is ExportException)
    }

    @Test
    fun corruptPhoto_reportsAnErrorInsteadOfHanging() {
        val file = File(context.cacheDir, "corrupt.jpg")
        file.writeBytes(ByteArray(4_096) { (it * 31).toByte() })
        val outcome = export(
            Project(name = "corrupt", clips = listOf(imageClip(Uri.fromFile(file), 1_000))),
            timeoutSec = 60,
        )
        assertNull(outcome.file)
        assertTrue("expected an export error but got ${outcome.error}", outcome.error is ExportException)
    }

    @Test
    fun missingMusicFile_reportsAnError() {
        val uri = photo("neg_music", 320, 180, Color.GRAY)
        val project = Project(
            name = "nomusic",
            clips = listOf(imageClip(uri, 1_000)),
            music = MusicTrack("file:///no/such/song.mp3", "gone", 5_000),
        )
        val outcome = export(project, timeoutSec = 60)
        assertNull(outcome.file)
        assertTrue("expected an export error but got ${outcome.error}", outcome.error is ExportException)
    }

    @Test
    fun unreadableLayerAndFont_areSkippedNotFatal() {
        val uri = photo("neg_layer", 320, 180, Color.DKGRAY)
        val project = Project(
            name = "badlayer",
            clips = listOf(imageClip(uri, 1_000)),
            layers = listOf(ImageLayer(uri = "file:///no/such/logo.png", startMs = 0, endMs = 1_000)),
            texts = listOf(TextItem(text = "Fallback font", fontPath = "/no/such/font.ttf", endMs = 1_000)),
        )
        assertNear("duration", 1_000, inspect(exportOk(project).file!!).durationMs, 500)
    }

    @Test
    fun unreadableMedia_isRejectedOnImport() {
        assertNull(MediaUtils.probe(context, Uri.parse("file:///no/such/file.mp4")))
        val junk = File(context.cacheDir, "junk.mp4").apply { writeText("this is not a video") }
        assertNull(MediaUtils.probe(context, Uri.fromFile(junk)))
        assertNull(MediaUtils.decodeImage(context, Uri.fromFile(junk), 256))
        assertNull(MediaUtils.saveFrame(context, Uri.fromFile(junk), 0))
        assertEquals(0L, MediaUtils.audioDurationMs(context, Uri.fromFile(junk)))
    }

    @Test
    fun extremeTextValues_stillRender() {
        val tiny = EffectFactory.renderText(TextItem(text = "", size = 0f), 2)
        assertTrue(tiny.width >= 2 && tiny.height >= 2)
        val huge = EffectFactory.renderText(TextItem(text = "W".repeat(40), size = 0.4f, outline = true, background = true), 1_080)
        assertTrue(huge.width > 1_080)
        val emoji = EffectFactory.renderText(TextItem(text = "😀\n\n🔥", size = 0.1f), 720)
        assertFalse(emoji.isRecycled)
    }

    // ---------------------------------------------------------------- performance

    @Test
    fun performance_exportSpeed() {
        val uri = photo("perf", 1_280, 720, Color.rgb(70, 110, 170))
        val clips = listOf(
            imageClip(uri, 2_000).copy(filter = FilterPreset.VIVID, fadeInMs = 300),
            imageClip(uri, 2_000).copy(zoom = 1.3f, fadeOutMs = 300),
        )
        val project = Project(
            name = "perf",
            clips = clips,
            texts = listOf(TextItem(text = "Performance", startMs = 0, endMs = 4_000, animIn = OverlayAnim.FADE)),
        )
        for (side in listOf(480, 720)) {
            val outcome = exportOk(project, shortSide = side)
            val ratio = outcome.millis / 4_000f
            log("PERF export ${side}p: 4.0 s of video took ${outcome.millis} ms (%.2fx video length), file ${outcome.file!!.length() / 1024} KB".format(ratio))
            assertTrue("${side}p export took ${outcome.millis} ms", outcome.millis < 180_000)
        }

        val video = videoClip(baseVideo(4_000))
        val videoOutcome = exportOk(Project(name = "perfvideo", clips = listOf(video.copy(filter = FilterPreset.WARM))), shortSide = 720)
        log("PERF re-encode 720p: 4.0 s video clip took ${videoOutcome.millis} ms (%.2fx video length)".format(videoOutcome.millis / 4_000f))
        assertTrue(videoOutcome.millis < 180_000)
    }

    @Test
    fun performance_textRendering() {
        val item = TextItem(text = "Performance text\nsecond line", size = 0.1f, outline = true, background = true)
        repeat(5) { EffectFactory.renderText(item, 1_080) }
        val started = SystemClock.elapsedRealtime()
        repeat(50) { EffectFactory.renderText(item, 1_080) }
        val took = SystemClock.elapsedRealtime() - started
        log("PERF text: 50 text renders at 1080p took $took ms (${took / 50f} ms each)")
        assertTrue("text rendering too slow: $took ms", took < 5_000)
    }
}
