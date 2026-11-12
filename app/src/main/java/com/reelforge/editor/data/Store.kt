package com.reelforge.editor.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.reelforge.editor.model.LoopFx
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
enum class SocialSticker(
    val label: String,
    val background: Int,
    val foreground: Int,
    /** True for comic-style reaction words drawn without a button behind them. */
    val burst: Boolean = false,
    /** The movement it starts with; it can be changed afterwards. */
    val loop: LoopFx = LoopFx.NONE,
) {
    SUBSCRIBE("SUBSCRIBE", 0xFFE53935.toInt(), 0xFFFFFFFF.toInt(), loop = LoopFx.PULSE),
    SUBSCRIBED("SUBSCRIBED", 0xFF616161.toInt(), 0xFFFFFFFF.toInt()),
    FOLLOW("FOLLOW", 0xFF1E88E5.toInt(), 0xFFFFFFFF.toInt(), loop = LoopFx.PULSE),
    LIKE("\uD83D\uDC4D LIKE", 0xFF1565C0.toInt(), 0xFFFFFFFF.toInt(), loop = LoopFx.BOUNCE),
    SHARE("\u2197 SHARE", 0xFF2E7D32.toInt(), 0xFFFFFFFF.toInt()),
    COMMENT("\uD83D\uDCAC COMMENT", 0xFFEF6C00.toInt(), 0xFFFFFFFF.toInt()),
    BELL("\uD83D\uDD14 NOTIFY ME", 0xFFFDD835.toInt(), 0xFF212121.toInt(), loop = LoopFx.SHAKE),
    NEW("NEW", 0xFFFFEB3B.toInt(), 0xFF212121.toInt(), loop = LoopFx.BLINK),
    WATCH("\u25B6 WATCH NOW", 0xFF212121.toInt(), 0xFFFFFFFF.toInt()),
    LINK("LINK IN BIO", 0xFF8E24AA.toInt(), 0xFFFFFFFF.toInt()),
    SWIPE("\u2191 SWIPE UP", 0xFFD81B60.toInt(), 0xFFFFFFFF.toInt(), loop = LoopFx.BOUNCE),

    LOL("LOL", 0xFF000000.toInt(), 0xFFFFEB3B.toInt(), true, LoopFx.SHAKE),
    HAHA("HA HA HA", 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), true, LoopFx.BOUNCE),
    OMG("OMG!", 0xFF000000.toInt(), 0xFFFF5252.toInt(), true, LoopFx.PULSE),
    WOW("WOW", 0xFF000000.toInt(), 0xFF40C4FF.toInt(), true, LoopFx.PULSE),
    FAIL("FAIL", 0xFFFFFFFF.toInt(), 0xFFD50000.toInt(), true, LoopFx.WOBBLE),
    WAIT("WAIT FOR IT...", 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), true, LoopFx.BLINK),
    BRUH("BRUH", 0xFF000000.toInt(), 0xFFFFAB40.toInt(), true, LoopFx.WOBBLE),
    NOWAY("NO WAY!", 0xFF000000.toInt(), 0xFF69F0AE.toInt(), true, LoopFx.SHAKE),
    OOPS("OOPS", 0xFF000000.toInt(), 0xFFFF80AB.toInt(), true, LoopFx.WOBBLE),
    WHAT("WHAT?!", 0xFF000000.toInt(), 0xFFFFFF00.toInt(), true, LoopFx.SHAKE),
    BOOM("BOOM!", 0xFF000000.toInt(), 0xFFFF6D00.toInt(), true, LoopFx.PULSE);

    /** Draws the sticker as a picture with a transparent surround. */
    fun render(height: Int = 160): Bitmap {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = foreground
            textSize = height * (if (burst) 0.62f else 0.42f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val width = (text.measureText(label) + height * 0.9f).toInt().coerceAtLeast(height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val baseline = height / 2f - (text.descent() + text.ascent()) / 2f
        if (burst) {
            // Comic lettering: a thick outline in the "background" colour, then the fill.
            val outline = Paint(text).apply {
                color = background
                style = Paint.Style.STROKE
                strokeWidth = height * 0.12f
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawText(label, width / 2f, baseline, outline)
        } else {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
            val r = height * 0.28f
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, fill)
        }
        canvas.drawText(label, width / 2f, baseline, text)
        return bitmap
    }

    fun file(context: Context): File = File(StickerCatalog.dir(context), "social_${name.lowercase()}.png")
}

/** Short sound effects made by the app itself (no download, no licence to worry about). */
enum class SoundEffect(val title: String) {
    POP("Pop"), DING("Ding"), WHOOSH("Whoosh"), CLICK("Click"),
    BEEP("Beep"), RISE("Rise"), BOOM("Boom"), BUZZ("Buzz"),
    BOING("Boing"), WHISTLE_UP("Slide whistle up"), WHISTLE_DOWN("Slide whistle down"),
    SAD_TROMBONE("Sad trombone"), RIMSHOT("Ba-dum-tss"), SCRATCH("Record scratch"),
    CASH("Ka-ching"), DRUM_ROLL("Drum roll"), FAIL("Fail horn"), SLIP("Cartoon slip");

    /** True for the funny ones, shown together in the store. */
    val comedy: Boolean get() = ordinal >= BOING.ordinal

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
            SoundEffect.BOING -> make(700) { _, t, u ->
                sweep(260.0 + 140.0 * exp(-t * 4) * sin(2 * PI * 11 * t), 180.0, u) * exp(-t * 4.5) * edges(u)
            }
            SoundEffect.WHISTLE_UP -> make(750) { _, t, u -> sweep(500.0, 1900.0, u) * (0.85 + 0.15 * sin(2 * PI * 7 * t)) * edges(u) }
            SoundEffect.WHISTLE_DOWN -> make(750) { _, t, u -> sweep(1900.0, 420.0, u) * (0.85 + 0.15 * sin(2 * PI * 7 * t)) * edges(u) }
            SoundEffect.SAD_TROMBONE -> {
                // Four falling notes; the last one is held and wobbles.
                val notes = doubleArrayOf(233.1, 220.0, 207.7, 196.0)
                make(1900) { _, t, _ ->
                    val n = minOf(3, (t / 0.34).toInt())
                    val local = t - n * 0.34
                    val length = if (n == 3) 0.88 else 0.34
                    val f = notes[n] * (1 + if (n == 3) 0.012 * sin(2 * PI * 6 * t) else 0.0)
                    phase += 2 * PI * f / rate
                    val brass = sin(phase) + 0.5 * sin(2 * phase) + 0.33 * sin(3 * phase) + 0.2 * sin(4 * phase)
                    brass * 0.5 * minOf(1.0, local * 40, (length - local) * 12).coerceAtLeast(0.0)
                }
            }
            SoundEffect.RIMSHOT -> make(1000) { _, t, _ ->
                fun tom(at: Double, f: Double): Double {
                    val d = t - at
                    return if (d < 0) 0.0 else (sin(2 * PI * f * d) + (random.nextDouble() - 0.5) * 0.6) * exp(-d * 22)
                }
                val d = t - 0.40
                val cymbal = if (d < 0) 0.0 else (random.nextDouble() * 2 - 1) * sin(2 * PI * 5200 * d + 3 * sin(2 * PI * 3100 * d)) * exp(-d * 6)
                tom(0.0, 190.0) * 0.8 + tom(0.19, 150.0) * 0.8 + cymbal * 0.9
            }
            SoundEffect.SCRATCH -> make(380) { _, t, u ->
                val f = 300.0 + 1500.0 * sin(PI * u) * sin(PI * u)
                phase += 2 * PI * f / rate
                (sin(phase) * 0.6 + (random.nextDouble() * 2 - 1) * 0.5) * (0.5 + 0.5 * sin(2 * PI * 45 * t)) * edges(u)
            }
            SoundEffect.CASH -> make(900) { _, t, u ->
                val click = (random.nextDouble() * 2 - 1) * exp(-t * 120) * 0.7
                val d = t - 0.09
                val bell = if (d < 0) 0.0 else (sin(2 * PI * 2093 * d) * 0.6 + sin(2 * PI * 2637 * d) * 0.4) * exp(-d * 6)
                (click + bell) * edges(u)
            }
            SoundEffect.DRUM_ROLL -> make(1500) { _, t, u ->
                val hit = t % 0.04
                val roll = if (t < 1.2) (random.nextDouble() * 2 - 1) * exp(-hit * 90) * (0.25 + 0.6 * t / 1.2) else 0.0
                val d = t - 1.2
                val crash = if (d < 0) 0.0 else ((random.nextDouble() * 2 - 1) * 0.7 + sin(2 * PI * 110 * d) * 0.6) * exp(-d * 12)
                (roll + crash) * edges(u)
            }
            SoundEffect.FAIL -> make(900) { _, _, u ->
                val f = 320.0 - 210.0 * u
                phase += 2 * PI * f / rate
                (sin(phase) + 0.5 * sin(2 * phase) + 0.3 * sin(3 * phase)) * 0.5 * edges(u)
            }
            SoundEffect.SLIP -> make(300) { _, _, u -> sweep(280.0, 2600.0, u * u) * edges(u) }
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
