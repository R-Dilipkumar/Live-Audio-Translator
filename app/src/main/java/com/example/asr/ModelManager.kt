package com.example.asr

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.example.service.NotificationHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * A streaming (live) Zipformer transducer model for Sherpa-ONNX.
 *
 * totalSizeBytes is the exact archive size on the server (what the download shows).
 * The four file names match the files inside the archive. memoryMb is roughly the size of those
 * files, which is about what the model takes in RAM once loaded.
 */
data class AsrModelConfig(
    val id: String,
    val name: String,
    // Main language code ("multi" for the multilingual model)
    val languageCode: String,
    // Languages it recognizes, for display
    val languages: List<String>,
    val description: String,
    val downloadUrl: String,
    val totalSizeBytes: Long,
    val memoryMb: Int,
    val encoderFilename: String,
    val decoderFilename: String,
    val joinerFilename: String,
    val tokensFilename: String = "tokens.txt",
    val isMultilingual: Boolean = false,
    // Output includes punctuation and capital letters (better subtitles and translation)
    val hasPunctuation: Boolean = false,
    val license: String = "Apache-2.0",
    // Sherpa-ONNX transducer type. Empty means sherpa-onnx reads the "model_type" entry from the
    // encoder's ONNX metadata, which every model here carries.
    val modelType: String = ""
)

sealed class ModelDownloadState {
    object NotDownloaded : ModelDownloadState()
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long, val progress: Float) : ModelDownloadState()
    data class Extracting(val currentFile: String = "", val progress: Float = 0f) : ModelDownloadState()
    data class Ready(val modelDir: File) : ModelDownloadState()
    data class Error(val errorMsg: String) : ModelDownloadState()
}

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(
        val progress: Float,
        val speedBytesPerSec: Long = 0L,
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long = 0L
    ) : DownloadState()
    object Extracting : DownloadState()
    object Ready : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class ModelManager(private val context: Context) {

    private val TAG = "ModelManager"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // One streaming model per language: the most accurate streaming Zipformer published for it
    // (k2-fsa/sherpa-onnx "asr-models" release, checked 2026-10-13), plus one multilingual model.
    // Japanese, Thai, Vietnamese, Arabic and Indonesian have no dedicated streaming Zipformer;
    // the multilingual model covers them.
    val availableModels: List<AsrModelConfig> = listOf(
        AsrModelConfig(
            id = "zipformer_multilingual_universal",
            name = "Multilingual",
            languageCode = "multi",
            languages = listOf("Japanese", "Chinese", "English", "Russian", "Arabic", "Thai", "Vietnamese", "Indonesian"),
            description = "One model for 8 languages, switches automatically. The only streaming Zipformer that covers Japanese. PengChengStarling, 2025.",
            downloadUrl = "$RELEASE_URL/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10.tar.bz2",
            totalSizeBytes = 258_999_581L,
            memoryMb = 325,
            encoderFilename = "encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx",
            decoderFilename = "decoder-epoch-75-avg-11-chunk-16-left-128.onnx",
            joinerFilename = "joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx",
            isMultilingual = true
        ),
        AsrModelConfig(
            id = "kroko_en",
            name = "English",
            languageCode = "en",
            languages = listOf("English"),
            description = "Kroko community model trained on real-world speech, with punctuation and capitals. 2025.",
            downloadUrl = "$RELEASE_URL/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06.tar.bz2",
            totalSizeBytes = 57_267_600L,
            memoryMb = 70,
            encoderFilename = "encoder.onnx",
            decoderFilename = "decoder.onnx",
            joinerFilename = "joiner.onnx",
            hasPunctuation = true,
            license = "CC-BY-SA (Kroko community)"
        ),
        AsrModelConfig(
            id = "xasr_zh_en",
            name = "Chinese",
            languageCode = "zh",
            languages = listOf("Chinese", "English"),
            description = "X-ASR, trained on about 1 million hours. Handles Chinese with English mixed in, with punctuation. 2026.",
            downloadUrl = "$RELEASE_URL/sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05.tar.bz2",
            totalSizeBytes = 133_895_136L,
            memoryMb = 160,
            encoderFilename = "encoder.int8.onnx",
            decoderFilename = "decoder.onnx",
            joinerFilename = "joiner.int8.onnx",
            hasPunctuation = true
        ),
        AsrModelConfig(
            id = "zipformer_ko",
            name = "Korean",
            languageCode = "ko",
            languages = listOf("Korean"),
            description = "Trained on KsponSpeech conversational Korean. 2024.",
            downloadUrl = "$RELEASE_URL/sherpa-onnx-streaming-zipformer-korean-2024-06-16.tar.bz2",
            totalSizeBytes = 418_218_652L,
            memoryMb = 135,
            encoderFilename = "encoder-epoch-99-avg-1.int8.onnx",
            decoderFilename = "decoder-epoch-99-avg-1.onnx",
            joinerFilename = "joiner-epoch-99-avg-1.int8.onnx"
        ),
        AsrModelConfig(
            id = "kroko_es",
            name = "Spanish",
            languageCode = "es",
            languages = listOf("Spanish"),
            description = "Kroko community model trained on real-world speech, with punctuation and capitals. 2025.",
            downloadUrl = "$RELEASE_URL/sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06.tar.bz2",
            totalSizeBytes = 124_394_665L,
            memoryMb = 150,
            encoderFilename = "encoder.onnx",
            decoderFilename = "decoder.onnx",
            joinerFilename = "joiner.onnx",
            hasPunctuation = true,
            license = "CC-BY-SA (Kroko community)"
        ),
        AsrModelConfig(
            id = "kroko_fr",
            name = "French",
            languageCode = "fr",
            languages = listOf("French"),
            description = "Kroko community model trained on real-world speech, with punctuation and capitals. 2025.",
            downloadUrl = "$RELEASE_URL/sherpa-onnx-streaming-zipformer-fr-kroko-2025-08-06.tar.bz2",
            totalSizeBytes = 57_220_361L,
            memoryMb = 70,
            encoderFilename = "encoder.onnx",
            decoderFilename = "decoder.onnx",
            joinerFilename = "joiner.onnx",
            hasPunctuation = true,
            license = "CC-BY-SA (Kroko community)"
        ),
        AsrModelConfig(
            id = "kroko_de",
            name = "German",
            languageCode = "de",
            languages = listOf("German"),
            description = "Kroko community model trained on real-world speech, with punctuation and capitals. 2025.",
            downloadUrl = "$RELEASE_URL/sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06.tar.bz2",
            totalSizeBytes = 57_565_698L,
            memoryMb = 70,
            encoderFilename = "encoder.onnx",
            decoderFilename = "decoder.onnx",
            joinerFilename = "joiner.onnx",
            hasPunctuation = true,
            license = "CC-BY-SA (Kroko community)"
        ),
        AsrModelConfig(
            id = "vosk_ru",
            name = "Russian",
            languageCode = "ru",
            languages = listOf("Russian"),
            description = "Vosk Russian streaming model (full precision). 2025.",
            downloadUrl = "$RELEASE_URL/sherpa-onnx-streaming-zipformer-small-ru-vosk-2025-08-16.tar.bz2",
            totalSizeBytes = 89_653_224L,
            memoryMb = 90,
            encoderFilename = "encoder.onnx",
            decoderFilename = "decoder.onnx",
            joinerFilename = "joiner.onnx"
        ),
        AsrModelConfig(
            id = "zipformer_bn",
            name = "Bengali",
            languageCode = "bn",
            languages = listOf("Bengali"),
            description = "Vosk Bengali streaming model. 2026.",
            downloadUrl = "$RELEASE_URL/sherpa-onnx-streaming-zipformer-bn-vosk-2026-02-09.tar.bz2",
            totalSizeBytes = 87_289_525L,
            memoryMb = 90,
            encoderFilename = "encoder.onnx",
            decoderFilename = "decoder.onnx",
            joinerFilename = "joiner.onnx"
        )
    )

    companion object {
        // Official sherpa-onnx model release (GitHub)
        const val RELEASE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

        // Default selection on a fresh install
        const val DEFAULT_MODEL_ID = "zipformer_multilingual_universal"

        // Old storage layout: one flat folder shared by every model. Only read by the migration.
        const val LEGACY_FLAT_DIR = "models/sherpa-onnx"

        private const val PREFS_NAME = "asr_model_prefs"
        private const val KEY_SELECTED_MODEL_ID = "selected_model_id"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val selectionLock = Any()

    private val _selectedModel = MutableStateFlow(
        availableModels.firstOrNull { it.id == prefs.getString(KEY_SELECTED_MODEL_ID, null) }
            ?: availableModels.first { it.id == DEFAULT_MODEL_ID }
    )
    val selectedModel: StateFlow<AsrModelConfig> = _selectedModel.asStateFlow()

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.NotDownloaded)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    // Id of the model being downloaded or unpacked, or null. Only one download runs at a time.
    private val _activeDownloadId = MutableStateFlow<String?>(null)
    val activeDownloadId: StateFlow<String?> = _activeDownloadId.asStateFlow()

    // True once the startup scan of installed models has finished.
    private val _hasScannedModels = MutableStateFlow(false)
    val hasScannedModels: StateFlow<Boolean> = _hasScannedModels.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _availableStorageMb = MutableStateFlow(0L)
    val availableStorageMb: StateFlow<Long> = _availableStorageMb.asStateFlow()

    private val _readyModelIds = MutableStateFlow<Set<String>>(emptySet())
    val readyModelIds: StateFlow<Set<String>> = _readyModelIds.asStateFlow()

    @Volatile private var legacyMigrationDone = false
    private val migrationLock = Any()

    init {
        scope.launch {
            migrateLegacyFlatInstall()
            cleanupOrphanedTempFiles()
            val ready = refreshReadyModels()
            // Free space taken by files of earlier installs that are never loaded
            availableModels.filter { it.id in ready }.forEach { pruneUnusedFiles(it) }
            refreshAvailableStorage()
            chooseInitialSelectionIfUnset(ready)
            _hasScannedModels.value = true
        }
    }

    /**
     * Removes model files that no current catalogue entry can load:
     * - the old flat models/sherpa-onnx folder (it held the retired English 20M model), and
     * - folders of models that were dropped from the catalogue.
     * Runs once per process; safe to call from any thread.
     */
    fun migrateLegacyFlatInstall() {
        if (legacyMigrationDone) return
        synchronized(migrationLock) {
            if (legacyMigrationDone) return
            try {
                val legacyDir = File(context.filesDir, LEGACY_FLAT_DIR)
                if (legacyDir.exists()) {
                    Log.i(TAG, "Deleting legacy flat model folder (retired model)")
                    legacyDir.deleteRecursively()
                }
                val knownIds = availableModels.map { it.id }.toSet()
                getModelsDirectory().listFiles()?.forEach { dir ->
                    if (dir.isDirectory && dir.name !in knownIds) {
                        Log.i(TAG, "Deleting retired model folder ${dir.name}")
                        dir.deleteRecursively()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Legacy model cleanup failed: ${e.message}")
            } finally {
                legacyMigrationDone = true
            }
        }
    }

    /**
     * When there is no saved selection (first run, or the saved model was retired), prefer the
     * default model if it is installed, otherwise any installed model.
     */
    private fun chooseInitialSelectionIfUnset(ready: Set<String>) {
        synchronized(selectionLock) {
            val saved = prefs.getString(KEY_SELECTED_MODEL_ID, null)
            if (saved != null && availableModels.any { it.id == saved }) return
            val pick = availableModels.firstOrNull { it.id == DEFAULT_MODEL_ID && it.id in ready }
                ?: availableModels.firstOrNull { it.id in ready }
                ?: return
            _selectedModel.value = pick
            prefs.edit().putString(KEY_SELECTED_MODEL_ID, pick.id).apply()
        }
        checkCurrentModelStatus()
    }

    suspend fun refreshAvailableStorage(): Long = withContext(Dispatchers.IO) {
        try {
            val statFs = StatFs(context.filesDir.path)
            val availableBytes = statFs.availableBlocksLong * statFs.blockSizeLong
            val mb = availableBytes / (1024L * 1024L)
            _availableStorageMb.value = mb
            mb
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading StatFs on IO dispatcher: ${e.message}")
            _availableStorageMb.value
        }
    }

    suspend fun refreshReadyModels(): Set<String> = withContext(Dispatchers.IO) {
        val ready = mutableSetOf<String>()
        for (model in availableModels) {
            if (checkModelFilesOnDisk(model)) {
                ready.add(model.id)
            }
        }
        _readyModelIds.value = ready
        checkCurrentModelStatus()
        ready
    }

    fun getAvailableStorageMb(): Long {
        val cached = _availableStorageMb.value
        if (cached > 0L) return cached

        return try {
            val statFs = StatFs(context.filesDir.path)
            val availableBytes = statFs.availableBlocksLong * statFs.blockSizeLong
            val mb = availableBytes / (1024L * 1024L)
            _availableStorageMb.value = mb
            mb
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading StatFs during storage fallback: ${e.message}")
            0L
        }
    }

    /**
     * Cleans up any leftover temporary archive files from interrupted downloads.
     */
    fun cleanupOrphanedTempFiles() {
        try {
            val modelsDir = getModelsDirectory()
            modelsDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".archive") || file.name.contains("_temp")) {
                    file.delete()
                }
            }
        } catch (_: Exception) {}
    }

    private fun cleanupPartialFiles(tempFile: File?, targetDir: File?, model: AsrModelConfig) {
        try {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete()
            }
            if (targetDir != null && targetDir.exists() && !checkModelFilesOnDisk(model)) {
                targetDir.deleteRecursively()
            }
        } catch (_: Exception) {}
    }

    /**
     * Selects [model] as the one the speech engine loads, and remembers it across restarts.
     * The engine loads exactly this model; it never falls back to another installed one.
     */
    fun selectModel(model: AsrModelConfig) {
        synchronized(selectionLock) {
            _selectedModel.value = model
            prefs.edit().putString(KEY_SELECTED_MODEL_ID, model.id).apply()
        }
        checkCurrentModelStatus()
    }

    fun getModelsDirectory(): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getModelDirectory(model: AsrModelConfig): File {
        return File(getModelsDirectory(), model.id)
    }

    private fun hasUsableModelFile(file: File?, minBytes: Long): Boolean {
        return file != null && file.exists() && file.length() >= minBytes
    }

    /**
     * Data class holding verified ONNX model file pointers on disk.
     */
    data class ResolvedModelFiles(
        val model: AsrModelConfig,
        val encoderFile: File,
        val decoderFile: File,
        val joinerFile: File,
        val tokensFile: File
    )

    /**
     * Checks if all required model files exist and are non-empty.
     * Uses in-memory StateFlow cache to avoid blocking the main UI thread during Compose recomposition.
     */
    fun isModelReady(model: AsrModelConfig): Boolean {
        return _readyModelIds.value.contains(model.id) || checkModelFilesOnDisk(model)
    }

    /**
     * Checks if the currently selected ASR model is installed. Does not change the selection.
     */
    fun isSelectedModelReady(): Boolean = checkModelFilesOnDisk(_selectedModel.value)

    /**
     * True if any model is installed on disk. Strictly called on Dispatchers.IO.
     */
    fun hasAnyModelInstalled(): Boolean = availableModels.any { checkModelFilesOnDisk(it) }

    /**
     * Checks disk files for the given model. Strictly called on Dispatchers.IO.
     */
    fun checkModelFilesOnDisk(model: AsrModelConfig): Boolean {
        return resolveFilesForModel(model) != null
    }

    /**
     * Resolves model files for a specific model from its own folder, models/<model id>/.
     */
    fun resolveFilesForModel(model: AsrModelConfig): ResolvedModelFiles? {
        migrateLegacyFlatInstall()
        return resolveFilesInModelDir(model)
    }

    private fun resolveFilesInModelDir(model: AsrModelConfig): ResolvedModelFiles? {
        val dir = getModelDirectory(model)
        if (dir.exists() && dir.isDirectory) {
            val encoder = findFileInDir(dir, model.encoderFilename) ?: findOnnxByKeyword(dir, "encoder")
            val decoder = findFileInDir(dir, model.decoderFilename) ?: findOnnxByKeyword(dir, "decoder")
            val joiner = findFileInDir(dir, model.joinerFilename) ?: findOnnxByKeyword(dir, "joiner")
            val tokens = findFileInDir(dir, model.tokensFilename) ?: findFileByKeyword(dir, "tokens.txt")

            if (encoder != null && decoder != null && joiner != null && tokens != null &&
                hasUsableModelFile(encoder, 1_000_000L) &&
                hasUsableModelFile(decoder, 100_000L) &&
                hasUsableModelFile(joiner, 10_000L) &&
                hasUsableModelFile(tokens, 10L)
            ) {
                return ResolvedModelFiles(model, encoder, decoder, joiner, tokens)
            }
        }
        return null
    }

    /**
     * Fallback when a configured file name is not in the archive: picks an ONNX file containing
     * [keyword] the same way every time, preferring the int8 (smaller) variant.
     */
    private fun findOnnxByKeyword(dir: File, keyword: String): File? {
        return dir.walkTopDown()
            .filter { it.isFile && it.name.contains(keyword, ignoreCase = true) && it.name.endsWith(".onnx", ignoreCase = true) }
            .sortedWith(compareBy<File>({ !it.name.contains(".int8.", ignoreCase = true) }, { it.name }))
            .firstOrNull()
    }

    /**
     * Deletes everything in the model's folder except the four files that are loaded
     * (archives also ship other precision variants, test audio and scripts).
     */
    fun pruneUnusedFiles(model: AsrModelConfig) {
        val resolved = resolveFilesInModelDir(model) ?: return
        val keep = listOf(resolved.encoderFile, resolved.decoderFile, resolved.joinerFile, resolved.tokensFile)
            .map { it.canonicalPath }
            .toSet()
        val dir = getModelDirectory(model)
        var freed = 0L
        dir.walkBottomUp().forEach { file ->
            if (file == dir) return@forEach
            if (file.isFile && file.canonicalPath !in keep) {
                freed += file.length()
                file.delete()
            } else if (file.isDirectory && file.listFiles().isNullOrEmpty()) {
                file.delete()
            }
        }
        if (freed > 0) Log.i(TAG, "Removed ${freed / (1024 * 1024)} MB of unused files from ${model.id}")
    }

    /**
     * Resolves the files of the selected model, and only the selected model.
     * Returns null when it is not installed, even if other models are.
     */
    fun resolveSelectedModelFiles(): ResolvedModelFiles? = resolveFilesForModel(_selectedModel.value)

    fun checkCurrentModelStatus() {
        val current = _selectedModel.value
        if (isModelReady(current)) {
            _downloadState.value = ModelDownloadState.Ready(getModelDirectory(current))
        } else {
            if (_downloadState.value is ModelDownloadState.Ready) {
                _downloadState.value = ModelDownloadState.NotDownloaded
            }
        }
    }

    /**
     * Downloads and installs [model] into its own folder, models/<model id>/, with byte-level
     * progress reporting. Files in the archive that the app never loads are deleted afterwards.
     */
    suspend fun downloadModel(model: AsrModelConfig = _selectedModel.value) = withContext(Dispatchers.IO) {
        synchronized(this@ModelManager) {
            if (_activeDownloadId.value != null) {
                Log.w(TAG, "Download of ${model.id} ignored: ${_activeDownloadId.value} is still downloading")
                return@withContext
            }
            _activeDownloadId.value = model.id
        }
        val targetDir = getModelDirectory(model)
        val tempArchiveFile = File(getModelsDirectory(), "${model.id}_temp.archive")

        fun report(state: ModelDownloadState) {
            _downloadState.value = state
        }

        try {
            // Storage pre-flight check: the archive plus its unpacked files must fit before the
            // unused files are deleted. Unpacked files are up to about twice the archive size.
            val availableMb = refreshAvailableStorage()
            val minRequiredMb = maxOf(150L, (model.totalSizeBytes * 3) / (1024L * 1024L))
            if (availableMb < minRequiredMb) {
                val error = "Insufficient storage space: About ${minRequiredMb}MB of free space is needed to install this model (Available: ${availableMb}MB)."
                report(ModelDownloadState.Error(error))
                NotificationHelper.showErrorNotification(context, "Storage Check Failed", error)
                return@withContext
            }

            requireHttpsUrl(model.downloadUrl)
            report(ModelDownloadState.Downloading(0, model.totalSizeBytes, 0f))

            val request = Request.Builder()
                .url(model.downloadUrl)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val error = "Download failed with HTTP ${response.code}: ${response.message}"
                    cleanupPartialFiles(tempArchiveFile, targetDir, model)
                    report(ModelDownloadState.Error(error))
                    NotificationHelper.showErrorNotification(context, "Model Download Failed", error)
                    return@withContext
                }

                val contentType = response.header("Content-Type")?.lowercase().orEmpty()
                if (contentType.contains("text/html")) {
                    cleanupPartialFiles(tempArchiveFile, targetDir, model)
                    throw IOException("File not found on server")
                }

                val body = response.body ?: throw IOException("Empty response body")
                val contentLength = if (body.contentLength() > 0) body.contentLength() else model.totalSizeBytes

                // Download file stream with progress tracking
                body.byteStream().use { input ->
                    FileOutputStream(tempArchiveFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var bytesRead: Int
                        var totalRead = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            val progress = if (contentLength > 0) (totalRead.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f) else 0f
                            report(ModelDownloadState.Downloading(totalRead, contentLength, progress))
                        }
                        output.flush()
                    }
                }
            }

            // Extract the downloaded model
            report(ModelDownloadState.Extracting("Preparing model directory...", 0.05f))
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            targetDir.mkdirs()

            val progressCallback: (String, Float) -> Unit = { entryName, prog ->
                report(ModelDownloadState.Extracting(entryName, prog))
            }

            // Try unpacking based on archive format (tar.bz2, zip, or direct file)
            val isZip = isZipFile(tempArchiveFile)
            val isBz2 = isBzip2File(tempArchiveFile) || model.downloadUrl.endsWith(".tar.bz2") || model.downloadUrl.endsWith(".bz2")
            if (isZip) {
                unzip(tempArchiveFile, targetDir, progressCallback)
            } else if (isBz2) {
                extractTarBz2(tempArchiveFile, targetDir, progressCallback)
            } else {
                // For direct file or custom archive, move to target
                val extractedFile = File(targetDir, model.encoderFilename)
                tempArchiveFile.copyTo(extractedFile, overwrite = true)
            }

            tempArchiveFile.delete()

            // Verify installation
            if (checkModelFilesOnDisk(model)) {
                pruneUnusedFiles(model)
                report(ModelDownloadState.Ready(targetDir))
                refreshReadyModels()
                refreshAvailableStorage()
            } else {
                // BUG 4 FIX: Do NOT write zero-byte placeholder ONNX files. If the archive
                // extracted but the expected model files are still missing, the archive content
                // is incompatible or corrupt. Delete the partial directory and report a hard error.
                // Writing placeholder bytes causes a silent crash in the Sherpa-ONNX JNI native loader.
                Log.e(TAG, "Model extraction succeeded but required files are missing. Cleaning up.")
                cleanupPartialFiles(null, targetDir, model)
                val error = "Model archive did not contain expected ONNX files. Please retry the download."
                report(ModelDownloadState.Error(error))
                NotificationHelper.showErrorNotification(context, "Model Extraction Failed", error)
            }

        } catch (e: CancellationException) {
            // Requirement 2: Clean up partial corrupted files on cancellation
            cleanupPartialFiles(tempArchiveFile, targetDir, model)
            report(ModelDownloadState.NotDownloaded)
            throw e
        } catch (e: Exception) {
            // Requirement 2: Clean up partial corrupted files on failure
            cleanupPartialFiles(tempArchiveFile, targetDir, model)
            val error = e.localizedMessage ?: "Unknown model download error"
            report(ModelDownloadState.Error(error))
            NotificationHelper.showErrorNotification(context, "ASR Model Download Error", error)
        } finally {
            if (tempArchiveFile.exists()) {
                tempArchiveFile.delete()
            }
            _activeDownloadId.value = null
        }
    }

    /**
     * Deletes the model's folder to reclaim storage. This is the only place a model's files live,
     * so a deleted model can no longer be loaded. If the selected model is deleted and another
     * model is installed, that one becomes the selection.
     */
    suspend fun deleteModel(model: AsrModelConfig = _selectedModel.value): Boolean = withContext(Dispatchers.IO) {
        migrateLegacyFlatInstall()
        val dir = getModelDirectory(model)
        val success = if (dir.exists()) dir.deleteRecursively() else true
        val ready = refreshReadyModels()
        if (_selectedModel.value.id == model.id) {
            availableModels.firstOrNull { it.id in ready }?.let { selectModel(it) }
        }
        refreshAvailableStorage()
        checkCurrentModelStatus()
        success
    }

    /**
     * Extracted model files are loaded by native code, so only fetch archives over HTTPS.
     * Throws IllegalArgumentException, which downloadModel() reports as an error state.
     */
    private fun requireHttpsUrl(url: String) {
        require(url.startsWith("https://", ignoreCase = true)) { "Insecure model download URL (HTTPS required): $url" }
    }

    private fun isZipFile(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        FileInputStream(file).use { fis ->
            val header = ByteArray(4)
            val read = fis.read(header)
            return read == 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
        }
    }

    private fun isBzip2File(file: File): Boolean {
        if (!file.exists() || file.length() < 3) return false
        FileInputStream(file).use { fis ->
            val header = ByteArray(3)
            val read = fis.read(header)
            // 'B' 'Z' 'h' magic bytes
            return read == 3 && header[0] == 'B'.code.toByte() && header[1] == 'Z'.code.toByte() && header[2] == 'h'.code.toByte()
        }
    }

    private fun extractTarBz2(
        archiveFile: File,
        targetDirectory: File,
        onProgress: ((entryName: String, progress: Float) -> Unit)? = null
    ) {
        val targetCanonicalDirPath = targetDirectory.canonicalPath + File.separator
        val bufferSize = 64 * 1024
        var extractedCount = 0

        FileInputStream(archiveFile).use { fis ->
            BufferedInputStream(fis, bufferSize).use { bis ->
                BZip2CompressorInputStream(bis).use { bzIn ->
                    TarArchiveInputStream(bzIn).use { tarIn ->
                        var entry = tarIn.nextEntry
                        val buffer = ByteArray(bufferSize)
                        while (entry != null) {
                            val newFile = File(targetDirectory, entry.name)
                            val canonicalPath = newFile.canonicalPath
                            // Protect against Zip/Tar Slip vulnerability
                            if (!canonicalPath.startsWith(targetCanonicalDirPath) && canonicalPath != targetDirectory.canonicalPath) {
                                throw SecurityException("Tar entry is outside of target dir: ${entry.name}")
                            }
                            if (entry.isDirectory) {
                                newFile.mkdirs()
                            } else {
                                newFile.parentFile?.mkdirs()
                                val simpleName = entry.name.substringAfterLast('/')
                                extractedCount++
                                onProgress?.invoke(simpleName, (extractedCount / 10f).coerceIn(0.1f, 0.95f))

                                BufferedOutputStream(FileOutputStream(newFile), bufferSize).use { bos ->
                                    var count: Int
                                    while (tarIn.read(buffer).also { count = it } != -1) {
                                        bos.write(buffer, 0, count)
                                    }
                                    bos.flush()
                                }
                            }
                            entry = tarIn.nextEntry
                        }
                    }
                }
            }
        }
    }

    private fun unzip(
        zipFile: File,
        targetDirectory: File,
        onProgress: ((entryName: String, progress: Float) -> Unit)? = null
    ) {
        val targetCanonicalDirPath = targetDirectory.canonicalPath + File.separator
        val bufferSize = 64 * 1024
        var extractedCount = 0
        val buffer = ByteArray(bufferSize)

        FileInputStream(zipFile).use { fis ->
            BufferedInputStream(fis, bufferSize).use { bis ->
                ZipInputStream(bis).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val newFile = File(targetDirectory, entry.name)
                        val canonicalPath = newFile.canonicalPath
                        // Protect against Zip Slip vulnerability
                        if (!canonicalPath.startsWith(targetCanonicalDirPath) && canonicalPath != targetDirectory.canonicalPath) {
                            throw SecurityException("Zip entry is outside of target dir: ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            newFile.mkdirs()
                        } else {
                            newFile.parentFile?.mkdirs()
                            val simpleName = entry.name.substringAfterLast('/')
                            extractedCount++
                            onProgress?.invoke(simpleName, (extractedCount / 8f).coerceIn(0.1f, 0.95f))

                            BufferedOutputStream(FileOutputStream(newFile), bufferSize).use { bos ->
                                var count: Int
                                while (zis.read(buffer).also { count = it } != -1) {
                                    bos.write(buffer, 0, count)
                                }
                                bos.flush()
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        }
    }

    fun findFileInDir(dir: File, fileName: String): File? {
        val target = File(dir, fileName)
        if (target.exists()) return target
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                val found = findFileInDir(file, fileName)
                if (found != null) return found
            } else if (file.name == fileName) {
                return file
            }
        }
        return null
    }

    fun findFileByKeyword(dir: File, keyword: String): File? {
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                val found = findFileByKeyword(file, keyword)
                if (found != null) return found
            } else if (file.name.contains(keyword, ignoreCase = true)) {
                return file
            }
        }
        return null
    }
}
