package com.example.glasskeyboard

/** Emoji offered in the suggestion bar for some words (like iOS predictive emoji). */
object EmojiHints {
    private val HU = mapOf(
        "szeretlek" to "❤️", "szeretem" to "❤️", "szív" to "❤️", "szerelem" to "😍", "puszi" to "😘", "pusz" to "😘",
        "haha" to "😂", "hahaha" to "😂", "nevetek" to "😂", "vicces" to "😂", "mosoly" to "😊", "boldog" to "😊",
        "szomorú" to "😢", "sírok" to "😭", "dühös" to "😠", "mérges" to "😠", "fáradt" to "😴", "álmos" to "😴",
        "aludni" to "😴", "jóéjt" to "🌙", "éjszakát" to "🌙", "reggelt" to "☀️", "nap" to "☀️", "eső" to "🌧️",
        "hó" to "❄️", "hideg" to "🥶", "meleg" to "🥵", "oké" to "👌", "ok" to "👌", "rendben" to "👍", "jó" to "👍",
        "szuper" to "👍", "király" to "👑", "köszi" to "🙏", "köszönöm" to "🙏", "kösz" to "🙏", "kérlek" to "🙏",
        "buli" to "🎉", "szülinap" to "🎂", "szülinapot" to "🎂", "születésnapot" to "🎂", "torta" to "🎂",
        "ajándék" to "🎁", "karácsony" to "🎄", "pizza" to "🍕", "kávé" to "☕", "sör" to "🍺", "bor" to "🍷",
        "étel" to "🍽️", "kaja" to "🍽️", "éhes" to "😋", "finom" to "😋", "foci" to "⚽", "zene" to "🎵",
        "autó" to "🚗", "kocsi" to "🚗", "vonat" to "🚆", "busz" to "🚌", "repülő" to "✈️", "nyaralás" to "🏖️",
        "tenger" to "🌊", "ház" to "🏠", "itthon" to "🏠", "otthon" to "🏠", "munka" to "💼", "pénz" to "💰",
        "telefon" to "📱", "kutya" to "🐶", "macska" to "🐱", "cica" to "🐱", "virág" to "🌸", "tűz" to "🔥",
        "csúcs" to "🔥", "igen" to "✅", "nem" to "❌", "hát" to "🤷", "gondolkodom" to "🤔", "hmm" to "🤔",
        "szia" to "👋", "helló" to "👋", "hello" to "👋", "csá" to "👋", "gratulálok" to "👏", "baba" to "👶",
        "film" to "🎬", "könyv" to "📚", "iskola" to "🏫", "orvos" to "👨‍⚕️", "beteg" to "🤒", "idő" to "⏰",
        "siess" to "🏃", "futás" to "🏃", "edzés" to "💪", "erős" to "💪", "sajnálom" to "😔", "bocsi" to "😅",
        "bocs" to "😅", "wow" to "😮", "hűha" to "😮", "csók" to "💋", "ölelés" to "🤗", "ölellek" to "🤗",
    )

    private val EN = mapOf(
        "love" to "❤️", "heart" to "❤️", "kiss" to "😘", "haha" to "😂", "lol" to "😂", "funny" to "😂",
        "happy" to "😊", "smile" to "😊", "sad" to "😢", "cry" to "😭", "angry" to "😠", "mad" to "😠",
        "tired" to "😴", "sleep" to "😴", "night" to "🌙", "morning" to "☀️", "sun" to "☀️", "rain" to "🌧️",
        "snow" to "❄️", "cold" to "🥶", "hot" to "🥵", "ok" to "👌", "okay" to "👌", "good" to "👍",
        "great" to "👍", "thanks" to "🙏", "thank" to "🙏", "please" to "🙏", "party" to "🎉", "birthday" to "🎂",
        "cake" to "🎂", "gift" to "🎁", "christmas" to "🎄", "pizza" to "🍕", "coffee" to "☕", "beer" to "🍺",
        "wine" to "🍷", "food" to "🍽️", "hungry" to "😋", "yummy" to "😋", "football" to "⚽", "soccer" to "⚽",
        "music" to "🎵", "car" to "🚗", "train" to "🚆", "bus" to "🚌", "plane" to "✈️", "flight" to "✈️",
        "holiday" to "🏖️", "beach" to "🏖️", "home" to "🏠", "house" to "🏠", "work" to "💼", "money" to "💰",
        "phone" to "📱", "dog" to "🐶", "cat" to "🐱", "flower" to "🌸", "fire" to "🔥", "lit" to "🔥",
        "yes" to "✅", "no" to "❌", "think" to "🤔", "hmm" to "🤔", "hi" to "👋", "hello" to "👋", "hey" to "👋",
        "bye" to "👋", "congrats" to "👏", "baby" to "👶", "movie" to "🎬", "book" to "📚", "school" to "🏫",
        "sick" to "🤒", "time" to "⏰", "run" to "🏃", "gym" to "💪", "strong" to "💪", "sorry" to "😔",
        "wow" to "😮", "hug" to "🤗", "cool" to "😎", "king" to "👑", "star" to "⭐", "rocket" to "🚀",
    )

    fun forWord(word: String, hungarian: Boolean): String? {
        val w = word.lowercase()
        return (if (hungarian) HU[w] else EN[w])
    }
}
