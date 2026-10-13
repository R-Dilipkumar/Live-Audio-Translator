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

enum class ModelTier(
    val label: String,
    val badge: String,
    val icon: String,
    val description: String
) {
    FAST(
        label = "Fast / Low-Latency",
        badge = "⚡ Fast (100ms)",
        icon = "⚡",
        description = "Optimized for instant 100ms response, low battery, and fast-paced action games."
    ),
    CINEMATIC(
        label = "High Accuracy / Cinematic",
        badge = "🎯 Cinematic",
        icon = "🎯",
        description = "Optimized for complex sentence grammar, anime/movie dialogue, and dialect recognition."
    )
}

data class AsrModelConfig(
    val id: String,
    val name: String,
    val languageCode: String,
    val description: String,
    val downloadUrl: String,
    val totalSizeBytes: Long,
    val encoderFilename: String,
    val decoderFilename: String,
    val joinerFilename: String,
    val tokensFilename: String,
    val isMultilingual: Boolean = false,
    val category: String = "Monolingual",
    val tier: ModelTier = ModelTier.FAST,
    val latencyProfile: String = "~100ms",
    val ramProfile: String = "~50MB RAM",
    // Sherpa-ONNX transducer type: "zipformer" (icefall pruned_transducer_stateless7_streaming exports)
    // or "zipformer2" (newer icefall zipformer exports). Empty means sherpa-onnx reads the
    // "model_type" entry from the encoder's ONNX metadata, which every icefall export carries.
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

    // Pre-configured lightweight streaming ASR models categorized into Fast and Cinematic tiers
    val availableModels: List<AsrModelConfig> = listOf(
        // ==========================================
        // 1. MULTILINGUAL (Auto-Detect Universal)
        // ==========================================
        AsrModelConfig(
            id = "zipformer_multilingual_universal",
            name = "Multilingual Streaming Zipformer (Auto-Detect)",
            languageCode = "auto",
            description = "Universal multilingual streaming recognizer supporting auto-detection across Arabic, English, Indonesian, Japanese, Russian, Thai, Vietnamese, and Chinese (approx. 128 MB)",
            downloadUrl = "https://huggingface.co/xumo/onnx_models/resolve/main/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10.tar.bz2",
            totalSizeBytes = 134_200_000L,
            encoderFilename = "encoder-epoch-75-avg-11-chunk-16-left-128.onnx",
            decoderFilename = "decoder-epoch-75-avg-11-chunk-16-left-128.onnx",
            joinerFilename = "joiner-epoch-75-avg-11-chunk-16-left-128.onnx",
            tokensFilename = "tokens.txt",
            isMultilingual = true,
            category = "Multilingual / Auto-Detect",
            tier = ModelTier.FAST,
            latencyProfile = "⚡ ~110ms",
            ramProfile = "💾 ~65MB RAM"
        ),

        // ==========================================
        // 2. ENGLISH FAST (20M)
        // ==========================================
        AsrModelConfig(
            id = "zipformer_en_fast",
            name = "English Fast Streaming Zipformer (20M)",
            languageCode = "en",
            description = "Instantaneous response time for fast gaming commentary and action scenes (approx. 42 MB)",
            downloadUrl = "https://huggingface.co/xumo/onnx_models/resolve/main/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2",
            totalSizeBytes = 44_200_000L,
            encoderFilename = "encoder-epoch-99-avg-1.onnx",
            decoderFilename = "decoder-epoch-99-avg-1.onnx",
            joinerFilename = "joiner-epoch-99-avg-1.onnx",
            tokensFilename = "tokens.txt",
            isMultilingual = false,
            category = "English",
            tier = ModelTier.FAST,
            latencyProfile = "⚡ ~85ms",
            ramProfile = "💾 ~40MB RAM",
            modelType = "zipformer"
        ),

        // ==========================================
        // 3. ENGLISH CINEMATIC
        // ==========================================
        AsrModelConfig(
            id = "zipformer_en_cinematic",
            name = "English Cinematic Dialogue Zipformer",
            languageCode = "en",
            description = "High-accuracy ASR optimized for conversational nuance, accents, and film dialogue (approx. 92 MB)",
            downloadUrl = "https://huggingface.co/xumo/onnx_models/resolve/main/sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2",
            totalSizeBytes = 96_500_000L,
            encoderFilename = "encoder-epoch-99-avg-1.int8.onnx",
            decoderFilename = "decoder-epoch-99-avg-1.onnx",
            joinerFilename = "joiner-epoch-99-avg-1.int8.onnx",
            tokensFilename = "tokens.txt",
            isMultilingual = false,
            category = "English",
            tier = ModelTier.CINEMATIC,
            latencyProfile = "🎯 ~190ms",
            ramProfile = "💾 ~90MB RAM",
            modelType = "zipformer2"
        ),

        // ==========================================
        // 4. CHINESE FAST (14M)
        // ==========================================
        AsrModelConfig(
            id = "zipformer_zh_fast",
            name = "Chinese Fast Streaming Zipformer (14M)",
            languageCode = "zh",
            description = "Ultra-fast Mandarin recognition with minimal memory footprint (approx. 32 MB)",
            downloadUrl = "https://huggingface.co/xumo/onnx_models/resolve/main/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23.tar.bz2",
            totalSizeBytes = 33_500_000L,
            encoderFilename = "encoder-epoch-99-avg-1.int8.onnx",
            decoderFilename = "decoder-epoch-99-avg-1.onnx",
            joinerFilename = "joiner-epoch-99-avg-1.int8.onnx",
            tokensFilename = "tokens.txt",
            isMultilingual = false,
            category = "Chinese",
            tier = ModelTier.FAST,
            latencyProfile = "⚡ ~90ms",
            ramProfile = "💾 ~38MB RAM",
            modelType = "zipformer"
        ),

        // ==========================================
        // 5. CHINESE & ENGLISH BILINGUAL
        // ==========================================
        AsrModelConfig(
            id = "zipformer_bilingual_zh_en",
            name = "Chinese & English Bilingual Streaming Zipformer",
            languageCode = "zh",
            description = "High-accuracy dual-language recognizer for code-switching and bilingual dialogue (approx. 115 MB)",
            downloadUrl = "https://huggingface.co/xumo/onnx_models/resolve/main/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2",
            totalSizeBytes = 120_500_000L,
            encoderFilename = "encoder-epoch-99-avg-1.int8.onnx",
            decoderFilename = "decoder-epoch-99-avg-1.onnx",
            joinerFilename = "joiner-epoch-99-avg-1.int8.onnx",
            tokensFilename = "tokens.txt",
            isMultilingual = false,
            category = "Bilingual (ZH/EN)",
            tier = ModelTier.CINEMATIC,
            latencyProfile = "🎯 ~200ms",
            ramProfile = "💾 ~95MB RAM",
            modelType = "zipformer"
        ),

        // ==========================================
        // 6. FRENCH FAST
        // ==========================================
        AsrModelConfig(
            id = "zipformer_fr",
            name = "French Streaming Zipformer",
            languageCode = "fr",
            description = "Real-time French streaming speech recognition with low latency (approx. 95 MB)",
            downloadUrl = "https://huggingface.co/xumo/onnx_models/resolve/main/sherpa-onnx-streaming-zipformer-fr-2023-04-14.tar.bz2",
            totalSizeBytes = 95_000_000L,
            encoderFilename = "encoder-epoch-29-avg-9-with-head-epoch-29.int8.onnx",
            decoderFilename = "decoder-epoch-29-avg-9-with-head-epoch-29.onnx",
            joinerFilename = "joiner-epoch-29-avg-9-with-head-epoch-29.int8.onnx",
            tokensFilename = "tokens.txt",
            isMultilingual = false,
            category = "French",
            tier = ModelTier.FAST,
            latencyProfile = "⚡ ~110ms",
            ramProfile = "💾 ~55MB RAM",
            modelType = "zipformer"
        ),

        // ==========================================
        // 7. KOREAN FAST
        // ==========================================
        AsrModelConfig(
            id = "zipformer_ko",
            name = "Korean Streaming Zipformer",
            languageCode = "ko",
            description = "Real-time Korean streaming speech recognition for K-dramas, gaming, and media (approx. 85 MB)",
            downloadUrl = "https://huggingface.co/xumo/onnx_models/resolve/main/sherpa-onnx-streaming-zipformer-korean-2024-06-16.tar.bz2",
            totalSizeBytes = 85_000_000L,
            encoderFilename = "encoder-epoch-99-avg-1.int8.onnx",
            decoderFilename = "decoder-epoch-99-avg-1.onnx",
            joinerFilename = "joiner-epoch-99-avg-1.int8.onnx",
            tokensFilename = "tokens.txt",
            isMultilingual = false,
            category = "Korean",
            tier = ModelTier.FAST,
            latencyProfile = "⚡ ~105ms",
            ramProfile = "💾 ~50MB RAM"
        ),

        // ==========================================
        // 8. BENGALI FAST
        // ==========================================
        AsrModelConfig(
            id = "zipformer_bn",
            name = "Bengali Streaming Zipformer",
            languageCode = "bn",
            description = "Real-time Bengali streaming speech recognition based on Vosk data (approx. 80 MB)",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-bn-vosk-2026-02-09.tar.bz2",
            totalSizeBytes = 80_000_000L,
            encoderFilename = "encoder-epoch-99-avg-1.int8.onnx",
            decoderFilename = "decoder-epoch-99-avg-1.onnx",
            joinerFilename = "joiner-epoch-99-avg-1.int8.onnx",
            tokensFilename = "tokens.txt",
            isMultilingual = false,
            category = "Bengali",
            tier = ModelTier.FAST,
            latencyProfile = "⚡ ~100ms",
            ramProfile = "💾 ~45MB RAM"
        )
    )

    companion object {
        // Model installed on first launch (~42 MB). This is the model the old flat
        // models/sherpa-onnx folder was always downloaded from.
        const val DEFAULT_MODEL_ID = "zipformer_en_fast"

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

    // Progress of the first-launch download (shown in the onboarding dialog, with speed).
    private val _setupDownloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val setupDownloadState: StateFlow<DownloadState> = _setupDownloadState.asStateFlow()

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
            refreshAvailableStorage()
            val ready = refreshReadyModels()
            chooseInitialSelectionIfUnset(ready)
        }
    }

    /**
     * Moves an install from the old flat models/sherpa-onnx folder into the per-model folder
     * models/<model id>/, so every model lives in exactly one place.
     *
     * The flat folder was only ever filled from the English 20M model URL, so a complete install
     * there is treated as [DEFAULT_MODEL_ID] after a tokens.txt sanity check (that model uses a
     * 500-entry BPE vocabulary). Anything that fails the check is deleted, not guessed at.
     * Runs once per process; safe to call from any thread.
     */
    fun migrateLegacyFlatInstall() {
        if (legacyMigrationDone) return
        synchronized(migrationLock) {
            if (legacyMigrationDone) return
            try {
                val legacyDir = File(context.filesDir, LEGACY_FLAT_DIR)
                if (legacyDir.exists()) {
                    val target = availableModels.first { it.id == DEFAULT_MODEL_ID }
                    val targetDir = getModelDirectory(target)
                    when {
                        resolveFilesInModelDir(target) != null -> {
                            Log.i(TAG, "Legacy flat model folder duplicates ${target.id}; deleting it")
                            legacyDir.deleteRecursively()
                        }
                        isLegacyInstallOfDefaultModel(legacyDir) -> {
                            if (targetDir.exists()) targetDir.deleteRecursively()
                            val moved = legacyDir.renameTo(targetDir) ||
                                (legacyDir.copyRecursively(targetDir, overwrite = true) && legacyDir.deleteRecursively())
                            Log.i(TAG, "Migrated legacy flat model folder into ${targetDir.name}: $moved")
                            if (!moved) {
                                targetDir.deleteRecursively()
                                legacyDir.deleteRecursively()
                            }
                        }
                        else -> {
                            Log.w(TAG, "Legacy flat model folder could not be identified; deleting it")
                            legacyDir.deleteRecursively()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Legacy model migration failed: ${e.message}")
            } finally {
                legacyMigrationDone = true
            }
        }
    }

    private fun isLegacyInstallOfDefaultModel(dir: File): Boolean {
        val encoder = findFileByKeyword(dir, "encoder")
        val decoder = findFileByKeyword(dir, "decoder")
        val joiner = findFileByKeyword(dir, "joiner")
        val tokens = findFileByKeyword(dir, "tokens")
        if (!hasUsableModelFile(encoder, 1_000_000L) ||
            !hasUsableModelFile(decoder, 100_000L) ||
            !hasUsableModelFile(joiner, 10_000L) ||
            !hasUsableModelFile(tokens, 10L)
        ) return false
        val tokenCount = try {
            tokens!!.useLines { lines -> lines.count { it.isNotBlank() } }
        } catch (_: Exception) {
            return false
        }
        return tokenCount in 400..600
    }

    /**
     * On the first run after this change there is no saved selection. Prefer the default model if
     * it is installed, otherwise any installed model, so an existing install keeps working.
     */
    private fun chooseInitialSelectionIfUnset(ready: Set<String>) {
        synchronized(selectionLock) {
            if (prefs.contains(KEY_SELECTED_MODEL_ID)) return
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
            val encoder = findFileInDir(dir, model.encoderFilename) ?: findFileByKeyword(dir, "encoder")
            val decoder = findFileInDir(dir, model.decoderFilename) ?: findFileByKeyword(dir, "decoder")
            val joiner = findFileInDir(dir, model.joinerFilename) ?: findFileByKeyword(dir, "joiner")
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
     * progress reporting. With [reportSetupProgress], progress is also published on
     * [setupDownloadState] for the first-launch dialog.
     */
    suspend fun downloadModel(
        model: AsrModelConfig = _selectedModel.value,
        reportSetupProgress: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val targetDir = getModelDirectory(model)
        val tempArchiveFile = File(getModelsDirectory(), "${model.id}_temp.archive")
        var speedBytesPerSec = 0L

        fun report(state: ModelDownloadState) {
            _downloadState.value = state
            if (!reportSetupProgress) return
            _setupDownloadState.value = when (state) {
                is ModelDownloadState.Downloading -> DownloadState.Downloading(
                    progress = state.progress,
                    speedBytesPerSec = speedBytesPerSec,
                    bytesDownloaded = state.bytesDownloaded,
                    totalBytes = state.totalBytes
                )
                is ModelDownloadState.Extracting -> DownloadState.Extracting
                is ModelDownloadState.Ready -> DownloadState.Ready
                is ModelDownloadState.Error -> DownloadState.Error(state.errorMsg)
                ModelDownloadState.NotDownloaded -> DownloadState.Idle
            }
        }

        try {
            // Requirement 1: Storage pre-flight check using StatFs (require at least 150MB free)
            val availableMb = getAvailableStorageMb()
            val minRequiredMb = 150L
            if (availableMb < minRequiredMb) {
                val error = "Insufficient storage space: At least 150MB of free space is required for offline models (Available: ${availableMb}MB)."
                report(ModelDownloadState.Error(error))
                NotificationHelper.showErrorNotification(context, "Storage Check Failed", error)
                return@withContext
            }

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
                        var lastSpeedTime = System.currentTimeMillis()
                        var lastSpeedBytes = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            val now = System.currentTimeMillis()
                            if (now - lastSpeedTime >= 350) {
                                speedBytesPerSec = ((totalRead - lastSpeedBytes) * 1000L) / (now - lastSpeedTime)
                                lastSpeedTime = now
                                lastSpeedBytes = totalRead
                            }
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
