package com.reelforge.editor.data

import android.content.Context

/** How the app follows the way the phone is held. */
enum class ScreenMode(val label: String) {
    AUTO("Auto"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape"),
}

/** Everything the user can choose on the Settings screen. */
data class AppSettings(
    val screen: ScreenMode = ScreenMode.AUTO,
    val keepScreenOn: Boolean = true,
    /** How long a newly added photo stays on screen. */
    val photoMs: Long = 3000,
    /** How long newly added text, stickers and pictures stay on screen. */
    val itemMs: Long = 3000,
    /** Whether dragged bars and trim handles stick to the red line and to other edges. */
    val snap: Boolean = true,
    val exportSide: Int = 720,
    val exportFps: Int = 30,
    /** 0 = small file, 1 = balanced, 2 = best quality. */
    val exportQuality: Int = 1,
    val exportHevc: Boolean = false,
    /** Play the video without sound while recording a voice-over, so only the voice is recorded. */
    val silentWhileRecording: Boolean = false,
    /** Seconds counted down on the video before a voice-over starts recording (0 = none). */
    val voiceCountdown: Int = 3,
    /** Record voice-overs with the phone's noise and echo removal, then clean them up further. */
    val cleanVoice: Boolean = true,
    /** The short animated tour of Ratio has been shown (it plays once). */
    val ratioTourSeen: Boolean = false,
    /** Voice style given to each new voice-over (it can still be changed afterwards). */
    val recordFx: com.reelforge.editor.model.AudioFx = com.reelforge.editor.model.AudioFx.NONE,
    /** Pitch shift in semitones given to each new voice-over (-12..12). */
    val recordSemitones: Int = 0,
) {
    /** The same settings with every value forced into its allowed range. */
    fun sanitized(): AppSettings = copy(
        photoMs = photoMs.coerceIn(500, 20_000),
        itemMs = itemMs.coerceIn(500, 20_000),
        exportSide = when {
            exportSide in SIDES -> exportSide
            exportSide > SIDES.last() -> SIDES.last() // 1440p and 4K were offered before
            else -> 720
        },
        exportFps = if (exportFps in FPS) exportFps else 30,
        exportQuality = exportQuality.coerceIn(0, 2),
        voiceCountdown = voiceCountdown.coerceIn(0, 5),
        recordSemitones = recordSemitones.coerceIn(-12, 12),
    )

    companion object {
        // Export sizes offered on every phone. Larger sizes were dropped: many phones cannot
        // encode them in hardware, and they made exports slow or stall.
        val SIDES = listOf(480, 720, 1080)
        val FPS = listOf(24, 25, 30, 50, 60)
        val LENGTHS = listOf(1000L, 2000L, 3000L, 5000L, 8000L, 10_000L)
    }
}

/** Saves the settings on the phone. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            screen = runCatching { ScreenMode.valueOf(prefs.getString("screen", d.screen.name)!!) }.getOrDefault(d.screen),
            keepScreenOn = prefs.getBoolean("keepScreenOn", d.keepScreenOn),
            photoMs = prefs.getLong("photoMs", d.photoMs),
            itemMs = prefs.getLong("itemMs", d.itemMs),
            snap = prefs.getBoolean("snap", d.snap),
            exportSide = prefs.getInt("exportSide", d.exportSide),
            exportFps = prefs.getInt("exportFps", d.exportFps),
            exportQuality = prefs.getInt("exportQuality", d.exportQuality),
            exportHevc = prefs.getBoolean("exportHevc", d.exportHevc),
            silentWhileRecording = prefs.getBoolean("silentWhileRecording", d.silentWhileRecording),
            voiceCountdown = prefs.getInt("voiceCountdown", d.voiceCountdown),
            cleanVoice = prefs.getBoolean("cleanVoice", d.cleanVoice),
            ratioTourSeen = prefs.getBoolean("ratioTourSeen", d.ratioTourSeen),
            recordFx = runCatching {
                com.reelforge.editor.model.AudioFx.valueOf(prefs.getString("recordFx", d.recordFx.name)!!)
            }.getOrDefault(d.recordFx),
            recordSemitones = prefs.getInt("recordSemitones", d.recordSemitones),
        ).sanitized()
    }

    fun save(s: AppSettings) {
        prefs.edit()
            .putString("screen", s.screen.name)
            .putBoolean("keepScreenOn", s.keepScreenOn)
            .putLong("photoMs", s.photoMs)
            .putLong("itemMs", s.itemMs)
            .putBoolean("snap", s.snap)
            .putInt("exportSide", s.exportSide)
            .putInt("exportFps", s.exportFps)
            .putInt("exportQuality", s.exportQuality)
            .putBoolean("exportHevc", s.exportHevc)
            .putBoolean("silentWhileRecording", s.silentWhileRecording)
            .putInt("voiceCountdown", s.voiceCountdown)
            .putBoolean("cleanVoice", s.cleanVoice)
            .putBoolean("ratioTourSeen", s.ratioTourSeen)
            .putString("recordFx", s.recordFx.name)
            .putInt("recordSemitones", s.recordSemitones)
            .apply()
    }
}
