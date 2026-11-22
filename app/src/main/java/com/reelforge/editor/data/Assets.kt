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
    /** Download size in bytes (0 when not known). */
    val bytes: Long = 0,
)

/** A language's fonts, offered together in the plug-in store. */
data class FontPack(val group: String, val fonts: List<FontAsset>) {
    val bytes: Long get() = fonts.sumOf { it.bytes }

    /** A few words in the pack's own script, to show what the fonts look like. */
    val sample: String get() = FontPacks.sample(group)
}

object FontPacks {
    /** Groups of fonts made for one language, in the order the store shows them. */
    val LANGUAGES = listOf(
        "Telugu", "Hindi & Marathi", "Tamil", "Kannada", "Malayalam", "Bengali", "Gujarati", "Punjabi", "Odia",
    )

    fun sample(group: String): String = when (group) {
        "Telugu" -> "తెలుగు అక్షరాలు"
        "Hindi & Marathi" -> "हिंदी मराठी"
        "Tamil" -> "தமிழ் எழுத்து"
        "Kannada" -> "ಕನ್ನಡ ಅಕ್ಷರ"
        "Malayalam" -> "മലയാളം"
        "Bengali" -> "বাংলা লেখা"
        "Gujarati" -> "ગુજરાતી"
        "Punjabi" -> "ਪੰਜਾਬੀ"
        "Odia" -> "ଓଡ଼ିଆ"
        "Handwriting" -> "Handwritten"
        "Clean" -> "Clean & simple"
        else -> "Aa Bb Cc"
    }

    /** Every group as a pack: languages first, then the English styles. */
    fun packs(fonts: List<FontAsset>): List<FontPack> {
        val groups = fonts.groupBy { it.group }
        val order = LANGUAGES + groups.keys.filter { it !in LANGUAGES }
        return order.mapNotNull { g -> groups[g]?.let { FontPack(g, it) } }
    }

    /** "1.2 MB" / "350 KB": a download size people can read. */
    fun size(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
        else -> "${(bytes / 1000).coerceAtLeast(1)} KB"
    }
}

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
                bytes = o.optLong("bytes", 0),
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
