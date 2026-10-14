package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.asr.ModelManager
import com.example.asr.RecognitionMode
import com.example.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Fixes from AUDIT.md that can be checked without a device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuditFixesTest {

    private lateinit var context: Context
    private lateinit var modelsDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        modelsDir = File(context.filesDir, "models")
        modelsDir.deleteRecursively()
        context.getSharedPreferences("asr_model_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun writeFile(file: File, bytes: Int) {
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(bytes))
    }

    private fun ModelManager.model(id: String) = availableModels.first { it.id == id }

    private fun installPhraseModels(manager: ModelManager) {
        writeFile(manager.vadModelFile(), 650_000)
        val senseVoice = manager.getModelDirectory(manager.model(ModelManager.DEFAULT_ACCURATE_MODEL_ID))
        writeFile(File(senseVoice, "model.int8.onnx"), 1_200_000)
        writeFile(File(senseVoice, "tokens.txt"), 1_000)
        val moonshine = manager.getModelDirectory(manager.model("moonshine_ja"))
        writeFile(File(moonshine, "encoder_model.ort"), 1_200_000)
        writeFile(File(moonshine, "decoder_model_merged.ort"), 1_200_000)
        writeFile(File(moonshine, "tokens.txt"), 1_000)
    }

    // BUG-15
    @Test
    fun deletingTheOtherLatencysModelKeepsAnotherInstalledOne() = runBlocking {
        val manager = ModelManager(context)
        installPhraseModels(manager)
        manager.refreshReadyModels()
        manager.selectModel(manager.model("moonshine_ja"))
        // Now on Instant: the About-1-second choice is not the selected model
        manager.setMode(RecognitionMode.SPEED)
        assertEquals("moonshine_ja", manager.chosenModelIds.value[RecognitionMode.ACCURACY])

        manager.deleteModel(manager.model("moonshine_ja"))

        assertEquals(ModelManager.DEFAULT_ACCURATE_MODEL_ID, manager.chosenModelIds.value[RecognitionMode.ACCURACY])
        // The latency is not changed, and switching to it uses the remaining model
        assertEquals(RecognitionMode.SPEED, manager.mode.value)
        manager.setMode(RecognitionMode.ACCURACY)
        assertEquals(ModelManager.DEFAULT_ACCURATE_MODEL_ID, manager.selectedModel.value.id)
    }

    // BUG-15: deleting the selected model of the current latency still falls back too
    @Test
    fun deletingTheSelectedModelFallsBackWithinItsLatency() = runBlocking {
        val manager = ModelManager(context)
        installPhraseModels(manager)
        manager.refreshReadyModels()
        manager.selectModel(manager.model("moonshine_ja"))

        manager.deleteModel(manager.model("moonshine_ja"))

        assertEquals(RecognitionMode.ACCURACY, manager.mode.value)
        assertEquals(ModelManager.DEFAULT_ACCURATE_MODEL_ID, manager.selectedModel.value.id)
        assertFalse(manager.checkModelFilesOnDisk(manager.model("moonshine_ja")))
    }

    // BUG-08: the floating window's settings are saved in one write
    @Test
    fun overlaySettingsAreSavedTogether() = runBlocking {
        val repo = SettingsRepository(context)
        repo.updateOverlaySettings(
            fontSizeSp = 23f,
            opacity = 0.42f,
            maxLines = 3,
            showOriginal = false,
            lingerSeconds = 6f,
            textShadow = false,
            textShadowRadius = 2.5f,
            styleCode = 0
        )
        val saved = repo.settingsFlow.first()
        assertEquals(23f, saved.fontSizeSp, 0.001f)
        assertEquals(0.42f, saved.overlayOpacity, 0.001f)
        assertEquals(3, saved.maxDisplayLines)
        assertEquals(false, saved.showOriginalSpeech)
        assertEquals(6f, saved.subtitleLingerTimeSeconds, 0.001f)
        assertEquals(false, saved.isTextOutlineShadowEnabled)
        assertEquals(2.5f, saved.textShadowRadius, 0.001f)
        assertEquals(0, saved.subtitleStyle)
    }
}
