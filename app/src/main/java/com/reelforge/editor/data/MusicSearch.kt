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
) {
    /** True when the artist must be named wherever the video is posted. */
    val needsCredit: Boolean get() = licence == "CC BY"

    val credit: String get() = "Music: \"$title\" by ${creator.ifBlank { "unknown artist" }} (CC BY)"
}

/**
 * Searches Openverse (openverse.org), a free catalogue of openly licensed audio. Public-domain
 * results (CC0 / public domain mark) are preferred; they need no credit. Music that only asks for
 * a credit (CC BY) is offered when nothing else is found, and is marked. No account or key is needed.
 */
object MusicSearch {
    private const val BASE = "https://api.openverse.org/v1/audio/"
    private val SAFE_TYPES = setOf("mp3", "mp32", "ogg", "oga", "wav", "flac", "m4a", "aac")

    /**
     * The addresses to try, in order; the first one that returns anything wins.
     * Sounds: public domain only (no credit needed). Music: full songs that ask for a credit
     * (CC BY) first, because the public-domain catalogue holds almost no real songs.
     */
    fun searchUrls(query: String, soundEffects: Boolean): List<String> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val tail = "&page_size=20&mature=false"
        val free = "$BASE?q=$q&license=cc0,pdm$tail"
        return if (soundEffects) listOf(free) else listOf("$BASE?q=$q&license=by&category=music$tail", free)
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
            if (id.isEmpty() || !url.startsWith("https://") || licence !in setOf("cc0", "pdm", "by")) continue
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
                licence = when (licence) { "cc0" -> "CC0"; "by" -> "CC BY"; else -> "Public domain" },
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
