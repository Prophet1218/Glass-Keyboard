package com.example.glasskeyboard

import android.graphics.RectF

enum class KeyType { CHAR, SHIFT, DELETE, SPACE, ENTER, TO_SYMBOLS, TO_LETTERS, TO_MORE, EMOJI, GLOBE, MIC, TEXT }

/** One key. [width] is in key units (a full row is 10 units). */
class Key(
    val type: KeyType,
    val label: String,
    val output: String = label,
    val width: Float = 1f,
    val popup: List<String> = emptyList(),
) {
    val rect = RectF()

    /** Function keys get the darker glass, like on iOS. */
    val special: Boolean
        get() = when (type) {
            KeyType.CHAR, KeyType.SPACE, KeyType.TEXT -> false
            else -> true
        }
}

class KbRow(val keys: List<Key>, val pad: Float = 0f)

enum class LayoutKind { LETTERS, SYMBOLS, MORE, NUMBER, PHONE }

class KbLayout(val kind: LayoutKind, val rows: List<KbRow>)

enum class FieldKind { TEXT, EMAIL, URL }

object Layouts {

    private val HU_POPUPS = mapOf(
        "a" to listOf("á", "ä", "à", "â", "æ", "ã", "å"),
        "e" to listOf("é", "ë", "è", "ê", "ē", "ę"),
        "i" to listOf("í", "ï", "ì", "î"),
        "o" to listOf("ö", "ó", "ő", "ô", "ò", "õ", "ø", "œ"),
        "u" to listOf("ü", "ú", "ű", "û", "ù"),
        "c" to listOf("č", "ç", "ć"),
        "s" to listOf("š", "ß", "ś"),
        "z" to listOf("ž", "ź", "ż"),
        "n" to listOf("ñ", "ń"),
        "y" to listOf("ý", "ÿ"),
        "l" to listOf("ł"),
        "r" to listOf("ř"),
    )

    private val EN_POPUPS = mapOf(
        "a" to listOf("à", "á", "â", "ä", "æ", "ã", "å", "ā"),
        "e" to listOf("è", "é", "ê", "ë", "ē", "ė", "ę"),
        "i" to listOf("î", "ï", "í", "ī", "į", "ì"),
        "o" to listOf("ô", "ö", "ò", "ó", "œ", "ø", "ō", "õ", "ő"),
        "u" to listOf("û", "ü", "ù", "ú", "ū", "ű"),
        "c" to listOf("ç", "ć", "č"),
        "s" to listOf("ß", "ś", "š"),
        "z" to listOf("ž", "ź", "ż"),
        "n" to listOf("ñ", "ń"),
        "y" to listOf("ÿ"),
        "l" to listOf("ł"),
    )

    private val SYMBOL_POPUPS = mapOf(
        "-" to listOf("–", "—", "•"),
        "/" to listOf("\\"),
        "\"" to listOf("„", "”", "“", "«", "»"),
        "'" to listOf("’", "‘", "`"),
        "?" to listOf("¿"),
        "!" to listOf("¡"),
        "." to listOf("…"),
        "&" to listOf("§"),
        "Ft" to listOf("€", "$", "£", "¥", "₿"),
        "$" to listOf("€", "£", "¥", "Ft", "₿"),
        "0" to listOf("°"),
        "%" to listOf("‰"),
        "1" to listOf("¹", "½", "⅓", "¼"),
        "2" to listOf("²", "⅔"),
        "3" to listOf("³", "¾"),
        "*" to listOf("×", "·"),
        "=" to listOf("≠", "≈", "±"),
        "<" to listOf("≤", "«"),
        ">" to listOf("≥", "»"),
    )

    private fun ch(s: String, hu: Boolean, w: Float = 1f): Key {
        val pops = if (s.length == 1 && s[0].isLetter()) (if (hu) HU_POPUPS[s] else EN_POPUPS[s]) else SYMBOL_POPUPS[s]
        return Key(KeyType.CHAR, s, s, w, pops ?: emptyList())
    }

    private fun row(chars: String, hu: Boolean, pad: Float = 0f) =
        KbRow(chars.split(" ").map { ch(it, hu) }, pad)

    /**
     * Bottom row. The space bar is always exactly in the middle: the keys on its left and on its
     * right take the same total width.
     */
    private fun bottomRow(hu: Boolean, field: FieldKind, toggle: Key, period: Boolean = false): KbRow {
        fun tg(w: Float) = Key(toggle.type, toggle.label, toggle.output, w)
        fun space(w: Float) = Key(KeyType.SPACE, "", " ", w)
        fun enter(w: Float) = Key(KeyType.ENTER, "", "\n", w)
        val periodKey = Key(KeyType.CHAR, ".", ".", 1f, listOf(",", "?", "!", "…", ":", ";"))
        val keys = when (field) {
            FieldKind.EMAIL -> listOf(tg(1.25f), Key(KeyType.CHAR, "@", "@", 1.25f), space(5f), dotKey(hu, false), enter(1.5f))
            FieldKind.URL -> listOf(tg(1.25f), Key(KeyType.CHAR, "/", "/", 1.25f), space(5f), dotKey(hu, true), enter(1.5f))
            FieldKind.TEXT -> if (period) {
                listOf(tg(1.25f), Key(KeyType.EMOJI, "", "", 1.25f), space(5f), periodKey, enter(1.5f))
            } else {
                listOf(tg(1.1f), Key(KeyType.EMOJI, "", "", 1.1f), space(5.6f), enter(2.2f))
            }
        }
        return KbRow(keys)
    }

    private fun dotKey(hu: Boolean, url: Boolean): Key {
        val pops = if (url) {
            if (hu) listOf(".hu", ".com", ".eu", ".org", ".net") else listOf(".com", ".org", ".net", ".edu", ".hu")
        } else listOf(".hu", ".com", "…")
        return Key(KeyType.CHAR, ".", ".", 1f, pops)
    }

    fun letters(hu: Boolean, accentRow: Boolean, field: FieldKind, numberRow: Boolean = false, period: Boolean = false): KbLayout {
        val rows = ArrayList<KbRow>()
        if (numberRow) rows += row("1 2 3 4 5 6 7 8 9 0", hu)
        if (hu && accentRow) rows += row("á é í ó ö ő ú ü ű", true, 0.5f)
        rows += row(if (hu) "q w e r t z u i o p" else "q w e r t y u i o p", hu)
        rows += row("a s d f g h j k l", hu, 0.5f)
        val third = ArrayList<Key>()
        third += Key(KeyType.SHIFT, "", "", 1.5f)
        for (c in (if (hu) "y x c v b n m" else "z x c v b n m").split(" ")) third += ch(c, hu)
        third += Key(KeyType.DELETE, "", "", 1.5f)
        rows += KbRow(third)
        rows += bottomRow(hu, field, Key(KeyType.TO_SYMBOLS, "123", "", 1.25f), period)
        return KbLayout(LayoutKind.LETTERS, rows)
    }

    /** Rows of suggested emoji that fill the space the symbol pages have left over. */
    private fun emojiRows(count: Int, emoji: List<String>): List<KbRow> {
        val out = ArrayList<KbRow>()
        for (r in 0 until count) {
            val slice = emoji.drop(r * 10).take(10)
            if (slice.isEmpty()) break
            out += KbRow(slice.map { Key(KeyType.TEXT, it, it, 1f) }, (10 - slice.size) / 2f)
        }
        return out
    }

    fun symbols(hu: Boolean, field: FieldKind, period: Boolean = false, extraRows: Int = 0, emoji: List<String> = emptyList(), numbersOnTop: Boolean = false): KbLayout {
        val rows = ArrayList<KbRow>()
        // with the number row on the letters, the digits stay exactly where they were
        if (!numbersOnTop) rows += emojiRows(extraRows, emoji)
        rows += row("1 2 3 4 5 6 7 8 9 0", hu)
        if (numbersOnTop) rows += emojiRows(extraRows, emoji)
        rows += row(if (hu) "- / : ; ( ) Ft & @ \"" else "- / : ; ( ) $ & @ \"", hu)
        val third = ArrayList<Key>()
        third += Key(KeyType.TO_MORE, "#+=", "", 1.5f)
        for (c in ". , ? ! '".split(" ")) third += ch(c, hu, 1.4f)
        third += Key(KeyType.DELETE, "", "", 1.5f)
        rows += KbRow(third)
        rows += bottomRow(hu, field, Key(KeyType.TO_LETTERS, "ABC", "", 1.25f), period)
        return KbLayout(LayoutKind.SYMBOLS, rows)
    }

    fun more(hu: Boolean, field: FieldKind, period: Boolean = false, extraRows: Int = 0, emoji: List<String> = emptyList(), numbersOnTop: Boolean = false): KbLayout {
        val rows = ArrayList<KbRow>()
        if (!numbersOnTop) rows += emojiRows(extraRows, emoji)
        rows += row("[ ] { } # % ^ * + =", hu)
        if (numbersOnTop) rows += emojiRows(extraRows, emoji)
        rows += row(if (hu) "_ \\ | ~ < > € $ £ •" else "_ \\ | ~ < > € £ ¥ •", hu)
        val third = ArrayList<Key>()
        third += Key(KeyType.TO_SYMBOLS, "123", "", 1.5f)
        for (c in ". , ? ! '".split(" ")) third += ch(c, hu, 1.4f)
        third += Key(KeyType.DELETE, "", "", 1.5f)
        rows += KbRow(third)
        rows += bottomRow(hu, field, Key(KeyType.TO_LETTERS, "ABC", "", 1.25f), period)
        return KbLayout(LayoutKind.MORE, rows)
    }

    fun number(hu: Boolean, signed: Boolean, decimal: Boolean): KbLayout {
        fun k(s: String) = Key(KeyType.CHAR, s, s, 2.5f)
        val comma = if (hu) "," else "."
        return KbLayout(
            LayoutKind.NUMBER, listOf(
                KbRow(listOf(k("1"), k("2"), k("3"), if (signed) k("-") else k("+"))),
                KbRow(listOf(k("4"), k("5"), k("6"), if (signed) k("+") else k("-"))),
                KbRow(listOf(k("7"), k("8"), k("9"), Key(KeyType.DELETE, "", "", 2.5f))),
                KbRow(listOf(if (decimal) k(comma) else k(" "), k("0"), k(if (decimal && hu) "." else ","), Key(KeyType.ENTER, "", "\n", 2.5f))),
            )
        )
    }

    fun phone(): KbLayout {
        fun k(s: String) = Key(KeyType.CHAR, s, s, 2.5f)
        return KbLayout(
            LayoutKind.PHONE, listOf(
                KbRow(listOf(k("1"), k("2"), k("3"), k("+"))),
                KbRow(listOf(k("4"), k("5"), k("6"), k(" "))),
                KbRow(listOf(k("7"), k("8"), k("9"), Key(KeyType.DELETE, "", "", 2.5f))),
                KbRow(listOf(k("*"), k("0"), k("#"), Key(KeyType.ENTER, "", "\n", 2.5f))),
            )
        )
    }
}
