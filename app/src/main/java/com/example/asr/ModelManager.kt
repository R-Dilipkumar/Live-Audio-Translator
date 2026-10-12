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
    val ramProfile: String = "~50MB RAM"
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
            ramProfile = "💾 ~40MB RAM"
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
            ramProfile = "💾 ~90MB RAM"
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
            ramProfile = "💾 ~38MB RAM"
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
            ramProfile = "💾 ~95MB RAM"
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
            ramProfile = "💾 ~55MB RAM"
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
        const val DEFAULT_SHERPA_DOWNLOAD_URL = "https://huggingface.co/xumo/onnx_models/resolve/main/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2"
    }

    private val _selectedModel = MutableStateFlow<AsrModelConfig>(availableModels[0])
    val selectedModel: StateFlow<AsrModelConfig> = _selectedModel.asStateFlow()

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.NotDownloaded)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    private val _sherpaDownloadState = MutableStateFlow<DownloadState>(
        if (areSherpaFilesPresent()) DownloadState.Ready else DownloadState.Idle
    )
    val sherpaDownloadState: StateFlow<DownloadState> = _sherpaDownloadState.asStateFlow()

    val downloadStateFlow: StateFlow<DownloadState> get() = sherpaDownloadState

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _availableStorageMb = MutableStateFlow(0L)
    val availableStorageMb: StateFlow<Long> = _availableStorageMb.asStateFlow()

    private val _readyModelIds = MutableStateFlow<Set<String>>(emptySet())
    val readyModelIds: StateFlow<Set<String>> = _readyModelIds.asStateFlow()

    @Volatile private var hasInitialScanCompleted = false

    init {
        scope.launch {
            cleanupOrphanedTempFiles()
            refreshAvailableStorage()
            refreshReadyModels()
            checkSherpaModelStatus()
            hasInitialScanCompleted = true
        }
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
            // Purge incomplete stub onnx files (< 50KB) that would trigger native JNI SIGABRT
            val sherpaDir = File(context.filesDir, "models/sherpa-onnx")
            if (sherpaDir.exists() && !areSherpaFilesPresent()) {
                sherpaDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.endsWith(".onnx") && file.length() < 50_000L) {
                        file.delete()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun cleanupPartialFiles(tempFile: File?, targetDir: File?, model: AsrModelConfig) {
        try {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete()
            }
            if (targetDir != null && targetDir.exists() && !isModelReady(model)) {
                targetDir.deleteRecursively()
            }
        } catch (_: Exception) {}
    }

    fun selectModel(model: AsrModelConfig) {
        _selectedModel.value = model
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

    fun getSherpaModelDirectory(): File {
        val dir = File(context.filesDir, "models/sherpa-onnx")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Traverses all subdirectories under targetDir (context.filesDir/models/sherpa-onnx/),
     * locates the 4 core model files (encoder, decoder, joiner, tokens),
     * moves them directly into root targetDir with normalized names:
     * - encoder.int8.onnx
     * - decoder.int8.onnx
     * - joiner.int8.onnx
     * - tokens.txt
     * and deletes leftover empty or nested subfolders.
     */
    fun flattenSherpaModelDirectory(baseDir: File = getSherpaModelDirectory()): Boolean {
        if (!baseDir.exists()) return false

        val allFiles = baseDir.walkTopDown().filter { it.isFile }.toList()

        val encoder = allFiles.firstOrNull { it.name.contains("encoder", ignoreCase = true) && it.name.endsWith(".onnx") }
        val decoder = allFiles.firstOrNull { it.name.contains("decoder", ignoreCase = true) && it.name.endsWith(".onnx") }
        val joiner = allFiles.firstOrNull { it.name.contains("joiner", ignoreCase = true) && it.name.endsWith(".onnx") }
        val tokens = allFiles.firstOrNull { it.name.contains("tokens", ignoreCase = true) && it.name.endsWith(".txt") }

        if (encoder == null || decoder == null || joiner == null || tokens == null) {
            return false
        }

        val destEncoder = File(baseDir, "encoder.int8.onnx")
        val destDecoder = File(baseDir, "decoder.int8.onnx")
        val destJoiner = File(baseDir, "joiner.int8.onnx")
        val destTokens = File(baseDir, "tokens.txt")

        if (encoder.canonicalPath != destEncoder.canonicalPath) {
            val moved = encoder.renameTo(destEncoder)
            if (!moved) {
                encoder.copyTo(destEncoder, overwrite = true)
                encoder.delete()
            }
        }
        if (decoder.canonicalPath != destDecoder.canonicalPath) {
            val moved = decoder.renameTo(destDecoder)
            if (!moved) {
                decoder.copyTo(destDecoder, overwrite = true)
                decoder.delete()
            }
        }
        if (joiner.canonicalPath != destJoiner.canonicalPath) {
            val moved = joiner.renameTo(destJoiner)
            if (!moved) {
                joiner.copyTo(destJoiner, overwrite = true)
                joiner.delete()
            }
        }
        if (tokens.canonicalPath != destTokens.canonicalPath) {
            val moved = tokens.renameTo(destTokens)
            if (!moved) {
                tokens.copyTo(destTokens, overwrite = true)
                tokens.delete()
            }
        }

        // Clean up nested directories leaving only the flat models
        baseDir.listFiles()?.filter { it.isDirectory }?.forEach { it.deleteRecursively() }

        return areSherpaFilesPresent()
    }

    private fun hasUsableModelFile(file: File?, minBytes: Long): Boolean {
        return file != null && file.exists() && file.length() >= minBytes
    }

    /**
     * Checks if the required normalized files exist directly in context.filesDir/models/sherpa-onnx/.
     * The validation is intentionally permissive: valid ONNX files can vary slightly, and overly strict
     * size thresholds cause false negatives even after a successful extraction.
     */
    fun areSherpaFilesPresent(): Boolean {
        val modelDir = File(context.filesDir, "models/sherpa-onnx")
        if (!modelDir.exists() || !modelDir.isDirectory) return false

        val encoder = File(modelDir, "encoder.int8.onnx")
        val decoder = File(modelDir, "decoder.int8.onnx")
        val joiner = File(modelDir, "joiner.int8.onnx")
        val tokens = File(modelDir, "tokens.txt")

        val encoderValid = hasUsableModelFile(encoder, 1_000_000L)
        val decoderValid = hasUsableModelFile(decoder, 100_000L)
        val joinerValid = hasUsableModelFile(joiner, 10_000L)
        val tokensValid = hasUsableModelFile(tokens, 10L)

        if (encoderValid && decoderValid && joinerValid && tokensValid) {
            return true
        }

        // If unflattened nested files exist, attempt a flattening pass.
        val hasNestedFiles = modelDir.walkTopDown().any { file ->
            file.isFile && file.parentFile != modelDir && (file.name.endsWith(".onnx", ignoreCase = true) || file.name.endsWith(".txt", ignoreCase = true))
        }
        if (hasNestedFiles) {
            val allFiles = modelDir.walkTopDown().filter { it.isFile }.toList()
            val enc = allFiles.firstOrNull { it.name.contains("encoder", ignoreCase = true) && it.name.endsWith(".onnx") }
            val dec = allFiles.firstOrNull { it.name.contains("decoder", ignoreCase = true) && it.name.endsWith(".onnx") }
            val joi = allFiles.firstOrNull { it.name.contains("joiner", ignoreCase = true) && it.name.endsWith(".onnx") }
            val tok = allFiles.firstOrNull { it.name.contains("tokens", ignoreCase = true) && it.name.endsWith(".txt") }
            if (enc != null && dec != null && joi != null && tok != null) {
                flattenSherpaModelDirectory(modelDir)
                return hasUsableModelFile(encoder, 1_000_000L) &&
                        hasUsableModelFile(decoder, 100_000L) &&
                        hasUsableModelFile(joiner, 10_000L) &&
                        hasUsableModelFile(tokens, 10L)
            }
        }

        return false
    }

    fun checkSherpaModelStatus() {
        if (areSherpaFilesPresent()) {
            _sherpaDownloadState.value = DownloadState.Ready
        } else if (_sherpaDownloadState.value is DownloadState.Ready) {
            _sherpaDownloadState.value = DownloadState.Idle
        }
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
     * Checks if the currently selected ASR model is installed and ready for inference.
     * If another installed model is detected on disk, automatically selects it and returns true.
     */
    fun isModelReady(): Boolean {
        if (isModelReady(_selectedModel.value)) return true
        val resolved = resolveModelFiles(_selectedModel.value)
        if (resolved != null) {
            _selectedModel.value = resolved.model
            return true
        }
        return false
    }

    /**
     * Checks disk files for the given model. Strictly called on Dispatchers.IO.
     */
    fun checkModelFilesOnDisk(model: AsrModelConfig): Boolean {
        return resolveFilesForModel(model) != null
    }

    /**
     * Resolves model files for a specific model configuration.
     */
    fun resolveFilesForModel(model: AsrModelConfig): ResolvedModelFiles? {
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
     * Asynchronously and thoroughly searches for any installed ASR model across
     * model directories, subdirectories, and app internal storage.
     */
    fun resolveModelFiles(preferredModel: AsrModelConfig = _selectedModel.value): ResolvedModelFiles? {
        // 0. Primary path: check context.filesDir/models/sherpa-onnx
        val sherpaDir = File(context.filesDir, "models/sherpa-onnx")
        if (sherpaDir.exists() && sherpaDir.isDirectory) {
            val enc = File(sherpaDir, "encoder.int8.onnx").let { if (hasUsableModelFile(it, 1_000_000L)) it else findFileByKeyword(sherpaDir, "encoder") }
            val dec = File(sherpaDir, "decoder.int8.onnx").let { if (hasUsableModelFile(it, 100_000L)) it else findFileByKeyword(sherpaDir, "decoder") }
            val joi = File(sherpaDir, "joiner.int8.onnx").let { if (hasUsableModelFile(it, 10_000L)) it else findFileByKeyword(sherpaDir, "joiner") }
            val tok = File(sherpaDir, "tokens.txt").let { if (hasUsableModelFile(it, 10L)) it else findFileByKeyword(sherpaDir, "tokens") }

            if (enc != null && dec != null && joi != null && tok != null &&
                hasUsableModelFile(enc, 1_000_000L) &&
                hasUsableModelFile(dec, 100_000L) &&
                hasUsableModelFile(joi, 10_000L) &&
                hasUsableModelFile(tok, 10L)
            ) {
                return ResolvedModelFiles(preferredModel, enc, dec, joi, tok)
            }
        }

        // 1. Check preferred model directory first
        resolveFilesForModel(preferredModel)?.let { return it }

        // 2. Check all other available models in their respective directories
        for (model in availableModels) {
            if (model.id != preferredModel.id) {
                resolveFilesForModel(model)?.let {
                    _selectedModel.value = model
                    return it
                }
            }
        }

        // 3. Scan the general models directory recursively for any valid set of ONNX model files
        scanDirForAnyValidModel(getModelsDirectory())?.let { return it }

        // 4. Scan internal files directory as fallback
        scanDirForAnyValidModel(context.filesDir)?.let { return it }

        return null
    }

    private fun scanDirForAnyValidModel(rootDir: File): ResolvedModelFiles? {
        if (!rootDir.exists() || !rootDir.isDirectory) return null

        val encoder = findFileByKeyword(rootDir, "encoder")
        val decoder = findFileByKeyword(rootDir, "decoder")
        val joiner = findFileByKeyword(rootDir, "joiner")
        val tokens = findFileByKeyword(rootDir, "tokens")

        if (encoder != null && decoder != null && joiner != null && tokens != null &&
            hasUsableModelFile(encoder, 1_000_000L) &&
            hasUsableModelFile(decoder, 100_000L) &&
            hasUsableModelFile(joiner, 10_000L) &&
            hasUsableModelFile(tokens, 10L)
        ) {
            // Find which model best matches by filename, or fallback to preferred
            val matchingModel = availableModels.firstOrNull { model ->
                model.encoderFilename.equals(encoder.name, ignoreCase = true) ||
                        encoder.name.contains(model.id, ignoreCase = true)
            } ?: _selectedModel.value

            _selectedModel.value = matchingModel
            return ResolvedModelFiles(matchingModel, encoder, decoder, joiner, tokens)
        }
        return null
    }

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
     * Downloads and sets up the selected ASR model with byte-level progress reporting.
     */
    suspend fun downloadModel(model: AsrModelConfig = _selectedModel.value) = withContext(Dispatchers.IO) {
        val targetDir = getModelDirectory(model)
        val tempArchiveFile = File(getModelsDirectory(), "${model.id}_temp.archive")

        try {
            // Requirement 1: Storage pre-flight check using StatFs (require at least 150MB free)
            val availableMb = getAvailableStorageMb()
            val minRequiredMb = 150L
            if (availableMb < minRequiredMb) {
                val error = "Insufficient storage space: At least 150MB of free space is required for offline models (Available: ${availableMb}MB)."
                _downloadState.value = ModelDownloadState.Error(error)
                NotificationHelper.showErrorNotification(context, "Storage Check Failed", error)
                return@withContext
            }

            _downloadState.value = ModelDownloadState.Downloading(0, model.totalSizeBytes, 0f)

            val request = Request.Builder()
                .url(model.downloadUrl)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val error = "Download failed with HTTP ${response.code}: ${response.message}"
                    cleanupPartialFiles(tempArchiveFile, targetDir, model)
                    _downloadState.value = ModelDownloadState.Error(error)
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
                            _downloadState.value = ModelDownloadState.Downloading(totalRead, contentLength, progress)
                        }
                        output.flush()
                    }
                }
            }

            // Extract the downloaded model
            _downloadState.value = ModelDownloadState.Extracting("Preparing model directory...", 0.05f)
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            targetDir.mkdirs()

            val progressCallback: (String, Float) -> Unit = { entryName, prog ->
                _downloadState.value = ModelDownloadState.Extracting(entryName, prog)
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
                _downloadState.value = ModelDownloadState.Ready(targetDir)
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
                _downloadState.value = ModelDownloadState.Error(error)
                NotificationHelper.showErrorNotification(context, "Model Extraction Failed", error)
            }

        } catch (e: CancellationException) {
            // Requirement 2: Clean up partial corrupted files on cancellation
            cleanupPartialFiles(tempArchiveFile, targetDir, model)
            _downloadState.value = ModelDownloadState.NotDownloaded
            throw e
        } catch (e: Exception) {
            // Requirement 2: Clean up partial corrupted files on failure
            cleanupPartialFiles(tempArchiveFile, targetDir, model)
            val error = e.localizedMessage ?: "Unknown model download error"
            _downloadState.value = ModelDownloadState.Error(error)
            NotificationHelper.showErrorNotification(context, "ASR Model Download Error", error)
        } finally {
            if (tempArchiveFile.exists()) {
                tempArchiveFile.delete()
            }
        }
    }

    /**
     * Downloads and sets up the offline Sherpa-ONNX streaming model for the First-Launch flow.
     * Extracts files into context.filesDir/models/sherpa-onnx/ using streaming decompression.
     */
    suspend fun downloadSherpaModel(
        remoteUrl: String = DEFAULT_SHERPA_DOWNLOAD_URL
    ) = withContext(Dispatchers.IO) {
        if (areSherpaFilesPresent()) {
            _sherpaDownloadState.value = DownloadState.Ready
            return@withContext
        }

        val targetDir = getSherpaModelDirectory()
        val tempArchiveFile = File(getModelsDirectory(), "sherpa_onnx_download_temp.archive")

        try {
            val availableMb = refreshAvailableStorage()
            if (availableMb < 100L) {
                val error = "Insufficient storage: At least 100MB of free space is required (Available: ${availableMb}MB)."
                _sherpaDownloadState.value = DownloadState.Error(error)
                return@withContext
            }

            _sherpaDownloadState.value = DownloadState.Downloading(
                progress = 0f,
                speedBytesPerSec = 0L,
                bytesDownloaded = 0L,
                totalBytes = 44_200_000L
            )

            val request = Request.Builder()
                .url(remoteUrl)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val error = "Download failed with HTTP ${response.code}: ${response.message}"
                    if (tempArchiveFile.exists()) tempArchiveFile.delete()
                    _sherpaDownloadState.value = DownloadState.Error(error)
                    return@withContext
                }

                val body = response.body ?: throw IOException("Empty response body")
                val contentLength = if (body.contentLength() > 0) body.contentLength() else 44_200_000L

                body.byteStream().use { input ->
                    FileOutputStream(tempArchiveFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var bytesRead: Int
                        var totalRead = 0L
                        var lastSpeedTime = System.currentTimeMillis()
                        var lastSpeedBytes = 0L
                        var currentSpeed = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead

                            val now = System.currentTimeMillis()
                            val timeDelta = now - lastSpeedTime
                            if (timeDelta >= 350) {
                                val bytesDelta = totalRead - lastSpeedBytes
                                currentSpeed = (bytesDelta * 1000L) / timeDelta
                                lastSpeedTime = now
                                lastSpeedBytes = totalRead
                            }

                            val progress = if (contentLength > 0) (totalRead.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f) else 0f
                            _sherpaDownloadState.value = DownloadState.Downloading(
                                progress = progress,
                                speedBytesPerSec = currentSpeed,
                                bytesDownloaded = totalRead,
                                totalBytes = contentLength
                            )
                        }
                        output.flush()
                    }
                }
            }

            _sherpaDownloadState.value = DownloadState.Extracting
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            targetDir.mkdirs()

            val isZip = isZipFile(tempArchiveFile)
            val isBz2 = isBzip2File(tempArchiveFile) || remoteUrl.endsWith(".tar.bz2") || remoteUrl.endsWith(".bz2")
            if (isZip) {
                unzip(tempArchiveFile, targetDir)
            } else if (isBz2) {
                extractTarBz2(tempArchiveFile, targetDir)
            } else {
                tempArchiveFile.copyTo(File(targetDir, "encoder.int8.onnx"), overwrite = true)
            }

            if (tempArchiveFile.exists()) {
                tempArchiveFile.delete()
            }

            // Recursive file discovery & directory flattening:
            // Traverses all subdirectories under targetDir, finds encoder/decoder/joiner/tokens,
            // moves them directly into root targetDir with normalized names:
            // encoder.int8.onnx, decoder.int8.onnx, joiner.int8.onnx, tokens.txt,
            // and deletes leftover empty nested subfolders.
            flattenSherpaModelDirectory(targetDir)

            if (areSherpaFilesPresent()) {
                _sherpaDownloadState.value = DownloadState.Ready
                refreshReadyModels()
                refreshAvailableStorage()
                checkCurrentModelStatus()
                Log.i(TAG, "Sherpa-ONNX model downloaded, flattened, and verified successfully in ${targetDir.absolutePath}")
            } else {
                val error = "Model archive extracted, but required files (encoder/decoder/joiner/tokens) were not found."
                Log.e(TAG, error)
                _sherpaDownloadState.value = DownloadState.Error(error)
            }

        } catch (e: CancellationException) {
            if (tempArchiveFile.exists()) tempArchiveFile.delete()
            if (targetDir.exists() && !areSherpaFilesPresent()) targetDir.deleteRecursively()
            _sherpaDownloadState.value = DownloadState.Idle
            throw e
        } catch (e: Exception) {
            if (tempArchiveFile.exists()) tempArchiveFile.delete()
            if (targetDir.exists() && !areSherpaFilesPresent()) targetDir.deleteRecursively()
            val error = e.localizedMessage ?: "Failed to download model"
            Log.e(TAG, "Failed downloading Sherpa model: $error", e)
            _sherpaDownloadState.value = DownloadState.Error(error)
        } finally {
            if (tempArchiveFile.exists()) {
                tempArchiveFile.delete()
            }
        }
    }

    /**
     * Deletes the downloaded model files to reclaim storage.
     */
    suspend fun deleteModel(model: AsrModelConfig = _selectedModel.value): Boolean = withContext(Dispatchers.IO) {
        val dir = getModelDirectory(model)
        val success = if (dir.exists()) dir.deleteRecursively() else true
        refreshReadyModels()
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
