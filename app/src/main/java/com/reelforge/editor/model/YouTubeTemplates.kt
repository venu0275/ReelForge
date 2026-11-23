package com.reelforge.editor.model

/**
 * Templates for YouTube creators: intros, end screens, Shorts and the common video kinds.
 * Everything in them is the app's own: its effects and animated buttons are drawn by the app,
 * and the words and looks are its own designs, so there is nothing to license or credit.
 */
object YouTubeTemplates {
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()
    private const val YELLOW = 0xFFFFEB3B.toInt()
    private const val GOLD = 0xFFFFD54F.toInt()

    /** Big bold words with a coloured glow. */
    private fun headline(size: Float, y: Float, glow: Int, colour: Int = WHITE) = TextItem(
        text = "", color = colour, size = size, y = y, glow = true, glowColor = glow, shadow = false,
        letterSpacing = 0.04f, animIn = OverlayAnim.POP, animOut = OverlayAnim.FADE,
    )

    /** Words on a coloured box, like a label. */
    private fun label(size: Float, x: Float, y: Float, box: Int, colour: Int = WHITE, from: OverlayAnim = OverlayAnim.FROM_LEFT) = TextItem(
        text = "", color = colour, size = size, x = x, y = y, background = true, boxColor = box, shadow = false,
        animIn = from, animOut = OverlayAnim.FADE,
    )

    /** Bright words with a dark outline: readable on any picture. */
    private fun outlined(size: Float, y: Float, colour: Int = WHITE) = TextItem(
        text = "", color = colour, size = size, y = y, outline = true, outlineColor = BLACK, outlineWidth = 1.4f,
        animIn = OverlayAnim.ZOOM, animOut = OverlayAnim.FADE,
    )

    /** Shown for the rest of the video (parts are cut to the video's length). */
    private const val WHOLE = 600_000L

    val all: List<Template> = listOf(
        Template(
            id = "yt_intro", name = "Channel intro", icon = "🎬", group = "YouTube",
            about = "A 5-second opener: your channel name pops in with sparkles, then Subscribe.",
            photoMs = 1_200, videoMaxMs = 1_500,
            transition = TransitionStyle.ZOOM, transitionMs = 250, filter = FilterPreset.VIVID,
            motions = listOf(PhotoMotion.ZOOM_IN, PhotoMotion.ZOOM_OUT),
            title = "Welcome back!", ending = "",
            titleColor = WHITE, titleFont = TextFont.SANS, titleBox = false,
            musicHint = "upbeat intro", aspect = AspectRatio.LANDSCAPE, plainTexts = false, usesChannel = true,
            parts = listOf(
                TemplatePart.Effect("SPARKLES", At.START, 0, 4_000),
                TemplatePart.Words("channel", "", At.START, 200, 4_500, headline(0.14f, 0.08f, 0xFFFF1744.toInt())),
                TemplatePart.Words("title", "", At.START, 900, 3_800,
                    outlined(0.065f, -0.3f, YELLOW).copy(animIn = OverlayAnim.FROM_LEFT)),
                TemplatePart.Button("SUBSCRIBE", At.START, 2_200, 3_200, y = -0.7f, scale = 0.17f),
            ),
        ),
        Template(
            id = "yt_outro", name = "Outro / end screen", icon = "👋", group = "YouTube",
            about = "Thanks, Subscribe and Like, with the right side kept clear for YouTube's end-screen cards. " +
                "Best with 15-20 s at the end.",
            photoMs = 4_000, videoMaxMs = 20_000,
            transition = TransitionStyle.FADE, transitionMs = 600, filter = FilterPreset.NONE,
            motions = listOf(PhotoMotion.ZOOM_IN),
            title = "", ending = "Thanks for watching!",
            titleColor = WHITE, titleFont = TextFont.SANS, titleBox = false,
            musicHint = "chill outro", aspect = AspectRatio.LANDSCAPE, plainTexts = false, usesChannel = true,
            parts = listOf(
                TemplatePart.Words("ending", "", At.END, 16_000, WHOLE,
                    headline(0.09f, 0.72f, 0xFF40C4FF.toInt()).copy(x = -0.45f)),
                TemplatePart.Button("SUBSCRIBE", At.END, 14_000, WHOLE, x = -0.6f, y = 0.05f, scale = 0.2f),
                TemplatePart.Button("LIKE", At.END, 9_000, WHOLE, x = -0.6f, y = -0.45f, scale = 0.14f),
                TemplatePart.Words(null, "Watch next →", At.END, 12_000, WHOLE,
                    label(0.05f, 0.55f, 0.72f, 0xCC000000.toInt(), from = OverlayAnim.FROM_RIGHT)),
                TemplatePart.Words("channel", "", At.END, 16_000, WHOLE,
                    outlined(0.05f, -0.85f).copy(x = -0.6f, animIn = OverlayAnim.FADE)),
            ),
        ),
        Template(
            id = "yt_shorts", name = "Shorts hook", icon = "⚡", group = "YouTube",
            about = "Upright 9:16, quick cuts, a hook at the top to stop the scroll, Subscribe at the end. " +
                "Turn on captions after.",
            photoMs = 1_500, videoMaxMs = 2_000,
            transition = TransitionStyle.ZOOM, transitionMs = 200, filter = FilterPreset.VIVID,
            motions = listOf(PhotoMotion.ZOOM_IN, PhotoMotion.ZOOM_OUT),
            title = "WAIT FOR IT…", ending = "Follow for part 2",
            titleColor = YELLOW, titleFont = TextFont.SANS, titleBox = false,
            musicHint = "trending beat", aspect = AspectRatio.PORTRAIT, plainTexts = false, suggestCaptions = true,
            parts = listOf(
                TemplatePart.Words("title", "", At.START, 0, 2_500,
                    outlined(0.075f, 0.72f, YELLOW).copy(loop = LoopFx.PULSE, animIn = OverlayAnim.POP)),
                TemplatePart.Words("ending", "", At.END, 3_000, WHOLE, label(0.05f, 0f, 0.6f, 0xF2D50000.toInt())),
                TemplatePart.Button("SUBSCRIBE", At.END, 3_000, WHOLE, y = -0.55f, scale = 0.12f),
            ),
        ),
        Template(
            id = "yt_vlog", name = "Vlog", icon = "🎒", group = "YouTube",
            about = "Warm colours, sliding cuts and a lower-third title with the day and place.",
            photoMs = 2_500, videoMaxMs = 6_000,
            transition = TransitionStyle.SLIDE, transitionMs = 400, filter = FilterPreset.WARM,
            motions = listOf(PhotoMotion.PAN_LEFT, PhotoMotion.PAN_RIGHT, PhotoMotion.ZOOM_IN),
            title = "Day 1 · Goa", ending = "See you in the next vlog",
            titleColor = WHITE, titleFont = TextFont.SANS, titleBox = true,
            musicHint = "chill vlog", aspect = AspectRatio.LANDSCAPE, plainTexts = false, usesChannel = true,
            parts = listOf(
                TemplatePart.Words("title", "", At.START, 300, 3_800, label(0.065f, -0.7f, -0.62f, 0xE6FFFFFF.toInt(), colour = 0xFF212121.toInt())),
                TemplatePart.Words("channel", "", At.START, 500, 3_600, label(0.04f, -0.75f, -0.8f, 0xE6FF7043.toInt())),
                TemplatePart.Words("ending", "", At.END, 3_500, WHOLE, outlined(0.07f, 0.55f)),
                TemplatePart.Button("SUBSCRIBE", At.END, 3_500, WHOLE, y = -0.6f, scale = 0.17f),
            ),
        ),
        Template(
            id = "yt_tutorial", name = "Tutorial / how-to", icon = "🧑‍🏫", group = "YouTube",
            about = "Clean look, a clear title and a Step 1 label to build on. Turn on captions after.",
            photoMs = 4_000, videoMaxMs = 0,
            transition = TransitionStyle.FADE, transitionMs = 300, filter = FilterPreset.NONE,
            motions = listOf(PhotoMotion.NONE),
            title = "How to edit videos on your phone", ending = "Questions? Ask in the comments",
            titleColor = WHITE, titleFont = TextFont.SANS, titleBox = true,
            musicHint = "light background", aspect = AspectRatio.LANDSCAPE, plainTexts = false, suggestCaptions = true,
            parts = listOf(
                TemplatePart.Words("title", "", At.START, 0, 3_200, label(0.07f, 0f, 0.6f, 0xF21565C0.toInt(), from = OverlayAnim.POP)),
                TemplatePart.Words(null, "Step 1", At.START, 3_200, 3_000, label(0.055f, -0.82f, 0.8f, 0xF2FF6D00.toInt())),
                TemplatePart.Words("ending", "", At.END, 4_000, WHOLE, label(0.055f, 0f, 0.6f, 0xCC000000.toInt(), from = OverlayAnim.FADE)),
                TemplatePart.Button("LIKE", At.END, 4_000, WHOLE, x = -0.25f, y = -0.6f, scale = 0.14f),
                TemplatePart.Button("SUBSCRIBE", At.END, 4_000, WHOLE, x = 0.3f, y = -0.6f, scale = 0.16f),
            ),
        ),
        Template(
            id = "yt_gaming", name = "Gaming highlights", icon = "🎮", group = "YouTube",
            about = "Punchy colours, fast zoom cuts, fireworks on the big moment and confetti at the end.",
            photoMs = 1_000, videoMaxMs = 2_500,
            transition = TransitionStyle.ZOOM, transitionMs = 150, filter = FilterPreset.VIVID,
            motions = listOf(PhotoMotion.ZOOM_IN),
            title = "EPIC MOMENT", ending = "GG! Subscribe for more",
            titleColor = WHITE, titleFont = TextFont.BLACK, titleBox = false,
            musicHint = "gaming edm", aspect = AspectRatio.LANDSCAPE, plainTexts = false,
            parts = listOf(
                TemplatePart.Effect("FIREWORKS", At.START, 0, 3_000),
                TemplatePart.Words("title", "", At.START, 0, 2_800,
                    headline(0.13f, 0.35f, 0xFF00E676.toInt()).copy(font = TextFont.BLACK, loop = LoopFx.SHAKE)),
                TemplatePart.Effect("CONFETTI", At.END, 3_500, WHOLE),
                TemplatePart.Words("ending", "", At.END, 3_500, WHOLE, headline(0.075f, 0.5f, 0xFFD500F9.toInt())),
                TemplatePart.Button("SUBSCRIBE", At.END, 3_000, WHOLE, y = -0.6f, scale = 0.17f),
            ),
        ),
        Template(
            id = "yt_cooking", name = "Cooking / recipe", icon = "🍳", group = "YouTube",
            about = "Upright, warm colours, the dish name on top and an Ingredients label. Turn on captions after.",
            photoMs = 2_500, videoMaxMs = 5_000,
            transition = TransitionStyle.FADE, transitionMs = 400, filter = FilterPreset.WARM,
            motions = listOf(PhotoMotion.ZOOM_IN, PhotoMotion.PAN_UP),
            title = "Paneer Butter Masala", ending = "Try it and tag me",
            titleColor = 0xFFFFF3E0.toInt(), titleFont = TextFont.SERIF, titleBox = true,
            musicHint = "happy acoustic", aspect = AspectRatio.PORTRAIT, plainTexts = false, suggestCaptions = true,
            parts = listOf(
                TemplatePart.Words("title", "", At.START, 0, 3_200,
                    label(0.06f, 0f, 0.7f, 0xE65D4037.toInt(), colour = 0xFFFFF3E0.toInt(), from = OverlayAnim.POP).copy(font = TextFont.SERIF)),
                TemplatePart.Words(null, "Ingredients", At.START, 3_200, 3_000, label(0.045f, -0.6f, 0.75f, 0xE6FF8F00.toInt())),
                TemplatePart.Words("ending", "", At.END, 3_000, WHOLE, outlined(0.055f, 0.6f, GOLD)),
                TemplatePart.Button("LIKE", At.END, 3_000, WHOLE, y = -0.6f, scale = 0.1f),
            ),
        ),
        Template(
            id = "yt_podcast", name = "Podcast clip", icon = "🎙️", group = "YouTube",
            about = "Upright clip with the episode and your channel on screen the whole time. Turn on big captions after.",
            photoMs = 4_000, videoMaxMs = 0,
            transition = TransitionStyle.FADE, transitionMs = 300, filter = FilterPreset.NONE,
            motions = listOf(PhotoMotion.NONE),
            title = "Episode 12 · Money tips", ending = "",
            titleColor = WHITE, titleFont = TextFont.SANS, titleBox = true,
            musicHint = "soft podcast", aspect = AspectRatio.PORTRAIT, plainTexts = false,
            usesChannel = true, suggestCaptions = true,
            parts = listOf(
                TemplatePart.Words("title", "", At.START, 0, WHOLE, label(0.04f, 0f, 0.86f, 0xCC000000.toInt(), from = OverlayAnim.FADE)),
                TemplatePart.Words("channel", "", At.START, 0, WHOLE, label(0.035f, 0f, 0.77f, 0xE6E53935.toInt(), from = OverlayAnim.FADE)),
                TemplatePart.Button("FOLLOW", At.END, 3_500, WHOLE, y = -0.62f, scale = 0.1f),
            ),
        ),
        Template(
            id = "yt_review", name = "Unboxing / review", icon = "📦", group = "YouTube",
            about = "Bright, zooming cuts, a star rating near the end and a link reminder.",
            photoMs = 2_500, videoMaxMs = 6_000,
            transition = TransitionStyle.ZOOM, transitionMs = 300, filter = FilterPreset.VIVID,
            motions = listOf(PhotoMotion.ZOOM_IN, PhotoMotion.ZOOM_OUT),
            title = "Unboxing the new phone", ending = "Link in the description",
            titleColor = WHITE, titleFont = TextFont.SANS, titleBox = true,
            musicHint = "tech upbeat", aspect = AspectRatio.LANDSCAPE, plainTexts = false,
            parts = listOf(
                TemplatePart.Words("title", "", At.START, 0, 3_200, label(0.07f, 0f, 0.62f, 0xF2263238.toInt(), from = OverlayAnim.POP)),
                TemplatePart.Words(null, "★★★★☆", At.END, 6_000, 3_000, outlined(0.11f, 0.3f, GOLD).copy(animIn = OverlayAnim.POP)),
                TemplatePart.Words("ending", "", At.END, 3_000, WHOLE, label(0.055f, 0f, 0.55f, 0xF2D50000.toInt())),
                TemplatePart.Button("LIKE", At.END, 6_000, 3_000, x = 0f, y = -0.6f, scale = 0.14f),
                TemplatePart.Button("SUBSCRIBE", At.END, 3_000, WHOLE, y = -0.6f, scale = 0.17f),
            ),
        ),
        Template(
            id = "yt_festival", name = "Festival wishes", icon = "🪔", group = "YouTube",
            about = "Upright, Diwali lights all the way, a glowing wish (type it in Telugu, Hindi or English) and your channel.",
            photoMs = 2_500, videoMaxMs = 5_000,
            transition = TransitionStyle.FADE, transitionMs = 600, filter = FilterPreset.WARM,
            motions = listOf(PhotoMotion.ZOOM_IN, PhotoMotion.ZOOM_OUT),
            title = "శుభ దీపావళి", ending = "Happy Diwali from our family",
            titleColor = GOLD, titleFont = TextFont.SANS, titleBox = false,
            musicHint = "festive indian", aspect = AspectRatio.PORTRAIT, plainTexts = false, usesChannel = true,
            parts = listOf(
                TemplatePart.Effect("DIWALI", At.START, 0, WHOLE),
                TemplatePart.Words("title", "", At.START, 300, WHOLE,
                    headline(0.09f, 0.62f, 0xFFFF6F00.toInt(), GOLD).copy(loop = LoopFx.PULSE)),
                TemplatePart.Words("ending", "", At.END, 3_500, WHOLE, outlined(0.05f, 0.4f)),
                TemplatePart.Words("channel", "", At.END, 3_500, WHOLE, label(0.04f, 0f, -0.3f, 0xCC000000.toInt(), colour = GOLD)),
                TemplatePart.Button("SUBSCRIBE", At.END, 3_000, WHOLE, y = -0.55f, scale = 0.11f),
            ),
        ),
    )
}
