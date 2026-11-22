package com.reelforge.editor.engine

/**
 * Writes Hindi (Devanagari) in English letters the way people type it in chats ("Hinglish"):
 * क्या है → "kya hai", समझना → "samajhna", ज़िंदगी → "zindagi". Spelling in Hinglish varies, so
 * this aims for the common, easy-to-read form rather than a scholarly transliteration.
 * Anything that is not Devanagari (numbers, English words) is kept as it is.
 */
object Hinglish {
    private val consonants = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
        'ळ' to "l",
        // Letters with the dot already joined in.
        '\u0958' to "q", '\u0959' to "kh", '\u095A' to "gh", '\u095B' to "z", '\u095C' to "r", '\u095D' to "rh", '\u095E' to "f",
    )

    /** What a consonant becomes with the nukta dot (ज + ़ = ज़). */
    private val withNukta = mapOf('क' to "q", 'ख' to "kh", 'ग' to "gh", 'ज' to "z", 'ड' to "r", 'ढ' to "rh", 'फ' to "f")

    private enum class V { INHERENT, NONE, DELETED, A, AA, I, EE, U, OO, RI, E, AI, O, AU }

    private val standalone = mapOf(
        'अ' to V.A, 'आ' to V.AA, 'इ' to V.I, 'ई' to V.EE, 'उ' to V.U, 'ऊ' to V.OO, 'ऋ' to V.RI,
        'ए' to V.E, 'ऐ' to V.AI, 'ओ' to V.O, 'औ' to V.AU, 'ऑ' to V.O,
    )
    private val signs = mapOf(
        'ा' to V.AA, 'ि' to V.I, 'ी' to V.EE, 'ु' to V.U, 'ू' to V.OO, 'ृ' to V.RI,
        'े' to V.E, 'ै' to V.AI, 'ो' to V.O, 'ौ' to V.AU, 'ॉ' to V.O,
    )

    /** One letter group: a consonant (or "" for a standalone vowel, null for other text) and its vowel. */
    private class Unit(
        var sound: String?,
        var vowel: V,
        val letter: Char,
        var nasal: Boolean = false,
        var tail: String = "",
    ) {
        val voiced: Boolean get() = vowel != V.NONE && vowel != V.DELETED
        val consonant: Boolean get() = !sound.isNullOrEmpty()
    }

    fun convert(text: String): String =
        text.split(' ').joinToString(" ") { if (it.any { c -> c in 'ऀ'..'ॿ' }) word(it) else it }

    private fun word(w: String): String {
        val u = ArrayList<Unit>()
        for (ch in w) {
            val last = u.lastOrNull()
            when {
                consonants.containsKey(ch) -> u += Unit(consonants[ch], V.INHERENT, ch)
                ch == '़' && last != null && last.consonant -> last.sound = withNukta[last.letter] ?: last.sound
                standalone.containsKey(ch) -> u += Unit("", standalone.getValue(ch), ch)
                signs.containsKey(ch) && last != null && last.vowel == V.INHERENT -> last.vowel = signs.getValue(ch)
                ch == '्' && last != null -> last.vowel = V.NONE
                (ch == 'ं' || ch == 'ँ') && last != null -> last.nasal = true
                ch == 'ः' && last != null -> last.tail = "h"
                ch == '।' -> u += Unit(null, V.NONE, '.')
                else -> u += Unit(null, V.NONE, ch)
            }
        }
        val n = u.size
        // The "a" at the end of a word is not said (घर → ghar), unless the word is one letter.
        val end = u.lastOrNull()
        if (end != null && end.consonant && end.vowel == V.INHERENT && !end.nasal && u.count { it.voiced } > 1) {
            end.vowel = V.NONE
        }
        // Nor in the middle, between two sounded letters (करना → karna), unless that would
        // pile up three consonants (समझना → samajhna, ज़िंदगी → zindagi).
        for (i in n - 2 downTo 1) {
            val x = u[i]
            val before = u[i - 1]
            if (x.vowel != V.INHERENT || x.nasal || !x.consonant || !u[i + 1].consonant) continue
            if (!before.voiced || before.nasal) continue
            var j = i + 1
            while (j < n && u[j].consonant && u[j].vowel == V.NONE) j++
            if (j < n && u[j].consonant && u[j].voiced) x.vowel = V.DELETED
        }
        val vowels = u.count { it.voiced }
        val out = StringBuilder()
        for ((i, x) in u.withIndex()) {
            val sound = x.sound
            if (sound == null) {
                out.append(x.letter)
                continue
            }
            out.append(sound)
            val lastOne = i == n - 1
            val closed = !lastOne || x.nasal || x.tail.isNotEmpty()
            out.append(
                when (x.vowel) {
                    V.INHERENT, V.A -> "a"
                    V.NONE, V.DELETED -> ""
                    // आप, बात: long in short words; kya, hamara: short elsewhere.
                    V.AA -> if ((sound.isEmpty() && i == 0) || (vowels == 1 && closed)) "aa" else "a"
                    V.I -> "i"
                    V.EE -> if (lastOne) "i" else "ee"
                    V.U -> "u"
                    V.OO -> if (lastOne && !x.nasal) "u" else "oo"
                    V.RI -> "ri"
                    V.E -> "e"
                    V.AI -> "ai"
                    V.O -> "o"
                    V.AU -> "au"
                }
            )
            if (x.nasal) {
                val next = u.getOrNull(i + 1)?.sound
                out.append(if (next == "p" || next == "b" || next == "bh" || next == "m") "m" else "n")
            }
            out.append(x.tail)
        }
        return out.toString()
    }
}
