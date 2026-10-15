package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.asr.ModelManager
import com.example.asr.OfflineFamily
import com.example.asr.OfflinePhraseRecognizer
import com.example.asr.RecognitionMode
import com.example.translate.SupportedLanguage
import com.example.ui.languageHelp
import com.example.ui.understands
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Whole-phrase ("about 1 second" latency) models: catalog, per-latency selection, file layout. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccuracyModeTest {

    private lateinit var context: Context
    private lateinit var modelsDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        modelsDir = File(context.filesDir, "models")
        modelsDir.deleteRecursively()
        context.getSharedPreferences("asr_model_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun ModelManager.model(id: String) = availableModels.first { it.id == id }

    private fun writeFile(file: File, bytes: Int) {
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(bytes))
    }

    private fun installVad(manager: ModelManager) = writeFile(manager.vadModelFile(), 650_000)

    @Test
    fun phraseModelsAreConfiguredForTheOfflineEngine() {
        val manager = ModelManager(context)
        val phraseModels = manager.modelsFor(RecognitionMode.ACCURACY)
        assertTrue(phraseModels.size >= 10)
        assertTrue(phraseModels.all { it.offlineFamily != null && it.downloadUrl.startsWith(ModelManager.RELEASE_URL) })
        assertEquals(phraseModels.size, phraseModels.map { it.id }.toSet().size)
        // Japanese is covered by SenseVoice (default, can be locked to Japanese) and Moonshine
        val senseVoice = manager.model(ModelManager.DEFAULT_ACCURATE_MODEL_ID)
        assertEquals(OfflineFamily.SENSE_VOICE, senseVoice.offlineFamily)
        assertTrue("ja" in senseVoice.languageHints)
        assertEquals(OfflineFamily.MOONSHINE, manager.model("moonshine_ja").offlineFamily)
        // Streaming models are untouched
        assertTrue(manager.modelsFor(RecognitionMode.SPEED).all { it.offlineFamily == null })
    }

    @Test
    fun eachLatencyRemembersItsOwnModel() {
        val manager = ModelManager(context)
        assertEquals(RecognitionMode.SPEED, manager.mode.value)
        assertEquals(ModelManager.DEFAULT_MODEL_ID, manager.selectedModel.value.id)

        manager.setMode(RecognitionMode.ACCURACY)
        assertEquals(ModelManager.DEFAULT_ACCURATE_MODEL_ID, manager.selectedModel.value.id)

        // Picking a model of the other kind switches the latency to match
        manager.setMode(RecognitionMode.SPEED)
        manager.selectModel(manager.model("moonshine_ja"))
        assertEquals(RecognitionMode.ACCURACY, manager.mode.value)

        manager.setMode(RecognitionMode.SPEED)
        assertEquals(ModelManager.DEFAULT_MODEL_ID, manager.selectedModel.value.id)
        manager.setMode(RecognitionMode.ACCURACY)
        assertEquals("moonshine_ja", manager.selectedModel.value.id)

        // Survives a restart
        val again = ModelManager(context)
        assertEquals(RecognitionMode.ACCURACY, again.mode.value)
        assertEquals("moonshine_ja", again.selectedModel.value.id)
    }

    @Test
    fun senseVoiceIsReadyWithOneModelFileAndTheSpeechDetector() {
        val manager = ModelManager(context)
        val senseVoice = manager.model(ModelManager.DEFAULT_ACCURATE_MODEL_ID)
        val dir = manager.getModelDirectory(senseVoice)
        writeFile(File(dir, "model.int8.onnx"), 1_200_000)
        writeFile(File(dir, "tokens.txt"), 1_000)
        writeFile(File(dir, "test_wavs/en.wav"), 5_000)

        // Without the speech detector the model can't find phrases, so it is not ready
        assertFalse(manager.checkModelFilesOnDisk(senseVoice))

        installVad(manager)
        val resolved = manager.resolveFilesForModel(senseVoice)
        assertNotNull(resolved)
        assertNull(resolved!!.decoderFile)
        assertNull(resolved.joinerFile)
        assertEquals(manager.vadModelFile().canonicalPath, resolved.vadFile!!.canonicalPath)

        // Unused archive files are removed, the loaded ones kept
        manager.pruneUnusedFiles(senseVoice)
        assertFalse(File(dir, "test_wavs/en.wav").exists())
        assertTrue(File(dir, "model.int8.onnx").exists())
    }

    @Test
    fun moonshineUsesEncoderAndMergedDecoder() {
        val manager = ModelManager(context)
        installVad(manager)
        val moonshine = manager.model("moonshine_ja")
        val dir = manager.getModelDirectory(moonshine)
        writeFile(File(dir, "encoder_model.ort"), 1_200_000)
        writeFile(File(dir, "tokens.txt"), 1_000)
        // Missing decoder: not ready
        assertFalse(manager.checkModelFilesOnDisk(moonshine))
        writeFile(File(dir, "decoder_model_merged.ort"), 1_200_000)
        val resolved = manager.resolveFilesForModel(moonshine)
        assertEquals("decoder_model_merged.ort", resolved!!.decoderFile!!.name)
        assertNull(resolved.joinerFile)
    }

    @Test
    fun speechDetectorFolderSurvivesTheRetiredModelCleanup() {
        val manager = ModelManager(context)
        installVad(manager)
        ModelManager(context).migrateLegacyFlatInstall()
        assertTrue(manager.isVadInstalled())
    }

    private fun installFakeSenseVoice(manager: ModelManager): File {
        installVad(manager)
        val dir = manager.getModelDirectory(manager.model(ModelManager.DEFAULT_ACCURATE_MODEL_ID))
        writeFile(File(dir, "model.int8.onnx"), 1_200_000)
        writeFile(File(dir, "tokens.txt"), 1_000)
        return dir
    }

    @Test
    fun halfUnpackedModelIsNeverSeenAsInstalled() {
        val manager = ModelManager(context)
        installVad(manager)
        val senseVoice = manager.model(ModelManager.DEFAULT_ACCURATE_MODEL_ID)
        // Unpacking goes into a staging folder first
        val staging = File(modelsDir, "${ModelManager.DEFAULT_ACCURATE_MODEL_ID}${ModelManager.STAGING_SUFFIX}")
        writeFile(File(staging, "model.int8.onnx"), 1_200_000)
        writeFile(File(staging, "tokens.txt"), 1_000)
        assertFalse(manager.checkModelFilesOnDisk(senseVoice))

        // An install killed part-way is cleaned up on the next start
        ModelManager(context).migrateLegacyFlatInstall()
        assertFalse(staging.exists())
    }

    @Test
    fun cantoneseSenseVoiceFromEarlierBuildIsRemoved() {
        // The 2025-09-09 SenseVoice (Cantonese-tuned) was installed as "sensevoice_small"
        val old = File(modelsDir, "sensevoice_small/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09")
        writeFile(File(old, "model.int8.onnx"), 1_200_000)
        ModelManager(context).migrateLegacyFlatInstall()
        assertFalse(File(modelsDir, "sensevoice_small").exists())
        assertTrue(
            ModelManager(context).model(ModelManager.DEFAULT_ACCURATE_MODEL_ID).downloadUrl
                .endsWith("sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2")
        )
    }

    /** A manager whose start-up check (which also clears the crash marker) has finished. */
    private fun startedManager(): ModelManager = ModelManager(context).also { manager ->
        runBlocking { withTimeout(10_000) { manager.hasScannedModels.first { it } } }
    }

    @Test
    fun modelThatLoadedFineIsKept() {
        val manager = startedManager()
        val dir = installFakeSenseVoice(manager)
        manager.beginNativeLoad(ModelManager.DEFAULT_ACCURATE_MODEL_ID)
        manager.endNativeLoad()
        assertNull(manager.removeModelThatCrashedOnLoad())
        assertTrue(manager.checkModelFilesOnDisk(manager.model(ModelManager.DEFAULT_ACCURATE_MODEL_ID)))
        assertTrue(dir.exists())
    }

    @Test
    fun modelThatCrashedTheAppWhileLoadingIsRemoved() {
        // Waits for the start-up check, so it can't take the crash marker first
        val manager = startedManager()
        val dir = installFakeSenseVoice(manager)
        // The app died inside the native load: the marker was never cleared
        manager.beginNativeLoad(ModelManager.DEFAULT_ACCURATE_MODEL_ID)
        manager.removeModelThatCrashedOnLoad()
        assertFalse(dir.exists())
        assertFalse(manager.checkModelFilesOnDisk(manager.model(ModelManager.DEFAULT_ACCURATE_MODEL_ID)))
        // Only once: the marker is gone
        assertNull(manager.removeModelThatCrashedOnLoad())
    }

    @Test
    fun eachLatencyShowsItsOwnModel() {
        val manager = ModelManager(context)
        manager.selectModel(manager.model("moonshine_ja"))
        assertEquals("moonshine_ja", manager.chosenModelIds.value[RecognitionMode.ACCURACY])
        assertEquals(ModelManager.DEFAULT_MODEL_ID, manager.chosenModelIds.value[RecognitionMode.SPEED])
        // Switching latency keeps both choices
        manager.setMode(RecognitionMode.SPEED)
        assertEquals("moonshine_ja", manager.chosenModelIds.value[RecognitionMode.ACCURACY])
    }

    @Test
    fun spokenLanguageHelpMatchesWhatTheModelCanDo() {
        val manager = ModelManager(context)
        val japanese = SupportedLanguage("ja", "Japanese", "")
        val english = SupportedLanguage("en", "English", "")
        val auto = SupportedLanguage("auto", "Auto-Detect", "", isAutoDetect = true)

        // A Japanese-only model can't hear English: a warning
        assertTrue(languageHelp(english, manager.model("moonshine_ja")).isWarning)
        assertFalse(languageHelp(japanese, manager.model("moonshine_ja")).isWarning)
        // SenseVoice is locked to the chosen language
        val senseVoice = manager.model(ModelManager.DEFAULT_ACCURATE_MODEL_ID)
        assertTrue(languageHelp(japanese, senseVoice).text.contains("only"))
        // The multilingual streaming model can't be locked
        val streaming = manager.model(ModelManager.DEFAULT_MODEL_ID)
        assertTrue(languageHelp(japanese, streaming).text.contains("can't be locked"))
        assertFalse(languageHelp(auto, streaming).isWarning)
        // Whisper understands any language; Parakeet's list is incomplete, so no false warning
        assertEquals(true, manager.model("whisper_small").understands("ta"))
        assertEquals(null, manager.model("parakeet_v3").understands("sv"))
        assertEquals(false, manager.model("moonshine_ja").understands("en"))
    }

    @Test
    fun strayBreaksInsideJapaneseWordsAreRemoved() {
        // Seen on the phone with SenseVoice
        assertEquals("ありがとうございます。", OfflinePhraseRecognizer.tidyJapanese("ありがとうござ。います。"))
        assertEquals("どこで乗りますか？", OfflinePhraseRecognizer.tidyJapanese("どこで乗りま すか？"))
        assertEquals("行きました。", OfflinePhraseRecognizer.tidyJapanese("行き。ました。"))
        // Real sentence breaks stay
        assertEquals("はい。います。", OfflinePhraseRecognizer.tidyJapanese("はい。います。"))
        assertEquals("そうです。ですから", OfflinePhraseRecognizer.tidyJapanese("そうです。ですから"))
        // Spaces between Latin words stay
        assertEquals("Thank you. See you", OfflinePhraseRecognizer.tidyJapanese("Thank you. See you"))
        assertEquals("駅 station", OfflinePhraseRecognizer.tidyJapanese("駅 station"))
    }

    @Test
    fun heardLanguageTagsAreNormalized() {
        assertEquals("ja", OfflinePhraseRecognizer.normalizeLanguageTag("<|ja|>"))
        assertEquals("en", OfflinePhraseRecognizer.normalizeLanguageTag("en"))
        assertEquals("zh", OfflinePhraseRecognizer.normalizeLanguageTag("<|yue|>"))
        assertNull(OfflinePhraseRecognizer.normalizeLanguageTag("<|nospeech|>"))
        assertNull(OfflinePhraseRecognizer.normalizeLanguageTag(""))
    }

    @Test
    fun whisperHallucinationsAreRecognized() {
        assertTrue(OfflinePhraseRecognizer.isWhisperHallucination("ご視聴ありがとうございました"))
        assertTrue(OfflinePhraseRecognizer.isWhisperHallucination("Thank you for watching!"))
        assertTrue(OfflinePhraseRecognizer.isWhisperHallucination("♪ ♪ ♪"))
        assertFalse(OfflinePhraseRecognizer.isWhisperHallucination("駅はどこですか"))
        assertFalse(OfflinePhraseRecognizer.isWhisperHallucination("Thank you very much for your help today, it meant a lot to all of us."))
    }
}
