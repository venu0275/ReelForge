package com.reelforge.editor.engine

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short, local record of recent exports: the phone, the settings, each attempt and how it
 * ended (finished, the error code, or where it stalled). Export problems often happen on only
 * a few phone models; this lets a user share exactly what happened on theirs. It holds no
 * file names or project content, and stays on the phone unless the user shares it.
 */
object ExportLog {

    private const val FILE = "export_log.txt"
    private const val MAX_BYTES = 64 * 1024

    private fun file(context: Context) = File(context.filesDir, FILE)

    /** The phone, its chip and Android version, in one line. */
    fun device(): String {
        val chip = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        return "${Build.MANUFACTURER} ${Build.MODEL} · $chip · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }

    /** Adds one line, keeping only the most recent part of the log. Never throws. */
    @Synchronized
    fun add(context: Context, line: String) {
        runCatching {
            val f = file(context)
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText("$stamp  $line\n")
            if (f.length() > MAX_BYTES) {
                val text = f.readText()
                f.writeText(text.substring(text.length - MAX_BYTES / 2).substringAfter('\n'))
            }
        }
    }

    /** The report a user can share: app version, phone, and the most recent log lines. */
    fun report(context: Context, appVersion: String): String {
        val lines = runCatching { file(context).readLines() }.getOrDefault(emptyList())
        return buildString {
            appendLine("CutMitra export report")
            appendLine("App $appVersion")
            appendLine(device())
            appendLine()
            lines.takeLast(60).forEach { appendLine(it) }
        }
    }
}
