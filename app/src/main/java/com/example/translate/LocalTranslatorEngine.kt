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

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val languageIdentifier: LanguageIdentifier? = try {
        LanguageIdentification.getClient()
    } catch (e: Exception) {
        Log.w(TAG, "LanguageIdentification unavailable: ${e.message}")
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

    // Retained previously identified language for confidence threshold fallback
    private var lastIdentifiedLanguage: String = TranslateLanguage.JAPANESE
    // Configurable Language ID Confidence threshold (minimum 0.85 score before switching active source language)
    var confidenceThreshold: Float = 0.85f
    // Language Hysteresis: Require 2 consecutive high-confidence detections before changing active source language
    private var candidateLanguage: String? = null
    private var candidateConfidenceCount: Int = 0
    // Fallback cache for stable translations
    private var lastStableTranslation: String? = null

    // Sequential queue with draft preemption and epoch tracking
    private data class TranslationTask(
        val text: String,
        val isFinal: Boolean,
        val utteranceId: Long = 0L,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val translationChannel = Channel<TranslationTask>(capacity = Channel.UNLIMITED)
    private val translationMutex = Mutex()
    private val taskLock = Any()
    private var pendingDraftText: String? = null

    init {
        scope.launch {
            refreshDownloadedLanguages()
            checkAndPrepareTranslator()
        }
        startSequentialTranslationWorker()
    }

    private fun startSequentialTranslationWorker() {
        scope.launch(Dispatchers.Default) {
            for (task in translationChannel) {
                // Draft preemption: If this is a draft and a newer draft has already arrived, skip this stale draft
                if (!task.isFinal) {
                    val currentPending = synchronized(taskLock) { pendingDraftText }
                    if (currentPending != null && currentPending != task.text) {
                        Log.d(TAG, "Preempted stale partial draft: \"${task.text}\" for newer draft: \"$currentPending\"")
                        continue
                    }
                }

                // Process translations sequentially with Mutex lock
                translationMutex.withLock {
                    try {
                        processTranslationInternal(task.text, task.isFinal, task.utteranceId)
                    } catch (e: Exception) {
                        Log.w(TAG, "Sequential translation execution error: ${e.message}")
                    } finally {
                        if (!task.isFinal) {
                            synchronized(taskLock) {
                                if (pendingDraftText == task.text) {
                                    pendingDraftText = null
                                }
                            }
                        }
                    }
                }
            }
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
            // BUG 8 FIX: Clear stale fallback so a failure after a language change doesn't
            // display a translation from the previous language pair.
            lastStableTranslation = null
            scope.launch { checkAndPrepareTranslator() }
        }
    }

    fun setTargetLanguage(language: SupportedLanguage) {
        if (_targetLanguage.value != language && !language.isAutoDetect) {
            _targetLanguage.value = language
            // BUG 8 FIX: Clear stale fallback on target language change.
            lastStableTranslation = null
            scope.launch { checkAndPrepareTranslator() }
        }
    }

    fun swapLanguages() {
        val src = _sourceLanguage.value
        val tgt = _targetLanguage.value
        if (!src.isAutoDetect) {
            _sourceLanguage.value = tgt
            _targetLanguage.value = src
            lastStableTranslation = null
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
     * CRITICAL: NEVER triggers downloads. If models are missing, flags state as NeedsDownload without stalling.
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
     * Instantly identifies language script characteristics (Hiragana/Katakana -> Japanese,
     * Hangul -> Korean, Cyrillic -> Russian, Arabic -> Arabic, Han -> Chinese, Thai -> Thai).
     * Prevents short foreign dialogue from incorrectly falling back to English.
     */
    fun detectScriptLanguage(text: String): String? {
        var hasHiraganaOrKatakana = false
        var hasHangul = false
        var hasThai = false
        var hasArabic = false
        var hasCyrillic = false
        var hasHan = false

        for (char in text) {
            val code = char.code
            when {
                (code in 0x3040..0x309F) || (code in 0x30A0..0x30FF) -> hasHiraganaOrKatakana = true
                (code in 0xAC00..0xD7AF) || (code in 0x1100..0x11FF) -> hasHangul = true
                code in 0x0E00..0x0E7F -> hasThai = true
                code in 0x0600..0x06FF -> hasArabic = true
                code in 0x0400..0x04FF -> hasCyrillic = true
                code in 0x4E00..0x9FFF -> hasHan = true
            }
        }

        return when {
            hasHiraganaOrKatakana -> TranslateLanguage.JAPANESE
            hasHangul -> TranslateLanguage.KOREAN
            hasThai -> "th"
            hasArabic -> TranslateLanguage.ARABIC
            hasCyrillic -> TranslateLanguage.RUSSIAN
            hasHan -> TranslateLanguage.CHINESE
            else -> null
        }
    }

    /**
     * Identifies the language of the provided text via script heuristics and ML Kit Language Identification.
     * Enforces language hysteresis: requires confidence >= 0.85 and 2 consecutive detections before switching languages.
     * Prevents false "en" classification of non-Latin languages.
     */
    suspend fun identifyLanguage(text: String): String = withContext(Dispatchers.IO) {
        val scriptDetected = detectScriptLanguage(text)
        if (scriptDetected != null) {
            lastIdentifiedLanguage = scriptDetected
            candidateLanguage = null
            candidateConfidenceCount = 0
            return@withContext scriptDetected
        }

        val identifier = languageIdentifier ?: return@withContext lastIdentifiedLanguage
        try {
            val possibleLanguages = identifier.identifyPossibleLanguages(text).await()
            val best = possibleLanguages.maxByOrNull { it.confidence }
            val threshold = confidenceThreshold // 0.85
            if (best != null && best.languageTag != "und" && best.confidence >= threshold) {
                val detectedTag = best.languageTag
                if (detectedTag == lastIdentifiedLanguage) {
                    // Already active language
                    candidateLanguage = null
                    candidateConfidenceCount = 0
                    lastIdentifiedLanguage
                } else {
                    // Language Hysteresis: Require 2 consecutive high-confidence detections before switching
                    if (candidateLanguage == detectedTag) {
                        candidateConfidenceCount++
                        if (candidateConfidenceCount >= 2) {
                            Log.i(TAG, "Language hysteresis gate confirmed switch to $detectedTag ($candidateConfidenceCount detections @ ${best.confidence})")
                            lastIdentifiedLanguage = detectedTag
                            candidateLanguage = null
                            candidateConfidenceCount = 0
                            lastIdentifiedLanguage
                        } else {
                            Log.d(TAG, "Language candidate $detectedTag seen $candidateConfidenceCount/2 times. Retaining $lastIdentifiedLanguage")
                            lastIdentifiedLanguage
                        }
                    } else {
                        candidateLanguage = detectedTag
                        candidateConfidenceCount = 1
                        Log.d(TAG, "New language candidate $detectedTag (confidence=${best.confidence}). Waiting for 2nd consecutive detection. Retaining $lastIdentifiedLanguage")
                        lastIdentifiedLanguage
                    }
                }
            } else {
                Log.d(TAG, "Language confidence below $threshold threshold (best=${best?.languageTag}@${best?.confidence}). Retaining last identified: $lastIdentifiedLanguage")
                candidateLanguage = null
                candidateConfidenceCount = 0
                lastIdentifiedLanguage
            }
        } catch (e: Exception) {
            Log.w(TAG, "Language identification failed: ${e.message}. Retaining $lastIdentifiedLanguage")
            lastIdentifiedLanguage
        }
    }

    /**
     * Explicitly downloads the translation model pair using ML Kit Translator.downloadModelIfNeeded().
     * RESTRICTED: Callable strictly from explicit user actions inside the "Manage Models" screen.
     * It is NEVER called during live audio processing, streaming callbacks, or active utterance dispatching.
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
     * CRITICAL: downloadModelIfNeeded() is NEVER called here. Only pre-downloaded/cached
     * models are loaded on the fly without network downloads.
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
            // CRITICAL: downloadModelIfNeeded() is NEVER called during live translation
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
     * Translates a text string asynchronously via the sequential translation queue.
     * [isFinal] indicates whether the text is a committed final utterance (true) or an in-progress draft (false).
     * [utteranceId] identifies the utterance epoch to eliminate disappearing or out-of-order subtitles.
     * Drafts are subject to preemption if newer drafts arrive. Final commits are never dropped.
     */
    fun translateText(originalText: String, isFinal: Boolean = true, utteranceId: Long = 0L) {
        val trimmed = originalText.trim()
        if (trimmed.isEmpty()) return

        if (!isFinal) {
            synchronized(taskLock) {
                pendingDraftText = trimmed
            }
        }

        translationChannel.trySend(TranslationTask(trimmed, isFinal, utteranceId))
    }

    private suspend fun processTranslationInternal(trimmed: String, isFinal: Boolean, utteranceId: Long) {
        val srcLang = _sourceLanguage.value
        val tgtCode = _targetLanguage.value.code

        // 1. Determine Source Language (Script detection + Language ID)
        // No Language ID on short Partial Drafts:
        // NEVER run ML Kit Language Identification on streaming partials unless text has at least 25 characters,
        // or it is a committed Final utterance. For short partial drafts, reuse the current active source language.
        val detectedCode = if (srcLang.isAutoDetect) {
            val script = detectScriptLanguage(trimmed)
            if (script != null) {
                _lastDetectedLanguage.value = script
                script
            } else if (isFinal || trimmed.length >= 25) {
                val idResult = identifyLanguage(trimmed)
                _lastDetectedLanguage.value = idResult
                idResult
            } else {
                // Short partial draft: use active retained language without triggering ML Kit LangID
                lastIdentifiedLanguage
            }
        } else {
            srcLang.code
        }

        val detectedName = availableLanguages.find { it.code == detectedCode }?.displayName ?: detectedCode
        val srcCodeForEmission = if (srcLang.isAutoDetect) "auto ($detectedCode)" else detectedCode

        // 2. Target Language Passthrough & Mirroring check:
        // If the identified source language matches the user-selected target language (e.g. both English),
        // completely bypass ML Kit and emit the original transcript directly.
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

        // 3. Pre-Flight Model Cache Validation via RemoteModelManager:
        // Before attempting translation:
        // Check whether the models are already downloaded on-device using RemoteModelManager.getInstance().isModelDownloaded(model).
        // Both the source language pack and target language pack (plus English if pivoting) must be confirmed as downloaded on disk.
        // Fallback Behavior when Models are Missing:
        // If any required language model is NOT downloaded, DO NOT trigger a download.
        // Gracefully bypass ML Kit and emit the original transcript directly to the UI state flow without stalling, suspending, or dropping audio frames.
        val areModelsReady = areRequiredModelsDownloaded(detectedCode, tgtCode)
        if (!areModelsReady) {
            _isModelMissing.value = true
            Log.d(TAG, "Required models not downloaded for pair (${detectedCode} -> ${tgtCode}). Bypassing ML Kit and emitting original transcript without blocking.")
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
        var translatorToUse = synchronized(cacheLock) { translatorCache[key] }

        if (translatorToUse == null) {
            translatorToUse = getOrCreateTranslator(detectedCode, tgtCode)
        }

        if (translatorToUse != null) {
            performTranslation(translatorToUse, trimmed, srcCodeForEmission, tgtCode, detectedName, isFinal, utteranceId)
        } else {
            // If client creation failed, emit original transcript directly without stalling
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
            lastStableTranslation = translated
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
            Log.w(TAG, "ML Kit translation failed (${error.localizedMessage}). Bypassing and emitting original transcript directly.")
            emitTranslation(
                TranslationResult(
                    originalText = text,
                    translatedText = text,
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
     * Offline fallback dictionary that translates common conversational, anime, gaming,
     * and media phrases when Google Play services, Firebase, or downloaded packs are absent.
     */
    fun fallbackOfflineTranslate(text: String, srcCode: String, tgtCode: String): String {
        val clean = text.trim()
        val lower = clean.lowercase()

        val jaToEn = mapOf(
            "こんにちは" to "Hello",
            "ありがとう" to "Thank you",
            "ありがとうございます" to "Thank you very much",
            "助けて" to "Help me!",
            "助けてください" to "Please help me!",
            "行くぞ" to "Let's go!",
            "行こう" to "Let's go",
            "勝った" to "We won!",
            "負けた" to "We lost",
            "敵" to "Enemy",
            "敵を発見" to "Enemy spotted",
            "大丈夫" to "I'm okay",
            "大丈夫ですか" to "Are you okay?",
            "何" to "What?",
            "待って" to "Wait!",
            "すごい" to "Amazing!",
            "はい" to "Yes",
            "いいえ" to "No",
            "本当に" to "Really?",
            "おはよう" to "Good morning",
            "こんばんは" to "Good evening",
            "さようなら" to "Goodbye",
            "危ない" to "Look out!"
        )

        val zhToEn = mapOf(
            "你好" to "Hello",
            "谢谢" to "Thank you",
            "救命" to "Help me!",
            "走吧" to "Let's go",
            "冲啊" to "Let's go!",
            "我们赢了" to "We won!",
            "敌人" to "Enemy",
            "小心" to "Be careful",
            "没关系" to "It's okay",
            "好的" to "Understood / Okay",
            "再见" to "Goodbye",
            "太棒了" to "Awesome!",
            "等等" to "Wait"
        )

        val esToEn = mapOf(
            "hola" to "Hello",
            "gracias" to "Thank you",
            "muchas gracias" to "Thank you very much",
            "ayuda" to "Help!",
            "vamos" to "Let's go!",
            "ganamos" to "We won!",
            "enemigo" to "Enemy",
            "cuidado" to "Careful!",
            "esta bien" to "It's okay",
            "si" to "Yes",
            "no" to "No",
            "adios" to "Goodbye",
            "amigo" to "Friend",
            "buen trabajo" to "Good job!"
        )

        val enToEs = mapOf(
            "hello" to "Hola",
            "thank you" to "Gracias",
            "help" to "Ayuda",
            "let's go" to "Vamos",
            "enemy" to "Enemigo",
            "careful" to "Cuidado",
            "yes" to "Sí",
            "no" to "No",
            "goodbye" to "Adiós"
        )

        val enToJa = mapOf(
            "hello" to "こんにちは",
            "thank you" to "ありがとう",
            "help" to "助けて",
            "let's go" to "行くぞ",
            "enemy" to "敵",
            "yes" to "はい",
            "no" to "いいえ",
            "goodbye" to "さようなら"
        )

        val enToZh = mapOf(
            "hello" to "你好",
            "thank you" to "谢谢",
            "help" to "救命",
            "let's go" to "走吧",
            "enemy" to "敌人",
            "yes" to "是的",
            "no" to "不",
            "goodbye" to "再见"
        )

        val pureSrc = srcCode.substringBefore(" ").removePrefix("auto (").removeSuffix(")")
        val pureTgt = tgtCode.substringBefore(" ")

        val matched = when {
            pureSrc == "ja" && pureTgt == "en" -> jaToEn[clean] ?: jaToEn[lower]
            pureSrc == "zh" && pureTgt == "en" -> zhToEn[clean] ?: zhToEn[lower]
            pureSrc == "es" && pureTgt == "en" -> esToEn[lower]
            pureSrc == "en" && pureTgt == "es" -> enToEs[lower]
            pureSrc == "en" && pureTgt == "ja" -> enToJa[lower]
            pureSrc == "en" && pureTgt == "zh" -> enToZh[lower]
            else -> null
        }

        if (matched != null) return matched

        val activeDict = when {
            pureSrc == "ja" && pureTgt == "en" -> jaToEn
            pureSrc == "zh" && pureTgt == "en" -> zhToEn
            pureSrc == "es" && pureTgt == "en" -> esToEn
            pureSrc == "en" && pureTgt == "es" -> enToEs
            pureSrc == "en" && pureTgt == "ja" -> enToJa
            pureSrc == "en" && pureTgt == "zh" -> enToZh
            else -> emptyMap()
        }

        for ((key, value) in activeDict) {
            if (clean.contains(key, ignoreCase = true)) {
                return value
            }
        }

        val srcPure = pureSrc.lowercase()
        val tgtPure = pureTgt.lowercase()
        if (srcPure == tgtPure) {
            return clean
        }

        val srcName = srcPure.uppercase()
        val tgtName = tgtPure.uppercase()
        return "[Downloading ${srcName}-${tgtName} dictionary...] $clean"
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
            languageIdentifier?.close()
        } catch (_: Exception) {}
    }
}
