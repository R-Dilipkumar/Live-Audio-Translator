package com.example

import com.example.overlay.SubtitleLayout
import com.example.overlay.SubtitlePager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Movie-style subtitle cards. A card here holds up to 20 characters. */
class SubtitlePagerTest {

    private val paginate: (String) -> List<String> = { text ->
        SubtitleLayout.paginate(text) { candidate -> candidate.length <= 20 }
    }

    @Test
    fun tokenizeKeepsWordsAndSplitsCjkPerCharacter() {
        assertEquals(listOf("Hello, ", "world."), SubtitleLayout.tokenize("Hello, world."))
        // Punctuation stays on the character before it; Latin words after CJK start a new token
        assertEquals(
            listOf("日", "本", "語", "で", "す。", "テ", "ス", "ト", "test"),
            SubtitleLayout.tokenize("日本語です。テストtest")
        )
    }

    @Test
    fun fullCardsBreakAtTheLastSentenceEnd() {
        assertEquals(
            listOf("I went home.", "Then I slept for", "hours"),
            paginate("I went home. Then I slept for hours")
        )
    }

    @Test
    fun growingDraftFillsTheCardThenFlipsAfterReadingTime() {
        val pager = SubtitlePager()
        pager.submit(1, "Hello there", isFinal = false, nowMs = 0)
        val first = pager.display(0, paginate)
        assertEquals("Hello there", first.text)
        assertTrue(first.isLive)
        assertNull(first.nextCheckAtMs)

        // The draft overflows the card: the full card stays until it has been readable long enough
        pager.submit(1, "Hello there my good friend", isFinal = false, nowMs = 500)
        val full = pager.display(500, paginate)
        assertEquals("Hello there my good", full.text)
        assertEquals(1_200L, full.nextCheckAtMs)
        assertEquals("Hello there my good", pager.display(1_199, paginate).text)

        val next = pager.display(1_200, paginate)
        assertEquals("friend", next.text)
        assertTrue(next.isLive)
    }

    @Test
    fun nextSentenceWaitsForReadingTimeAndStartsOnAFreshCard() {
        val pager = SubtitlePager()
        pager.submit(1, "Short one.", isFinal = true, nowMs = 0)
        assertEquals("Short one.", pager.display(0, paginate).text)

        pager.submit(2, "Next sentence", isFinal = false, nowMs = 100)
        val held = pager.display(100, paginate)
        assertEquals("Short one.", held.text)
        assertFalse(held.isLive)
        assertEquals(1_200L, held.nextCheckAtMs)

        val next = pager.display(1_200, paginate)
        assertEquals("Next sentence", next.text)
        assertTrue(next.isLive)
    }

    @Test
    fun lateDraftsAndOlderUtterancesAreIgnored() {
        val pager = SubtitlePager()
        pager.submit(1, "Final text", isFinal = true, nowMs = 0)
        pager.submit(1, "Final te", isFinal = false, nowMs = 10)
        assertEquals("Final text", pager.display(10, paginate).text)

        pager.submit(2, "Second", isFinal = true, nowMs = 20)
        pager.submit(1, "old draft", isFinal = false, nowMs = 30)
        assertEquals("Second", pager.display(5_000, paginate).text)
    }

    @Test
    fun fastSpeechShortensHoldAndSkipsOldCardsSoSubtitlesDoNotLag() {
        val pager = SubtitlePager()
        for (id in 1L..8L) pager.submit(id, "Sentence $id.", isFinal = true, nowMs = 0)

        // 7 cards waiting: the oldest are skipped down to 4 waiting, then shown quickly
        assertEquals("Sentence 4.", pager.display(0, paginate).text)
        assertEquals("Sentence 5.", pager.display(600, paginate).text)
        assertEquals("Sentence 6.", pager.display(1_200, paginate).text)
        // Caught up (2 waiting): normal reading time again
        assertEquals("Sentence 6.", pager.display(2_399, paginate).text)
        assertEquals("Sentence 7.", pager.display(2_400, paginate).text)
        val last = pager.display(3_600, paginate)
        assertEquals("Sentence 8.", last.text)
        assertNull(last.nextCheckAtMs)
    }

    @Test
    fun retranslatedDraftThatGetsShorterStaysOnOneCard() {
        val pager = SubtitlePager()
        pager.submit(1, "Hello there my good friend", isFinal = false, nowMs = 0)
        pager.display(0, paginate)
        assertEquals("friend", pager.display(1_200, paginate).text)

        pager.submit(1, "Hi my friend", isFinal = false, nowMs = 1_300)
        val frame = pager.display(1_300, paginate)
        assertEquals("Hi my friend", frame.text)
        assertNull(frame.nextCheckAtMs)
    }
}
