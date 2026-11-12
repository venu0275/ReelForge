package com.reelforge.editor.data

import android.content.Context
import com.reelforge.editor.model.AspectRatio
import com.reelforge.editor.model.AudioClip
import com.reelforge.editor.model.Background
import com.reelforge.editor.model.TransitionStyle
import com.reelforge.editor.model.VideoLayer
import com.reelforge.editor.model.ImageLayer
import com.reelforge.editor.model.OverlayAnim
import com.reelforge.editor.model.Clip
import com.reelforge.editor.model.ClipType
import com.reelforge.editor.model.FilterPreset
import com.reelforge.editor.model.MusicTrack
import com.reelforge.editor.model.Project
import com.reelforge.editor.model.TextAlignH
import com.reelforge.editor.model.TextFont
import com.reelforge.editor.model.TextItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Stores each project as a JSON file in app-private storage. */
class ProjectRepository(context: Context) {

    private val dir = File(context.filesDir, "projects").apply { mkdirs() }

    fun list(): List<Project> =
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { runCatching { ProjectJson.decode(it.readText()) }.getOrNull() }
            .sortedByDescending { it.updatedAt }

    fun save(project: Project) {
        File(dir, "${project.id}.json").writeText(ProjectJson.encode(project))
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }
}

/** Converts projects to and from JSON text. */
object ProjectJson {

    fun encode(project: Project): String = toJson(project).toString()

    /** Throws if [text] is not a valid saved project. */
    fun decode(text: String): Project = fromJson(JSONObject(text))

    private fun toJson(p: Project) = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("aspect", p.aspect.name)
        put("updatedAt", p.updatedAt)
        put("background", p.background.name)
        put("backgroundColor", p.backgroundColor)
        put("clips", JSONArray().apply { p.clips.forEach { put(clipToJson(it)) } })
        put("texts", JSONArray().apply { p.texts.forEach { put(textToJson(it)) } })
        put("layers", JSONArray().apply {
            p.layers.forEach { l ->
                put(JSONObject().apply {
                    put("id", l.id)
                    put("uri", l.uri)
                    put("scale", l.scale.toDouble())
                    put("x", l.x.toDouble())
                    put("y", l.y.toDouble())
                    put("opacity", l.opacity.toDouble())
                    put("rotation", l.rotation.toDouble())
                    put("startMs", l.startMs)
                    put("endMs", l.endMs)
                    put("animIn", l.animIn.name)
                    put("animOut", l.animOut.name)
                    put("moving", l.moving)
                    put("x2", l.x2.toDouble())
                    put("y2", l.y2.toDouble())
                })
            }
        })
        put("videoLayers", JSONArray().apply {
            p.videoLayers.forEach { v ->
                put(JSONObject().apply {
                    put("id", v.id)
                    put("uri", v.uri)
                    put("sourceDurationMs", v.sourceDurationMs)
                    put("trimStartMs", v.trimStartMs)
                    put("trimEndMs", v.trimEndMs)
                    put("startMs", v.startMs)
                    put("aspect", v.aspect.toDouble())
                    put("scale", v.scale.toDouble())
                    put("x", v.x.toDouble())
                    put("y", v.y.toDouble())
                    put("opacity", v.opacity.toDouble())
                    put("rotation", v.rotation.toDouble())
                    put("volume", v.volume.toDouble())
                    put("muted", v.muted)
                    put("hasAudio", v.hasAudio)
                    put("chromaKey", v.chromaKey)
                    put("keyColor", v.keyColor)
                    put("keyTolerance", v.keyTolerance.toDouble())
                })
            }
        })
        put("audio", JSONArray().apply {
            p.audio.forEach { a ->
                put(JSONObject().apply {
                    put("id", a.id)
                    put("uri", a.uri)
                    put("title", a.title)
                    put("startMs", a.startMs)
                    put("durationMs", a.durationMs)
                    put("volume", a.volume.toDouble())
                    put("fadeInMs", a.fadeInMs)
                    put("fadeOutMs", a.fadeOutMs)
                    put("offsetMs", a.offsetMs)
                })
            }
        })
        p.music?.let { m ->
            put("music", JSONObject().apply {
                put("uri", m.uri)
                put("title", m.title)
                put("durationMs", m.durationMs)
                put("volume", m.volume.toDouble())
                put("loop", m.loop)
                put("fadeInMs", m.fadeInMs)
                put("fadeOutMs", m.fadeOutMs)
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
        put("opacity", c.opacity.toDouble())
        put("zoom", c.zoom.toDouble())
        put("flipH", c.flipH)
        put("fill", c.fill)
        put("transition", c.transition.name)
        put("pitch", c.pitch.toDouble())
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
        put("font", t.font.name)
        put("background", t.background)
        put("outline", t.outline)
        put("animIn", t.animIn.name)
        put("animOut", t.animOut.name)
        t.fontPath?.let { put("fontPath", it) }
        put("bold", t.bold)
        put("italic", t.italic)
        put("align", t.align.name)
        put("opacity", t.opacity.toDouble())
        put("rotation", t.rotation.toDouble())
        put("outlineColor", t.outlineColor)
        put("boxColor", t.boxColor)
        put("moving", t.moving)
        put("x2", t.x2.toDouble())
        put("y2", t.y2.toDouble())
    }

    private fun anim(name: String): OverlayAnim =
        runCatching { OverlayAnim.valueOf(name) }.getOrDefault(OverlayAnim.NONE)

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
                opacity = c.optDouble("opacity", 1.0).toFloat(),
                zoom = c.optDouble("zoom", 1.0).toFloat(),
                flipH = c.optBoolean("flipH", false),
                fill = c.optBoolean("fill", false),
                transition = runCatching { TransitionStyle.valueOf(c.optString("transition")) }
                    .getOrDefault(TransitionStyle.FADE),
                pitch = c.optDouble("pitch", 1.0).toFloat(),
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
                font = runCatching { TextFont.valueOf(t.optString("font")) }
                    .getOrDefault(TextFont.SANS),
                background = t.optBoolean("background", false),
                outline = t.optBoolean("outline", false),
                animIn = anim(t.optString("animIn")),
                animOut = anim(t.optString("animOut")),
                fontPath = t.optString("fontPath").ifBlank { null },
                bold = t.optBoolean("bold", true),
                italic = t.optBoolean("italic", false),
                align = runCatching { TextAlignH.valueOf(t.optString("align")) }
                    .getOrDefault(TextAlignH.CENTER),
                opacity = t.optDouble("opacity", 1.0).toFloat(),
                rotation = t.optDouble("rotation", 0.0).toFloat(),
                outlineColor = t.optInt("outlineColor", 0xFF000000.toInt()),
                boxColor = t.optInt("boxColor", 0x99000000.toInt()),
                moving = t.optBoolean("moving", false),
                x2 = t.optDouble("x2", 0.0).toFloat(),
                y2 = t.optDouble("y2", 0.0).toFloat(),
            )
        }
        val music = o.optJSONObject("music")?.let { m ->
            MusicTrack(
                uri = m.getString("uri"),
                title = m.optString("title", "Music"),
                durationMs = m.optLong("durationMs", 0),
                volume = m.optDouble("volume", 0.6).toFloat(),
                loop = m.optBoolean("loop", true),
                fadeInMs = m.optLong("fadeInMs", 0),
                fadeOutMs = m.optLong("fadeOutMs", 0),
            )
        }
        val layersJson = o.optJSONArray("layers") ?: JSONArray()
        val layers = (0 until layersJson.length()).map { i ->
            val l = layersJson.getJSONObject(i)
            ImageLayer(
                id = l.getString("id"),
                uri = l.getString("uri"),
                scale = l.optDouble("scale", 0.35).toFloat(),
                x = l.optDouble("x", 0.0).toFloat(),
                y = l.optDouble("y", 0.0).toFloat(),
                opacity = l.optDouble("opacity", 1.0).toFloat(),
                rotation = l.optDouble("rotation", 0.0).toFloat(),
                startMs = l.optLong("startMs", 0),
                endMs = l.optLong("endMs", 3000),
                animIn = anim(l.optString("animIn")),
                animOut = anim(l.optString("animOut")),
                moving = l.optBoolean("moving", false),
                x2 = l.optDouble("x2", 0.0).toFloat(),
                y2 = l.optDouble("y2", 0.0).toFloat(),
            )
        }
        val videoJson = o.optJSONArray("videoLayers") ?: JSONArray()
        val videoLayers = (0 until videoJson.length()).map { i ->
            val v = videoJson.getJSONObject(i)
            VideoLayer(
                id = v.getString("id"),
                uri = v.getString("uri"),
                sourceDurationMs = v.getLong("sourceDurationMs"),
                trimStartMs = v.optLong("trimStartMs", 0),
                trimEndMs = v.optLong("trimEndMs", v.getLong("sourceDurationMs")),
                startMs = v.optLong("startMs", 0),
                aspect = v.optDouble("aspect", 16.0 / 9.0).toFloat(),
                scale = v.optDouble("scale", 0.4).toFloat(),
                x = v.optDouble("x", 0.8).toFloat(),
                y = v.optDouble("y", 0.8).toFloat(),
                opacity = v.optDouble("opacity", 1.0).toFloat(),
                rotation = v.optDouble("rotation", 0.0).toFloat(),
                volume = v.optDouble("volume", 1.0).toFloat(),
                muted = v.optBoolean("muted", false),
                hasAudio = v.optBoolean("hasAudio", true),
                chromaKey = v.optBoolean("chromaKey", false),
                keyColor = v.optInt("keyColor", 0xFF00FF00.toInt()),
                keyTolerance = v.optDouble("keyTolerance", 0.18).toFloat(),
            )
        }
        val audioJson = o.optJSONArray("audio") ?: JSONArray()
        val audio = (0 until audioJson.length()).map { i ->
            val a = audioJson.getJSONObject(i)
            AudioClip(
                id = a.getString("id"),
                uri = a.getString("uri"),
                title = a.optString("title", "Audio"),
                startMs = a.optLong("startMs", 0),
                durationMs = a.optLong("durationMs", 0),
                volume = a.optDouble("volume", 1.0).toFloat(),
                fadeInMs = a.optLong("fadeInMs", 0),
                fadeOutMs = a.optLong("fadeOutMs", 0),
                offsetMs = a.optLong("offsetMs", 0),
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
            layers = layers,
            audio = audio,
            videoLayers = videoLayers,
            background = runCatching { Background.valueOf(o.optString("background")) }
                .getOrDefault(Background.BLACK),
            backgroundColor = o.optInt("backgroundColor", -1),
            updatedAt = o.optLong("updatedAt", 0),
        )
    }
}
