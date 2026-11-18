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
    val name: String = "",      // the file's name, shown on the timeline
    val hasAudio: Boolean = true,
    val opacity: Float = 1f,      // 0..1, blended over black
    val zoom: Float = 1f,         // 1..3, centre zoom
    val flipH: Boolean = false,   // mirror
    val fill: Boolean = false,    // true = crop to fill the frame, false = fit with bars
    val transition: TransitionStyle = TransitionStyle.FADE,
    val pitch: Float = 1f,        // voice changer: <1 deeper, >1 higher
    val audioFx: AudioFx = AudioFx.NONE,
    val motion: PhotoMotion = PhotoMotion.NONE, // slow pan / zoom on a photo (Ken Burns)
) {
    val effectiveSpeed: Float get() = if (type == ClipType.IMAGE) 1f else speed

    /** Duration on the timeline (after trim and speed). */
    val durationMs: Long get() = ((trimEndMs - trimStartMs) / effectiveSpeed).toLong()
}

/** What fills the bars around a clip that does not fill the frame. */
enum class Background(val label: String) { BLACK("Black"), COLOR("Colour"), BLUR("Blur") }

/** How a clip's fade-in / fade-out time is animated. */
enum class TransitionStyle(val label: String) { FADE("Fade"), SLIDE("Slide"), ZOOM("Zoom") }

/**
 * One point of a keyframe animation: where an item is, how big, how turned and how see-through
 * at [tMs] after the item appears. Between keyframes the values glide evenly.
 */
data class Keyframe(
    val tMs: Long,
    val x: Float,
    val y: Float,
    val scale: Float = 1f,      // multiplies the item's own size
    val rotation: Float = 0f,
    val opacity: Float = 1f,
)

/** A sound effect applied to a clip's audio on export. */
/**
 * A sound effect. [pitch] above 1 raises the voice (Chipmunk), below 1 lowers it (Deep voice);
 * the length of the sound stays the same.
 */
enum class AudioFx(val label: String, val pitch: Float = 1f) {
    NONE("None"),
    CHIPMUNK("Chipmunk", 1.6f),
    DEEP("Deep voice", 0.72f),
    ROBOT("Robot"),
    ECHO("Echo"),
    REVERB("Hall"),
    PHONE("Telephone"),
    BASS("Bass boost"),
    TREBLE("Treble boost");

    /** True when the effect only changes pitch (or nothing), with no filter to run. */
    val filterless: Boolean get() = this == NONE || pitch != 1f
}

/** Slow movement over a photo while it is on screen, so a slideshow feels alive (Ken Burns). */
enum class PhotoMotion(val label: String) {
    NONE("None"),
    ZOOM_IN("Zoom in"),
    ZOOM_OUT("Zoom out"),
    PAN_LEFT("Pan left"),
    PAN_RIGHT("Pan right"),
    PAN_UP("Pan up"),
    PAN_DOWN("Pan down");

    /**
     * How the photo is placed [progress] (0..1) of the way through its time: [scale] (1 = as is)
     * and a shift as a fraction of half the frame (positive [dx] = right, positive [dy] = up).
     * The photo is always scaled up enough that the shift never shows an edge.
     */
    fun pose(progress: Float): MotionPose {
        val t = progress.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) } // ease in and out
        return when (this) {
            NONE -> MotionPose(1f, 0f, 0f)
            ZOOM_IN -> MotionPose(1f + ZOOM * t, 0f, 0f)
            ZOOM_OUT -> MotionPose(1f + ZOOM * (1 - t), 0f, 0f)
            PAN_LEFT -> MotionPose(1f + ZOOM, PAN * (1 - 2 * t), 0f)
            PAN_RIGHT -> MotionPose(1f + ZOOM, -PAN * (1 - 2 * t), 0f)
            PAN_UP -> MotionPose(1f + ZOOM, 0f, -PAN * (1 - 2 * t))
            PAN_DOWN -> MotionPose(1f + ZOOM, 0f, PAN * (1 - 2 * t))
        }
    }

    companion object {
        const val ZOOM = 0.18f
        const val PAN = 0.14f   // less than ZOOM, so no edge shows
    }
}

data class MotionPose(val scale: Float, val dx: Float, val dy: Float)

/**
 * Speed that changes inside a clip. [curve] gives the speed (times the clip's own speed) at each
 * point 0..1 through the clip.
 */
enum class SpeedRamp(val label: String, private val points: List<Float>) {
    MONTAGE("Montage", listOf(1f, 1f, 0.4f, 0.4f, 2.5f, 2.5f)),
    HERO("Hero", listOf(2f, 2f, 0.3f, 0.3f, 2f, 2f)),
    BULLET("Bullet", listOf(2.5f, 0.2f, 0.2f, 0.2f, 2.5f)),
    FLASH_IN("Flash in", listOf(0.5f, 1f, 2f, 3f)),
    FLASH_OUT("Flash out", listOf(3f, 2f, 1f, 0.5f));

    /** The speed factor [t] (0..1) of the way through, joining the points smoothly. */
    fun curve(t: Float): Float {
        val x = t.coerceIn(0f, 1f) * (points.size - 1)
        val i = x.toInt().coerceAtMost(points.size - 2)
        val f = x - i
        val smooth = f * f * (3 - 2 * f)
        return points[i] + (points[i + 1] - points[i]) * smooth
    }
}

/** A movement that repeats for as long as a sticker or picture is on screen (the "GIF" look). */
enum class LoopFx(val label: String) {
    NONE("None"), BOUNCE("Bounce"), SHAKE("Shake"), PULSE("Pulse"),
    WOBBLE("Wobble"), SPIN("Spin"), FLOAT("Float"), BLINK("Blink"),
}

/** Offset from the resting pose caused by a [LoopFx]; dx / dy are in frame units (-1..1, up is +). */
data class LoopPose(
    val dx: Float = 0f, val dy: Float = 0f, val scale: Float = 1f, val rotation: Float = 0f, val alpha: Float = 1f,
)

/** Where a looping effect has moved things [tMs] after the item appeared. Repeats forever. */
fun loopPose(loop: LoopFx, tMs: Long): LoopPose {
    val t = tMs.coerceAtLeast(0).toDouble()
    fun wave(periodMs: Double) = kotlin.math.sin(2 * Math.PI * t / periodMs).toFloat()
    return when (loop) {
        LoopFx.NONE -> LoopPose()
        LoopFx.BOUNCE -> LoopPose(dy = kotlin.math.abs(wave(1200.0)) * 0.08f)
        LoopFx.SHAKE -> LoopPose(dx = wave(120.0) * 0.012f, rotation = wave(90.0) * 3f)
        LoopFx.PULSE -> LoopPose(scale = 1f + 0.12f * wave(700.0))
        LoopFx.WOBBLE -> LoopPose(rotation = 12f * wave(900.0))
        LoopFx.SPIN -> LoopPose(rotation = (t % 2000.0 / 2000.0 * 360.0).toFloat())
        LoopFx.FLOAT -> LoopPose(dx = 0.015f * wave(3100.0), dy = 0.03f * wave(2400.0))
        LoopFx.BLINK -> LoopPose(alpha = if ((tMs.coerceAtLeast(0) / 400) % 2 == 0L) 1f else 0.25f)
    }
}

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
    val keyframes: List<Keyframe> = emptyList(), // when set, these drive position / turn / opacity
    val underline: Boolean = false,
    val letterSpacing: Float = 0f,   // extra space between letters, in letter widths (em)
    val lineSpacing: Float = 1f,     // 1 = normal distance between lines
    val shadow: Boolean = true,
    val shadowColor: Int = 0xB0000000.toInt(),
    val shadowStrength: Float = 1f,  // 1 = normal; scales how far and how soft the shadow is
    val glow: Boolean = false,
    val glowColor: Int = 0xFF40C4FF.toInt(),
    val outlineWidth: Float = 1f,    // 1 = normal thickness
    val loop: LoopFx = LoopFx.NONE,  // movement that repeats while the text is showing
    val reveal: TextReveal = TextReveal.NONE,
) {
    /** This text with the look (colour, font, effects) of [other]; words, place and timing stay. */
    fun withLookOf(other: TextItem): TextItem = copy(
        color = other.color, size = other.size, font = other.font, fontPath = other.fontPath,
        bold = other.bold, italic = other.italic, underline = other.underline, align = other.align,
        letterSpacing = other.letterSpacing, lineSpacing = other.lineSpacing,
        outline = other.outline, outlineColor = other.outlineColor, outlineWidth = other.outlineWidth,
        background = other.background, boxColor = other.boxColor,
        shadow = other.shadow, shadowColor = other.shadowColor, shadowStrength = other.shadowStrength,
        glow = other.glow, glowColor = other.glowColor,
    )

    /** This text with the movement (in, out, keep moving, reveal) of [other]. */
    fun withMotionOf(other: TextItem): TextItem = copy(
        animIn = other.animIn, animOut = other.animOut, loop = other.loop, reveal = other.reveal,
    )
}

/** How the words of a text appear. */
enum class TextReveal(val label: String) {
    NONE("All at once"), LETTERS("Letter by letter"), WORDS("Word by word"),
}

/**
 * How many characters of [text] are showing at each step of a reveal; the last step shows all of
 * it. Empty when there is nothing to reveal. Long texts are grouped into at most [maxSteps] steps.
 */
fun revealSteps(reveal: TextReveal, text: String, maxSteps: Int = 24): List<Int> {
    if (reveal == TextReveal.NONE || text.isEmpty() || maxSteps < 1) return emptyList()
    val ends = ArrayList<Int>()
    for (i in text.indices) {
        val c = text[i]
        if (c.isWhitespace() || Character.isHighSurrogate(c)) continue
        val endsWord = i == text.lastIndex || text[i + 1].isWhitespace()
        if (reveal == TextReveal.LETTERS || endsWord) ends += i + 1
    }
    if (ends.isEmpty()) return emptyList()
    val steps = if (ends.size <= maxSteps) ends
    else ArrayList((1..maxSteps).map { ends[it * ends.size / maxSteps - 1] })
    steps[steps.lastIndex] = text.length
    return steps
}

/** How long a reveal takes for a text that is on screen for [durationMs]. */
fun revealDurationMs(durationMs: Long): Long = minOf(1500L, durationMs * 6 / 10).coerceAtLeast(1L)

/** Characters showing [tMs] after the text appeared, or null once everything is showing. */
fun revealCount(steps: List<Int>, tMs: Long, durationMs: Long): Int? {
    if (steps.isEmpty()) return null
    val span = revealDurationMs(durationMs)
    if (tMs >= span) return null
    val index = (tMs.coerceAtLeast(0) * steps.size / span).toInt().coerceIn(0, steps.lastIndex)
    return steps[index]
}

/**
 * Ready-made looks for a text. Applying one changes only how the text looks (colour, size, font,
 * weight, outline, box); the words, place, timing and animation are kept.
 */
enum class TextStyle(val label: String, val hint: String) {
    TITLE("Title", "Large white letters with a dark outline"),
    SUBTITLE("Subtitle", "Small white letters on a dark box"),
    CAPTION("Caption", "Yellow letters with a black outline"),
    COMIC("Comic", "Big slanted yellow letters, thick black outline"),
    VLOG("Vlog", "Thin white letters, no outline"),
    ALERT("Alert", "White letters on a red box"),
    NOTE("Note", "Dark letters on a pale yellow box"),
    NEON("Neon", "Bright letters with a blue glow"),
    PLAIN("Plain", "White letters, nothing else");

    fun applyTo(t: TextItem): TextItem {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val base = t.copy(
            fontPath = null, italic = false, bold = true, outline = false, background = false,
            outlineColor = black, boxColor = 0x99000000.toInt(),
            underline = false, letterSpacing = 0f, lineSpacing = 1f, outlineWidth = 1f,
            shadow = true, shadowColor = 0xB0000000.toInt(), shadowStrength = 1f, glow = false,
        )
        return when (this) {
            TITLE -> base.copy(color = white, size = 0.12f, font = TextFont.SANS, outline = true)
            SUBTITLE -> base.copy(color = white, size = 0.055f, font = TextFont.SANS, bold = false, background = true)
            CAPTION -> base.copy(color = 0xFFFFEB3B.toInt(), size = 0.07f, font = TextFont.SANS, outline = true)
            COMIC -> base.copy(color = 0xFFFFEB3B.toInt(), size = 0.14f, font = TextFont.SANS, italic = true, outline = true)
            VLOG -> base.copy(color = white, size = 0.08f, font = TextFont.THIN, bold = false)
            ALERT -> base.copy(color = white, size = 0.08f, font = TextFont.SANS, background = true, boxColor = 0xE6D32F2F.toInt())
            NOTE -> base.copy(color = 0xFF212121.toInt(), size = 0.06f, font = TextFont.SERIF, bold = false, background = true, boxColor = 0xF2FFF59D.toInt())
            NEON -> base.copy(
                color = white, size = 0.1f, font = TextFont.SANS, shadow = false, glow = true,
                glowColor = 0xFF40C4FF.toInt(),
            )
            PLAIN -> base.copy(color = white, size = 0.08f, font = TextFont.SANS, bold = false)
        }
    }
}

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
    val keyframes: List<Keyframe> = emptyList(),
    val loop: LoopFx = LoopFx.NONE,
)

/**
 * A video drawn on top of the main track (picture-in-picture). It plays its trimmed range once,
 * starting at [startMs] on the timeline. With [chromaKey] on, the key colour becomes see-through.
 */
data class VideoLayer(
    val id: String = newId(),
    val uri: String,
    val sourceDurationMs: Long,
    val trimStartMs: Long = 0,
    val trimEndMs: Long = sourceDurationMs,
    val startMs: Long = 0,
    val aspect: Float = 16f / 9f,   // width / height of the video as displayed
    val scale: Float = 0.4f,        // height as a fraction of frame height
    val x: Float = 0.8f,
    val y: Float = 0.8f,
    val opacity: Float = 1f,
    val rotation: Float = 0f,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val hasAudio: Boolean = true,
    val chromaKey: Boolean = false,
    val keyColor: Int = 0xFF00FF00.toInt(),
    val keyTolerance: Float = 0.18f,
    val keyframes: List<Keyframe> = emptyList(),
) {
    val durationMs: Long get() = trimEndMs - trimStartMs
}

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
    val offsetMs: Long = 0,   // where in the audio file this clip starts playing
    val audioFx: AudioFx = AudioFx.NONE,
    val voice: Boolean = false, // a voice-over (recorded or spoken); other sound is lowered under it
    val clean: Boolean = false, // background noise lowered and loudness evened out (Clean voice)
)

data class MusicTrack(
    val uri: String,
    val title: String,
    val durationMs: Long,
    val volume: Float = 0.6f,
    val loop: Boolean = true,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
    val startMs: Long = 0,    // where on the timeline the music begins
    val stopMs: Long = 0,     // where it is cut off; 0 = plays as long as it can
    val offsetMs: Long = 0,   // where in the audio file it begins
    val beats: List<Long> = emptyList(), // beat times found in the file (ms into the file)
) {
    /** Where the music ends on a video [videoMs] long. */
    fun windowEnd(videoMs: Long): Long {
        val natural = if (loop || durationMs <= 0) videoMs
        else minOf(videoMs, startMs + durationMs - offsetMs % durationMs)
        return if (stopMs > startMs) minOf(stopMs, natural) else natural
    }

    /**
     * Where on the timeline the music file itself runs out the first time through (or where it
     * was cut with [stopMs]). This can be past the end of the video; that part is not heard.
     */
    fun fileEnd(): Long {
        val whole = if (durationMs > 0) startMs + durationMs - offsetMs % durationMs else startMs
        return if (stopMs > startMs) minOf(stopMs, whole) else whole
    }

    /** The position in the audio file that plays at [timelineMs]. */
    fun filePosition(timelineMs: Long): Long {
        val raw = offsetMs + (timelineMs - startMs)
        return if (durationMs > 0 && loop) raw % durationMs else raw
    }
}

data class Project(
    val id: String = newId(),
    val name: String,
    /** How loud the video's own sound plays while a voice-over is speaking (1 = unchanged). */
    val duckLevel: Float = 1f,
    val aspect: AspectRatio = AspectRatio.LANDSCAPE,
    val clips: List<Clip> = emptyList(),
    val texts: List<TextItem> = emptyList(),
    val music: MusicTrack? = null,
    val layers: List<ImageLayer> = emptyList(),
    val audio: List<AudioClip> = emptyList(),
    val videoLayers: List<VideoLayer> = emptyList(),
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

/**
 * Gain (0..1) for the video's own sound at [tUs], lowered to [level] inside any of [ranges]
 * (microseconds), with short [rampUs] slopes at their edges so the change is smooth.
 */
fun duckGain(tUs: Long, ranges: List<LongRange>, level: Float, rampUs: Long = 150_000L): Float {
    if (level >= 1f || ranges.isEmpty()) return 1f
    var weight = 0f
    for (r in ranges) {
        val w = when {
            tUs < r.first - rampUs || tUs > r.last + rampUs -> 0f
            tUs < r.first -> 1f - (r.first - tUs).toFloat() / rampUs
            tUs > r.last -> 1f - (tUs - r.last).toFloat() / rampUs
            else -> 1f
        }
        if (w > weight) weight = w
    }
    return 1f - (1f - level.coerceIn(0f, 1f)) * weight
}

/** The stretches of time, in microseconds from the start of clip [index], where a voice-over plays. */
fun voiceRangesInClip(project: Project, index: Int): List<LongRange> {
    if (project.duckLevel >= 1f) return emptyList()
    val clipStart = project.clipStartMs(index)
    val clipEnd = clipStart + project.clips[index].durationMs
    return project.audio.filter { it.voice }.mapNotNull { a ->
        val from = maxOf(a.startMs, clipStart)
        val to = minOf(a.startMs + a.durationMs, clipEnd)
        if (to > from) ((from - clipStart) * 1000)..((to - clipStart) * 1000) else null
    }
}
