package com.reelforge.editor.model

import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

enum class ClipType { VIDEO, IMAGE }

enum class AspectRatio(val w: Int, val h: Int, val label: String) {
    LANDSCAPE(16, 9, "16:9"),
    PORTRAIT(9, 16, "9:16"),
    SQUARE(1, 1, "1:1"),
    FOUR_FIVE(4, 5, "4:5"),
    THREE_FOUR(3, 4, "3:4"),
    FOUR_THREE(4, 3, "4:3"),
    CINEMA(47, 20, "2.35:1"),
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
    val transition: TransitionStyle = TransitionStyle.FADE,
    val pitch: Float = 1f,        // voice changer: <1 deeper, >1 higher
) {
    val effectiveSpeed: Float get() = if (type == ClipType.IMAGE) 1f else speed

    /** Duration on the timeline (after trim and speed). */
    val durationMs: Long get() = ((trimEndMs - trimStartMs) / effectiveSpeed).toLong()
}

/** What fills the bars around a clip that does not fill the frame. */
enum class Background(val label: String) { BLACK("Black"), COLOR("Colour"), BLUR("Blur") }

/** How a clip's fade-in / fade-out time is animated. */
enum class TransitionStyle(val label: String) { FADE("Fade"), SLIDE("Slide"), ZOOM("Zoom") }

/** Entry / exit animation for text, stickers and image layers. */
enum class OverlayAnim(val label: String) {
    NONE("None"),
    FADE("Fade"),
    SLIDE("Slide up"),
    POP("Pop"),
    SLIDE_DOWN("Slide down"),
    FROM_LEFT("From left"),
    FROM_RIGHT("From right"),
    SPIN("Turn"),
    ZOOM("Zoom out"),
}

/** Every font family that ships with Android. [family] is the system family name. */
enum class TextFont(val label: String, val family: String) {
    SANS("Sans", "sans-serif"),
    SERIF("Serif", "serif"),
    MONO("Mono", "monospace"),
    LIGHT("Light", "sans-serif-light"),
    THIN("Thin", "sans-serif-thin"),
    MEDIUM("Medium", "sans-serif-medium"),
    BLACK("Heavy", "sans-serif-black"),
    CONDENSED("Condensed", "sans-serif-condensed"),
    CONDENSED_LIGHT("Condensed light", "sans-serif-condensed-light"),
    SMALLCAPS("Small caps", "sans-serif-smallcaps"),
    TYPEWRITER("Typewriter", "serif-monospace"),
    CASUAL("Casual", "casual"),
    CURSIVE("Cursive", "cursive"),
}

enum class TextAlignH(val label: String) { LEFT("Left"), CENTER("Centre"), RIGHT("Right") }

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
    val outline: Boolean = false,
    val animIn: OverlayAnim = OverlayAnim.NONE,
    val animOut: OverlayAnim = OverlayAnim.NONE,
    val bold: Boolean = true,
    val italic: Boolean = false,
    val align: TextAlignH = TextAlignH.CENTER,
    val opacity: Float = 1f,
    val rotation: Float = 0f,     // degrees, counter-clockwise
    val outlineColor: Int = 0xFF000000.toInt(),
    val boxColor: Int = 0x99000000.toInt(),
    val fontPath: String? = null, // imported .ttf / .otf file
    val moving: Boolean = false,  // glide from (x, y) to (x2, y2) while visible
    val x2: Float = 0f,
    val y2: Float = 0f,
)

/** A picture (photo, logo, watermark) drawn on top of the main track. */
data class ImageLayer(
    val id: String = newId(),
    val uri: String,
    val scale: Float = 0.35f,  // height as a fraction of frame height
    val x: Float = 0f,
    val y: Float = 0f,
    val opacity: Float = 1f,
    val rotation: Float = 0f,
    val startMs: Long = 0,
    val endMs: Long = 3000,
    val animIn: OverlayAnim = OverlayAnim.NONE,
    val animOut: OverlayAnim = OverlayAnim.NONE,
    val moving: Boolean = false,
    val x2: Float = 0f,
    val y2: Float = 0f,
)

/** A voice-over or sound effect placed at a position on the timeline. */
data class AudioClip(
    val id: String = newId(),
    val uri: String,
    val title: String,
    val startMs: Long,
    val durationMs: Long,
    val volume: Float = 1f,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
)

data class MusicTrack(
    val uri: String,
    val title: String,
    val durationMs: Long,
    val volume: Float = 0.6f,
    val loop: Boolean = true,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
)

data class Project(
    val id: String = newId(),
    val name: String,
    val aspect: AspectRatio = AspectRatio.LANDSCAPE,
    val clips: List<Clip> = emptyList(),
    val texts: List<TextItem> = emptyList(),
    val music: MusicTrack? = null,
    val layers: List<ImageLayer> = emptyList(),
    val audio: List<AudioClip> = emptyList(),
    val background: Background = Background.BLACK,
    val backgroundColor: Int = 0xFFFFFFFF.toInt(),
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
