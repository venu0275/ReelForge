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
import com.reelforge.editor.util.MediaUtils
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

    private fun export(project: Project, shortSide: Int = 360, timeoutSec: Long = 240): Outcome {
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
                    uri = Uri.fromFile(audio).toString(), title = "s", startMs = 1_000, durationMs = 1_500,
                    fadeInMs = 2_000, fadeOutMs = 2_000,
                ),
            ),
        )
        val info = inspect(exportOk(project).file!!)
        assertNear("duration", 4_000, info.durationMs, 700)
        assertTrue(info.hasAudio)
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
