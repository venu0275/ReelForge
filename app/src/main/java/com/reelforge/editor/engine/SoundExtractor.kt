package com.reelforge.editor.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Takes the sound out of a video (Extract sound): writes it as an AAC .m4a file. Uses the
 * phone's own encoder through Media3, so nothing extra is downloaded.
 */
@OptIn(UnstableApi::class)
object SoundExtractor {

    /** The pitch the exporter allows for a clip's own voice changer. */
    fun safePitch(pitch: Float) = pitch.coerceIn(0.5f, 2f)

    /**
     * Writes the sound of [uri] from [fromMs] to [toMs] to [out], played at [speed] and [pitch]
     * (as the clip sounds on the timeline). [progress] gets 0..1. Must be called from the main
     * thread. Cancelling the coroutine stops the work and deletes the file.
     */
    suspend fun extract(
        context: Context,
        uri: Uri,
        fromMs: Long,
        toMs: Long,
        speed: Float,
        pitch: Float,
        out: File,
        progress: (Float) -> Unit,
    ) = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        val media = MediaItem.Builder().setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(fromMs)
                    .setEndPositionMs(toMs)
                    .build()
            )
            .build()
        val audio = if (speed != 1f || pitch != 1f) {
            listOf(SonicAudioProcessor().apply {
                setSpeed(speed)
                setPitch(safePitch(pitch))
            })
        } else {
            emptyList()
        }
        val item = EditedMediaItem.Builder(media)
            .setRemoveVideo(true)
            .setEffects(Effects(audio, emptyList()))
            .build()
        val transformer = Transformer.Builder(context)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    handler.removeCallbacksAndMessages(null)
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    handler.removeCallbacksAndMessages(null)
                    out.delete()
                    if (cont.isActive) cont.resumeWithException(exportException)
                }
            })
            .build()
        val holder = ProgressHolder()
        val poll = object : Runnable {
            override fun run() {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    progress(holder.progress / 100f)
                }
                handler.postDelayed(this, 250)
            }
        }
        cont.invokeOnCancellation {
            handler.post {
                handler.removeCallbacksAndMessages(null)
                runCatching { transformer.cancel() }
                out.delete()
            }
        }
        out.parentFile?.mkdirs()
        transformer.start(item, out.absolutePath)
        handler.post(poll)
    }

    /** A file name with only safe characters. */
    fun fileName(title: String): String {
        val clean = title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().take(40)
        return (if (clean.isEmpty()) "Sound" else clean).replace(' ', '_')
    }

    /**
     * Copies [file] to the phone's Music/CutMitra folder so it shows in music players and can be
     * shared. Returns where it went, or null if it could not be saved.
     */
    fun saveToMusic(context: Context, file: File, title: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName(title) + "_" + System.currentTimeMillis() + ".m4a")
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/CutMitra")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
                ?: error("no output")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }
}
