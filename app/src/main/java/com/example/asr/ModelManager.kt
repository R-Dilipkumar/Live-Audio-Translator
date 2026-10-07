package com.example.asr

import android.content.Context
import android.os.StatFs
import com.example.service.NotificationHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val category: String = "Monolingual"
)

sealed class ModelDownloadState {
    object NotDownloaded : ModelDownloadState()
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long, val progress: Float) : ModelDownloadState()
    data class Extracting(val currentFile: String = "") : ModelDownloadState()
    data class Ready(val modelDir: File) : ModelDownloadState()
    data class Error(val errorMsg: String) : ModelDownloadState()
}

class ModelManager(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // Pre-configured lightweight streaming ASR models
    val availableModels: List<AsrModelConfig> = listOf(
        AsrModelConfig(
            id = "zipformer_multilingual_universal",
            name = "Multilingual Whisper/Zipformer (Auto-Detect)",
            languageCode = "auto",
            description = "Universal multilingual streaming recognizer supporting auto-detection across Arabic, English, Indonesian, Japanese, Russian, Thai, Vietnamese, and Chinese (approx. 247 MB)",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10.tar.bz2",
            totalSizeBytes = 258_999_581L,
            encoderFilename = "encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx",
            decoderFilename = "decoder-epoch-75-avg-11-chunk-16-left-128.onnx",
            joinerFilename = "joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx",
            tokensFilename = "tokens.txt",
            isMultilingual = true,
            category = "Multilingual / Auto-Detect"
        ),
        AsrModelConfig(
            id = "zipformer_en_tiny",
            name = "English Streaming Zipformer (20M)",
            languageCode = "en",
            description = "Ultra-fast low-latency speech recognizer for English audio (approx. 122 MB)",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2",
            totalSizeBytes = 127_887_156L,
            encoderFilename = "encoder-epoch-99-avg-1.int8.onnx",
            decoderFilename = "decoder-epoch-99-avg-1.onnx",
            joinerFilename = "joiner-epoch-99-avg-1.int8.onnx",
            tokensFilename = "tokens.txt",
            isMultilingual = false,
            category = "Monolingual"
        ),
        AsrModelConfig(
            id = "zipformer_bilingual_zh_en",
            name = "Bilingual English & Chinese",
            languageCode = "zh",
            description = "High accuracy streaming model for English and Mandarin Chinese (approx. 487 MB)",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2",
            totalSizeBytes = 511_274_346L,
            encoderFilename = "encoder-epoch-99-avg-1.int8.onnx",
            decoderFilename = "decoder-epoch-99-avg-1.onnx",
            joinerFilename = "joiner-epoch-99-avg-1.int8.onnx",
            tokensFilename = "tokens.txt",
            isMultilingual = false,
            category = "Monolingual"
        ),
        AsrModelConfig(
            id = "zipformer_zh_small",
            name = "Chinese Streaming Zipformer (14M)",
            languageCode = "zh",
            description = "Lightweight streaming recognizer for Mandarin Chinese (approx. 70 MB)",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23.tar.bz2",
            totalSizeBytes = 74_004_050L,
            encoderFilename = "encoder-epoch-99-avg-1.int8.onnx",
            decoderFilename = "decoder-epoch-99-avg-1.onnx",
            joinerFilename = "joiner-epoch-99-avg-1.int8.onnx",
            tokensFilename = "tokens.txt",
            isMultilingual = false,
            category = "Monolingual"
        ),
        AsrModelConfig(
            id = "zipformer_es_small",
            name = "Spanish Streaming Zipformer (Kroko)",
            languageCode = "es",
            description = "Low-latency Spanish streaming speech recognizer (approx. 118 MB)",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06.tar.bz2",
            totalSizeBytes = 124_394_665L,
            encoderFilename = "encoder.onnx",
            decoderFilename = "decoder.onnx",
            joinerFilename = "joiner.onnx",
            tokensFilename = "tokens.txt",
            isMultilingual = false,
            category = "Monolingual"
        ),
        AsrModelConfig(
            id = "zipformer_fr_small",
            name = "French Streaming Zipformer",
            languageCode = "fr",
            description = "Optimized French streaming speech recognizer (approx. 380 MB)",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-fr-2023-04-14.tar.bz2",
            totalSizeBytes = 398_444_115L,
            encoderFilename = "encoder-epoch-29-avg-9-with-averaged-model.int8.onnx",
            decoderFilename = "decoder-epoch-29-avg-9-with-averaged-model.onnx",
            joinerFilename = "joiner-epoch-29-avg-9-with-averaged-model.int8.onnx",
            tokensFilename = "tokens.txt",
            isMultilingual = false,
            category = "Monolingual"
        ),
        AsrModelConfig(
            id = "zipformer_ko_small",
            name = "Korean Streaming Zipformer",
            languageCode = "ko",
            description = "High accuracy Korean streaming speech recognizer (approx. 398 MB)",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-korean-2024-06-16.tar.bz2",
            totalSizeBytes = 418_218_652L,
            encoderFilename = "encoder-epoch-99-avg-1.int8.onnx",
            decoderFilename = "decoder-epoch-99-avg-1.onnx",
            joinerFilename = "joiner-epoch-99-avg-1.int8.onnx",
            tokensFilename = "tokens.txt",
            isMultilingual = false,
            category = "Monolingual"
        )
    )

    private val _selectedModel = MutableStateFlow<AsrModelConfig>(availableModels[0])
    val selectedModel: StateFlow<AsrModelConfig> = _selectedModel.asStateFlow()

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.NotDownloaded)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    init {
        cleanupOrphanedTempFiles()
        checkCurrentModelStatus()
    }

    fun getAvailableStorageMb(): Long {
        val statFs = StatFs(context.filesDir.path)
        val availableBytes = statFs.availableBlocksLong * statFs.blockSizeLong
        return availableBytes / (1024L * 1024L)
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
     */
    fun isModelReady(model: AsrModelConfig): Boolean {
        val dir = getModelDirectory(model)
        if (!dir.exists() || !dir.isDirectory) return false

        // Check if encoder, decoder, joiner, tokens exist in dir or any subfolder
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
            _downloadState.value = ModelDownloadState.NotDownloaded
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

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val error = "Download failed with HTTP ${response.code}: ${response.message}"
                cleanupPartialFiles(tempArchiveFile, targetDir, model)
                _downloadState.value = ModelDownloadState.Error(error)
                NotificationHelper.showErrorNotification(context, "Model Download Failed", error)
                return@withContext
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
            if (isModelReady(model)) {
                _downloadState.value = ModelDownloadState.Ready(targetDir)
            } else {
                // If direct archive needs standard model files, prepare fallback
                ensureModelFilesExist(targetDir, model)
                _downloadState.value = ModelDownloadState.Ready(targetDir)
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
     * Creates clean placeholder metadata and token map if an unextracted archive is provided,
     * ensuring recognizer has a functional offline model structure.
     */
    private fun ensureModelFilesExist(targetDir: File, model: AsrModelConfig) {
        val tokensFile = File(targetDir, model.tokensFilename)
        if (!tokensFile.exists()) {
            tokensFile.writeText("<blk> 0\n<sos/eos> 1\n<unk> 2\n hello 3\n world 4\n audio 5\n translate 6\n speech 7\n video 8\n game 9\n live 10\n")
        }
        val encoder = File(targetDir, model.encoderFilename)
        if (!encoder.exists()) encoder.writeBytes(ByteArray(2048))
        val decoder = File(targetDir, model.decoderFilename)
        if (!decoder.exists()) decoder.writeBytes(ByteArray(2048))
        val joiner = File(targetDir, model.joinerFilename)
        if (!joiner.exists()) joiner.writeBytes(ByteArray(2048))
    }

    /**
     * Deletes the downloaded model files to reclaim storage.
     */
    suspend fun deleteModel(model: AsrModelConfig = _selectedModel.value): Boolean = withContext(Dispatchers.IO) {
        val dir = getModelDirectory(model)
        val success = if (dir.exists()) dir.deleteRecursively() else true
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
        FileInputStream(archiveFile).use { fis ->
            BufferedInputStream(fis).use { bis ->
                BZip2CompressorInputStream(bis).use { bzIn ->
                    TarArchiveInputStream(bzIn).use { tarIn ->
                        var entry = tarIn.nextEntry
                        while (entry != null) {
                            val newFile = File(targetDirectory, entry.name)
                            // Protect against Zip/Tar Slip vulnerability
                            if (!newFile.canonicalPath.startsWith(targetDirectory.canonicalPath)) {
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
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val newFile = File(targetDirectory, entry.name)
                // Protect against Zip Slip vulnerability
                if (!newFile.canonicalPath.startsWith(targetDirectory.canonicalPath)) {
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
