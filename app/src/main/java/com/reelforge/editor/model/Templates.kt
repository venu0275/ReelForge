package com.reelforge.editor.model

/**
 * A ready-made look: how long photos stay, how clips change, colour, photo movement and title
 * texts. These are the app's own designs, so there is nothing to license.
 */
data class Template(
    val id: String,
    val name: String,
    val icon: String,
    val about: String,
    val photoMs: Long,
    /** Longest a video clip is kept (longer ones are shortened); 0 = leave videos as they are. */
    val videoMaxMs: Long,
    val transition: TransitionStyle,
    val transitionMs: Long,
    val filter: FilterPreset,
    /** Movements given to photos in turn. */
    val motions: List<PhotoMotion>,
    val title: String,
    val ending: String,
    val titleColor: Int,
    val titleFont: TextFont,
    val titleBox: Boolean,
    /** A suggestion shown after applying, for finding matching music. */
    val musicHint: String,
    /** "Moments" (birthday, travel...) or "YouTube" (intros, outros, Shorts...). */
    val group: String = "Moments",
    /** The video shape the template is made for (null = keep the project's). */
    val aspect: AspectRatio? = null,
    /** Effects, animated buttons and styled texts the template adds. */
    val parts: List<TemplatePart> = emptyList(),
    /** False when [parts] place the title and closing line themselves. */
    val plainTexts: Boolean = true,
    /** Asks for the channel name (remembered for next time). */
    val usesChannel: Boolean = false,
    /** Suggests Auto captions after applying (Shorts, tutorials, podcasts). */
    val suggestCaptions: Boolean = false,
)

/** Where a template part sits in time: counted from the start, or back from the end. */
enum class At { START, END }

/** Something a template adds on top of the clips. Times are clamped to the video's length. */
sealed interface TemplatePart {
    val at: At
    /** From the start (START) or before the end (END) to where it begins. */
    val offsetMs: Long
    val lengthMs: Long

    /** A full-screen effect (a SceneEffect name: "CONFETTI", "SPARKLES"...). */
    data class Effect(val effect: String, override val at: At, override val offsetMs: Long, override val lengthMs: Long) : TemplatePart

    /** An animated button (an AnimatedButton name: "SUBSCRIBE", "LIKE"...), [scale] of the frame's height. */
    data class Button(
        val button: String, override val at: At, override val offsetMs: Long, override val lengthMs: Long,
        val x: Float = 0f, val y: Float = -0.6f, val scale: Float = 0.18f,
    ) : TemplatePart

    /**
     * A text. Its words come from [source] ("title", "ending" or "channel", as typed when applying)
     * or are [words]; [look] gives everything else (colour, size, place, movement).
     */
    data class Words(
        val source: String?, val words: String, override val at: At, override val offsetMs: Long,
        override val lengthMs: Long, val look: TextItem,
    ) : TemplatePart
}

object Templates {
    /** Texts added by a template have ids starting with this, so applying again replaces them. */
    const val TEXT_PREFIX = "tpl_"

    val all = listOf(
        Template(
            id = "birthday", name = "Birthday", icon = "🎂",
            about = "Warm colours, gentle zooms and a big title.",
            photoMs = 2_500, videoMaxMs = 6_000,
            transition = TransitionStyle.ZOOM, transitionMs = 500, filter = FilterPreset.WARM,
            motions = listOf(PhotoMotion.ZOOM_IN, PhotoMotion.ZOOM_OUT),
            title = "Happy Birthday!", ending = "Make a wish 🎉",
            titleColor = 0xFFFFD54F.toInt(), titleFont = TextFont.SERIF, titleBox = false,
            musicHint = "happy upbeat",
        ),
        Template(
            id = "travel", name = "Travel reel", icon = "✈️",
            about = "Bright colours, sliding cuts and slow pans.",
            photoMs = 2_000, videoMaxMs = 4_000,
            transition = TransitionStyle.SLIDE, transitionMs = 400, filter = FilterPreset.VIVID,
            motions = listOf(PhotoMotion.PAN_LEFT, PhotoMotion.PAN_RIGHT, PhotoMotion.ZOOM_IN),
            title = "Travel Diaries", ending = "See you on the next trip",
            titleColor = 0xFFFFFFFF.toInt(), titleFont = TextFont.SANS, titleBox = true,
            musicHint = "travel adventure",
        ),
        Template(
            id = "promo", name = "Product promo", icon = "🛍️",
            about = "Quick zoom cuts and a call to action.",
            photoMs = 1_600, videoMaxMs = 3_000,
            transition = TransitionStyle.ZOOM, transitionMs = 300, filter = FilterPreset.NONE,
            motions = listOf(PhotoMotion.ZOOM_OUT, PhotoMotion.ZOOM_IN),
            title = "New Arrival", ending = "Order now · Link in bio",
            titleColor = 0xFFFFFFFF.toInt(), titleFont = TextFont.SANS, titleBox = true,
            musicHint = "corporate energetic",
        ),
        Template(
            id = "memories", name = "Memories", icon = "📷",
            about = "Soft faded colours and slow fades.",
            photoMs = 3_500, videoMaxMs = 8_000,
            transition = TransitionStyle.FADE, transitionMs = 900, filter = FilterPreset.FADED,
            motions = listOf(PhotoMotion.ZOOM_IN, PhotoMotion.PAN_RIGHT, PhotoMotion.ZOOM_OUT, PhotoMotion.PAN_LEFT),
            title = "Memories", ending = "Forever in our hearts",
            titleColor = 0xFFFFFFFF.toInt(), titleFont = TextFont.SERIF, titleBox = false,
            musicHint = "piano calm",
        ),
        Template(
            id = "wedding", name = "Wedding", icon = "💍",
            about = "Warm tones, long gentle fades.",
            photoMs = 3_000, videoMaxMs = 8_000,
            transition = TransitionStyle.FADE, transitionMs = 800, filter = FilterPreset.WARM,
            motions = listOf(PhotoMotion.ZOOM_IN, PhotoMotion.PAN_UP),
            title = "Together Forever", ending = "Thank you for celebrating with us",
            titleColor = 0xFFFFF3E0.toInt(), titleFont = TextFont.SERIF, titleBox = false,
            musicHint = "romantic strings",
        ),
        Template(
            id = "fast", name = "Fast cuts", icon = "⚡",
            about = "Very short clips for a high-energy reel.",
            photoMs = 800, videoMaxMs = 1_500,
            transition = TransitionStyle.ZOOM, transitionMs = 150, filter = FilterPreset.VIVID,
            motions = listOf(PhotoMotion.NONE),
            title = "Let's go!", ending = "Follow for more",
            titleColor = 0xFFFFFFFF.toInt(), titleFont = TextFont.SANS, titleBox = true,
            musicHint = "edm beat",
        ),
    ) + YouTubeTemplates.all

    /** Template groups in the order the picker shows them. */
    val groups = listOf("YouTube", "Moments")

    fun byId(id: String) = all.firstOrNull { it.id == id }

    /** When [part] shows in a video [total] long, or null when it does not fit at all. */
    fun timing(part: TemplatePart, total: Long): Pair<Long, Long>? {
        if (total <= 0) return null
        val start = when (part.at) {
            At.START -> part.offsetMs
            At.END -> total - part.offsetMs
        }.coerceIn(0, total)
        val end = (start + part.lengthMs).coerceAtMost(total)
        return if (end - start < 300) null else start to end
    }

    /** Names of the effects and buttons [template] uses (their pictures are made before applying). */
    fun movingArt(template: Template): List<String> = template.parts.mapNotNull {
        when (it) {
            is TemplatePart.Effect -> it.effect
            is TemplatePart.Button -> it.button
            else -> null
        }
    }.distinct()

    /**
     * Applies [template] to the clips already in [project]: photo and video lengths, colour,
     * photo movement, changes between clips, and an opening [title] and closing [ending] text
     * (blank to leave one out). Texts from an earlier template are replaced; everything else the
     * user added stays.
     */
    fun apply(
        project: Project,
        template: Template,
        title: String,
        ending: String,
        channel: String = "",
        /** Picture files for the effects and buttons, by name (the preview shows them while loading). */
        stills: Map<String, String> = emptyMap(),
    ): Project {
        if (project.clips.isEmpty()) return project
        var photoIndex = 0
        var p = project.copy(
            aspect = template.aspect ?: project.aspect,
            clips = project.clips.map { c ->
                val shaped = when (c.type) {
                    ClipType.IMAGE -> {
                        val motion = template.motions[photoIndex++ % template.motions.size]
                        c.copy(
                            sourceDurationMs = maxOf(c.sourceDurationMs, c.trimStartMs + template.photoMs),
                            trimEndMs = c.trimStartMs + template.photoMs,
                            motion = motion,
                        )
                    }
                    ClipType.VIDEO -> {
                        if (template.videoMaxMs > 0 && c.durationMs > template.videoMaxMs) {
                            c.copy(trimEndMs = c.trimStartMs + (template.videoMaxMs * c.effectiveSpeed).toLong())
                        } else c
                    }
                }
                shaped.copy(filter = template.filter, transition = template.transition, fadeInMs = 0, fadeOutMs = 0)
            }
        )
        // Changes between neighbouring clips.
        p.clips.dropLast(1).forEach { c -> p = ProjectOps.transitionAfter(p, c.id, template.transitionMs) }

        val total = p.durationMs
        val texts = p.texts.filterNot { it.id.startsWith(TEXT_PREFIX) }.toMutableList()
        val layers = p.layers.filterNot { it.id.startsWith(TEXT_PREFIX) }.toMutableList()
        for (part in template.parts) {
            val (start, end) = timing(part, total) ?: continue
            when (part) {
                is TemplatePart.Effect -> layers += ImageLayer(
                    id = TEXT_PREFIX + newId(), uri = stills[part.effect] ?: continue,
                    startMs = start, endMs = end, scale = 1f, x = 0f, y = 0f, button = part.effect,
                )
                is TemplatePart.Button -> layers += ImageLayer(
                    id = TEXT_PREFIX + newId(), uri = stills[part.button] ?: continue,
                    startMs = start, endMs = end, scale = part.scale, x = part.x, y = part.y, button = part.button,
                )
                is TemplatePart.Words -> {
                    val words = when (part.source) {
                        "title" -> title
                        "ending" -> ending
                        "channel" -> channel
                        else -> part.words
                    }.trim()
                    if (words.isEmpty()) continue
                    texts += part.look.copy(id = TEXT_PREFIX + newId(), text = words, startMs = start, endMs = end)
                }
            }
        }
        p = p.copy(layers = layers)
        if (!template.plainTexts) return p.copy(texts = texts)
        val titleEnd = minOf(2_500L, total)
        if (title.isNotBlank()) {
            texts += TextItem(
                id = TEXT_PREFIX + newId(), text = title.trim(), color = template.titleColor,
                size = 0.1f, y = 0.1f, startMs = 0, endMs = titleEnd, font = template.titleFont,
                background = template.titleBox, boxColor = 0x99000000.toInt(),
                outline = !template.titleBox, animIn = OverlayAnim.ZOOM, animOut = OverlayAnim.FADE,
            )
        }
        if (ending.isNotBlank() && total > 3_000) {
            val start = (total - 2_000).coerceAtLeast(titleEnd)
            texts += TextItem(
                id = TEXT_PREFIX + newId(), text = ending.trim(), color = template.titleColor,
                size = 0.065f, y = -0.55f, startMs = start, endMs = total, font = template.titleFont,
                background = template.titleBox, boxColor = 0x99000000.toInt(),
                outline = !template.titleBox, animIn = OverlayAnim.FADE, animOut = OverlayAnim.FADE,
            )
        }
        return p.copy(texts = texts)
    }
}
