package com.reelforge.editor.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One free-to-use track or sound found online. */
data class MusicResult(
    val id: String,
    val title: String,
    val creator: String,
    val url: String,
    val durationMs: Long,
    val licence: String,
    val fileType: String,
)

/**
 * Searches Openverse (openverse.org), a free catalogue of openly licensed audio. Only public-domain
 * results (CC0 / public domain mark) are asked for and accepted, so they can be used in any video
 * without paying or crediting anyone. No account or key is needed.
 */
object MusicSearch {
    private const val BASE = "https://api.openverse.org/v1/audio/"
    private val SAFE_TYPES = setOf("mp3", "mp32", "ogg", "oga", "wav", "flac", "m4a", "aac")

    /**
     * The addresses to try, in order; the first one that returns anything wins. Every search asks
     * only for public-domain audio (CC0 / public domain mark): free, and no credit is needed.
     */
    fun searchUrls(query: String, soundEffects: Boolean): List<String> {
        fun url(words: String) =
            "$BASE?q=${URLEncoder.encode(words.trim(), "UTF-8")}&license=cc0,pdm&page_size=20&mature=false"
        return if (soundEffects) listOf(url(query)) else listOf(url("$query music"), url(query))
    }

    /** Reads a search answer. Entries that are not public domain or not playable are dropped. */
    fun parse(json: String): List<MusicResult> {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<MusicResult>()
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val url = o.optString("url")
            val licence = o.optString("license").lowercase()
            val id = o.optString("id").filter { it.isLetterOrDigit() || it == '-' }
            if (id.isEmpty() || !url.startsWith("https://") || licence !in setOf("cc0", "pdm")) continue
            val type = o.optString("filetype").lowercase().ifEmpty {
                url.substringBefore('?').substringAfterLast('.', "").lowercase()
            }
            if (type.isNotEmpty() && type !in SAFE_TYPES) continue
            out += MusicResult(
                id = id,
                title = o.optString("title").ifBlank { "Untitled" }.take(60),
                creator = if (o.isNull("creator")) "" else o.optString("creator").take(40),
                url = url,
                durationMs = if (o.isNull("duration")) 0L else o.optLong("duration", 0L),
                licence = if (licence == "cc0") "CC0" else "Public domain",
                fileType = if (type.isEmpty() || type == "mp32") "mp3" else type,
            )
        }
        return out
    }

    /** Runs a search. Throws on network errors or when the service refuses (for example HTTP 429). */
    fun search(query: String, soundEffects: Boolean): List<MusicResult> {
        for (url in searchUrls(query, soundEffects)) {
            val found = fetch(url)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    fun fetch(address: String): List<MusicResult> {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "ReelForge-Android/1.0 (video editor)")
            val code = connection.responseCode
            if (code !in 200..299) throw java.io.IOException("Search failed (HTTP $code)")
            return parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}
