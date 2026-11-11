package com.reelforge.editor.data

import android.content.Context
import com.reelforge.editor.model.AspectRatio
import com.reelforge.editor.model.Clip
import com.reelforge.editor.model.ClipType
import com.reelforge.editor.model.FilterPreset
import com.reelforge.editor.model.MusicTrack
import com.reelforge.editor.model.Project
import com.reelforge.editor.model.TextItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Stores each project as a JSON file in app-private storage. */
class ProjectRepository(context: Context) {

    private val dir = File(context.filesDir, "projects").apply { mkdirs() }

    fun list(): List<Project> =
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { runCatching { fromJson(JSONObject(it.readText())) }.getOrNull() }
            .sortedByDescending { it.updatedAt }

    fun save(project: Project) {
        File(dir, "${project.id}.json").writeText(toJson(project).toString())
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }

    private fun toJson(p: Project) = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("aspect", p.aspect.name)
        put("updatedAt", p.updatedAt)
        put("clips", JSONArray().apply { p.clips.forEach { put(clipToJson(it)) } })
        put("texts", JSONArray().apply { p.texts.forEach { put(textToJson(it)) } })
        p.music?.let { m ->
            put("music", JSONObject().apply {
                put("uri", m.uri)
                put("title", m.title)
                put("durationMs", m.durationMs)
                put("volume", m.volume.toDouble())
                put("loop", m.loop)
            })
        }
    }

    private fun clipToJson(c: Clip) = JSONObject().apply {
        put("id", c.id)
        put("uri", c.uri)
        put("type", c.type.name)
        put("sourceDurationMs", c.sourceDurationMs)
        put("trimStartMs", c.trimStartMs)
        put("trimEndMs", c.trimEndMs)
        put("speed", c.speed.toDouble())
        put("volume", c.volume.toDouble())
        put("muted", c.muted)
        put("rotation", c.rotation)
        put("filter", c.filter.name)
        put("brightness", c.brightness.toDouble())
        put("contrast", c.contrast.toDouble())
        put("saturation", c.saturation.toDouble())
        put("fadeInMs", c.fadeInMs)
        put("fadeOutMs", c.fadeOutMs)
        put("hasAudio", c.hasAudio)
    }

    private fun textToJson(t: TextItem) = JSONObject().apply {
        put("id", t.id)
        put("text", t.text)
        put("color", t.color)
        put("size", t.size.toDouble())
        put("x", t.x.toDouble())
        put("y", t.y.toDouble())
        put("startMs", t.startMs)
        put("endMs", t.endMs)
    }

    private fun fromJson(o: JSONObject): Project {
        val clipsJson = o.optJSONArray("clips") ?: JSONArray()
        val textsJson = o.optJSONArray("texts") ?: JSONArray()
        val clips = (0 until clipsJson.length()).map { i ->
            val c = clipsJson.getJSONObject(i)
            Clip(
                id = c.getString("id"),
                uri = c.getString("uri"),
                type = ClipType.valueOf(c.getString("type")),
                sourceDurationMs = c.getLong("sourceDurationMs"),
                trimStartMs = c.getLong("trimStartMs"),
                trimEndMs = c.getLong("trimEndMs"),
                speed = c.optDouble("speed", 1.0).toFloat(),
                volume = c.optDouble("volume", 1.0).toFloat(),
                muted = c.optBoolean("muted", false),
                rotation = c.optInt("rotation", 0),
                filter = runCatching { FilterPreset.valueOf(c.optString("filter")) }
                    .getOrDefault(FilterPreset.NONE),
                brightness = c.optDouble("brightness", 0.0).toFloat(),
                contrast = c.optDouble("contrast", 0.0).toFloat(),
                saturation = c.optDouble("saturation", 0.0).toFloat(),
                fadeInMs = c.optLong("fadeInMs", 0),
                fadeOutMs = c.optLong("fadeOutMs", 0),
                hasAudio = c.optBoolean("hasAudio", true),
            )
        }
        val texts = (0 until textsJson.length()).map { i ->
            val t = textsJson.getJSONObject(i)
            TextItem(
                id = t.getString("id"),
                text = t.getString("text"),
                color = t.optInt("color", -1),
                size = t.optDouble("size", 0.08).toFloat(),
                x = t.optDouble("x", 0.0).toFloat(),
                y = t.optDouble("y", 0.0).toFloat(),
                startMs = t.optLong("startMs", 0),
                endMs = t.optLong("endMs", 3000),
            )
        }
        val music = o.optJSONObject("music")?.let { m ->
            MusicTrack(
                uri = m.getString("uri"),
                title = m.optString("title", "Music"),
                durationMs = m.optLong("durationMs", 0),
                volume = m.optDouble("volume", 0.6).toFloat(),
                loop = m.optBoolean("loop", true),
            )
        }
        return Project(
            id = o.getString("id"),
            name = o.getString("name"),
            aspect = runCatching { AspectRatio.valueOf(o.getString("aspect")) }
                .getOrDefault(AspectRatio.LANDSCAPE),
            clips = clips,
            texts = texts,
            music = music,
            updatedAt = o.optLong("updatedAt", 0),
        )
    }
}
