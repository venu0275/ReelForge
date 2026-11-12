package com.reelforge.editor.util

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File

/** Turns typed text into a spoken audio file with the phone's own text-to-speech voice. */
object SpeechMaker {

    private const val TIMEOUT_MS = 30_000L

    /**
     * Speaks [text] into [file] (a WAV file). [onDone] is called once, on the main thread, with
     * true when the file was written. It is false when the phone has no speech voice installed.
     */
    fun speak(context: Context, text: String, file: File, onDone: (Boolean) -> Unit) {
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
