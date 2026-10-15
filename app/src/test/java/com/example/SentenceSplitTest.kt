package com.example

import com.example.asr.SentenceAssembler
import org.junit.Assert.assertEquals
import org.junit.Test

/** Each sentence in a recognized piece becomes its own subtitle. */
class SentenceSplitTest {

    private fun split(text: String) = SentenceAssembler.splitSentences(text)

    @Test
    fun sentencesInOnePieceAreSeparated() {
        // Seen on the phone with Whisper Base
        assertEquals(
            listOf("Blue lantern, this is Razor!", "Kill the walk!", "Greatest leak!"),
            split("Blue lantern, this is Razor! Kill the walk! Greatest leak!")
        )
        assertEquals(
            listOf("It appears to be a member of the Blue Lantern Corps.", "Listen!"),
            split("It appears to be a member of the Blue Lantern Corps. Listen!")
        )
        // The unfinished end stays as one part, waiting for the rest of its sentence
        assertEquals(
            listOf("I found me.", "I took it as a sign to finally abandon my"),
            split("I found me. I took it as a sign to finally abandon my")
        )
    }

    @Test
    fun japaneseAndChineseSplitWithoutSpaces() {
        assertEquals(listOf("ありがとうございます。", "駅はどこですか？"), split("ありがとうございます。駅はどこですか？"))
        assertEquals(listOf("你好。", "我们走吧"), split("你好。我们走吧"))
        // Closing quote stays with its sentence
        assertEquals(listOf("「行こう！」", "うん。"), split("「行こう！」うん。"))
    }

    @Test
    fun abbreviationsNumbersAndTrailingOffAreNotSplit() {
        assertEquals(listOf("Mr. Smith went to Dr. Jones."), split("Mr. Smith went to Dr. Jones."))
        assertEquals(listOf("J. K. Rowling wrote it."), split("J. K. Rowling wrote it."))
        assertEquals(listOf("It costs 3.5 dollars."), split("It costs 3.5 dollars."))
        assertEquals(listOf("I am... all out of hope."), split("I am... all out of hope."))
        assertEquals(listOf("Bring snacks, e.g. chips."), split("Bring snacks, e.g. chips."))
        // A full stop followed by a lowercase word is most likely not a sentence end
        assertEquals(listOf("approx. three people came"), split("approx. three people came"))
    }

    @Test
    fun runsOfMarksAndEdgeCases() {
        assertEquals(listOf("Really?!", "No way."), split("Really?! No way."))
        assertEquals(listOf("He said \"Stop!\"", "Then left."), split("He said \"Stop!\" Then left."))
        assertEquals(listOf("Hello"), split("Hello"))
        assertEquals(emptyList<String>(), split("   "))
        // A stray mark with no words stays with the sentence before it
        assertEquals(listOf("Wait.!"), split("Wait. !"))
    }
}
