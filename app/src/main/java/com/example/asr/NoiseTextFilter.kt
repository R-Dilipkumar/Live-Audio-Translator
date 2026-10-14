package com.example.asr

/**
 * Drops recognized text that is most likely music or background noise rather than speech.
 *
 * The multilingual model sometimes turns music, laughter or noise into short scraps of text,
 * often Chinese characters, even while someone is speaking English. Those scraps get
 * translated as Chinese (or fail with "pack missing") and push real subtitles off screen.
 * This filter removes:
 * - filler-only text: 嗯, 啊, えー, "um", "hmm", ...
 * - repeated syllables, typical for singing: 啦啦啦啦, "la la la la"
 * - Chinese/Japanese characters mixed into otherwise English text
 * - a lone letter or digit in another script ("N", "H", ๐ during Japanese speech)
 * - short pieces in a different non-Latin script than the speech so far (a stray 嗯嗯好
 *   between English sentences). Short Latin pieces are kept, because English words are
 *   common inside Japanese or Chinese speech.
 *
 * The "speech so far" is learned from accepted finished pieces. Not thread-safe: the engine
 * calls it while holding its recognizer lock.
 */
class NoiseTextFilter(
    private val historySize: Int = 6,
    private val minHistory: Int = 3,
    // Pieces up to this many weighted letters (CJK counts 2) can be dropped as out of place
    private val maxOutlierLength: Int = 6
) {
    enum class Script { LATIN, CJK, HANGUL, CYRILLIC, ARABIC, THAI, OTHER }

    @Volatile var isEnabled: Boolean = true

    private val recentScripts = ArrayDeque<Script>()

    /**
     * Returns the text to use (possibly cleaned), or null to drop it. Pass [learn] = true for
     * finished pieces so they shape what counts as the current speech; drafts should not.
     */
    fun filter(text: String, learn: Boolean): String? {
        val trimmed = text.trim()
        if (!isEnabled) return trimmed.ifEmpty { null }
        if (trimmed.isEmpty() || isFillerOnly(trimmed) || isRepetitive(trimmed)) return null

        val dominant = dominantScript()
        // A lone character in another script ("N", "H" or a Thai digit during Japanese speech)
        // is a mishearing; glued to the next line it changed that line's meaning
        if (dominant != null && dominant != Script.LATIN &&
            trimmed.codePointCount(0, trimmed.length) == 1 && scriptOfChar(trimmed[0]) != dominant
        ) {
            return null
        }
        var cleaned = trimmed
        if (dominant == Script.LATIN) {
            cleaned = stripCjkFromLatin(cleaned) ?: return null
        }

        val script = scriptOf(cleaned)
        if (dominant != null && script != dominant &&
            script != Script.LATIN && script != Script.OTHER &&
            weightedLetters(cleaned) <= maxOutlierLength
        ) {
            return null
        }

        if (learn && script != Script.OTHER && weightedLetters(cleaned) >= 3) {
            recentScripts.addLast(script)
            while (recentScripts.size > historySize) recentScripts.removeFirst()
        }
        return cleaned
    }

    fun reset() {
        recentScripts.clear()
    }

    /** The script of most recent speech, or null if there isn't enough history or no clear majority. */
    fun dominantScript(): Script? {
        if (recentScripts.size < minHistory) return null
        val (script, count) = recentScripts.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return null
        return if (count * 3 >= recentScripts.size * 2) script else null
    }

    companion object {
        private const val CHINESE_FILLERS = "嗯啊哦呃唔哈嘿呀吧噢喔欸诶哎唉嘛呢了"
        // Japanese hesitation sounds (えー, あー); ん is left out so うん ("yeah") is kept
        private const val JAPANESE_FILLERS = "えあうおーっ"
        private val LATIN_FILLERS = setOf(
            "uh", "um", "umm", "hmm", "hm", "mm", "mmm", "ah", "oh", "eh", "er", "erm",
            "la", "na", "ha", "huh"
        )

        fun isFillerOnly(text: String): Boolean {
            val letters = text.filter { it.isLetterOrDigit() || it == 'ー' }
            if (letters.isEmpty()) return true
            if (letters.all { it in CHINESE_FILLERS || it in JAPANESE_FILLERS }) return true
            val words = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
            return words.isNotEmpty() && words.all { it in LATIN_FILLERS }
        }

        /** Four or more units (CJK characters or words) where one makes up at least 75%. */
        fun isRepetitive(text: String): Boolean {
            val units = mutableListOf<String>()
            val word = StringBuilder()
            for (c in text.lowercase()) {
                when {
                    isCjk(c) -> {
                        if (word.isNotEmpty()) { units.add(word.toString()); word.clear() }
                        units.add(c.toString())
                    }
                    c.isLetterOrDigit() -> word.append(c)
                    else -> if (word.isNotEmpty()) { units.add(word.toString()); word.clear() }
                }
            }
            if (word.isNotEmpty()) units.add(word.toString())
            if (units.size < 4) return false
            val top = units.groupingBy { it }.eachCount().maxOf { it.value }
            return top * 4 >= units.size * 3
        }

        fun scriptOf(text: String): Script {
            val counts = text.filter { it.isLetter() }
                .groupingBy { scriptOfChar(it) }
                .eachCount()
                .filterKeys { it != Script.OTHER }
            return counts.maxByOrNull { it.value }?.key ?: Script.OTHER
        }

        /** Removes Chinese/Japanese characters from text that is mostly Latin letters. */
        private fun stripCjkFromLatin(text: String): String? {
            val letters = text.filter { it.isLetter() }
            val cjk = letters.count { isCjk(it) }
            if (cjk == 0) return text
            val latin = letters.count { scriptOfChar(it) == Script.LATIN }
            if (latin * 5 < letters.length * 3) return text // not mostly Latin: leave it to the other rules
            val cleaned = text.filterNot { isCjk(it) }.replace(Regex("\\s+"), " ").trim()
            return cleaned.ifEmpty { null }
        }

        private fun weightedLetters(text: String): Int =
            text.sumOf { c -> if (!c.isLetter()) 0 else if (isCjk(c)) 2 else 1 }

        private fun isCjk(c: Char): Boolean = scriptOfChar(c) == Script.CJK

        private fun scriptOfChar(c: Char): Script = when (Character.UnicodeScript.of(c.code)) {
            Character.UnicodeScript.LATIN -> Script.LATIN
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA -> Script.CJK
            Character.UnicodeScript.HANGUL -> Script.HANGUL
            Character.UnicodeScript.CYRILLIC -> Script.CYRILLIC
            Character.UnicodeScript.ARABIC -> Script.ARABIC
            Character.UnicodeScript.THAI -> Script.THAI
            else -> Script.OTHER
        }
    }
}
