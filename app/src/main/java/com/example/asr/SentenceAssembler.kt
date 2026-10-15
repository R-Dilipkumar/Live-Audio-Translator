package com.example.asr

/**
 * Joins the recognizer's pieces into whole sentences before they are translated.
 *
 * The recognizer ends a piece at every short pause, so one spoken sentence often arrives as
 * two or three pieces ("I think that" / "we should go"). Translating each piece on its own
 * gives broken translations, worst for Japanese, where the verb comes last. Pieces are kept in
 * a buffer until the sentence looks complete:
 * - it ends with sentence punctuation (。？！.?!), or
 * - it ends with a typical Japanese sentence ending (です, ました, よね, ...), or
 * - the speaker stays quiet for [pauseMs] after a piece ([continuationPauseMs] if the piece
 *   clearly continues, e.g. ends in けど / て / "and" / "the"), or
 * - it gets long ([maxPieces] pieces or [maxLength] characters), so subtitles never wait too long.
 *
 * While a sentence is waiting, its text so far is still shown as a live draft.
 * The whole sentence keeps the utterance id of its first piece, so the display treats it as
 * one growing line. Not thread-safe: the engine calls it while holding its recognizer lock.
 */
class SentenceAssembler(
    // Waits after the recognizer's own end-of-piece (which already needs ~0.8 s of silence).
    // Kept short: longer waits merged separate lines, e.g. a misheard "ここで" with the next
    // "ありがとうございます" became "Thank you here".
    private val pauseMs: Long = 400L,
    private val continuationPauseMs: Long = 900L,
    private val maxPieces: Int = 4,
    // Weighted: a CJK character counts as 2
    private val maxLength: Int = 160
) {
    data class Sentence(val text: String, val utteranceId: Long, val isFinal: Boolean)

    /** When false, every piece is passed on as a finished sentence (the old behaviour). */
    @Volatile var isEnabled: Boolean = true

    private val pieces = mutableListOf<String>()
    private var sentenceId = 0L
    private var lastPieceAtMs = 0L

    /** Text of the waiting pieces, or "" if none. */
    fun pendingText(): String = joinAll(pieces)

    /** A finished piece from the recognizer. Returns the sentence so far, final if complete. */
    fun onPiece(text: String, utteranceId: Long, nowMs: Long): Sentence {
        if (!isEnabled && pieces.isEmpty()) return Sentence(text, utteranceId, isFinal = true)
        if (pieces.isEmpty()) sentenceId = utteranceId
        pieces.add(text)
        lastPieceAtMs = nowMs
        val joined = joinAll(pieces)
        val complete = !isEnabled ||
            looksComplete(joined) ||
            pieces.size >= maxPieces ||
            weightedLength(joined) >= maxLength
        return if (complete) takeSentence() else Sentence(joined, sentenceId, isFinal = false)
    }

    /** The recognizer's live text for the piece in progress, shown after the waiting pieces. */
    fun onDraft(text: String, utteranceId: Long): Sentence {
        if (pieces.isEmpty()) return Sentence(text, utteranceId, isFinal = false)
        return Sentence(SpeechRecognizerEngine.joinFragments(joinAll(pieces), text), sentenceId, isFinal = false)
    }

    /**
     * Call regularly while audio flows. Ends the waiting sentence once the speaker has been
     * quiet long enough. [isSpeaking] is true while the recognizer has live text.
     */
    fun onTick(nowMs: Long, isSpeaking: Boolean): Sentence? {
        if (pieces.isEmpty() || isSpeaking) return null
        val wait = if (looksUnfinished(pieces.last())) continuationPauseMs else pauseMs
        return if (nowMs - lastPieceAtMs >= wait) takeSentence() else null
    }

    /** Ends the waiting sentence now (capture stopped). Returns null if nothing is waiting. */
    fun flush(): Sentence? = if (pieces.isEmpty()) null else takeSentence()

    fun clear() {
        pieces.clear()
    }

    private fun takeSentence(): Sentence {
        val sentence = Sentence(joinAll(pieces), sentenceId, isFinal = true)
        pieces.clear()
        return sentence
    }

    private fun joinAll(parts: List<String>): String =
        parts.fold("") { acc, part -> SpeechRecognizerEngine.joinFragments(acc, part.trim()) }

    companion object {
        private val SENTENCE_PUNCTUATION = setOf('.', '!', '?', '。', '！', '？', '…')
        private val CLOSING_MARKS = charArrayOf('"', '\'', '」', '』', ')', '）')

        // Common ways a spoken Japanese sentence ends (polite and casual forms, final particles).
        // Left out on purpose: の, な and さ, which also end mid-sentence phrases (私の, 大きな).
        private val JAPANESE_ENDINGS = listOf(
            "です", "ます", "ました", "でした", "ません", "でしょう", "ましょう", "ください",
            "だ", "だった", "よ", "ね", "よね", "か", "かな", "わ", "ぞ", "ぜ"
        )

        // Endings that mean the sentence goes on: te-form, conjunctions and case particles
        private val JAPANESE_CONTINUATIONS = listOf(
            "て", "で", "けど", "けれど", "けれども", "から", "ので", "のに", "し", "ば", "たら",
            "ながら", "ても", "でも", "って", "が", "と", "に", "を", "は", "も", "へ", "や"
        )

        // English words a sentence rarely ends on
        private val ENGLISH_CONTINUATIONS = setOf(
            "and", "but", "or", "because", "if", "the", "a", "an",
            "to", "of", "for", "with", "from", "my", "your"
        )

        private val SPLIT_MARKS = setOf('.', '!', '?', '。', '！', '？', '．')
        private val CJK_SPLIT_MARKS = setOf('。', '！', '？', '．')
        // Words that end in a full stop without ending the sentence
        private val ABBREVIATIONS = setOf(
            "mr", "mrs", "ms", "dr", "st", "jr", "sr", "prof", "vs", "etc", "mt", "no", "fig", "approx", "e.g", "i.e"
        )

        /**
         * Splits [text] after each sentence end (. ! ? 。 ！ ？), so every sentence can be its
         * own subtitle. The last part may be unfinished. Not split: "...", "Mr. Smith", "J. K.",
         * "3.5", "e.g. this", or a full stop followed by a lowercase word.
         */
        fun splitSentences(text: String): List<String> {
            val parts = mutableListOf<String>()
            var start = 0
            var i = 0
            while (i < text.length) {
                val mark = text[i]
                if (mark !in SPLIT_MARKS) {
                    i++
                    continue
                }
                // A run like "?!" goes together, with any closing quote or bracket after it
                var end = i + 1
                while (end < text.length && text[end] in SPLIT_MARKS) end++
                val isEllipsis = text.substring(i, end).count { it == '.' } >= 2
                while (end < text.length && text[end] in CLOSING_MARKS) end++
                val next = text.substring(end).firstOrNull { !it.isWhitespace() }
                val splits = when {
                    next == null -> false // nothing follows
                    isEllipsis -> false // "I am..." trails off into what follows
                    mark in CJK_SPLIT_MARKS -> true
                    !text[end].isWhitespace() -> false // "3.5", "U.S."
                    mark == '.' && endsWithAbbreviation(text.substring(start, i)) -> false
                    else -> !next.isLowerCase()
                }
                if (splits) {
                    parts += text.substring(start, end).trim()
                    start = end
                }
                i = end
            }
            text.substring(start).trim().takeIf { it.isNotEmpty() }?.let { parts += it }
            // A part with no words (a stray "!") stays with the part before it
            return parts.fold(mutableListOf()) { merged, part ->
                if (merged.isNotEmpty() && part.none { it.isLetterOrDigit() }) {
                    merged[merged.lastIndex] = merged.last() + part
                } else {
                    merged += part
                }
                merged
            }
        }

        private fun endsWithAbbreviation(textBeforeMark: String): Boolean {
            val word = textBeforeMark.trimEnd().substringAfterLast(' ').trimStart('(', '"', '\'').lowercase()
            return (word.length == 1 && word[0].isLetter()) || word in ABBREVIATIONS
        }

        fun looksComplete(text: String): Boolean {
            val core = text.trim().trimEnd(*CLOSING_MARKS)
            if (core.isEmpty()) return false
            if (core.last() in SENTENCE_PUNCTUATION) return true
            if (hasKana(core) && !looksUnfinished(core)) {
                return JAPANESE_ENDINGS.any { core.endsWith(it) }
            }
            return false
        }

        fun looksUnfinished(text: String): Boolean {
            val core = text.trim().trimEnd(*CLOSING_MARKS)
            if (core.isEmpty()) return false
            val last = core.last()
            if (last == ',' || last == '、' || last == '，') return true
            if (hasKana(core)) return JAPANESE_CONTINUATIONS.any { core.endsWith(it) }
            val lastWord = core.substringAfterLast(' ').lowercase()
            return lastWord in ENGLISH_CONTINUATIONS
        }

        private fun hasKana(text: String): Boolean = text.any {
            val script = Character.UnicodeScript.of(it.code)
            script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA
        }

        private fun weightedLength(text: String): Int = text.sumOf { c -> if (c.code >= 0x2E80) 2 else 1 }
    }
}
