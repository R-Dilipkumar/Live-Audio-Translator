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

    private fun writeModelFiles(dir: File, encoder: String, decoder: String, joiner: String, tokenLines: Int) {
        dir.mkdirs()
        File(dir, encoder).writeBytes(ByteArray(1_100_000))
        File(dir, decoder).writeBytes(ByteArray(150_000))
        File(dir, joiner).writeBytes(ByteArray(20_000))
        File(dir, "tokens.txt").writeText((0 until tokenLines).joinToString("\n") { "tok$it $it" })
    }

    private fun writeLegacyFlatInstall(tokenLines: Int = 500) {
        writeModelFiles(
            File(context.filesDir, ModelManager.LEGACY_FLAT_DIR),
            "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", tokenLines
        )
    }

    private fun ModelManager.model(id: String) = availableModels.first { it.id == id }

    @Test
    fun selectingModelBLoadsModelBNotTheFlatFolder() {
        // Old layout: a flat install that used to win regardless of selection.
        writeLegacyFlatInstall()
        val manager = ModelManager(context)
        val french = manager.model("zipformer_fr")
        writeModelFiles(
            manager.getModelDirectory(french),
            french.encoderFilename, french.decoderFilename, french.joinerFilename, tokenLines = 5000
        )
        val engine = SpeechRecognizerEngine(context, manager)

        manager.selectModel(french)
        val loaded = engine.resolveModelForLoad()

        assertNotNull(loaded)
        assertEquals("zipformer_fr", loaded!!.modelId)
        assertEquals("zipformer", loaded.modelType)
        assertTrue(loaded.encoderFile.canonicalPath.startsWith(manager.getModelDirectory(french).canonicalPath))
        assertFalse("Flat folder must be gone after migration", File(context.filesDir, ModelManager.LEGACY_FLAT_DIR).exists())
    }

    @Test
    fun flatInstallIsMigratedIntoDefaultModelFolder() {
        writeLegacyFlatInstall()
        val manager = ModelManager(context)
        val englishFast = manager.model(ModelManager.DEFAULT_MODEL_ID)

        val resolved = manager.resolveFilesForModel(englishFast)

        assertNotNull(resolved)
        assertTrue(resolved!!.encoderFile.canonicalPath.startsWith(manager.getModelDirectory(englishFast).canonicalPath))
        assertFalse(File(context.filesDir, ModelManager.LEGACY_FLAT_DIR).exists())
    }

    @Test
    fun unidentifiableFlatInstallIsDeletedNotMigrated() {
        // A character-level vocabulary (thousands of tokens) is not the English 20M model.
        writeLegacyFlatInstall(tokenLines = 6000)
        val manager = ModelManager(context)

        manager.migrateLegacyFlatInstall()

        assertFalse(File(context.filesDir, ModelManager.LEGACY_FLAT_DIR).exists())
        assertNull(manager.resolveFilesForModel(manager.model(ModelManager.DEFAULT_MODEL_ID)))
    }

    @Test
    fun selectedModelThatIsNotInstalledDoesNotFallBackToAnotherModel() {
        val manager = ModelManager(context)
        val englishFast = manager.model(ModelManager.DEFAULT_MODEL_ID)
        writeModelFiles(
            manager.getModelDirectory(englishFast),
            englishFast.encoderFilename, englishFast.decoderFilename, englishFast.joinerFilename, tokenLines = 500
        )
        val engine = SpeechRecognizerEngine(context, manager)

        manager.selectModel(manager.model("zipformer_ko"))

        assertNull(engine.resolveModelForLoad())
        assertEquals("zipformer_ko", manager.selectedModel.value.id)
    }

    @Test
    fun deletedModelCanNoLongerBeLoaded() = runBlocking {
        writeLegacyFlatInstall()
        val manager = ModelManager(context)
        val englishFast = manager.model(ModelManager.DEFAULT_MODEL_ID)
        val engine = SpeechRecognizerEngine(context, manager)
        manager.selectModel(englishFast)
        assertNotNull(engine.resolveModelForLoad())

        assertTrue(manager.deleteModel(englishFast))

        assertNull(engine.resolveModelForLoad())
        assertFalse(manager.getModelDirectory(englishFast).exists())
        assertFalse(File(context.filesDir, ModelManager.LEGACY_FLAT_DIR).exists())
    }

    @Test
    fun selectionIsRememberedAcrossInstances() {
        val first = ModelManager(context)
        first.selectModel(first.model("zipformer_zh_fast"))

        val second = ModelManager(context)
        assertEquals("zipformer_zh_fast", second.selectedModel.value.id)
    }
}
