package com.reelforge.editor.util

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File

/** What the app needs to know about one of the phone's speech voices (plain data, testable). */
data class VoiceInfo(val name: String, val localeTag: String, val needsInternet: Boolean, val installed: Boolean)

/** A voice offered in AI Voice: a phone voice that works offline, in a language we list. */
data class AiVoice(val name: String, val language: String, val label: String, val installed: Boolean)

/**
 * AI Voice uses the phone's own text-to-speech voices (Google's on most phones): free, offline once
 * downloaded, and the sound is the user's to use. This picks and names them.
 */
object AiVoices {
    /** Language tag → name, in the order they are shown. */
    val LANGUAGES: List<Pair<String, String>> = listOf(
        "en-IN" to "English (India)", "hi-IN" to "Hindi", "te-IN" to "Telugu", "ta-IN" to "Tamil",
        "kn-IN" to "Kannada", "ml-IN" to "Malayalam", "mr-IN" to "Marathi", "bn-IN" to "Bengali",
        "gu-IN" to "Gujarati", "pa-IN" to "Punjabi", "ur-IN" to "Urdu",
        "en-US" to "English (US)", "en-GB" to "English (UK)",
    )

    /** "hi_IN", "hi-in" and "hi-IN" all mean Hindi (India). */
    fun normalise(tag: String): String {
        val parts = tag.replace('_', '-').split('-').filter { it.isNotBlank() }
        if (parts.isEmpty()) return ""
        return if (parts.size == 1) parts[0].lowercase() else parts[0].lowercase() + "-" + parts[1].uppercase()
    }

    fun languageName(tag: String): String? = LANGUAGES.firstOrNull { it.first == normalise(tag) }?.second

    /**
     * The voices to offer: offline ones in our languages, in language order, named "Voice 1",
     * "Voice 2"... within each language (phone voice names like "te-in-x-tem-local" mean nothing
     * to people).
     */
    fun choose(voices: Collection<VoiceInfo>): List<AiVoice> {
        val order = LANGUAGES.map { it.first }
        return voices
            .filter { !it.needsInternet }
            .mapNotNull { v -> normalise(v.localeTag).takeIf { it in order }?.let { it to v } }
            .distinctBy { it.second.name }
            .groupBy({ it.first }, { it.second })
            .toSortedMap(compareBy { order.indexOf(it) })
            .flatMap { (tag, list) ->
                list.sortedWith(compareBy<VoiceInfo>({ !it.installed }, { it.name })).mapIndexed { i, v ->
                    AiVoice(v.name, tag, "Voice ${i + 1}", v.installed)
                }
            }
    }
}

/** Turns typed text into a spoken audio file with the phone's own text-to-speech voice. */
object SpeechMaker {

    private const val TIMEOUT_MS = 60_000L

    /**
     * Speaks [text] into [file] (a WAV file), with the voice called [voiceName] (or the phone's
     * default) at [rate] and [pitch] (1 = normal). [onDone] is called once, on the main thread,
     * with true when the file was written. It is false when the phone has no speech voice installed.
     */
    fun speak(
        context: Context,
        text: String,
        file: File,
        voiceName: String? = null,
        rate: Float = 1f,
        pitch: Float = 1f,
        onDone: (Boolean) -> Unit,
    ) {
        val main = Handler(Looper.getMainLooper())
        var engine: TextToSpeech? = null
        var finished = false

        fun finish(ok: Boolean) {
            main.post {
                if (finished) return@post
                finished = true
                runCatching { engine?.shutdown() }
                onDone(ok && file.length() > 44)
            }
        }

        engine = TextToSpeech(context.applicationContext) { status ->
            val tts = engine
            if (status != TextToSpeech.SUCCESS || tts == null) {
                finish(false)
                return@TextToSpeech
            }
            if (voiceName != null) {
                runCatching { tts.voices?.firstOrNull { it.name == voiceName }?.let { tts.voice = it } }
            }
            tts.setSpeechRate(rate.coerceIn(0.3f, 3f))
            tts.setPitch(pitch.coerceIn(0.3f, 3f))
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) = finish(true)

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finish(false)

                override fun onError(utteranceId: String?, errorCode: Int) = finish(false)
            })
            if (tts.synthesizeToFile(text, Bundle(), file, "reelforge-speech") != TextToSpeech.SUCCESS) {
                finish(false)
            }
        }
        main.postDelayed({ finish(false) }, TIMEOUT_MS)
    }
}
