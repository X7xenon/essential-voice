package com.ishaan.essentialvoice.whisper

/**
 * Intelligent on-device Indic (Devanagari) and Perso-Arabic (Urdu) to Hinglish transliterator.
 *
 * When the user speaks English, the recognizer emits English (Latin script) which passes untouched.
 * When the user speaks Hindi or conversational Hindustani, this automatically converts any
 * Devanagari or Perso-Arabic script into natural, romanized conversational Hinglish.
 */
object HinglishConverter {

    private val independentVowels = mapOf(
        0x0905 to "a",   // अ
        0x0906 to "aa",  // आ
        0x0907 to "i",   // इ
        0x0908 to "ee",  // ई
        0x0909 to "u",   // उ
        0x090A to "oo",  // ऊ
        0x090B to "ri",  // ऋ
        0x090F to "e",   // ए
        0x0910 to "ai",  // ऐ
        0x0913 to "o",   // ओ
        0x0914 to "au",  // औ
    )

    private val consonants = mapOf(
        0x0915 to "k",   0x0916 to "kh",  0x0917 to "g",   0x0918 to "gh",  0x0919 to "ng",
        0x091A to "ch",  0x091B to "chh", 0x091C to "j",   0x091D to "jh",  0x091E to "ny",
        0x091F to "t",   0x0920 to "th",  0x0921 to "d",   0x0922 to "dh",  0x0923 to "n",
        0x0924 to "t",   0x0925 to "th",  0x0926 to "d",   0x0927 to "dh",  0x0928 to "n",
        0x092A to "p",   0x092B to "f",   0x092C to "b",   0x092D to "bh",  0x092E to "m",
        0x092F to "y",   0x0930 to "r",   0x0932 to "l",   0x0935 to "v",   0x0936 to "sh",
        0x0937 to "sh",  0x0938 to "s",   0x0939 to "h",
        0x0958 to "q",   0x0959 to "kh",  0x095A to "gh",  0x095B to "z",   0x095C to "d",
        0x095D to "rh",  0x095E to "f",   0x095F to "y",
    )

    private val matras = mapOf(
        0x093E to "a",   // ा
        0x093F to "i",   // ि
        0x0940 to "i",   // ी
        0x0941 to "u",   // ु
        0x0942 to "oo",  // ू
        0x0943 to "ri",  // ृ
        0x0947 to "e",   // े
        0x0948 to "ai",  // ै
        0x094B to "o",   // ो
        0x094C to "au",  // ौ
    )

    private const val VIRAMA = 0x094D       // ्
    private const val ANUSVARA = 0x0902     // ं
    private const val CHANDRABINDU = 0x0901 // ँ
    private const val VISARGA = 0x0903      // ः
    private const val NUKTA = 0x093C        // ़
    private const val DANDA = 0x0964        // ।

    private val urduWordMap = mapOf(
        "کیا" to "kya", "حال" to "haal", "چال" to "chaal", "بھائی" to "bhai",
        "ہا" to "haan", "ہاں" to "haan", "ہے" to "hai", "ہیں" to "hain",
        "آپ" to "aap", "کیسے" to "kaise", "کیسی" to "kaisi", "کیسا" to "kaisa",
        "ہو" to "ho", "میں" to "main", "ٹھیک" to "theek", "ہوں" to "hoon",
        "کل" to "kal", "ملتے" to "milte", "کام" to "kaam", "نہیں" to "nahi",
        "کرو" to "karo", "کر" to "kar", "رہا" to "raha", "رہے" to "rahe",
        "رہی" to "rahi", "بات" to "baat", "شکریہ" to "shukriya", "دوست" to "dost",
        "آج" to "aaj", "ابھی" to "abhi", "کیوں" to "kyun", "کب" to "kab",
        "کہاں" to "kahan", "کون" to "kaun", "کتنا" to "kitna", "کتنے" to "kitne", "کتنی" to "kitni",
        "اچھا" to "achha", "بہت" to "bohot", "تھوڑا" to "thoda", "چلو" to "chalo",
        "دیکھو" to "dekho", "سنو" to "suno", "بول" to "bol", "لکھو" to "likho",
        "پڑھو" to "padho", "فون" to "phone", "کرنا" to "karna", "کرتے" to "karte", "کرتی" to "karti",
        "کرنے" to "karne", "کرنی" to "karni", "کریں" to "karein", "کرے" to "kare",
        "وہ" to "woh", "یہ" to "yeh", "اور" to "aur", "تو" to "toh", "اگر" to "agar",
        "لیکن" to "lekin", "پر" to "par", "سے" to "se", "کو" to "ko", "کا" to "ka",
        "کی" to "ki", "کے" to "ke", "تھا" to "tha", "تھی" to "thi", "تھے" to "the",
        "مجھ" to "mujh", "مجھے" to "mujhe", "تجھے" to "tujhe", "ہم" to "hum", "ہمیں" to "humein",
        "تم" to "tum", "تمہیں" to "tumhein", "میرا" to "mera", "میری" to "meri", "میرے" to "mere",
        "ہمارا" to "hamara", "ہماری" to "hamari", "ہمارے" to "hamare",
        "تمہارا" to "tumhara", "تمہاری" to "tumhari", "تمہارے" to "tumhare",
        "اپنا" to "apna", "اپنی" to "apni", "اپنے" to "apne",
        "اس" to "iss", "اسے" to "isse", "اسکا" to "iska", "اسکی" to "iski", "اسکے" to "iske",
        "ان" to "un", "انہیں" to "unhein", "انکا" to "unka", "انکی" to "unki", "انکے" to "unke",
        "سب" to "sab", "سبھی" to "sabhi", "کچھ" to "kuch", "کوئی" to "koi",
        "گا" to "ga", "گی" to "gi", "گے" to "ge",
        "ساتھ" to "saath", "پاس" to "paas", "پہلے" to "pehle", "بعد" to "baad",
        "صرف" to "sirf", "ضرور" to "zaroor", "ضرورت" to "zaroorat",
        "صحیح" to "sahi", "غلط" to "galat", "شاید" to "shayad",
        "کیونکہ" to "kyunki", "اس لئے" to "is liye", "اسلیئے" to "isliye",
        "میٹنگ" to "meeting", "کال" to "call", "ٹیسٹ" to "test", "ٹائم" to "time",
        "صبح" to "subah", "شام" to "shaam", "رات" to "raat", "دن" to "din",
        "پرسوں" to "parson", "بجے" to "baje"
    )

    private val urduCharMap = mapOf(
        0x0622 to "aa", 0x0627 to "a", 0x0628 to "b", 0x067E to "p", 0x062A to "t",
        0x0679 to "t", 0x062B to "s", 0x062C to "j", 0x0686 to "ch", 0x062D to "h",
        0x062E to "kh", 0x062F to "d", 0x0688 to "d", 0x0630 to "z", 0x0631 to "r",
        0x0691 to "r", 0x0632 to "z", 0x0698 to "zh", 0x0633 to "s", 0x0634 to "sh",
        0x0635 to "s", 0x0636 to "z", 0x0637 to "t", 0x0638 to "z", 0x0639 to "a",
        0x063A to "gh", 0x0641 to "f", 0x0642 to "q", 0x06A9 to "k", 0x0643 to "k",
        0x06AF to "g", 0x0644 to "l", 0x0645 to "m", 0x0646 to "n", 0x06BA to "n",
        0x0648 to "o", 0x06C1 to "h", 0x06BE to "h", 0x06C2 to "h", 0x0629 to "h",
        0x0647 to "h", 0x06CC to "i", 0x064A to "i", 0x0649 to "i", 0x06D2 to "e",
        0x0626 to "i", 0x0623 to "a", 0x0625 to "a", 0x0624 to "o", 0x0621 to "",
        0x064E to "a", 0x0650 to "i", 0x064F to "u",
        0x064B to "n", 0x064C to "n", 0x064D to "n",
        0x0651 to "", 0x0652 to "", 0x0670 to "a", 0x0640 to "",
        0x061F to "?", 0x060C to ",", 0x06D4 to "."
    )

    fun hasDevanagari(text: String): Boolean =
        text.any { it in '\u0900'..'\u097F' }

    fun hasUrdu(text: String): Boolean =
        text.any { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' || it in '\uFB50'..'\uFDFF' || it in '\uFE70'..'\uFEFF' }

    fun process(text: String): String {
        if (text.isBlank()) return text
        if (!hasUrdu(text) && !hasDevanagari(text)) return text
        var result = text
        if (hasUrdu(result)) {
            result = processUrdu(result)
        }
        if (hasDevanagari(result)) {
            result = processDevanagari(result)
        }
        return polishHinglish(result)
    }

    private fun normalizeUrduChars(text: String): String {
        val sb = java.lang.StringBuilder(text.length)
        for (ch in text) {
            when (ch.code) {
                0x0643 -> sb.append('\u06A9') // Arabic Kaf -> Urdu Kaf
                0x064A, 0x0649 -> sb.append('\u06CC') // Arabic Yeh -> Urdu Yeh
                0x0647, 0x0629 -> sb.append('\u06C1') // Arabic Heh -> Urdu Heh
                0x064B, 0x064C, 0x064D, 0x064E, 0x064F, 0x0650, 0x0651, 0x0652, 0x0670, 0x0640,
                0x200C, 0x200D -> Unit // strip Harakaat, Tashkeel, Tatweel, ZWNJ
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun processUrdu(text: String): String {
        var str = normalizeUrduChars(text)
        // First map complete common words
        for ((uWord, hWord) in urduWordMap) {
            str = str.replace(uWord, hWord)
        }
        // Fallback for remaining characters
        val sb = StringBuilder()
        for (ch in str) {
            val code = ch.code
            val mapped = urduCharMap[code]
            if (mapped != null) {
                sb.append(mapped)
            } else if (code in 0x0600..0x06FF || code in 0x0750..0x077F || code in 0xFB50..0xFDFF || code in 0xFE70..0xFEFF) {
                // Drop any unmapped Perso-Arabic code point to guarantee clean output
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun processDevanagari(text: String): String {
        val sb = StringBuilder()
        val chars = text.map { it.code }
        val n = chars.size
        var i = 0

        while (i < n) {
            val c = chars[i]

            if (independentVowels.containsKey(c)) {
                val vow = independentVowels[c] ?: ""
                sb.append(if (c == 0x0908 && sb.endsWith("a")) "i" else vow)
                i++
            } else if (consonants.containsKey(c)) {
                val base = consonants[c] ?: ""
                if (i + 1 < n && chars[i + 1] == NUKTA) {
                    i++
                }

                if (i + 1 < n) {
                    val next = chars[i + 1]
                    when {
                        next == VIRAMA -> {
                            sb.append(base)
                            i += 2
                        }
                        matras.containsKey(next) -> {
                            val matraStr = matras[next] ?: ""
                            sb.append(base).append(matraStr)
                            i += 2
                        }
                        next == ANUSVARA || next == CHANDRABINDU -> {
                            sb.append(base).append("an")
                            i += 2
                        }
                        next == VISARGA -> {
                            sb.append(base).append("ah")
                            i += 2
                        }
                        consonants.containsKey(next) || independentVowels.containsKey(next) -> {
                            sb.append(base).append("a")
                            i++
                        }
                        else -> {
                            sb.append(base)
                            i++
                        }
                    }
                } else {
                    sb.append(base)
                    i++
                }
            } else if (matras.containsKey(c)) {
                sb.append(matras[c])
                i++
            } else if (c == ANUSVARA || c == CHANDRABINDU) {
                sb.append("n")
                i++
            } else if (c == VIRAMA) {
                i++
            } else if (c == DANDA) {
                sb.append(".")
                i++
            } else if (c in 0x0900..0x097F) {
                // Ignore any unmapped Devanagari sign
                i++
            } else {
                sb.append(c.toChar())
                i++
            }
        }

        return sb.toString()
    }

    private val polishRules: List<Pair<Regex, String>> = listOf(
        "\\bkarana\\b" to "karna",
        "\\bkarane\\b" to "karne",
        "\\bkarani\\b" to "karni",
        "\\bkarate\\b" to "karte",
        "\\bbolana\\b" to "bolna",
        "\\bbolane\\b" to "bolne",
        "\\bbolani\\b" to "bolni",
        "\\bbolate\\b" to "bolte",
        "\\bdekhana\\b" to "dekhna",
        "\\bdekhane\\b" to "dekhne",
        "\\bdekhani\\b" to "dekhni",
        "\\bdekhate\\b" to "dekhte",
        "\\bsamajhana\\b" to "samajhna",
        "\\bsamajhane\\b" to "samajhne",
        "\\bapana\\b" to "apna",
        "\\bapane\\b" to "apne",
        "\\bapani\\b" to "apni",
        "\\bitana\\b" to "itna",
        "\\bitane\\b" to "itne",
        "\\bitani\\b" to "itni",
        "\\butana\\b" to "utna",
        "\\butane\\b" to "utne",
        "\\butani\\b" to "utni",
        "\\bkitana\\b" to "kitna",
        "\\bkitane\\b" to "kitne",
        "\\bkitani\\b" to "kitni",
        "\\bmilate\\b" to "milte",
        "\\bsunate\\b" to "sunte",
        "\\bjanate\\b" to "jaante",
        "\\bmanate\\b" to "maante",
        "\\brahata\\b" to "rehta",
        "\\brahate\\b" to "rehte",
        "\\brahati\\b" to "rehti",
        "\\bhal\\b" to "haal",
        "\\bkam\\b" to "kaam",
        "\\bkhatm\\b" to "khatam",
        "\\bbhaee\\b" to "bhai",
        "\\byeh\\b" to "yeh",
        "\\bwoh\\b" to "woh",
        "\\bvoh\\b" to "woh",
        "\\bnahin\\b" to "nahi",
        "\\bkudarat\\b" to "kudrat",
        "\\bzindagee\\b" to "zindagi",
        "\\bdostee\\b" to "dosti",
        "\\bkhushee\\b" to "khushi",
        "\\bgadabad\\b" to "gadbad",
    ).map { (pattern, repl) -> pattern.toRegex(RegexOption.IGNORE_CASE) to repl }

    private val strayScriptRegex = "[\\u0600-\\u06FF\\u0750-\\u077F\\uFB50-\\uFDFF\\uFE70-\\uFEFF\\u0900-\\u097F]".toRegex()
    private val multiSpaceRegex = "\\s+".toRegex()

    private fun polishHinglish(raw: String): String {
        var text = raw
        for ((regex, replacement) in polishRules) {
            text = text.replace(regex, replacement)
        }
        text = text.replace(strayScriptRegex, "")
        return text.replace(multiSpaceRegex, " ").trim()
    }
}
