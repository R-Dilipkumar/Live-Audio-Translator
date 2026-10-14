package com.example

import com.example.asr.NoiseTextFilter
import com.example.asr.SentenceAssembler
import com.example.translate.LanguageDetectionGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sentence joining before translation, the music/noise text filter, and installed-only language choice. */
class SentenceAndNoiseTest {

    // ---- SentenceAssembler ----

    @Test
    fun japaneseSentenceSplitByAPauseIsTranslatedWhole() {
        val assembler = SentenceAssembler()
        // Ends in と: clearly unfinished, so it waits
        val first = assembler.onPiece("昨日友達と", utteranceId = 5, nowMs = 0)
        assertFalse(first.isFinal)
        assertEquals(5L, first.utteranceId)

        // Ends in ました: a complete sentence, keeping the first piece's id
        val whole = assembler.onPiece("映画を見ました", utteranceId = 6, nowMs = 400)
        assertTrue(whole.isFinal)
        assertEquals("昨日友達と映画を見ました", whole.text)
        assertEquals(5L, whole.utteranceId)
    }

    @Test
    fun punctuationEndsTheSentenceAtOnce() {
        val sentence = SentenceAssembler().onPiece("Hello there.", utteranceId = 1, nowMs = 0)
        assertTrue(sentence.isFinal)
    }

    @Test
    fun pauseEndsTheSentenceAndUnfinishedEndingsWaitLonger() {
        val assembler = SentenceAssembler()
        assembler.onPiece("I think it works", utteranceId = 1, nowMs = 0)
        assertNull(assembler.onTick(399, isSpeaking = false))
        // Still talking: never cut while there is live text
        assertNull(assembler.onTick(5_000, isSpeaking = true))
        assertEquals("I think it works", assembler.onTick(5_001, isSpeaking = false)?.text)

        assembler.onPiece("I went to the", utteranceId = 2, nowMs = 10_000)
        assertNull(assembler.onTick(10_400, isSpeaking = false))
        assertEquals("I went to the", assembler.onTick(10_900, isSpeaking = false)?.text)
    }

    @Test
    fun separateLinesWithANormalPauseAreNotMerged() {
        // From a device log: "ここです" was heard as "ここで", and the next line got glued on
        val assembler = SentenceAssembler()
        assertFalse(assembler.onPiece("ここで", utteranceId = 26, nowMs = 0).isFinal)
        assertEquals("ここで", assembler.onTick(900, isSpeaking = false)?.text)
        assertTrue(assembler.onPiece("ありがとうございます", utteranceId = 27, nowMs = 3_000).isFinal)
    }

    @Test
    fun liveDraftShowsTheWaitingPiecesFirst() {
        val assembler = SentenceAssembler()
        assembler.onPiece("I went to the", utteranceId = 3, nowMs = 0)
        val draft = assembler.onDraft("store", utteranceId = 4)
        assertEquals("I went to the store", draft.text)
        assertEquals(3L, draft.utteranceId)
        assertFalse(draft.isFinal)
    }

    @Test
    fun longSentencesAreCappedAndJoiningCanBeTurnedOff() {
        val assembler = SentenceAssembler(maxPieces = 3)
        assertFalse(assembler.onPiece("one", 1, 0).isFinal)
        assertFalse(assembler.onPiece("two", 2, 0).isFinal)
        assertEquals("one two three", assembler.onPiece("three", 3, 0).text)

        assembler.isEnabled = false
        assertTrue(assembler.onPiece("no joining", 4, 0).isFinal)
    }

    @Test
    fun flushReturnsWhatIsWaiting() {
        val assembler = SentenceAssembler()
        assertNull(assembler.flush())
        assembler.onPiece("left over", 7, 0)
        val flushed = assembler.flush()
        assertEquals("left over", flushed?.text)
        assertTrue(flushed!!.isFinal)
        assertEquals("", assembler.pendingText())
    }

    // ---- NoiseTextFilter ----

    @Test
    fun fillerSoundsAndSingingAreDropped() {
        val filter = NoiseTextFilter()
        assertNull(filter.filter("嗯", learn = true))
        assertNull(filter.filter("えー", learn = true))
        assertNull(filter.filter("um", learn = true))
        assertNull(filter.filter("啦啦啦啦", learn = true))
        assertNull(filter.filter("la la la la", learn = true))
        assertEquals("Hello", filter.filter("Hello", learn = true))
        // A word repeated twice is still speech
        assertEquals("そうそうそうそう", filter.filter("そうそうそうそう", learn = true))
    }

    @Test
    fun strayChineseDuringEnglishSpeechIsRemoved() {
        val filter = NoiseTextFilter()
        filter.filter("How are you doing", learn = true)
        filter.filter("I am fine thanks", learn = true)
        filter.filter("What about you", learn = true)
        assertEquals(NoiseTextFilter.Script.LATIN, filter.dominantScript())

        assertNull(filter.filter("嗯嗯好", learn = true))
        assertEquals("I like pizza", filter.filter("I like 我 pizza", learn = true))
        // A long line in another language is a real language switch and is kept
        assertEquals("这是一个很长的中文句子", filter.filter("这是一个很长的中文句子", learn = false))
    }

    @Test
    fun englishWordsInsideJapaneseSpeechAreKept() {
        val filter = NoiseTextFilter()
        filter.filter("今日はいい天気ですね", learn = true)
        filter.filter("そうですね本当に", learn = true)
        filter.filter("散歩に行きましょう", learn = true)
        assertEquals(NoiseTextFilter.Script.CJK, filter.dominantScript())
        assertEquals("OK", filter.filter("OK", learn = true))
    }

    @Test
    fun loneLettersDuringJapaneseSpeechAreDropped() {
        val filter = NoiseTextFilter()
        filter.filter("駅はどこですか", learn = true)
        filter.filter("どこで乗りますか", learn = true)
        filter.filter("ありがとうございます", learn = true)
        assertNull(filter.filter("N", learn = true))
        assertNull(filter.filter("H", learn = true))
        assertNull(filter.filter("๐", learn = true))
        // A lone kanji is the same script and is left to the one-character rule
        assertEquals("何", filter.filter("何", learn = true))
    }

    @Test
    fun turnedOffFilterPassesEverything() {
        val filter = NoiseTextFilter().apply { isEnabled = false }
        assertEquals("嗯", filter.filter("嗯", learn = true))
    }

    // ---- LanguageDetectionGate.resolveAmong ----

    @Test
    fun autoDetectOnlyPicksLanguagesThatCanBeTranslated() {
        val gate = LanguageDetectionGate()
        val installed = setOf("en", "ja")
        val usable: (String) -> Boolean = { it in installed }

        assertEquals("ja", gate.resolveAmong(listOf("ja" to 0.9f), 0.5f, "駅はどこですか", usable))

        // Misheard Japanese written in Chinese characters: translated as Japanese, not skipped
        assertEquals("ja", gate.resolveAmong(listOf("zh" to 0.95f, "ja" to 0.03f), 0.5f, "玛谢谢", usable))
        // An unclear next line still uses the remembered Japanese, not Chinese
        assertEquals("ja", gate.resolveAmong(listOf("zh" to 0.4f, "ja" to 0.3f), 0.5f, "好的", usable))

        // An installed language that is reasonably likely wins over an uninstalled one
        assertEquals("en", gate.resolveAmong(listOf("zh" to 0.6f, "en" to 0.55f), 0.5f, "ok", usable))
    }

    @Test
    fun chineseNoiseDuringEnglishSpeechIsStillSkipped() {
        val gate = LanguageDetectionGate()
        val usable: (String) -> Boolean = { it == "en" || it == "ja" }
        assertEquals("en", gate.resolveAmong(listOf("en" to 0.9f), 0.5f, "How are you", usable))
        // Chinese characters do not match English's script: reported for this line only
        assertEquals("zh", gate.resolveAmong(listOf("zh" to 0.95f), 0.5f, "嗯好的", usable))
        assertEquals("en", gate.lastConfidentLanguage)
    }
}
