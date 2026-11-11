package com.reelforge.editor.model

import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

enum class ClipType { VIDEO, IMAGE }

enum class AspectRatio(val w: Int, val h: Int, val label: String) {
    LANDSCAPE(16, 9, "16:9"),
    PORTRAIT(9, 16, "9:16"),
    SQUARE(1, 1, "1:1"),
    FOUR_FIVE(4, 5, "4:5"),
}

enum class FilterPreset(val label: String) {
    NONE("Original"),
    GRAYSCALE("Mono"),
    SEPIA("Sepia"),
    INVERT("Invert"),
    WARM("Warm"),
    COOL("Cool"),
    VIVID("Vivid"),
    FADED("Faded"),
}

/**
 * One item on the main track.
 *
 * For videos, [trimStartMs]..[trimEndMs] is the used range of the source file.
 * For images, [trimStartMs] is 0-based filler and (trimEnd - trimStart) is the display time.
 */
data class Clip(
    val id: String = newId(),
    val uri: String,
    val type: ClipType,
    val sourceDurationMs: Long,
    val trimStartMs: Long = 0,
    val trimEndMs: Long = sourceDurationMs,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val rotation: Int = 0,
    val filter: FilterPreset = FilterPreset.NONE,
    val brightness: Float = 0f,   // -1..1
    val contrast: Float = 0f,     // -1..1
    val saturation: Float = 0f,   // -100..100
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
    val hasAudio: Boolean = true,
    val opacity: Float = 1f,      // 0..1, blended over black
    val zoom: Float = 1f,         // 1..3, centre zoom
    val flipH: Boolean = false,   // mirror
    val fill: Boolean = false,    // true = crop to fill the frame, false = fit with bars
) {
    val effectiveSpeed: Float get() = if (type == ClipType.IMAGE) 1f else speed

    /** Duration on the timeline (after trim and speed). */
    val durationMs: Long get() = ((trimEndMs - trimStartMs) / effectiveSpeed).toLong()
}

enum class TextFont(val label: String) { SANS("Sans"), SERIF("Serif"), MONO("Mono") }

data class TextItem(
    val id: String = newId(),
    val text: String,
    val color: Int = 0xFFFFFFFF.toInt(),
    val size: Float = 0.08f,   // fraction of frame height
    val x: Float = 0f,         // -1 (left) .. 1 (right)
    val y: Float = 0f,         // -1 (bottom) .. 1 (top)
    val startMs: Long = 0,
    val endMs: Long = 3000,
    val font: TextFont = TextFont.SANS,
    val background: Boolean = false,
)

data class MusicTrack(
    val uri: String,
    val title: String,
    val durationMs: Long,
    val volume: Float = 0.6f,
    val loop: Boolean = true,
)

data class Project(
    val id: String = newId(),
    val name: String,
    val aspect: AspectRatio = AspectRatio.LANDSCAPE,
    val clips: List<Clip> = emptyList(),
    val texts: List<TextItem> = emptyList(),
    val music: MusicTrack? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val durationMs: Long get() = clips.sumOf { it.durationMs }

    fun clipStartMs(index: Int): Long = clips.take(index).sumOf { it.durationMs }

    /** Returns (clip index, time inside that clip on the timeline) for a timeline position. */
    fun locate(globalMs: Long): Pair<Int, Long>? {
        if (clips.isEmpty()) return null
        var acc = 0L
        clips.forEachIndexed { i, c ->
            val d = c.durationMs
            if (globalMs < acc + d) return i to (globalMs - acc).coerceAtLeast(0)
            acc += d
        }
        val last = clips.lastIndex
        return last to (clips[last].durationMs - 1).coerceAtLeast(0)
    }
}
