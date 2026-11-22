package com.reelforge.editor.model

/**
 * Ready-made texts (words + look + movement) for festivals, greetings and reels. Each one may
 * use a store font ([fontId], from assets/font_catalog.json); the app downloads that font the
 * first time the preset is used. Everything is the app's own design, so there is nothing to credit.
 */
data class TextPreset(
    val id: String,
    /** "Festivals", "Telugu", "Hindi", "Tamil", "Reels" or "Titles". */
    val group: String,
    val text: String,
    val fontId: String?,
    private val look: TextItem,
) {
    /** The text to add, with [fontPath] set when its font is on the phone. */
    fun item(fontPath: String?): TextItem = look.copy(id = newId(), fontPath = fontPath)
}

object TextPresets {
    val GROUPS = listOf("Festivals", "Telugu", "Hindi", "Tamil", "Reels", "Titles")

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()
    private const val GOLD = 0xFFFFD54F.toInt()
    private const val ORANGE = 0xFFFF8F00.toInt()

    /** A greeting that glows: gold letters with a warm light around them. */
    private fun glowing(text: String, colour: Int = GOLD, glow: Int = ORANGE, size: Float = 0.11f) = TextItem(
        text = text, color = colour, size = size, glow = true, glowColor = glow, shadow = false,
        animIn = OverlayAnim.POP, animOut = OverlayAnim.FADE, loop = LoopFx.PULSE,
    )

    /** Bright letters with a contrasting outline. */
    private fun outlined(text: String, colour: Int, outline: Int, size: Float = 0.1f, width: Float = 1.4f) = TextItem(
        text = text, color = colour, size = size, outline = true, outlineColor = outline, outlineWidth = width,
        animIn = OverlayAnim.ZOOM, animOut = OverlayAnim.FADE,
    )

    /** Letters on a coloured box, like a label. */
    private fun boxed(text: String, colour: Int, box: Int, size: Float = 0.065f) = TextItem(
        text = text, color = colour, size = size, background = true, boxColor = box, shadow = false,
        animIn = OverlayAnim.FROM_LEFT, animOut = OverlayAnim.FADE,
    )

    val ALL: List<TextPreset> = listOf(
        // Festivals and wishes, in English.
        TextPreset("diwali", "Festivals", "Happy Diwali", "rozhaone", glowing("Happy Diwali")),
        TextPreset("sankranti", "Festivals", "Happy Sankranti", "yatraone",
            outlined("Happy Sankranti", 0xFFFFB300.toInt(), 0xFF6D2A0C.toInt())),
        TextPreset("holi", "Festivals", "Happy Holi", "baloo2",
            glowing("Happy Holi", 0xFFFF4FD8.toInt(), 0xFFFFEA00.toInt()).copy(loop = LoopFx.BOUNCE)),
        TextPreset("christmas", "Festivals", "Merry Christmas", "lobster",
            outlined("Merry Christmas", 0xFFE53935.toInt(), WHITE)),
        TextPreset("newyear", "Festivals", "Happy New Year", "bebasneue",
            glowing("HAPPY NEW YEAR", GOLD, 0xFFFFFFFF.toInt(), 0.12f).copy(letterSpacing = 0.08f)),
        TextPreset("eid", "Festivals", "Eid Mubarak", "abrilfatface",
            glowing("Eid Mubarak", GOLD, 0xFF00C853.toInt())),
        TextPreset("birthday", "Festivals", "Happy Birthday", "pacifico",
            outlined("Happy Birthday", 0xFFFF4081.toInt(), WHITE).copy(loop = LoopFx.BOUNCE)),
        TextPreset("anniversary", "Festivals", "Happy Anniversary", "greatvibes",
            glowing("Happy Anniversary", 0xFFFFF8E1.toInt(), 0xFFFF4081.toInt(), 0.1f)),

        // Telugu.
        TextPreset("te_diwali", "Telugu", "శుభ దీపావళి", "ramaraja", glowing("శుభ దీపావళి")),
        TextPreset("te_sankranti", "Telugu", "సంక్రాంతి శుభాకాంక్షలు", "mallanna",
            outlined("సంక్రాంతి శుభాకాంక్షలు", 0xFFFFB300.toInt(), 0xFF6D2A0C.toInt(), 0.085f)),
        TextPreset("te_ugadi", "Telugu", "ఉగాది శుభాకాంక్షలు", "gidugu",
            outlined("ఉగాది శుభాకాంక్షలు", 0xFF76FF03.toInt(), 0xFF1B5E20.toInt(), 0.09f)),
        TextPreset("te_birthday", "Telugu", "పుట్టినరోజు శుభాకాంక్షలు", "balootammudu2",
            outlined("పుట్టినరోజు శుభాకాంక్షలు", 0xFFFF4081.toInt(), WHITE, 0.08f).copy(loop = LoopFx.BOUNCE)),
        TextPreset("te_subscribe", "Telugu", "సబ్‌స్క్రైబ్ చేయండి", "hindguntur",
            boxed("సబ్‌స్క్రైబ్ చేయండి", WHITE, 0xF2E53935.toInt()).copy(loop = LoopFx.PULSE)),

        // Hindi.
        TextPreset("hi_diwali", "Hindi", "शुभ दीपावली", "rozhaone", glowing("शुभ दीपावली")),
        TextPreset("hi_holi", "Hindi", "होली की शुभकामनाएं", "baloo2",
            glowing("होली की शुभकामनाएं", 0xFFFF4FD8.toInt(), 0xFFFFEA00.toInt(), 0.09f)),
        TextPreset("hi_birthday", "Hindi", "जन्मदिन मुबारक", "yatraone",
            outlined("जन्मदिन मुबारक", 0xFFFF4081.toInt(), WHITE)),
        TextPreset("hi_subscribe", "Hindi", "सब्सक्राइब करें", "mukta",
            boxed("सब्सक्राइब करें", WHITE, 0xF2E53935.toInt()).copy(loop = LoopFx.PULSE)),

        // Tamil.
        TextPreset("ta_pongal", "Tamil", "இனிய பொங்கல் நல்வாழ்த்துக்கள்", "baloothambi2",
            outlined("இனிய பொங்கல்\nநல்வாழ்த்துக்கள்", 0xFFFFB300.toInt(), 0xFF6D2A0C.toInt(), 0.075f)),
        TextPreset("ta_diwali", "Tamil", "இனிய தீபாவளி நல்வாழ்த்துக்கள்", "muktamalar",
            glowing("இனிய தீபாவளி\nநல்வாழ்த்துக்கள்", size = 0.075f)),

        // Reels and YouTube.
        TextPreset("waitforit", "Reels", "WAIT FOR IT…", "bebasneue",
            outlined("WAIT FOR IT…", 0xFFFFEB3B.toInt(), BLACK, 0.12f).copy(loop = LoopFx.BLINK)),
        TextPreset("part2", "Reels", "PART 2", "anton",
            boxed("PART 2", WHITE, 0xF2D50000.toInt(), 0.1f).copy(animIn = OverlayAnim.POP)),
        TextPreset("breaking", "Reels", "BREAKING NEWS", "oswald",
            boxed("BREAKING NEWS", WHITE, 0xF2C62828.toInt(), 0.07f).copy(letterSpacing = 0.06f)),
        TextPreset("dontforget", "Reels", "Don't forget to subscribe", "poppins",
            boxed("Don't forget to subscribe 🔔", WHITE, 0xCC000000.toInt(), 0.05f)),
        TextPreset("pov", "Reels", "POV:", "permanentmarker",
            outlined("POV:", WHITE, BLACK, 0.11f).copy(animIn = OverlayAnim.POP)),

        // Titles.
        TextPreset("chapter", "Titles", "Chapter 1", "cinzel",
            TextItem(text = "Chapter 1", color = WHITE, size = 0.1f, letterSpacing = 0.12f,
                animIn = OverlayAnim.FADE, animOut = OverlayAnim.FADE)),
        TextPreset("comingsoon", "Titles", "Coming soon", "orbitron",
            glowing("COMING SOON", WHITE, 0xFF40C4FF.toInt(), 0.09f).copy(loop = LoopFx.BLINK)),
        TextPreset("theend", "Titles", "The End", "playfairdisplay",
            TextItem(text = "The End", color = WHITE, size = 0.13f, italic = true,
                animIn = OverlayAnim.FADE, animOut = OverlayAnim.FADE)),
    )
}
