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
     * BUG 11 FIX: maxRetries is now implemented with exponential backoff. Previously the parameter
     * was accepted but silently ignored — download failures were never retried.
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
     * Identifies the language of the provided text via ML Kit Language Identification.
     * Requires a minimum confidence threshold (default 0.55). If below threshold or undetermined,
     * retains and returns the previously identified language rather than falling back.
     */
    suspend fun identifyLanguage(text: String): String = withContext(Dispatchers.IO) {
        val identifier = languageIdentifier ?: return@withContext lastIdentifiedLanguage
        try {
            val possibleLanguages = identifier.identifyPossibleLanguages(text).await()
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
        synchronized(cacheLock) {
            translatorCache[key]?.let { return@withContext it }
        }

        try {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(srcCode)
                .setTargetLanguage(tgtCode)
                .build()
            val translator = Translation.getClient(options)
            // Ensure models downloaded
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            synchronized(cacheLock) {
                translatorCache[key] = translator
            }
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
     * When ML Kit is unavailable or packs are missing, uses the offline fallback dictionary.
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
                    val fallback = fallbackOfflineTranslate(trimmed, detectedCode, tgtCode)
                    emitTranslation(
                        TranslationResult(
                            originalText = trimmed,
                            translatedText = fallback,
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
                    Log.w(TAG, "Translator is not ready yet. Using offline fallback while pack prepares.")
                    val fallback = fallbackOfflineTranslate(trimmed, srcCode, tgtCode)
                    emitTranslation(
                        TranslationResult(
                            originalText = trimmed,
                            translatedText = fallback,
                            sourceLangCode = srcCode,
                            targetLangCode = tgtCode
                        )
                    )
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
                Log.w(TAG, "ML Kit translation failed (${error.localizedMessage}). Engaging offline fallback.")
                val fallback = fallbackOfflineTranslate(text, srcCode, tgtCode)
                scope.launch {
                    emitTranslation(
                        TranslationResult(
                            originalText = text,
                            translatedText = fallback,
                            sourceLangCode = srcCode,
                            targetLangCode = tgtCode,
                            detectedSourceLanguage = detectedLang
                        )
                    )
                }
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

        return clean
    }

    private val translationLock = Any()

    private suspend fun emitTranslation(result: TranslationResult) {
        synchronized(translationLock) {
            val currentList = _recentTranslations.value.toMutableList()
            currentList.add(result)
            if (currentList.size > 20) {
                currentList.removeAt(0)
            }
            _recentTranslations.value = currentList
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
