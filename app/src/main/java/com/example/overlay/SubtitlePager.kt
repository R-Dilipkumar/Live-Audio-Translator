package com.example.overlay

/**
 * Movie-style subtitle cards.
 *
 * Instead of scrolling the text line by line as words arrive, translated text is cut into
 * "cards" that fill the subtitle box. A card stays on screen until it has been readable for
 * a while, then the box clears and the next card starts filling it.
 *
 * - Each utterance (one recognized sentence) is queued in arrival order.
 * - An utterance that is still being spoken (draft) grows its last card in place. When it
 *   overflows the box, the next card is shown only after the current one's reading time.
 * - A new utterance always starts on a fresh card, so sentences don't run into each other.
 * - If speech outpaces reading, cards are shown for a shorter time, and very old cards are
 *   skipped so the subtitles never fall far behind the video.
 *
 * Pure logic with no Android dependencies; the overlay supplies the page layout through
 * [display]'s `paginate` function. Not thread-safe: call it from one thread (the UI thread).
 */
class SubtitlePager(
    private val minHoldMs: Long = 1_200L,
    private val maxHoldMs: Long = 4_000L,
    // Reading time per character: ~18 characters a second for Latin text,
    // CJK characters count as CJK_CHAR_WEIGHT characters.
    private val msPerChar: Long = 55L,
    // Shorter hold used when more than fastBacklogCards cards are waiting
    private val fastHoldMs: Long = 600L,
    private val fastBacklogCards: Int = 2,
    // Waiting cards beyond this are skipped
    private val maxBacklogCards: Int = 4
) {
    private class Utterance(val id: Long, var text: String, var isFinal: Boolean)

    // Utterances still to be shown, oldest first. The first one is on screen.
    private val queue = ArrayDeque<Utterance>()
    private var cardIndex = 0
    private var cardShownSince = 0L
    // When the next card first became available while the current one was on screen
    private var nextWaitingSince: Long? = null
    private var lastUtteranceId = Long.MIN_VALUE

    /** Adds or updates the text of an utterance. Drafts may be sent repeatedly as they grow. */
    fun submit(utteranceId: Long, text: String, isFinal: Boolean, nowMs: Long) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || utteranceId < lastUtteranceId) return

        val existing = queue.lastOrNull()?.takeIf { it.id == utteranceId }
        if (existing != null) {
            // A late draft must not replace the committed sentence
            if (existing.isFinal && !isFinal) return
            existing.text = trimmed
            existing.isFinal = isFinal
            return
        }
        if (utteranceId == lastUtteranceId) return // already shown and dropped

        // A newer utterance means the previous one is finished even if its final never came
        queue.lastOrNull()?.isFinal = true
        queue.addLast(Utterance(utteranceId, trimmed, isFinal))
        lastUtteranceId = utteranceId
        if (queue.size == 1) {
            cardIndex = 0
            onCardChanged(nowMs)
        } else if (nextWaitingSince == null) {
            // The card on screen now has a successor; its settle time starts now
            nextWaitingSince = nowMs
        }
    }

    fun clear() {
        queue.clear()
        cardIndex = 0
        nextWaitingSince = null
    }

    /**
     * Returns what to show now, advancing to the next card when the current one has been
     * readable long enough. Call again at [SubtitleFrame.nextCheckAtMs], or when new text arrives.
     */
    fun display(nowMs: Long, paginate: (String) -> List<String>): SubtitleFrame {
        if (queue.isEmpty()) return SubtitleFrame.EMPTY

        var cards = cardsOf(queue.first(), paginate)

        // Far behind: drop the oldest waiting cards
        while (backlog(cards, paginate) > maxBacklogCards && advance(cards)) {
            cards = cardsOf(queue.first(), paginate)
            onCardChanged(nowMs)
        }

        if (hasNext(cards)) {
            if (nextWaitingSince == null) nextWaitingSince = nowMs
            if (isDue(nowMs, cards, paginate)) {
                advance(cards)
                cards = cardsOf(queue.first(), paginate)
                onCardChanged(nowMs)
                if (hasNext(cards)) nextWaitingSince = nowMs
            }
        } else {
            // A re-translated draft can shrink back onto one card
            nextWaitingSince = null
        }

        val head = queue.first()
        return SubtitleFrame(
            text = cards[cardIndex],
            isLive = !head.isFinal && cardIndex == cards.lastIndex,
            nextCheckAtMs = if (hasNext(cards)) dueAt(cards, paginate) else null
        )
    }

    private fun onCardChanged(nowMs: Long) {
        cardShownSince = nowMs
        nextWaitingSince = null
    }

    private fun isDue(nowMs: Long, cards: List<String>, paginate: (String) -> List<String>): Boolean =
        nowMs >= dueAt(cards, paginate)

    /**
     * The card on screen may flip once it has been shown for its reading time, and has also
     * been complete (the next card is waiting) for a short settle time, so its last words
     * are readable too.
     */
    private fun dueAt(cards: List<String>, paginate: (String) -> List<String>): Long {
        val behind = backlog(cards, paginate) > fastBacklogCards
        val hold = if (behind) fastHoldMs else readingHoldMs(cards[cardIndex])
        val settle = if (behind) fastHoldMs else minOf(SETTLE_MS, hold)
        val waitingSince = nextWaitingSince ?: cardShownSince
        return maxOf(cardShownSince + hold, waitingSince + settle)
    }

    private fun cardsOf(utterance: Utterance, paginate: (String) -> List<String>): List<String> {
        val cards = paginate(utterance.text).ifEmpty { listOf(utterance.text) }
        // A re-translated draft can get shorter; stay on its last card
        cardIndex = cardIndex.coerceIn(0, cards.lastIndex)
        return cards
    }

    private fun hasNext(cards: List<String>): Boolean = cardIndex < cards.lastIndex || queue.size > 1

    /** Moves to the next card. Returns false if there is none. */
    private fun advance(cards: List<String>): Boolean {
        return when {
            cardIndex < cards.lastIndex -> { cardIndex++; true }
            queue.size > 1 -> { queue.removeFirst(); cardIndex = 0; true }
            else -> false
        }
    }

    /** Cards waiting after the one on screen. */
    private fun backlog(cards: List<String>, paginate: (String) -> List<String>): Int {
        var waiting = cards.lastIndex - cardIndex
        for (i in 1 until queue.size) waiting += paginate(queue[i].text).size.coerceAtLeast(1)
        return waiting
    }

    private fun readingHoldMs(card: String): Long =
        (readingWeight(card) * msPerChar).coerceIn(minHoldMs, maxHoldMs)

    private fun readingWeight(text: String): Long {
        var weight = 0L
        for (c in text) weight += if (SubtitleLayout.isCjkLetter(c)) CJK_CHAR_WEIGHT else 1L
        return weight
    }

    companion object {
        // A Chinese or Japanese character carries about as much as three Latin letters
        private const val CJK_CHAR_WEIGHT = 3L
        // A full card stays at least this long before flipping
        private const val SETTLE_MS = 700L
    }
}

data class SubtitleFrame(
    val text: String,
    /** True while the shown card is still being filled by live speech. */
    val isLive: Boolean,
    /** When the next card may be due, or null if nothing is waiting. */
    val nextCheckAtMs: Long?
) {
    companion object {
        val EMPTY = SubtitleFrame("", isLive = false, nextCheckAtMs = null)
    }
}

/** Splits text into word-sized pieces and packs them into cards that fit the subtitle box. */
object SubtitleLayout {

    /**
     * Breaks text into tokens that keep their trailing spaces, so joining them restores the text.
     * Space-separated languages split per word; Chinese, Japanese and Thai split per character,
     * with punctuation kept on the character before it.
     */
    fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        for (c in text) {
            val startsNew = current.isNotEmpty() && !c.isWhitespace() && (
                current.last().isWhitespace() ||
                    isCjkLetter(c) ||
                    (isCjkLetter(current.last()) && c.isLetterOrDigit())
                )
            if (startsNew) {
                tokens.add(current.toString())
                current.clear()
            }
            current.append(c)
        }
        if (current.isNotEmpty()) tokens.add(current.toString())
        return tokens
    }

    /**
     * Packs [text] into cards, each as long as [fits] allows. When a card is full, it is cut at
     * the last sentence end (or, failing that, the last comma) in its second half, so sentences
     * stay together on a card where possible. [fits] must be monotonic: more text never fits better.
     */
    fun paginate(text: String, fits: (String) -> Boolean): List<String> {
        val tokens = tokenize(text.trim())
        val cards = mutableListOf<String>()
        var start = 0
        while (start < tokens.size) {
            var count = largestFittingCount(tokens, start, fits)
            if (start + count < tokens.size) {
                count = preferredBreak(tokens, start, count)
            }
            cards.add(join(tokens, start, start + count))
            start += count
        }
        return cards
    }

    private fun largestFittingCount(tokens: List<String>, start: Int, fits: (String) -> Boolean): Int {
        // Binary search for the most tokens that fit; a single over-long token still gets a card
        var lo = 1
        var hi = tokens.size - start
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (fits(join(tokens, start, start + mid))) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun preferredBreak(tokens: List<String>, start: Int, count: Int): Int {
        val minSentence = (count + 1) / 2
        for (n in count downTo minSentence) {
            if (endsSentence(tokens[start + n - 1])) return n
        }
        val minClause = (count * 7 + 9) / 10
        for (n in count downTo minClause) {
            if (endsClause(tokens[start + n - 1])) return n
        }
        return count
    }

    private fun join(tokens: List<String>, from: Int, to: Int): String =
        tokens.subList(from, to).joinToString("").trim()

    private fun endsSentence(token: String): Boolean =
        token.trimEnd().trimEnd('"', '\'', '」', '』', ')', '）').lastOrNull() in SENTENCE_ENDS

    private fun endsClause(token: String): Boolean = token.trimEnd().lastOrNull() in CLAUSE_ENDS

    fun isCjkLetter(c: Char): Boolean {
        if (c == 'ー') return true
        return when (Character.UnicodeScript.of(c.code)) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.THAI -> true
            else -> false
        }
    }

    private val SENTENCE_ENDS = setOf('.', '!', '?', '。', '！', '？', '…')
    private val CLAUSE_ENDS = setOf(',', ';', ':', '、', '，', '；', '：')
}
