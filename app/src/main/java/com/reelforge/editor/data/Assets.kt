package com.reelforge.editor.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A font that can be downloaded on demand. All entries are open-licence Google Fonts. */
data class FontAsset(
    val id: String,
    val name: String,
    val group: String,
    val licence: String,
    val url: String,
)

/**
 * Things the app can download after it is installed, so the install stays small. The list ships
 * inside the app (assets/font_catalog.json); the files themselves are fetched the first time the
 * user picks one and are then kept on the phone.
 */
object AssetCatalog {

    fun parseFonts(json: String): List<FontAsset> {
        val array = JSONObject(json).getJSONArray("fonts")
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            FontAsset(
                id = o.getString("id"),
                name = o.getString("name"),
                group = o.optString("group", "Other"),
                licence = o.optString("licence", ""),
                url = o.getString("url"),
            )
        }
    }

    fun fonts(context: Context): List<FontAsset> = runCatching {
        parseFonts(context.assets.open("font_catalog.json").bufferedReader().use { it.readText() })
    }.getOrDefault(emptyList())

    /** Where a downloaded font lives on this phone. */
    fun fontFile(context: Context, asset: FontAsset): File =
        File(File(context.filesDir, "fonts").apply { mkdirs() }, "store_${asset.id}.ttf")
}

object AssetDownloader {

    /**
     * Downloads [url] to [target], reporting progress as 0..100. The file only appears at
     * [target] once the whole download has succeeded. Throws on any network or HTTP error.
     */
    fun download(url: String, target: File, onProgress: (Int) -> Unit = {}) {
        val partial = File(target.parentFile, target.name + ".part")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            val code = connection.responseCode
            if (code !in 200..299) throw java.io.IOException("Download failed (HTTP $code)")
            val total = connection.contentLengthLong
            var done = 0L
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) onProgress(((done * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            if (done == 0L) throw java.io.IOException("Download was empty")
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) throw java.io.IOException("Could not save the download")
        } catch (e: Exception) {
            partial.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }
}
