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
 * results (CC0 / public domain mark) are asked for, so they can be used in any video without
 * crediting anyone. No account or key is needed.
 */
object MusicSearch {
    private const val BASE = "https://api.openverse.org/v1/audio/"
    private val SAFE_TYPES = setOf("mp3", "ogg", "oga", "wav", "flac", "m4a", "aac")

    fun searchUrl(query: String, soundEffects: Boolean): String {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val category = if (soundEffects) "sound_effect" else "music"
        return "$BASE?q=$q&license=cc0,pdm&category=$category&page_size=20&mature=false"
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
            if (type !in SAFE_TYPES) continue
            out += MusicResult(
                id = id,
                title = o.optString("title").ifBlank { "Untitled" }.take(60),
                creator = if (o.isNull("creator")) "" else o.optString("creator").take(40),
                url = url,
                durationMs = if (o.isNull("duration")) 0L else o.optLong("duration", 0L),
                licence = if (licence == "cc0") "CC0" else "Public domain",
                fileType = type,
            )
        }
        return out
    }

    /** Runs a search. Throws on network errors or when the service refuses (for example HTTP 429). */
    fun search(query: String, soundEffects: Boolean): List<MusicResult> {
        val connection = URL(searchUrl(query, soundEffects)).openConnection() as HttpURLConnection
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
