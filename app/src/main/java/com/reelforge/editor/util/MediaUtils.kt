package com.reelforge.editor.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.reelforge.editor.model.ClipType
import java.io.File
import kotlin.math.max

data class MediaInfo(val type: ClipType, val durationMs: Long, val hasAudio: Boolean)

object MediaUtils {

    const val DEFAULT_IMAGE_DURATION_MS = 3000L

    fun probe(context: Context, uri: Uri): MediaInfo? {
        val mime = context.contentResolver.getType(uri).orEmpty()
        if (mime.startsWith("image")) {
            return MediaInfo(ClipType.IMAGE, DEFAULT_IMAGE_DURATION_MS, false)
        }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return null
            val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            MediaInfo(ClipType.VIDEO, duration, hasAudio)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    fun audioDurationMs(context: Context, uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { r.release() }
        }
    }

    fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val name = c.getString(0)
                        if (!name.isNullOrBlank()) return name
                    }
                }
        }
        return uri.lastPathSegment ?: "Audio"
    }

    fun videoFrame(context: Context, uri: Uri, timeMs: Long, size: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.getScaledFrameAtTime(
                timeMs * 1000,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                size,
                size,
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    fun decodeImage(context: Context, uri: Uri, maxSide: Int): Bitmap? = try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val largest = max(info.size.width, info.size.height)
            decoder.setTargetSampleSize(max(1, largest / maxSide))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (e: Exception) {
        null
    }

    /** Copies an exported file into the shared Movies/ReelForge folder and returns its Uri. */
    fun saveToGallery(context: Context, file: File, displayName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ReelForge")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Could not create gallery entry")
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: throw IllegalStateException("Could not open gallery entry")
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        file.delete()
        return uri
    }

    /** Saves the video frame nearest to [timeMs] as a JPEG in app storage. */
    fun saveFrame(context: Context, uri: Uri, timeMs: Long): File? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val frame = r.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: return null
            val dir = File(context.filesDir, "frames").apply { mkdirs() }
            val file = File(dir, "frame_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            file
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /** Returns a WAV file of silence ([durationMs] long, mono 44.1 kHz), creating it if needed. */
    fun silenceFile(context: Context, durationMs: Long): File {
        val file = File(context.cacheDir, "silence_$durationMs.wav")
        if (file.exists() && file.length() > 44) return file
        val sampleRate = 44100
        val dataSize = (sampleRate * 2L * durationMs / 1000).toInt()
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)                 // PCM
            putShort(1)                 // mono
            putInt(sampleRate)
            putInt(sampleRate * 2)      // byte rate
            putShort(2)                 // block align
            putShort(16)                // bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }
        file.outputStream().use { out ->
            out.write(header.array())
            val chunk = ByteArray(8192)
            var left = dataSize
            while (left > 0) {
                val n = minOf(left, chunk.size)
                out.write(chunk, 0, n)
                left -= n
            }
        }
        return file
    }

    fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val tenths = ((ms % 1000) / 100).coerceAtLeast(0)
        return "%02d:%02d.%d".format(totalSec / 60, totalSec % 60, tenths)
    }
}
