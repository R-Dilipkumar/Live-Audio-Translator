package com.example.translate

import android.content.Context
import android.util.Log
import com.example.service.NotificationHelper
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    val timestamp: Long = System.currentTimeMillis()
)

sealed class TranslationPackStatus {
    object Checking : TranslationPackStatus()
    object Ready : TranslationPackStatus()
    data class NeedsDownload(val sourceMissing: Boolean, val targetMissing: Boolean) : TranslationPackStatus()
    data class Downloading(val progressPercent: Int) : TranslationPackStatus()
    data class Error(val message: String) : TranslationPackStatus()
}

class LocalTranslatorEngine(private val context: Context) {

    private val TAG = "LocalTranslatorEngine"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val modelManager = RemoteModelManager.getInstance()
    private val languageIdentifier: LanguageIdentifier = LanguageIdentification.getClient()

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

    private val _sourceLanguage = MutableStateFlow(availableLanguages[0]) // Auto-Detect by default
    val sourceLanguage: StateFlow<SupportedLanguage> = _sourceLanguage.asStateFlow()

    private val _targetLanguage = MutableStateFlow(targetAvailableLanguages.first { it.code == TranslateLanguage.ENGLISH })
    val targetLanguage: StateFlow<SupportedLanguage> = _targetLanguage.asStateFlow()

    private val _packStatus = MutableStateFlow<TranslationPackStatus>(TranslationPackStatus.Checking)
    val packStatus: StateFlow<TranslationPackStatus> = _packStatus.asStateFlow()

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
    // Cache translators dynamically for detected languages
    private val translatorCache = mutableMapOf<String, Translator>()

    // Retained previously identified language for confidence threshold fallback
    private var lastIdentifiedLanguage: String = TranslateLanguage.JAPANESE
    // Configurable Language ID Confidence threshold (default 0.55)
    var confidenceThreshold: Float = 0.55f
    // Fallback cache for stable translations
    private var lastStableTranslation: String? = null

    init {
        scope.launch {
            refreshDownloadedLanguages()
            checkAndPrepareTranslator()
        }
    }

    suspend fun refreshDownloadedLanguages() = withContext(Dispatchers.IO) {
        try {
            val models = modelManager.getDownloadedModels(TranslateRemoteModel::class.java).await()
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
     * Checks if the required language pack models are present locally.
     */
    suspend fun checkModelPresence(): Pair<Boolean, Boolean> = withContext(Dispatchers.IO) {
        refreshDownloadedLanguages()
        val currentDownloaded = _downloadedLanguages.value

        val srcIsAuto = _sourceLanguage.value.isAutoDetect
        val srcDownloaded = if (srcIsAuto) true else currentDownloaded.contains(_sourceLanguage.value.code)
        val tgtDownloaded = currentDownloaded.contains(_targetLanguage.value.code)

        Pair(srcDownloaded, tgtDownloaded)
    }

    suspend fun checkAndPrepareTranslator() = withContext(Dispatchers.IO) {
        _packStatus.value = TranslationPackStatus.Checking
        try {
            activeTranslator?.close()
            activeTranslator = null

            val srcLang = _sourceLanguage.value
            val tgtLang = _targetLanguage.value

            val (srcDownloaded, tgtDownloaded) = checkModelPresence()

            if (srcLang.isAutoDetect) {
                // For auto-detect, target must be downloaded
                if (tgtDownloaded) {
                    _packStatus.value = TranslationPackStatus.Ready
                } else {
                    _packStatus.value = TranslationPackStatus.NeedsDownload(
                        sourceMissing = false,
                        targetMissing = true
                    )
                }
            } else {
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(srcLang.code)
                    .setTargetLanguage(tgtLang.code)
                    .build()

                val translator = Translation.getClient(options)

                if (srcDownloaded && tgtDownloaded) {
                    activeTranslator = translator
                    _packStatus.value = TranslationPackStatus.Ready
                } else {
                    _packStatus.value = TranslationPackStatus.NeedsDownload(
                        sourceMissing = !srcDownloaded,
                        targetMissing = !tgtDownloaded
                    )
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
     * Downloads an individual language pack (e.g., from Model Management Screen).
     */
    suspend fun downloadSingleLanguagePack(langCode: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val currentMap = _downloadingPacks.value.toMutableMap()
            currentMap[langCode] = 20
            _downloadingPacks.value = currentMap

            val model = TranslateRemoteModel.Builder(langCode).build()
            val conditions = DownloadConditions.Builder().build()
            
            currentMap[langCode] = 50
            _downloadingPacks.value = currentMap

            modelManager.download(model, conditions).await()

            currentMap[langCode] = 100
            _downloadingPacks.value = currentMap
            delay(300)
            currentMap.remove(langCode)
            _downloadingPacks.value = currentMap

            refreshDownloadedLanguages()
            checkAndPrepareTranslator()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed downloading pack $langCode: ${e.message}")
            val currentMap = _downloadingPacks.value.toMutableMap()
            currentMap.remove(langCode)
            _downloadingPacks.value = currentMap
            NotificationHelper.showErrorNotification(context, "Language Pack Download Failed", "Failed to download $langCode: ${e.localizedMessage}")
            false
        }
    }

    /**
     * Downloads missing models for the current active source & target language pair.
     */
    suspend fun downloadRequiredModels(maxRetries: Int = 3): Boolean = withContext(Dispatchers.IO) {
        _packStatus.value = TranslationPackStatus.Downloading(10)

        val src = _sourceLanguage.value
        val tgt = _targetLanguage.value

        var success = true
        if (!src.isAutoDetect) {
            val srcSuccess = downloadSingleLanguagePack(src.code)
            if (!srcSuccess) success = false
        }
        val tgtSuccess = downloadSingleLanguagePack(tgt.code)
        if (!tgtSuccess) success = false

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
            val model = TranslateRemoteModel.Builder(langCode).build()
            modelManager.deleteDownloadedModel(model).await()
            translatorCache.remove(langCode)
            refreshDownloadedLanguages()
            checkAndPrepareTranslator()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting model $langCode", e)
            false
        }
    }

    /**
     * Identifies the language of the provided text via ML Kit Language Identification.
     * Requires a minimum confidence threshold (default 0.55). If below threshold or undetermined,
     * retains and returns the previously identified language rather than falling back.
     */
    suspend fun identifyLanguage(text: String): String = withContext(Dispatchers.IO) {
        try {
            val possibleLanguages = languageIdentifier.identifyPossibleLanguages(text).await()
            val best = possibleLanguages.maxByOrNull { it.confidence }
            val threshold = confidenceThreshold
            if (best != null && best.languageTag != "und" && best.confidence >= threshold) {
                lastIdentifiedLanguage = best.languageTag
                best.languageTag
            } else {
                Log.d(TAG, "Language confidence below $threshold threshold (best=${best?.languageTag}@${best?.confidence}). Retaining last identified: $lastIdentifiedLanguage")
                lastIdentifiedLanguage
            }
        } catch (e: Exception) {
            Log.w(TAG, "Language identification failed: ${e.message}. Retaining $lastIdentifiedLanguage")
            lastIdentifiedLanguage
        }
    }

    /**
     * Gets or creates a Translator client for a given source and target code.
     */
    private suspend fun getOrCreateTranslator(srcCode: String, tgtCode: String): Translator? = withContext(Dispatchers.IO) {
        val key = "${srcCode}_$tgtCode"
        translatorCache[key]?.let { return@withContext it }

        try {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(srcCode)
                .setTargetLanguage(tgtCode)
                .build()
            val translator = Translation.getClient(options)
            // Ensure models downloaded
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            translatorCache[key] = translator
            refreshDownloadedLanguages()
            translator
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get translator for $key: ${e.message}")
            null
        }
    }

    /**
     * Translates a text string asynchronously and emits into the flow.
     * If source language is Auto-Detect, detects language via ML Kit and routes dynamically.
     */
    fun translateText(originalText: String) {
        val trimmed = originalText.trim()
        if (trimmed.isEmpty()) return

        scope.launch {
            val srcLang = _sourceLanguage.value
            val tgtCode = _targetLanguage.value.code

            if (srcLang.isAutoDetect) {
                // Auto-detect language
                val detectedCode = identifyLanguage(trimmed)
                _lastDetectedLanguage.value = detectedCode
                val detectedName = availableLanguages.find { it.code == detectedCode }?.displayName ?: detectedCode

                if (detectedCode == tgtCode) {
                    emitTranslation(
                        TranslationResult(
                            originalText = trimmed,
                            translatedText = trimmed,
                            sourceLangCode = "auto ($detectedCode)",
                            targetLangCode = tgtCode,
                            detectedSourceLanguage = detectedName
                        )
                    )
                    return@launch
                }

                val translator = getOrCreateTranslator(detectedCode, tgtCode)
                if (translator != null) {
                    performTranslation(translator, trimmed, "auto ($detectedCode)", tgtCode, detectedName)
                } else {
                    emitTranslation(
                        TranslationResult(
                            originalText = trimmed,
                            translatedText = "[Auto-Detect ($detectedName): Downloading pack $detectedCode → $tgtCode...]",
                            sourceLangCode = "auto ($detectedCode)",
                            targetLangCode = tgtCode,
                            detectedSourceLanguage = detectedName
                        )
                    )
                }
            } else {
                val srcCode = srcLang.code
                if (srcCode == tgtCode) {
                    emitTranslation(
                        TranslationResult(
                            originalText = trimmed,
                            translatedText = trimmed,
                            sourceLangCode = srcCode,
                            targetLangCode = tgtCode
                        )
                    )
                    return@launch
                }

                val translator = activeTranslator
                if (translator == null) {
                    Log.w(TAG, "Translator is not ready yet. Queuing or downloading...")
                    val downloaded = downloadRequiredModels(maxRetries = 2)
                    if (downloaded && activeTranslator != null) {
                        performTranslation(activeTranslator!!, trimmed, srcCode, tgtCode, null)
                    } else {
                        emitTranslation(
                            TranslationResult(
                                originalText = trimmed,
                                translatedText = "[Translation Pack Not Downloaded: $srcCode → $tgtCode]",
                                sourceLangCode = srcCode,
                                targetLangCode = tgtCode
                            )
                        )
                    }
                    return@launch
                }

                performTranslation(translator, trimmed, srcCode, tgtCode, null)
            }
        }
    }

    private fun performTranslation(
        translator: Translator,
        text: String,
        srcCode: String,
        tgtCode: String,
        detectedLang: String?
    ) {
        translator.translate(text)
            .addOnSuccessListener { translated ->
                lastStableTranslation = translated
                scope.launch {
                    emitTranslation(
                        TranslationResult(
                            originalText = text,
                            translatedText = translated,
                            sourceLangCode = srcCode,
                            targetLangCode = tgtCode,
                            detectedSourceLanguage = detectedLang
                        )
                    )
                }
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "Translation execution failed", error)
                val msg = "Translation error: ${error.localizedMessage}"
                NotificationHelper.showErrorNotification(context, "Translation Engine Error", msg)
                // Use fallback cache so the overlay retains the last stable translated sentence
                val fallbackText = lastStableTranslation ?: "[Translation in progress…]"
                scope.launch {
                    emitTranslation(
                        TranslationResult(
                            originalText = text,
                            translatedText = fallbackText,
                            sourceLangCode = srcCode,
                            targetLangCode = tgtCode,
                            detectedSourceLanguage = detectedLang
                        )
                    )
                }
            }
    }

    private suspend fun emitTranslation(result: TranslationResult) {
        val currentList = _recentTranslations.value.toMutableList()
        currentList.add(result)
        if (currentList.size > 20) {
            currentList.removeAt(0)
        }
        _recentTranslations.value = currentList
        _latestTranslation.value = result
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
            activeTranslator?.close()
            translatorCache.values.forEach { it.close() }
            translatorCache.clear()
            languageIdentifier.close()
        } catch (_: Exception) {}
        activeTranslator = null
    }
}
