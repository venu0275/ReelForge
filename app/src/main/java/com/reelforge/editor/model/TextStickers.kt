package com.reelforge.editor.model

/** A set of text stickers that share a look ("Chat", "Hacker", "HUD"...). */
data class TextStickerPack(
    val id: String,
    /** "Text & Titles" or "Action". */
    val group: String,
    val name: String,
    val stickers: List<TextItem>,
)

/**
 * Text stickers: words inside a shape (chat bubble, terminal, sticky note, HUD, versus badge...).
 * After adding, the words, colours and movement can be changed like any text. All designs are
 * the app's own and drawn by it ([TextFrame]), so there is nothing to download or credit.
 */
object TextStickers {
    const val TITLES = "Text & Titles"
    const val ACTION = "Action"
    val GROUPS = listOf(TITLES, ACTION)

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()
    private const val INK = 0xFF1C1C1E.toInt()

    private fun sticker(
        text: String, frame: TextFrame, fill: Int, accent: Int, colour: Int,
        size: Float = 0.06f, font: TextFont = TextFont.SANS, bold: Boolean = true,
        animIn: OverlayAnim = OverlayAnim.POP, loop: LoopFx = LoopFx.NONE,
        align: TextAlignH = TextAlignH.CENTER, glow: Int? = null, outline: Int? = null,
        spacing: Float = 0f, reveal: TextReveal = TextReveal.NONE, rotation: Float = 0f,
    ) = TextItem(
        text = text, frame = frame, frameColor = fill, accentColor = accent, color = colour,
        size = size, font = font, bold = bold, align = align, animIn = animIn, animOut = OverlayAnim.FADE,
        loop = loop, shadow = false, glow = glow != null, glowColor = glow ?: 0xFF40C4FF.toInt(),
        outline = outline != null, outlineColor = outline ?: BLACK, letterSpacing = spacing,
        reveal = reveal, rotation = rotation,
    )

    val PACKS: List<TextStickerPack> = listOf(
        // ------------------------------------------------------------ Text & Titles
        TextStickerPack("chat", TITLES, "Chat", listOf(
            sticker("Hey! Are you free?", TextFrame.CHAT_IN, WHITE, 0xFF34B7F1.toInt(), INK, align = TextAlignH.LEFT, bold = false),
            sticker("On my way 🏃", TextFrame.CHAT_OUT, 0xFFDCF8C6.toInt(), 0xFF34B7F1.toInt(), INK, align = TextAlignH.LEFT, bold = false, animIn = OverlayAnim.FROM_RIGHT),
            sticker("Miss you ❤️", TextFrame.CHAT_OUT, 0xFF0A84FF.toInt(), WHITE, WHITE, align = TextAlignH.LEFT, bold = false, animIn = OverlayAnim.FROM_RIGHT),
            sticker("Wait… what?! 😳", TextFrame.CHAT_IN, 0xFF2C2C2E.toInt(), WHITE, WHITE, align = TextAlignH.LEFT, bold = false),
        )),
        TextStickerPack("hacker", TITLES, "Hacker", listOf(
            sticker("> ACCESS GRANTED", TextFrame.TERMINAL, 0xF20B0F0B.toInt(), 0xFF00E676.toInt(), 0xFF00E676.toInt(),
                font = TextFont.MONO, align = TextAlignH.LEFT, reveal = TextReveal.LETTERS, glow = 0xFF00C853.toInt()),
            sticker("> loading files…\n> 87% done", TextFrame.TERMINAL, 0xF20B0F0B.toInt(), 0xFF00E676.toInt(), 0xFF00E676.toInt(),
                size = 0.045f, font = TextFont.MONO, align = TextAlignH.LEFT, reveal = TextReveal.LETTERS),
            sticker("WARNING", TextFrame.ALERT, 0xE6000000.toInt(), 0xFF00E676.toInt(), 0xFF00E676.toInt(),
                font = TextFont.MONO, loop = LoopFx.BLINK, spacing = 0.2f),
            sticker("WELCOME", TextFrame.HUD, 0x2200E676, 0xFF00E676.toInt(), 0xFF00E676.toInt(), font = TextFont.MONO, spacing = 0.25f),
        )),
        TextStickerPack("frosted", TITLES, "Frosted Glass", listOf(
            sticker("Love you ♡", TextFrame.FROSTED, 0x55FFFFFF, WHITE, WHITE, font = TextFont.CURSIVE, loop = LoopFx.FLOAT),
            sticker("Smile!", TextFrame.FROSTED, 0x55FFFFFF, WHITE, WHITE, loop = LoopFx.FLOAT),
            sticker("Don't forget me", TextFrame.FROSTED, 0x44B3E5FC, WHITE, WHITE, size = 0.05f, font = TextFont.LIGHT),
            sticker("good vibes only", TextFrame.FROSTED, 0x55F8BBD0, WHITE, WHITE, size = 0.05f, font = TextFont.CASUAL),
        )),
        TextStickerPack("texting", TITLES, "Texting", listOf(
            sticker("Mom: Please call me back", TextFrame.NOTIFICATION, WHITE, 0xFF34C759.toInt(), INK, size = 0.045f, align = TextAlignH.LEFT, bold = false, animIn = OverlayAnim.SLIDE_DOWN),
            sticker("See you soon 😊", TextFrame.NOTIFICATION, WHITE, 0xFF0A84FF.toInt(), INK, size = 0.045f, align = TextAlignH.LEFT, bold = false, animIn = OverlayAnim.SLIDE_DOWN),
            sticker("New follower: you!", TextFrame.NOTIFICATION, 0xF21C1C1E.toInt(), 0xFFFF2D55.toInt(), WHITE, size = 0.045f, align = TextAlignH.LEFT, bold = false, animIn = OverlayAnim.SLIDE_DOWN),
        )),
        TextStickerPack("notifiers", TITLES, "Notifiers", listOf(
            sticker("Do not disturb", TextFrame.TOGGLE, WHITE, 0xFF34C759.toInt(), INK, size = 0.045f, bold = false),
            sticker("APP PUSH", TextFrame.TOGGLE, 0xFF1C1C1E.toInt(), 0xFF0A84FF.toInt(), WHITE, size = 0.045f),
            sticker("Notifications ON", TextFrame.TOGGLE, WHITE, 0xFFFF9500.toInt(), INK, size = 0.045f, bold = false),
        )),
        TextStickerPack("remind", TITLES, "Remind Me", listOf(
            sticker("This is for you!", TextFrame.STICKY, 0xFFFFF59D.toInt(), WHITE, 0xFF4E342E.toInt(), font = TextFont.CASUAL, rotation = 3f),
            sticker("Don't forget!", TextFrame.STICKY, 0xFFF8BBD0.toInt(), WHITE, 0xFF4E342E.toInt(), font = TextFont.CASUAL, rotation = -3f),
            sticker("To do:\n✓ Smile\n✓ Repeat", TextFrame.STICKY, 0xFFB3E5FC.toInt(), WHITE, 0xFF263238.toInt(), size = 0.045f, font = TextFont.CASUAL, align = TextAlignH.LEFT),
        )),
        TextStickerPack("neon", TITLES, "Neon Decos", listOf(
            sticker("Neon City", TextFrame.NEON, 0x66000000, 0xFFFF4FD8.toInt(), WHITE, font = TextFont.CURSIVE, glow = 0xFFFF4FD8.toInt(), loop = LoopFx.PULSE),
            sticker("OPEN", TextFrame.NEON, 0x66000000, 0xFF18FFFF.toInt(), WHITE, glow = 0xFF18FFFF.toInt(), loop = LoopFx.BLINK, spacing = 0.2f),
            sticker("GAME ON", TextFrame.NEON, 0x66000000, 0xFF76FF03.toInt(), WHITE, font = TextFont.BLACK, glow = 0xFF76FF03.toInt(), loop = LoopFx.PULSE),
        )),
        TextStickerPack("movie", TITLES, "Movie Trailer", listOf(
            sticker("AT THE END OF THE WORLD", TextFrame.MOVIE, 0, 0xFFFFD54F.toInt(), WHITE, size = 0.055f, font = TextFont.SERIF, spacing = 0.18f, animIn = OverlayAnim.FADE),
            sticker("COMING SOON", TextFrame.MOVIE, 0, WHITE, WHITE, size = 0.06f, font = TextFont.SERIF, spacing = 0.3f, animIn = OverlayAnim.ZOOM),
            sticker("DIRECTED BY\nYOUR NAME", TextFrame.MOVIE, 0, 0xFFB0BEC5.toInt(), 0xFFECEFF1.toInt(), size = 0.04f, font = TextFont.CONDENSED_LIGHT, spacing = 0.25f, animIn = OverlayAnim.FADE),
        )),
        TextStickerPack("bubbles", TITLES, "Bubbles & Tags", listOf(
            sticker("Hmm… 🤔", TextFrame.CLOUD, WHITE, WHITE, INK, font = TextFont.CASUAL, loop = LoopFx.FLOAT),
            sticker("NEW!", TextFrame.RIBBON, 0xFFE53935.toInt(), 0xFFFFCDD2.toInt(), WHITE, font = TextFont.BLACK, loop = LoopFx.PULSE),
            sticker("SALE 50% OFF", TextFrame.RIBBON, 0xFF212121.toInt(), 0xFFFFD54F.toInt(), 0xFFFFD54F.toInt(), font = TextFont.BLACK),
        )),

        // ------------------------------------------------------------ Action
        TextStickerPack("versus", ACTION, "Versus Mode", listOf(
            sticker("VS", TextFrame.VERSUS, 0xFFE53935.toInt(), 0xFF1E88E5.toInt(), WHITE, size = 0.12f, font = TextFont.BLACK, outline = BLACK, loop = LoopFx.SHAKE),
            sticker("TEAM A  VS  TEAM B", TextFrame.VERSUS, 0xFFFF6D00.toInt(), 0xFF6200EA.toInt(), WHITE, size = 0.06f, font = TextFont.BLACK, outline = BLACK),
        )),
        TextStickerPack("hud", ACTION, "HUD in Motion", listOf(
            sticker("TARGET LOCKED", TextFrame.HUD, 0x2218FFFF, 0xFF18FFFF.toInt(), 0xFF18FFFF.toInt(), size = 0.05f, font = TextFont.MONO, spacing = 0.15f, loop = LoopFx.BLINK),
            sticker("94%", TextFrame.HUD_RING, 0x3318FFFF, 0xFF18FFFF.toInt(), WHITE, size = 0.07f, font = TextFont.MONO, glow = 0xFF18FFFF.toInt(), loop = LoopFx.PULSE),
            sticker("LOADING…", TextFrame.PROGRESS, 0x44FFFFFF, 0xFF18FFFF.toInt(), WHITE, size = 0.045f, font = TextFont.MONO, spacing = 0.2f),
            sticker("SCAN", TextFrame.HUD_RING, 0x22FF1744, 0xFFFF1744.toInt(), 0xFFFF8A80.toInt(), size = 0.055f, font = TextFont.MONO, loop = LoopFx.SPIN),
        )),
        TextStickerPack("error", ACTION, "Error", listOf(
            sticker("Access denied", TextFrame.ERROR, 0xF2210B0B.toInt(), 0xFFE53935.toInt(), 0xFFFF8A80.toInt(), size = 0.05f, font = TextFont.MONO, loop = LoopFx.SHAKE),
            sticker("SYSTEM FAILURE", TextFrame.ERROR, 0xF20D0D12.toInt(), 0xFFFF1744.toInt(), WHITE, size = 0.05f, font = TextFont.MONO, reveal = TextReveal.LETTERS),
            sticker("ALERT", TextFrame.ALERT, 0xE6210B0B.toInt(), 0xFFFF1744.toInt(), 0xFFFF1744.toInt(), font = TextFont.BLACK, loop = LoopFx.BLINK, spacing = 0.2f),
        )),
        TextStickerPack("levelup", ACTION, "Level Up", listOf(
            sticker("LEVEL UP!", TextFrame.LEVEL_UP, 0xFFFFB300.toInt(), 0xFF6D4C00.toInt(), WHITE, size = 0.07f, font = TextFont.BLACK, outline = 0xFF6D4C00.toInt(), loop = LoopFx.BOUNCE),
            sticker("WIN", TextFrame.HEX, 0xFF1565C0.toInt(), 0xFF64FFDA.toInt(), WHITE, size = 0.08f, font = TextFont.BLACK, glow = 0xFF64FFDA.toInt()),
            sticker("MISSION COMPLETE", TextFrame.HEX, 0xFF1B5E20.toInt(), 0xFFB2FF59.toInt(), WHITE, size = 0.045f, font = TextFont.BLACK),
            sticker("START", TextFrame.HEX, 0xFF263238.toInt(), 0xFF40C4FF.toInt(), 0xFF40C4FF.toInt(), size = 0.055f, font = TextFont.BLACK, loop = LoopFx.PULSE),
        )),
        TextStickerPack("danger", ACTION, "Danger Zone", listOf(
            sticker("DANGER", TextFrame.DANGER, 0xFFFFD600.toInt(), BLACK, 0xFFFFD600.toInt(), size = 0.07f, font = TextFont.BLACK, spacing = 0.2f, loop = LoopFx.BLINK),
            sticker("DO NOT CROSS", TextFrame.DANGER, 0xFFFFD600.toInt(), BLACK, WHITE, size = 0.05f, font = TextFont.BLACK, spacing = 0.15f, animIn = OverlayAnim.FROM_LEFT),
            sticker("CAUTION", TextFrame.DANGER, 0xFFFF6D00.toInt(), BLACK, 0xFFFFAB40.toInt(), size = 0.06f, font = TextFont.BLACK, spacing = 0.2f),
        )),
        TextStickerPack("knockout", ACTION, "Knockout", listOf(
            sticker("K.O.!", TextFrame.BURST, 0xFFFFEB3B.toInt(), 0xFFD50000.toInt(), 0xFFD50000.toInt(), size = 0.1f, font = TextFont.BLACK, outline = WHITE, loop = LoopFx.SHAKE),
            sticker("BOOM!", TextFrame.BURST, 0xFFFF6D00.toInt(), BLACK, 0xFFFFEB3B.toInt(), size = 0.09f, font = TextFont.BLACK, outline = BLACK),
            sticker("ROUND 1", TextFrame.RIBBON, 0xFFD50000.toInt(), 0xFFFFCDD2.toInt(), WHITE, size = 0.07f, font = TextFont.BLACK, animIn = OverlayAnim.ZOOM),
            sticker("FIGHT!", TextFrame.BURST, 0xFFD50000.toInt(), 0xFFFFEB3B.toInt(), WHITE, size = 0.09f, font = TextFont.BLACK, outline = BLACK, loop = LoopFx.PULSE),
        )),
    )

    fun packs(group: String) = PACKS.filter { it.group == group }
}
