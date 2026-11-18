package com.reelforge.editor.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/** One song or audio file on the phone. [uri] is a content:// address. */
data class SongItem(
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: String,
    val durationMs: Long,
    val folderId: String,
    val folderName: String,
    val modified: Long,
)

/** A group of songs: an album or a folder. */
data class SongGroup(val id: String, val name: String, val songs: List<SongItem>)

/** Reads the phone's music and audio files for the in-app music screen (with the user's permission). */
object MusicLibrary {

    fun permission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasAccess(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, permission()) == PackageManager.PERMISSION_GRANTED

    /** Every audio file of at least 3 seconds, newest first. Empty without permission. */
    fun load(context: Context): List<SongItem> {
        val out = ArrayList<SongItem>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.DURATION} >= ?",
                arrayOf("3000"),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
            )?.use { c ->
                fun str(col: String): String {
                    val i = c.getColumnIndex(col)
                    return if (i >= 0) c.getString(i).orEmpty() else ""
                }
                fun long(col: String): Long {
                    val i = c.getColumnIndex(col)
                    return if (i >= 0) c.getLong(i) else 0L
                }
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val artist = str(MediaStore.Audio.Media.ARTIST)
                    out += SongItem(
                        uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString(),
                        title = str(MediaStore.Audio.Media.TITLE).ifBlank { str(MediaStore.Audio.Media.DISPLAY_NAME) },
                        artist = if (artist == "<unknown>") "" else artist,
                        album = str(MediaStore.Audio.Media.ALBUM),
                        albumId = str(MediaStore.Audio.Media.ALBUM_ID),
                        durationMs = long(MediaStore.Audio.Media.DURATION),
                        folderId = str(MediaStore.MediaColumns.BUCKET_ID),
                        folderName = str(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME),
                        modified = long(MediaStore.MediaColumns.DATE_MODIFIED),
                    )
                }
            }
        }
        return out
    }

    /** Songs grouped by album, albums with the newest song first. */
    fun albums(songs: List<SongItem>): List<SongGroup> = group(songs, { it.albumId }, { it.album.ifBlank { "Unknown album" } })

    /** Songs grouped by folder (WhatsApp Audio, Recordings, Download ...), newest first. */
    fun folders(songs: List<SongItem>): List<SongGroup> = group(songs, { it.folderId }, { it.folderName.ifBlank { "Other" } })

    private fun group(songs: List<SongItem>, key: (SongItem) -> String, name: (SongItem) -> String): List<SongGroup> =
        songs.groupBy(key)
            .map { (id, list) ->
                val sorted = list.sortedByDescending { it.modified }
                SongGroup(id, name(sorted.first()), sorted)
            }
            .sortedByDescending { it.songs.first().modified }

    /** Songs whose title, artist or album contains [query] (any letter case). */
    fun search(songs: List<SongItem>, query: String): List<SongItem> {
        val q = query.trim()
        if (q.isEmpty()) return songs
        return songs.filter {
            it.title.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true) ||
                it.album.contains(q, ignoreCase = true)
        }
    }
}
