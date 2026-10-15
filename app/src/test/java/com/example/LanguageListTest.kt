package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.asr.ModelManager
import com.example.asr.OfflinePhraseRecognizer
import com.example.asr.SlowModelWatch
import com.example.asr.SpeechRecognizerEngine
import com.example.translate.allTranslatableLanguages
import com.example.translate.flagFor
import com.example.translate.languageDisplayName
import com.example.ui.canLockTo
import com.example.ui.languageHelp
import com.example.ui.understands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every ML Kit language is offered, and slow phrase models are noticed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LanguageListTest {

    @Test
    fun everyTranslatableLanguageIsOffered() {
        val languages = allTranslatableLanguages()
        assertTrue("expected all ML Kit languages, got ${languages.size}", languages.size >= 55)
        val byCode = languages.associateBy { it.code }
        for ((code, name) in listOf("ta" to "Tamil", "te" to "Telugu", "kn" to "Kannada", "mr" to "Marathi",
            "bn" to "Bengali", "gu" to "Gujarati", "ur" to "Urdu", "en" to "English", "ja" to "Japanese")) {
            assertEquals(name, byCode[code]?.displayName)
        }
        assertEquals("Chinese", languageDisplayName("zh"))
        // Sorted by name, with no blank names
        assertEquals(languages.map { it.displayName }.sorted(), languages.map { it.displayName })
        assertTrue(languages.none { it.displayName.isBlank() || it.isAutoDetect })
        assertEquals("🇮🇳", flagFor("ta"))
        assertEquals("🌐", flagFor("eo"))
    }

    @Test
    fun onlyWhisperUnderstandsTamil() {
        val manager = ModelManager(ApplicationProvider.getApplicationContext<Context>())
        val tamil = allTranslatableLanguages().first { it.code == "ta" }
        assertEquals(true, manager.availableModels.first { it.id == "whisper_base" }.understands("ta"))
        val senseVoice = manager.availableModels.first { it.id == ModelManager.DEFAULT_ACCURATE_MODEL_ID }
        assertTrue(languageHelp(tamil, senseVoice).isWarning)
        assertFalse(languageHelp(tamil, manager.availableModels.first { it.id == "whisper_base" }).isWarning)
    }

    @Test
    fun slowModelIsNoticedAfterTwoSlowPhrases() {
        val watch = SlowModelWatch()
        // Seen on the phone with Whisper Small: 17.4 s of speech took 19.2 s, 10.4 s took 10.7 s
        assertFalse(watch.record(audioSec = 17.4f, decodeSec = 19.2f))
        assertTrue(watch.record(audioSec = 10.4f, decodeSec = 10.7f))
        // A tiny phrase doesn't change the verdict either way
        assertTrue(watch.record(audioSec = 0.5f, decodeSec = 0.1f))
        // A phrase recognized faster than it lasts clears it
        assertFalse(watch.record(audioSec = 3f, decodeSec = 0.3f))
        watch.reset()
        assertFalse(watch.record(audioSec = 3f, decodeSec = 4f))
    }

    @Test
    fun aPieceCutMidSpeechDoesNotEndTheSentence() {
        // SenseVoice ends every piece with a full stop, even one cut while the speaker kept talking
        assertEquals("駅は", SpeechRecognizerEngine.trimCutPieceEnding("駅は。"))
        assertEquals("I think that", SpeechRecognizerEngine.trimCutPieceEnding("I think that. "))
        assertEquals("Really", SpeechRecognizerEngine.trimCutPieceEnding("Really?!"))
        // Commas and other marks inside stay
        assertEquals("はい、そうです", SpeechRecognizerEngine.trimCutPieceEnding("はい、そうです"))
    }

    @Test
    fun whisperSoundLabelsAreNotSubtitles() {
        // Seen on the phone with Whisper Base
        assertEquals("", OfflinePhraseRecognizer.removeSoundLabels("[Music]"))
        assertEquals("", OfflinePhraseRecognizer.removeSoundLabels("(dramatic music"))
        assertEquals("", OfflinePhraseRecognizer.removeSoundLabels("*c"))
        assertEquals("Look at you!", OfflinePhraseRecognizer.removeSoundLabels("*laughs* Look at you!"))
        assertEquals("I'm done! Alright", OfflinePhraseRecognizer.removeSoundLabels("I'm done! ♪ Alright"))
        assertEquals("Only my rage remains.", OfflinePhraseRecognizer.removeSoundLabels("Only my rage remains."))
    }

    @Test
    fun whisperIsNeverLockedToALanguageItDoesNotKnow() {
        val whisper = ModelManager(ApplicationProvider.getApplicationContext<Context>())
            .availableModels.first { it.id == "whisper_base" }
        // Irish and Esperanto can be translated by ML Kit but Whisper doesn't know them: locking
        // it to one could make the native library exit the app
        for (code in listOf("ga", "eo")) {
            assertEquals(false, whisper.understands(code))
            assertFalse(whisper.canLockTo(code))
        }
        assertTrue(whisper.canLockTo("ta"))
        // Every other ML Kit language is one Whisper knows
        val unknown = allTranslatableLanguages().map { it.code }.filter { it !in ModelManager.WHISPER_LANGUAGE_CODES }
        assertEquals(setOf("ga", "eo"), unknown.toSet())
    }
}
