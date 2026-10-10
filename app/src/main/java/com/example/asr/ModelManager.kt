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
    data class Extracting(val currentFile: String = "") : ModelDownloadState()
    data class Ready(val modelDir: File) : ModelDownloadState()
    data class Error(val errorMsg: String) : ModelDownloadState()
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
            name = "Chinese & English Bilingual Cinematic Zipformer",
            languageCode = "zh",
            description = "High-accuracy dual-language recognizer for code-switching and bilingual dialogue (approx. 115 MB)",
            downloadUrl = "https://huggingface.co/xumo/onnx_models/resolve/main/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2",
            totalSizeBytes = 120_500_000L,
            encoderFilename = "encoder-epoch-99-avg-1.int8.onnx",
            decoderFilename = "decoder-epoch-99-avg-1.onnx",
            joinerFilename = "joiner-epoch-99-avg-1.int8.onnx",
            tokensFilename = "tokens.txt",
            isMultilingual = false,
            category = "Chinese",
            tier = ModelTier.CINEMATIC,
            latencyProfile = "🎯 ~200ms",
            ramProfile = "💾 ~95MB RAM"
        )
    )

    private val _selectedModel = MutableStateFlow<AsrModelConfig>(availableModels[0])
    val selectedModel: StateFlow<AsrModelConfig> = _selectedModel.asStateFlow()

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.NotDownloaded)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

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
            (availableBytes / (1024L * 1024L)).also { _availableStorageMb.value = it }
        } catch (_: Exception) {
            500L
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

    /**
     * Checks if all required model files exist and are non-empty.
     * Uses in-memory StateFlow cache to avoid blocking the main UI thread during Compose recomposition.
     */
    fun isModelReady(model: AsrModelConfig): Boolean {
        val ready = _readyModelIds.value
        if (ready.contains(model.id)) return true
        if (!hasInitialScanCompleted) {
            val onDisk = checkModelFilesOnDisk(model)
            if (onDisk) {
                _readyModelIds.value = ready + model.id
            }
            return onDisk
        }
        return false
    }

    /**
     * Checks disk files for the given model. Strictly called on Dispatchers.IO.
     */
    fun checkModelFilesOnDisk(model: AsrModelConfig): Boolean {
        val dir = getModelDirectory(model)
        if (!dir.exists() || !dir.isDirectory) return false

        val encoder = findFileInDir(dir, model.encoderFilename) ?: findFileByKeyword(dir, "encoder")
        val decoder = findFileInDir(dir, model.decoderFilename) ?: findFileByKeyword(dir, "decoder")
        val joiner = findFileInDir(dir, model.joinerFilename) ?: findFileByKeyword(dir, "joiner")
        val tokens = findFileInDir(dir, model.tokensFilename) ?: findFileByKeyword(dir, "tokens.txt")

        return encoder != null && encoder.length() > 1000L &&
                decoder != null && decoder.length() > 1000L &&
                joiner != null && joiner.length() > 1000L &&
                tokens != null && tokens.length() > 10L
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
            _downloadState.value = ModelDownloadState.Extracting("Preparing model directory...")
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            targetDir.mkdirs()

            // Try unpacking based on archive format (tar.bz2, zip, or direct file)
            val isZip = isZipFile(tempArchiveFile)
            val isBz2 = isBzip2File(tempArchiveFile) || model.downloadUrl.endsWith(".tar.bz2") || model.downloadUrl.endsWith(".bz2")
            if (isZip) {
                unzip(tempArchiveFile, targetDir)
            } else if (isBz2) {
                extractTarBz2(tempArchiveFile, targetDir)
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

    private fun extractTarBz2(archiveFile: File, targetDirectory: File) {
        val targetCanonicalDirPath = targetDirectory.canonicalPath + File.separator
        FileInputStream(archiveFile).use { fis ->
            BufferedInputStream(fis).use { bis ->
                BZip2CompressorInputStream(bis).use { bzIn ->
                    TarArchiveInputStream(bzIn).use { tarIn ->
                        var entry = tarIn.nextEntry
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
                                FileOutputStream(newFile).use { fos ->
                                    tarIn.copyTo(fos)
                                }
                            }
                            entry = tarIn.nextEntry
                        }
                    }
                }
            }
        }
    }

    private fun unzip(zipFile: File, targetDirectory: File) {
        val targetCanonicalDirPath = targetDirectory.canonicalPath + File.separator
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
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
                    FileOutputStream(newFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
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
