package com.reelforge.editor.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/** How much of the phone's photo and video library the user has let the app see. */
enum class MediaAccess { FULL, PARTIAL, NONE }

/** One photo or video on the phone. [uri] is a content:// address. */
data class LibraryItem(
    val uri: String,
    val name: String,
    val isVideo: Boolean,
    val durationMs: Long,
    val albumId: String,
    val albumName: String,
    val modified: Long,
)

/** A folder of photos and videos, newest first. */
data class LibraryAlbum(val id: String, val name: String, val items: List<LibraryItem>)

/** Reads the phone's photos and videos for the in-app media screen (only with the user's permission). */
object MediaLibrary {

    /** The permissions to ask for, in one request, on this Android version. */
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun access(context: Context): MediaAccess {
        fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 33 &&
                (has(Manifest.permission.READ_MEDIA_IMAGES) || has(Manifest.permission.READ_MEDIA_VIDEO)) -> MediaAccess.FULL
            Build.VERSION.SDK_INT >= 34 && has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.PARTIAL
            Build.VERSION.SDK_INT < 33 && has(Manifest.permission.READ_EXTERNAL_STORAGE) -> MediaAccess.FULL
            else -> MediaAccess.NONE
        }
    }

    /** Every photo and video the app may see, newest first. Empty without permission. */
    fun load(context: Context): List<LibraryItem> {
        val out = ArrayList<LibraryItem>()
        val type = MediaStore.Files.FileColumns.MEDIA_TYPE
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            type,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val image = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
        val video = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        runCatching {
            context.contentResolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                projection,
                "$type = ? OR $type = ?",
                arrayOf(image.toString(), video.toString()),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val typeCol = c.getColumnIndexOrThrow(type)
                val nameCol = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val durationCol = c.getColumnIndex(MediaStore.MediaColumns.DURATION)
                val albumCol = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID)
                val albumNameCol = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val modifiedCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val isVideo = c.getInt(typeCol) == video
                    val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    out += LibraryItem(
                        uri = ContentUris.withAppendedId(base, id).toString(),
                        name = (if (nameCol >= 0) c.getString(nameCol) else null).orEmpty(),
                        isVideo = isVideo,
                        durationMs = if (durationCol >= 0) c.getLong(durationCol) else 0L,
                        albumId = (if (albumCol >= 0) c.getString(albumCol) else null).orEmpty(),
                        albumName = (if (albumNameCol >= 0) c.getString(albumNameCol) else null).orEmpty(),
                        modified = if (modifiedCol >= 0) c.getLong(modifiedCol) else 0L,
                    )
                }
            }
        }
        return out
    }

    /** The items grouped into folders, the folder with the newest item first. */
    fun albums(items: List<LibraryItem>): List<LibraryAlbum> =
        items.groupBy { it.albumId }
            .map { (id, list) ->
                LibraryAlbum(id, list.first().albumName.ifBlank { "Other" }, list.sortedByDescending { it.modified })
            }
            .sortedByDescending { album -> album.items.first().modified }
}
