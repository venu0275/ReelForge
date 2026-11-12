package com.reelforge.editor.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** One downloadable sticker: an open-licence emoji picture (Noto Emoji, Apache-2.0). */
data class StickerAsset(val code: String, val group: String, val url: String) {
    /** The emoji itself, used as the button label so nothing has to download just to browse. */
    val label: String get() = String(Character.toChars(code.toInt(16)))
}

object StickerCatalog {

    fun parse(json: String): List<StickerAsset> {
        val root = JSONObject(json)
        val base = root.getString("base")
        val packs = root.getJSONArray("packs")
        val out = ArrayList<StickerAsset>()
        for (i in 0 until packs.length()) {
            val pack = packs.getJSONObject(i)
            val codes = pack.getJSONArray("codes")
            for (j in 0 until codes.length()) {
                val code = codes.getString(j)
                out += StickerAsset(code, pack.getString("name"), "$base$code.png")
            }
        }
        return out
    }

    fun stickers(context: Context): List<StickerAsset> = runCatching {
        parse(context.assets.open("sticker_catalog.json").bufferedReader().use { it.readText() })
    }.getOrDefault(emptyList())

    fun dir(context: Context): File = File(context.filesDir, "stickers").apply { mkdirs() }

    fun file(context: Context, asset: StickerAsset): File = File(dir(context), "noto_${asset.code}.png")
}

/**
 * Channel-style buttons ("Subscribe", "Like", ...) drawn by the app itself, so they need no
 * download and carry no one else's logo.
 */
enum class SocialSticker(val label: String, val background: Int, val foreground: Int) {
    SUBSCRIBE("SUBSCRIBE", 0xFFE53935.toInt(), 0xFFFFFFFF.toInt()),
    SUBSCRIBED("SUBSCRIBED", 0xFF616161.toInt(), 0xFFFFFFFF.toInt()),
    FOLLOW("FOLLOW", 0xFF1E88E5.toInt(), 0xFFFFFFFF.toInt()),
    LIKE("👍 LIKE", 0xFF1565C0.toInt(), 0xFFFFFFFF.toInt()),
    SHARE("↗ SHARE", 0xFF2E7D32.toInt(), 0xFFFFFFFF.toInt()),
    COMMENT("💬 COMMENT", 0xFFEF6C00.toInt(), 0xFFFFFFFF.toInt()),
    BELL("🔔 NOTIFY ME", 0xFFFDD835.toInt(), 0xFF212121.toInt()),
    NEW("NEW", 0xFFFFEB3B.toInt(), 0xFF212121.toInt()),
    WATCH("▶ WATCH NOW", 0xFF212121.toInt(), 0xFFFFFFFF.toInt()),
    LINK("LINK IN BIO", 0xFF8E24AA.toInt(), 0xFFFFFFFF.toInt()),
    SWIPE("↑ SWIPE UP", 0xFFD81B60.toInt(), 0xFFFFFFFF.toInt());

    /** Draws the button as a picture with a transparent surround. */
    fun render(height: Int = 160): Bitmap {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = foreground
            textSize = height * 0.42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val width = (text.measureText(label) + height * 0.9f).toInt().coerceAtLeast(height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
        val r = height * 0.28f
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, fill)
        val baseline = height / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(label, width / 2f, baseline, text)
        return bitmap
    }

    fun file(context: Context): File = File(StickerCatalog.dir(context), "social_${name.lowercase()}.png")
}

/** Short sound effects made by the app itself (no download, no licence to worry about). */
enum class SoundEffect(val title: String) {
    POP("Pop"), DING("Ding"), WHOOSH("Whoosh"), CLICK("Click"),
    BEEP("Beep"), RISE("Rise"), BOOM("Boom"), BUZZ("Buzz");

    fun file(context: Context): File =
        File(File(context.filesDir, "sounds").apply { mkdirs() }, "fx_${name.lowercase()}.wav")
}

object SoundMaker {
    const val SAMPLE_RATE = 44100

    /** Mono 16-bit samples of [effect] at [SAMPLE_RATE]. Always the same for the same effect. */
    fun samples(effect: SoundEffect): ShortArray {
        val rate = SAMPLE_RATE.toDouble()
        fun make(ms: Int, f: (Int, Double, Double) -> Double): ShortArray {
            val n = (rate * ms / 1000).toInt()
            return ShortArray(n) { i ->
                val t = i / rate
                val v = f(i, t, i.toDouble() / n).coerceIn(-1.0, 1.0)
                (v * 0.8 * Short.MAX_VALUE).toInt().toShort()
            }
        }
        val random = java.util.Random(7)
        var phase = 0.0
        fun sweep(from: Double, to: Double, u: Double): Double {
            phase += 2 * PI * (from + (to - from) * u) / rate
            return sin(phase)
        }
        // Short ramps at both ends so nothing clicks.
        fun edges(u: Double) = minOf(1.0, u * 40, (1 - u) * 40).coerceAtLeast(0.0)
        return when (effect) {
            SoundEffect.POP -> make(140) { _, t, u -> sweep(520.0, 90.0, u) * exp(-t * 28) }
            SoundEffect.DING -> make(900) { _, t, u ->
                (sin(2 * PI * 1320 * t) * 0.7 + sin(2 * PI * 2640 * t) * 0.3) * exp(-t * 5) * edges(u)
            }
            SoundEffect.WHOOSH -> {
                var low = 0.0
                make(650) { _, _, u ->
                    val k = 0.02 + 0.5 * sin(PI * u)
                    low += k * (random.nextDouble() * 2 - 1 - low)
                    low * sin(PI * u) * 1.6
                }
            }
            SoundEffect.CLICK -> make(30) { _, t, _ -> (random.nextDouble() * 2 - 1) * exp(-t * 300) }
            SoundEffect.BEEP -> make(260) { _, t, u -> sin(2 * PI * 1000 * t) * edges(u) }
            SoundEffect.RISE -> make(700) { _, _, u -> sweep(200.0, 1400.0, u * u) * edges(u) }
            SoundEffect.BOOM -> make(950) { _, t, u ->
                (sweep(130.0, 38.0, u) + (random.nextDouble() * 2 - 1) * exp(-t * 60) * 0.5) * exp(-t * 4.5) * edges(u)
            }
            SoundEffect.BUZZ -> make(420) { _, t, u -> (if (sin(2 * PI * 110 * t) >= 0) 0.6 else -0.6) * edges(u) }
        }
    }

    /** The bytes of a WAV file holding [samples] (mono, 16-bit). */
    fun wav(samples: ShortArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val data = samples.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + data)
        b.put("WAVE".toByteArray(Charsets.US_ASCII)).put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray(Charsets.US_ASCII)).putInt(data)
        samples.forEach { b.putShort(it) }
        return b.array()
    }
}
