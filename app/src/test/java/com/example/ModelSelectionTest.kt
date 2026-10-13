package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.asr.ModelManager
import com.example.asr.SpeechRecognizerEngine
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelSelectionTest {

    private lateinit var context: Context
    private lateinit var modelsDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        modelsDir = File(context.filesDir, "models")
        modelsDir.deleteRecursively()
        context.getSharedPreferences("asr_model_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun writeModelFiles(dir: File, encoder: String, decoder: String, joiner: String, tokenLines: Int = 500) {
        dir.mkdirs()
        File(dir, encoder).writeBytes(ByteArray(1_100_000))
        File(dir, decoder).writeBytes(ByteArray(150_000))
        File(dir, joiner).writeBytes(ByteArray(20_000))
        File(dir, "tokens.txt").writeText((0 until tokenLines).joinToString("\n") { "tok$it $it" })
    }

    private fun ModelManager.model(id: String) = availableModels.first { it.id == id }

    private fun ModelManager.install(id: String) {
        val model = model(id)
        writeModelFiles(getModelDirectory(model), model.encoderFilename, model.decoderFilename, model.joinerFilename)
    }

    @Test
    fun catalogueHasOneModelPerLanguageAndOneMultilingual() {
        val manager = ModelManager(context)
        val single = manager.availableModels.filter { !it.isMultilingual }
        assertEquals(1, manager.availableModels.count { it.isMultilingual })
        assertEquals(single.size, single.map { it.languageCode }.toSet().size)
        assertTrue(manager.availableModels.all { it.downloadUrl.startsWith(ModelManager.RELEASE_URL) })
    }

    @Test
    fun selectingModelBLoadsModelBNotTheFlatFolder() {
        // Old layout: a flat install that used to win regardless of selection.
        writeModelFiles(
            File(context.filesDir, ModelManager.LEGACY_FLAT_DIR),
            "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx"
        )
        val manager = ModelManager(context)
        manager.install("kroko_fr")
        val engine = SpeechRecognizerEngine(context, manager)

        manager.selectModel(manager.model("kroko_fr"))
        val loaded = engine.resolveModelForLoad()

        assertNotNull(loaded)
        assertEquals("kroko_fr", loaded!!.modelId)
        assertTrue(loaded.encoderFile.canonicalPath.startsWith(manager.getModelDirectory(manager.model("kroko_fr")).canonicalPath))
        assertFalse("Flat folder must be removed", File(context.filesDir, ModelManager.LEGACY_FLAT_DIR).exists())
    }

    @Test
    fun retiredModelFoldersAreRemoved() {
        File(modelsDir, "zipformer_en_fast").mkdirs()
        File(modelsDir, "zipformer_en_fast/encoder.onnx").writeBytes(ByteArray(10))
        val manager = ModelManager(context)
        manager.install("kroko_en")

        manager.migrateLegacyFlatInstall()

        assertFalse(File(modelsDir, "zipformer_en_fast").exists())
        assertNotNull(manager.resolveFilesForModel(manager.model("kroko_en")))
    }

    @Test
    fun selectedModelThatIsNotInstalledDoesNotFallBackToAnotherModel() {
        val manager = ModelManager(context)
        manager.install("kroko_en")
        val engine = SpeechRecognizerEngine(context, manager)

        manager.selectModel(manager.model("zipformer_ko"))

        assertNull(engine.resolveModelForLoad())
        assertEquals("zipformer_ko", manager.selectedModel.value.id)
    }

    @Test
    fun deletedModelCanNoLongerBeLoaded() = runBlocking {
        val manager = ModelManager(context)
        manager.install("kroko_en")
        val englishModel = manager.model("kroko_en")
        val engine = SpeechRecognizerEngine(context, manager)
        manager.selectModel(englishModel)
        assertNotNull(engine.resolveModelForLoad())

        assertTrue(manager.deleteModel(englishModel))

        assertNull(engine.resolveModelForLoad())
        assertFalse(manager.getModelDirectory(englishModel).exists())
    }

    @Test
    fun unusedArchiveFilesAreDeletedAndConfiguredFilesKept() {
        val manager = ModelManager(context)
        val korean = manager.model("zipformer_ko")
        val dir = File(manager.getModelDirectory(korean), "sherpa-onnx-streaming-zipformer-korean-2024-06-16")
        writeModelFiles(dir, korean.encoderFilename, korean.decoderFilename, korean.joinerFilename)
        // Extra files shipped in the archive: the other precision variants and test audio
        File(dir, "encoder-epoch-99-avg-1.onnx").writeBytes(ByteArray(1_200_000))
        File(dir, "joiner-epoch-99-avg-1.onnx").writeBytes(ByteArray(20_000))
        File(dir, "test_wavs").mkdirs()
        File(dir, "test_wavs/0.wav").writeBytes(ByteArray(1000))

        manager.pruneUnusedFiles(korean)

        val remaining = manager.getModelDirectory(korean).walkTopDown().filter { it.isFile }.map { it.name }.toSet()
        assertEquals(
            setOf(korean.encoderFilename, korean.decoderFilename, korean.joinerFilename, "tokens.txt"),
            remaining
        )
        assertNotNull(manager.resolveFilesForModel(korean))
    }

    @Test
    fun selectionIsRememberedAcrossInstances() {
        val first = ModelManager(context)
        first.selectModel(first.model("xasr_zh_en"))

        val second = ModelManager(context)
        assertEquals("xasr_zh_en", second.selectedModel.value.id)
    }

    @Test
    fun retiredSavedSelectionFallsBackToDefault() {
        context.getSharedPreferences("asr_model_prefs", Context.MODE_PRIVATE)
            .edit().putString("selected_model_id", "zipformer_en_fast").commit()

        val manager = ModelManager(context)
        assertEquals(ModelManager.DEFAULT_MODEL_ID, manager.selectedModel.value.id)
    }
}
