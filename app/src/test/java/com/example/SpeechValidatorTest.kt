package com.example

import com.example.asr.SpeechValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechValidatorTest {

    @Test
    fun realSpeechStartingWithStatusWordsIsKept() {
        val speech = listOf(
            "Listening to music is my hobby",
            "listening carefully, she nodded",
            "Waiting for audio cues in the game is boring",
            "Live audio stream quality matters",
            "Ready to transcribe the meeting notes?",
            "Failed to initialize the rocket, commander"
        )
        for (text in speech) {
            assertTrue("Expected real speech '$text' to be kept", SpeechValidator.isGenuineSpeech(text))
        }
    }

    @Test
    fun exactStatusPhrasesAreRejectedIgnoringCaseAndTrailingPunctuation() {
        val statuses = listOf(
            "Listening",
            "listening...",
            "LISTENING…",
            "  Listening  ",
            "Waiting for speech...",
            "Awaiting audio stream…",
            "ASR Model not initialized.",
            "Ready to transcribe & translate audio"
        )
        for (text in statuses) {
            assertFalse("Expected status '$text' to be rejected", SpeechValidator.isGenuineSpeech(text))
        }
    }

    @Test
    fun bracketedStatusIsRejectedButDownloadingIsKept() {
        assertFalse(SpeechValidator.isGenuineSpeech("[Translation Paused]"))
        assertTrue(SpeechValidator.isGenuineSpeech("[Downloading En-Ja dictionary...]"))
    }

    @Test
    fun blankIsRejected() {
        assertFalse(SpeechValidator.isGenuineSpeech(null))
        assertFalse(SpeechValidator.isGenuineSpeech(""))
        assertFalse(SpeechValidator.isGenuineSpeech("  \n "))
    }
}
