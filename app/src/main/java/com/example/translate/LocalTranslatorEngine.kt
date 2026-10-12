package com.example.translate

import android.content.Context
import android.util.Log
import com.example.service.NotificationHelper
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext


data class SupportedLanguage(
    val code: String,
    val displayName: String,
    val flagEmoji: String,
    val isAutoDetect: Boolean = false
)

data class TranslationResult(
    val originalText: String,
    val translatedText: String,
    val sourceLangCode: String,
    val targetLangCode: String,
    val detectedSourceLanguage: String? = null,
    val isFinal: Boolean = true,
    val utteranceId: Long = 0L,
    val timestamp: Long = System.currentTimeMillis()
)

sealed class TranslationPackStatus {
    object Checking : TranslationPackStatus()
    object Ready : TranslationPackStatus()
    data class NeedsDownload(
        val sourceMissing: Boolean,
        val targetMissing: Boolean,
        val englishPivotMissing: Boolean = false
    ) : TranslationPackStatus()
    data class Downloading(val progressPercent: Int) : TranslationPackStatus()
    data class Error(val message: String) : TranslationPackStatus()
}

class LocalTranslatorEngine(private val context: Context) {

    private val TAG = "LocalTranslatorEngine"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val modelManager = try {
        RemoteModelManager.getInstance()
    } catch (e: Exception) {
        Log.w(TAG, "RemoteModelManager unavailable: ${e.message}")
        null
    }
    val autoDetectLanguage = SupportedLanguage("auto", "Auto-Detect", "🌐", isAutoDetect = true)

    val availableLanguages = listOf(
        autoDetectLanguage,
        SupportedLanguage(TranslateLanguage.ENGLISH, "English", "🇺🇸"),
        SupportedLanguage(TranslateLanguage.SPANISH, "Spanish", "🇪🇸"),
        SupportedLanguage(TranslateLanguage.CHINESE, "Chinese", "🇨🇳"),
        SupportedLanguage(TranslateLanguage.JAPANESE, "Japanese", "🇯🇵"),
        SupportedLanguage(TranslateLanguage.KOREAN, "Korean", "🇰🇷"),
        SupportedLanguage(TranslateLanguage.FRENCH, "French", "🇫🇷"),
        SupportedLanguage(TranslateLanguage.GERMAN, "German", "🇩🇪"),
        SupportedLanguage(TranslateLanguage.HINDI, "Hindi", "🇮🇳"),
        SupportedLanguage(TranslateLanguage.ITALIAN, "Italian", "🇮🇹"),
        SupportedLanguage(TranslateLanguage.PORTUGUESE, "Portuguese", "🇧🇷"),
        SupportedLanguage(TranslateLanguage.RUSSIAN, "Russian", "🇷🇺"),
        SupportedLanguage(TranslateLanguage.ARABIC, "Arabic", "🇸🇦")
    )

    // Language list excluding Auto-Detect for target language selection
    val targetAvailableLanguages = availableLanguages.filter { !it.isAutoDetect }

    var confidenceThreshold: Float = 0.55f

    private val _sourceLanguage = MutableStateFlow(availableLanguages[0]) // Auto-Detect by default
    val sourceLanguage: StateFlow<SupportedLanguage> = _sourceLanguage.asStateFlow()

    private val _targetLanguage = MutableStateFlow(targetAvailableLanguages.first { it.code == TranslateLanguage.ENGLISH })
    val targetLanguage: StateFlow<SupportedLanguage> = _targetLanguage.asStateFlow()

    private val _packStatus = MutableStateFlow<TranslationPackStatus>(TranslationPackStatus.Checking)
    val packStatus: StateFlow<TranslationPackStatus> = _packStatus.asStateFlow()

    // Lightweight state flag indicating if any required language model is currently missing on disk
    private val _isModelMissing = MutableStateFlow(false)
    val isModelMissing: StateFlow<Boolean> = _isModelMissing.asStateFlow()

    private val _lastDetectedLanguage = MutableStateFlow<String?>(null)
    val lastDetectedLanguage: StateFlow<String?> = _lastDetectedLanguage.asStateFlow()

    // Downloaded language codes for individual pack management
    private val _downloadedLanguages = MutableStateFlow<Set<String>>(emptySet())
    val downloadedLanguages: StateFlow<Set<String>> = _downloadedLanguages.asStateFlow()

    // Map of currently downloading language packs and their progress percentage
    private val _downloadingPacks = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloadingPacks: StateFlow<Map<String, Int>> = _downloadingPacks.asStateFlow()

    // Emits completed translation pairs in real-time
    private val _translationFlow = MutableSharedFlow<TranslationResult>(extraBufferCapacity = 64)
    val translationFlow: SharedFlow<TranslationResult> = _translationFlow.asSharedFlow()

    // Rolling buffer of recent completed sentence translations (retains up to 20 sentences)
    private val _recentTranslations = MutableStateFlow<List<TranslationResult>>(emptyList())
    val recentTranslations: StateFlow<List<TranslationResult>> = _recentTranslations.asStateFlow()

    // Latest translation result as a StateFlow for immediate state sync
    private val _latestTranslation = MutableStateFlow<TranslationResult?>(null)
    val latestTranslation: StateFlow<TranslationResult?> = _latestTranslation.asStateFlow()

    private var activeTranslator: Translator? = null
    private val maxCacheSize = 3
    private val cacheLock = Any()
    // LRU cache bounded to max 3 translators (each holds ~30-50MB native RAM).
    // Automatically invokes .close() on evicted instances to prevent native memory leaks and OutOfMemoryError.
    private val translatorCache = object : LinkedHashMap<String, Translator>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Translator>?): Boolean {
            if (size > maxCacheSize) {
                try {
                    eldest?.value?.close()
                    Log.d(TAG, "Evicted translator for ${eldest?.key} from LRU cache to reclaim native RAM")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed closing evicted translator: ${e.message}")
                }
                return true
            }
            return false
        }
    }

    private val activeDownloadKeys = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    init {
        scope.launch {
            refreshDownloadedLanguages()
            checkAndPrepareTranslator()
        }
    }

    suspend fun refreshDownloadedLanguages() = withContext(Dispatchers.IO) {
        try {
            val mm = modelManager ?: return@withContext
            val models = mm.getDownloadedModels(TranslateRemoteModel::class.java).await()
            val codes = models.map { it.language }.toSet()
            _downloadedLanguages.value = codes
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load downloaded models: ${e.message}")
        }
    }

    fun setSourceLanguage(language: SupportedLanguage) {
        if (_sourceLanguage.value != language) {
            _sourceLanguage.value = language
            scope.launch { checkAndPrepareTranslator() }
        }
    }

    fun setTargetLanguage(language: SupportedLanguage) {
        if (_targetLanguage.value != language && !language.isAutoDetect) {
            _targetLanguage.value = language
            scope.launch { checkAndPrepareTranslator() }
        }
    }

    fun swapLanguages() {
        val src = _sourceLanguage.value
        val tgt = _targetLanguage.value
        if (!src.isAutoDetect) {
            _sourceLanguage.value = tgt
            _targetLanguage.value = src
            scope.launch { checkAndPrepareTranslator() }
        }
    }

    /**
     * Identifies the full set of language models required on disk for translating between [srcCode] and [tgtCode].
     * In ML Kit, non-English to non-English translations pivot through English, requiring the English model as well.
     */
    fun getRequiredLanguageCodes(srcCode: String, tgtCode: String): Set<String> {
        val codes = mutableSetOf(srcCode, tgtCode)
        if (srcCode != TranslateLanguage.ENGLISH && tgtCode != TranslateLanguage.ENGLISH) {
            codes.add(TranslateLanguage.ENGLISH)
        }
        return codes
    }

    /**
     * Validates whether a specific language pack is downloaded on disk using RemoteModelManager.isModelDownloaded().
     */
    suspend fun isModelDownloadedOnDisk(langCode: String): Boolean = withContext(Dispatchers.IO) {
        val mm = modelManager ?: return@withContext _downloadedLanguages.value.contains(langCode)
        try {
            val model = TranslateRemoteModel.Builder(langCode).build()
            val isDownloaded = mm.isModelDownloaded(model).await()
            if (isDownloaded && !_downloadedLanguages.value.contains(langCode)) {
                _downloadedLanguages.value = _downloadedLanguages.value + langCode
            }
            isDownloaded
        } catch (e: Exception) {
            Log.w(TAG, "isModelDownloaded check failed for $langCode: ${e.message}")
            _downloadedLanguages.value.contains(langCode)
        }
    }

    /**
     * Pre-flight model cache validation via RemoteModelManager:
     * Verifies that the source, target, and (if pivoting) English models are all confirmed downloaded on disk.
     */
    suspend fun areRequiredModelsDownloaded(srcCode: String, tgtCode: String): Boolean = withContext(Dispatchers.IO) {
        val needed = getRequiredLanguageCodes(srcCode, tgtCode)
        for (code in needed) {
            if (!isModelDownloadedOnDisk(code)) {
                return@withContext false
            }
        }
        true
    }

    /**
     * Checks if the required language pack models are present locally on disk via RemoteModelManager.
     */
    suspend fun checkModelPresence(): Pair<Boolean, Boolean> = withContext(Dispatchers.IO) {
        refreshDownloadedLanguages()
        val srcIsAuto = _sourceLanguage.value.isAutoDetect
        val srcDownloaded = if (srcIsAuto) true else isModelDownloadedOnDisk(_sourceLanguage.value.code)
        val tgtDownloaded = isModelDownloadedOnDisk(_targetLanguage.value.code)

        Pair(srcDownloaded, tgtDownloaded)
    }

    /**
     * Pre-flight check and client setup when switching active source/target language pairs.
     */
    suspend fun checkAndPrepareTranslator() = withContext(Dispatchers.IO) {
        _packStatus.value = TranslationPackStatus.Checking
        try {
            activeTranslator?.close()
            activeTranslator = null

            val srcLang = _sourceLanguage.value
            val tgtLang = _targetLanguage.value

            refreshDownloadedLanguages()

            if (srcLang.isAutoDetect) {
                val tgtDownloaded = isModelDownloadedOnDisk(tgtLang.code)
                val needsPivot = tgtLang.code != TranslateLanguage.ENGLISH
                val englishDownloaded = if (needsPivot) isModelDownloadedOnDisk(TranslateLanguage.ENGLISH) else true

                if (tgtDownloaded && englishDownloaded) {
                    _packStatus.value = TranslationPackStatus.Ready
                    _isModelMissing.value = false
                } else {
                    _packStatus.value = TranslationPackStatus.NeedsDownload(
                        sourceMissing = false,
                        targetMissing = !tgtDownloaded,
                        englishPivotMissing = !englishDownloaded
                    )
                    _isModelMissing.value = true
                }
            } else {
                val srcDownloaded = isModelDownloadedOnDisk(srcLang.code)
                val tgtDownloaded = isModelDownloadedOnDisk(tgtLang.code)
                val needsPivot = srcLang.code != TranslateLanguage.ENGLISH && tgtLang.code != TranslateLanguage.ENGLISH
                val englishDownloaded = if (needsPivot) isModelDownloadedOnDisk(TranslateLanguage.ENGLISH) else true

                if (srcDownloaded && tgtDownloaded && englishDownloaded) {
                    val options = TranslatorOptions.Builder()
                        .setSourceLanguage(srcLang.code)
                        .setTargetLanguage(tgtLang.code)
                        .build()
                    // Client created directly WITHOUT calling downloadModelIfNeeded()
                    activeTranslator = Translation.getClient(options)
                    _packStatus.value = TranslationPackStatus.Ready
                    _isModelMissing.value = false
                } else {
                    _packStatus.value = TranslationPackStatus.NeedsDownload(
                        sourceMissing = !srcDownloaded,
                        targetMissing = !tgtDownloaded,
                        englishPivotMissing = !englishDownloaded
                    )
                    _isModelMissing.value = true
                }
            }
        } catch (e: Exception) {
            val error = "Language model check error: ${e.localizedMessage}"
            Log.e(TAG, error, e)
            _packStatus.value = TranslationPackStatus.Error(error)
            NotificationHelper.showErrorNotification(context, "Translation Engine Error", error)
        }
    }

    /**
     * Downloads an individual language pack upon explicit user action (e.g., from Model Management Screen).
     */
    suspend fun downloadSingleLanguagePack(langCode: String, maxRetries: Int = 2): Boolean = withContext(Dispatchers.IO) {
        val mm = modelManager
        if (mm == null) {
            Log.w(TAG, "Cannot download language pack: RemoteModelManager is null. Offline fallback will be used.")
            return@withContext false
        }
        val currentMap = _downloadingPacks.value.toMutableMap()
        currentMap[langCode] = 20
        _downloadingPacks.value = currentMap

        val model = TranslateRemoteModel.Builder(langCode).build()
        val conditions = DownloadConditions.Builder().build()

        var lastException: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                currentMap[langCode] = 20 + (attempt * 15)
                _downloadingPacks.value = currentMap.toMap()

                mm.download(model, conditions).await()

                currentMap[langCode] = 100
                _downloadingPacks.value = currentMap.toMap()
                delay(300)
                currentMap.remove(langCode)
                _downloadingPacks.value = currentMap.toMap()

                refreshDownloadedLanguages()
                checkAndPrepareTranslator()
                return@withContext true
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Download attempt ${attempt + 1}/$maxRetries for $langCode failed: ${e.message}")
                if (attempt < maxRetries - 1) {
                    delay(1500L * (attempt + 1)) // exponential backoff: 1.5s, 3s, ...
                }
            }
        }

        Log.e(TAG, "All $maxRetries download attempts failed for $langCode", lastException)
        currentMap.remove(langCode)
        _downloadingPacks.value = currentMap.toMap()
        NotificationHelper.showErrorNotification(
            context,
            "Language Pack Download Failed",
            "Failed to download $langCode after $maxRetries attempts: ${lastException?.localizedMessage}"
        )
        false
    }

    /**
     * Downloads missing models for the current active source & target language pair upon explicit user action.
     */
    suspend fun downloadRequiredModels(maxRetries: Int = 3): Boolean = withContext(Dispatchers.IO) {
        _packStatus.value = TranslationPackStatus.Downloading(10)

        val src = _sourceLanguage.value
        val tgt = _targetLanguage.value

        var success = true
        val neededCodes = getRequiredLanguageCodes(if (src.isAutoDetect) TranslateLanguage.ENGLISH else src.code, tgt.code)
        for (code in neededCodes) {
            val codeSuccess = downloadSingleLanguagePack(code, maxRetries)
            if (!codeSuccess) success = false
        }

        if (success) {
            checkAndPrepareTranslator()
        }
        success
    }

    /**
     * Deletes a language model to free space.
     */
    suspend fun deleteLanguageModel(langCode: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val mm = modelManager ?: return@withContext false
            val model = TranslateRemoteModel.Builder(langCode).build()
            mm.deleteDownloadedModel(model).await()
            synchronized(cacheLock) {
                val iterator = translatorCache.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (entry.key.startsWith("${langCode}_") || entry.key.endsWith("_$langCode")) {
                        try {
                            entry.value.close()
                        } catch (_: Exception) {}
                        iterator.remove()
                    }
                }
            }
            refreshDownloadedLanguages()
            checkAndPrepareTranslator()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting model $langCode", e)
            false
        }
    }

    /**
     * Downloads the translation model pair for explicit user action, typically from the model management screen.
     */
    suspend fun downloadModelPairExplicitly(srcCode: String, tgtCode: String, conditions: DownloadConditions = DownloadConditions.Builder().build()): Boolean = withContext(Dispatchers.IO) {
        val key = "${srcCode}_$tgtCode"
        if (activeDownloadKeys.contains(key)) return@withContext false
        activeDownloadKeys.add(key)

        try {
            Log.i(TAG, "Explicit user download initiated for model pair: $key")
            val currentMap = _downloadingPacks.value.toMutableMap()
            currentMap[srcCode] = 20
            currentMap[tgtCode] = 20
            _downloadingPacks.value = currentMap

            val options = TranslatorOptions.Builder()
                .setSourceLanguage(srcCode)
                .setTargetLanguage(tgtCode)
                .build()
            val translator = Translation.getClient(options)
            translator.downloadModelIfNeeded(conditions).await()

            synchronized(cacheLock) {
                translatorCache[key] = translator
            }
            if (_sourceLanguage.value.code == srcCode && _targetLanguage.value.code == tgtCode) {
                activeTranslator = translator
                _packStatus.value = TranslationPackStatus.Ready
                _isModelMissing.value = false
            }
            refreshDownloadedLanguages()
            Log.i(TAG, "Explicit ML Kit model download completed for: $key")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Explicit model download failed for $key: ${e.message}")
            false
        } finally {
            activeDownloadKeys.remove(key)
            val currentMap = _downloadingPacks.value.toMutableMap()
            currentMap.remove(srcCode)
            currentMap.remove(tgtCode)
            _downloadingPacks.value = currentMap
        }
    }

    /**
     * Gets or creates a Translator client for a given source and target code.
     */
    private suspend fun getOrCreateTranslator(srcCode: String, tgtCode: String): Translator? = withContext(Dispatchers.IO) {
        val key = "${srcCode}_$tgtCode"
        synchronized(cacheLock) {
            translatorCache[key]?.let { return@withContext it }
        }

        try {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(srcCode)
                .setTargetLanguage(tgtCode)
                .build()
            val translator = Translation.getClient(options)
            synchronized(cacheLock) {
                translatorCache[key] = translator
            }
            translator
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get translator for $key: ${e.message}")
            null
        }
    }

    /**
     * Submits a translation request using the minimal ML Kit path required by the app.
     */
    fun translateText(originalText: String, isFinal: Boolean = true, utteranceId: Long = 0L) {
        val trimmed = originalText.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            processTranslationInternal(trimmed, isFinal, utteranceId)
        }
    }

    private suspend fun processTranslationInternal(trimmed: String, isFinal: Boolean, utteranceId: Long) {
        val srcLang = _sourceLanguage.value
        val tgtCode = _targetLanguage.value.code
        val detectedCode = if (srcLang.isAutoDetect) {
            _lastDetectedLanguage.value ?: TranslateLanguage.ENGLISH
        } else {
            srcLang.code
        }
        _lastDetectedLanguage.value = detectedCode

        val detectedName = availableLanguages.find { it.code == detectedCode }?.displayName ?: detectedCode
        val srcCodeForEmission = if (srcLang.isAutoDetect) "auto ($detectedCode)" else detectedCode

        if (detectedCode == tgtCode) {
            emitTranslation(
                TranslationResult(
                    originalText = trimmed,
                    translatedText = trimmed,
                    sourceLangCode = srcCodeForEmission,
                    targetLangCode = tgtCode,
                    detectedSourceLanguage = detectedName,
                    isFinal = isFinal,
                    utteranceId = utteranceId
                )
            )
            return
        }

        if (!areRequiredModelsDownloaded(detectedCode, tgtCode)) {
            _isModelMissing.value = true
            emitTranslation(
                TranslationResult(
                    originalText = trimmed,
                    translatedText = trimmed,
                    sourceLangCode = srcCodeForEmission,
                    targetLangCode = tgtCode,
                    detectedSourceLanguage = detectedName,
                    isFinal = isFinal,
                    utteranceId = utteranceId
                )
            )
            return
        }

        _isModelMissing.value = false
        val key = "${detectedCode}_$tgtCode"
        val translatorToUse = synchronized(cacheLock) { translatorCache[key] } ?: getOrCreateTranslator(detectedCode, tgtCode)

        if (translatorToUse != null) {
            performTranslation(translatorToUse, trimmed, srcCodeForEmission, tgtCode, detectedName, isFinal, utteranceId)
        } else {
            emitTranslation(
                TranslationResult(
                    originalText = trimmed,
                    translatedText = trimmed,
                    sourceLangCode = srcCodeForEmission,
                    targetLangCode = tgtCode,
                    detectedSourceLanguage = detectedName,
                    isFinal = isFinal,
                    utteranceId = utteranceId
                )
            )
        }
    }

    private suspend fun performTranslation(
        translator: Translator,
        text: String,
        srcCode: String,
        tgtCode: String,
        detectedLang: String?,
        isFinal: Boolean,
        utteranceId: Long
    ) {
        try {
            val translated = translator.translate(text).await()
            emitTranslation(
                TranslationResult(
                    originalText = text,
                    translatedText = translated,
                    sourceLangCode = srcCode,
                    targetLangCode = tgtCode,
                    detectedSourceLanguage = detectedLang,
                    isFinal = isFinal,
                    utteranceId = utteranceId
                )
            )
        } catch (error: Exception) {
            Log.w(TAG, "ML Kit translation failed (${error.localizedMessage}), using offline fallback")
            val fallbackText = fallbackOfflineTranslate(text, srcCode, tgtCode)
            emitTranslation(
                TranslationResult(
                    originalText = text,
                    translatedText = fallbackText,
                    sourceLangCode = srcCode,
                    targetLangCode = tgtCode,
                    detectedSourceLanguage = detectedLang,
                    isFinal = isFinal,
                    utteranceId = utteranceId
                )
            )
        }
    }

    /**
     * Self-contained offline dictionary fallback when Google Play Services / ML Kit
     * are unavailable on device.
     */
    fun fallbackOfflineTranslate(text: String, sourceLang: String, targetLang: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val lower = trimmed.lowercase()

        val jaToEn = mapOf(
            "こんにちは" to "Hello",
            "ありがとう" to "Thank you",
            "ありがとうございます" to "Thank you very much",
            "さようなら" to "Goodbye",
            "はい" to "Yes",
            "いいえ" to "No",
            "おはよう" to "Good morning",
            "こんばんは" to "Good evening"
        )
        val esToEn = mapOf(
            "gracias" to "Thank you",
            "muchas gracias" to "Thank you very much",
            "hola" to "Hello",
            "adiós" to "Goodbye",
            "adios" to "Goodbye",
            "si" to "Yes",
            "sí" to "Yes",
            "no" to "No",
            "buenos días" to "Good morning",
            "buenas noches" to "Good night"
        )
        val frToEn = mapOf(
            "bonjour" to "Hello",
            "merci" to "Thank you",
            "au revoir" to "Goodbye",
            "oui" to "Yes",
            "non" to "No",
            "salut" to "Hi"
        )
        val deToEn = mapOf(
            "hallo" to "Hello",
            "danke" to "Thank you",
            "auf wiedersehen" to "Goodbye",
            "ja" to "Yes",
            "nein" to "No",
            "guten tag" to "Good day"
        )
        val zhToEn = mapOf(
            "你好" to "Hello",
            "谢谢" to "Thank you",
            "再见" to "Goodbye"
        )

        val srcCode = sourceLang.lowercase().take(2)
        val tgtCode = targetLang.lowercase().take(2)

        if (srcCode == tgtCode) return trimmed

        val match = when (srcCode) {
            "ja" -> jaToEn[trimmed] ?: jaToEn[lower]
            "es" -> esToEn[trimmed] ?: esToEn[lower]
            "fr" -> frToEn[trimmed] ?: frToEn[lower]
            "de" -> deToEn[trimmed] ?: deToEn[lower]
            "zh" -> zhToEn[trimmed] ?: zhToEn[lower]
            else -> null
        }

        if (match != null && tgtCode == "en") {
            return match
        }

        val enToOthers = mapOf(
            "hello" to mapOf("es" to "Hola", "ja" to "こんにちは", "fr" to "Bonjour", "de" to "Hallo", "zh" to "你好"),
            "thank you" to mapOf("es" to "Gracias", "ja" to "ありがとう", "fr" to "Merci", "de" to "Danke", "zh" to "谢谢"),
            "goodbye" to mapOf("es" to "Adiós", "ja" to "さようなら", "fr" to "Au revoir", "de" to "Auf Wiedersehen", "zh" to "再见")
        )
        if (srcCode == "en") {
            val tgtMap = enToOthers[lower]
            if (tgtMap != null && tgtMap.containsKey(tgtCode)) {
                return tgtMap[tgtCode] ?: trimmed
            }
        }

        return trimmed
    }

    private val translationLock = Any()

    private suspend fun emitTranslation(result: TranslationResult) {
        synchronized(translationLock) {
            if (result.isFinal) {
                val currentList = _recentTranslations.value.toMutableList()
                currentList.add(result)
                if (currentList.size > 20) {
                    currentList.removeAt(0)
                }
                _recentTranslations.value = currentList
            }
            _latestTranslation.value = result
        }
        _translationFlow.emit(result)
    }

    /**
     * Returns a rolling concatenated string of the most recent translated sentences
     * limited to [maxLines] (1, 2, or 3 lines).
     */
    fun getRollingTranslatedText(maxLines: Int): String {
        val list = _recentTranslations.value
        if (list.isEmpty()) return ""
        return list.takeLast(maxLines.coerceIn(1, 3)).joinToString("\n") { it.translatedText }
    }

    /**
     * Returns a rolling concatenated string of the most recent original sentences
     * limited to [maxLines] (1, 2, or 3 lines).
     */
    fun getRollingOriginalText(maxLines: Int): String {
        val list = _recentTranslations.value
        if (list.isEmpty()) return ""
        return list.takeLast(maxLines.coerceIn(1, 3)).joinToString("\n") { it.originalText }
    }

    fun clearRecentTranslations() {
        _recentTranslations.value = emptyList()
        _latestTranslation.value = null
    }

    fun release() {
        try {
            scope.cancel()
            activeTranslator?.close()
            activeTranslator = null
            synchronized(cacheLock) {
                translatorCache.values.forEach { 
                    try { it.close() } catch (_: Exception) {}
                }
                translatorCache.clear()
            }
        } catch (_: Exception) {}
    }
}
