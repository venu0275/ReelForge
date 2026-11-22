package com.reelforge.editor.engine

import android.content.Context
import android.net.Uri
import com.reelforge.editor.data.AssetDownloader
import com.reelforge.editor.util.PcmDecoder
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Speech models for Auto captions, from the Vosk project (alphacephei.com). They are Apache 2.0
 * licensed, so no payment or credit is needed. Each is downloaded once, when first used.
 */
enum class CaptionLanguage(
    val label: String,
    val model: String,
    val sizeMb: Int,
    /** "India" or "World": how the language list is grouped. */
    val group: String = "World",
    /** Hindi written in English letters (Hinglish). Uses the Hindi model. */
    val roman: Boolean = false,
    /** False for languages written without spaces between words (Chinese, Japanese). */
    val spaced: Boolean = true,
) {
    EN_IN("English (India)", "vosk-model-small-en-in-0.4", 36, "India"),
    HI("Hindi", "vosk-model-small-hi-0.22", 45, "India"),
    HINGLISH("Hinglish", "vosk-model-small-hi-0.22", 45, "India", roman = true),
    TE("Telugu", "vosk-model-small-te-0.42", 58, "India"),
    GU("Gujarati", "vosk-model-small-gu-0.42", 100, "India"),
    EN_US("English (US)", "vosk-model-small-en-us-0.15", 40),
    ES("Spanish", "vosk-model-small-es-0.42", 39),
    FR("French", "vosk-model-small-fr-0.22", 41),
    DE("German", "vosk-model-small-de-0.15", 45),
    PT("Portuguese", "vosk-model-small-pt-0.3", 31),
    IT("Italian", "vosk-model-small-it-0.22", 48),
    RU("Russian", "vosk-model-small-ru-0.22", 45),
    TR("Turkish", "vosk-model-small-tr-0.3", 35),
    VI("Vietnamese", "vosk-model-small-vn-0.4", 32),
    KO("Korean", "vosk-model-small-ko-0.22", 82),
    JA("Japanese", "vosk-model-small-ja-0.22", 48, spaced = false),
    ZH("Chinese", "vosk-model-small-cn-0.22", 42, spaced = false);

    val url: String get() = "https://alphacephei.com/vosk/models/$model.zip"

    /** What the caption shows for a line the model wrote. */
    fun shown(text: String): String = CaptionText.tidy(if (roman) Hinglish.convert(text) else text)
}

/** A word heard in the sound, with when it was said (milliseconds into the piece of sound). */
data class SpokenWord(val text: String, val startMs: Long, val endMs: Long)

/** One caption line and when it shows on the timeline. */
data class CaptionLine(val text: String, val startMs: Long, val endMs: Long)

/** Pure helpers for turning recognised words into caption lines. Testable without a phone. */
object CaptionText {

    /** Reads the words from one Vosk result, e.g. {"result":[{"word":"hi","start":0.5,"end":0.8}]}. */
    fun parseWords(json: String, offsetMs: Long = 0): List<SpokenWord> {
        val result = runCatching { JSONObject(json).optJSONArray("result") }.getOrNull() ?: return emptyList()
        return (0 until result.length()).mapNotNull { i ->
            val w = result.optJSONObject(i) ?: return@mapNotNull null
            val text = w.optString("word").trim()
            if (text.isEmpty() || text == "[unk]") return@mapNotNull null
            SpokenWord(
                text,
                offsetMs + (w.optDouble("start") * 1000).toLong(),
                offsetMs + (w.optDouble("end") * 1000).toLong(),
            )
        }
    }

    /**
     * Groups words into short lines that are easy to read on a phone: a new line starts after
     * [maxWords] words, when a line would pass [maxChars] letters or last longer than [maxMs], or
     * after a pause of [pauseMs]. Each line stays up a little after its last word (until the next
     * line starts, at most [holdMs]).
     */
    fun group(
        words: List<SpokenWord>,
        maxWords: Int = 5,
        maxChars: Int = 28,
        maxMs: Long = 2_600,
        pauseMs: Long = 600,
        holdMs: Long = 400,
        spaced: Boolean = true,
    ): List<CaptionLine> {
        val lines = mutableListOf<MutableList<SpokenWord>>()
        for (word in words.sortedBy { it.startMs }) {
            val line = lines.lastOrNull()
            val fits = line != null &&
                line.size < maxWords &&
                line.sumOf { it.text.length + 1 } + word.text.length <= maxChars &&
                word.endMs - line.first().startMs <= maxMs &&
                word.startMs - line.last().endMs < pauseMs
            if (fits) line!! += word else lines += mutableListOf(word)
        }
        return lines.mapIndexed { i, line ->
            val nextStart = lines.getOrNull(i + 1)?.first()?.startMs ?: Long.MAX_VALUE
            val end = minOf(line.last().endMs + holdMs, nextStart).coerceAtLeast(line.first().startMs + 300)
            CaptionLine(line.joinToString(if (spaced) " " else "") { it.text }, line.first().startMs, end)
        }
    }

    /** Hindi models write Devanagari; others write lower case, so the first letter is raised. */
    fun tidy(text: String): String = text.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}

/** Downloads, unpacks and runs the speech models. Everything runs on the phone. */
object CaptionModels {
    private fun root(context: Context) = File(context.filesDir, "speech").apply { mkdirs() }

    fun folder(context: Context, language: CaptionLanguage) = File(root(context), language.model)

    fun isInstalled(context: Context, language: CaptionLanguage) =
        File(folder(context, language), ".ready").exists()

    /** Downloads and unpacks a model. [onProgress] gets 0..100. Throws on failure. */
    fun install(context: Context, language: CaptionLanguage, onProgress: (Int) -> Unit) {
        val zip = File(context.cacheDir, "${language.model}.zip")
        AssetDownloader.download(language.url, zip) { onProgress((it * 0.9).toInt()) }
        val target = folder(context, language)
        target.deleteRecursively()
        target.mkdirs()
        val base = target.canonicalPath + File.separator
        try {
            ZipInputStream(zip.inputStream().buffered()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    // The zip holds one folder named after the model; its contents go into target.
                    // (Some zips name that folder a little differently, so any "vosk-model…" folder counts.)
                    val raw = entry.name.trimStart('/')
                    val name = if (raw.substringBefore('/').startsWith("vosk-model")) raw.substringAfter('/', "") else raw
                    if (name.isEmpty()) continue
                    val out = File(target, name)
                    if (!out.canonicalPath.startsWith(base)) continue // never write outside
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { input.copyTo(it) }
                    }
                }
            }
            File(target, ".ready").writeText("ok")
            onProgress(100)
        } catch (e: Exception) {
            target.deleteRecursively()
            throw e
        } finally {
            zip.delete()
        }
    }

    fun delete(context: Context, language: CaptionLanguage) {
        folder(context, language).deleteRecursively()
    }
}

/** A stretch of sound to caption, and where it sits on the timeline. */
class SpeechPiece(
    val uri: String,
    val fromMs: Long,
    val toMs: Long,
    /** Turns a time in the file into a time on the timeline. */
    val toTimeline: (Long) -> Long,
)

/** Listens to pieces of sound and writes down what is said, word by word with times. */
class Transcriber(context: Context, language: CaptionLanguage) : AutoCloseable {
    private val model = org.vosk.Model(CaptionModels.folder(context, language).absolutePath)

    /**
     * The words in [piece], with times on the timeline. [onProgress] gets how far through the
     * piece the listening is (0..1).
     */
    fun words(
        context: Context,
        piece: SpeechPiece,
        stop: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): List<SpokenWord> {
        val found = mutableListOf<SpokenWord>()
        val recognizer = org.vosk.Recognizer(model, PcmDecoder.RATE.toFloat())
        try {
            recognizer.setWords(true)
            var heardMs = 0L
            val length = (piece.toMs - piece.fromMs).coerceAtLeast(1)
            PcmDecoder.decode(context, Uri.parse(piece.uri), piece.fromMs, piece.toMs, stop) { samples, count ->
                if (recognizer.acceptWaveForm(samples, count)) {
                    found += CaptionText.parseWords(recognizer.result, piece.fromMs)
                }
                heardMs += count * 1000L / PcmDecoder.RATE
                onProgress((heardMs.toFloat() / length).coerceIn(0f, 1f))
            }
            found += CaptionText.parseWords(recognizer.finalResult, piece.fromMs)
        } finally {
            recognizer.close()
        }
        return found.map { w ->
            w.copy(startMs = piece.toTimeline(w.startMs), endMs = piece.toTimeline(w.endMs))
        }
    }

    override fun close() = model.close()
}
