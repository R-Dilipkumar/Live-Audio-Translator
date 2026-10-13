package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.example.asr.AsrModelConfig
import com.example.asr.ModelDownloadState
import com.example.asr.ModelManager
import com.example.asr.SpeechRecognizerEngine
import com.example.asr.SpeechValidator
import com.example.data.AppDatabase
import com.example.data.TranscriptEntity
import com.example.overlay.FloatingSubtitleService
import com.example.service.AudioCaptureService
import com.example.translate.LocalTranslatorEngine
import com.example.translate.SubtitleOrderGate
import com.example.translate.SupportedLanguage
import com.example.translate.TranslationPackStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AudioTranslatorViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication()
    val modelManager: ModelManager = AudioCaptureService.getModelManager(context)
    val speechEngine: SpeechRecognizerEngine = AudioCaptureService.getSpeechEngine(context)
    val translatorEngine: LocalTranslatorEngine = AudioCaptureService.getTranslatorEngine(context)
    private val transcriptDao = AppDatabase.getInstance(context).transcriptDao()
    val settingsRepository = com.example.data.SettingsRepository(context)

    // Persistent App Settings Flow
    val appSettings: StateFlow<com.example.data.AppSettings> = settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.example.data.AppSettings())

    // Service capture state
    val isCapturing: StateFlow<Boolean> = AudioCaptureService.isServiceRunning
    val audioLevelDb: StateFlow<Float> = AudioCaptureService.liveAudioDb

    // ASR model state
    val asrDownloadState: StateFlow<ModelDownloadState> = modelManager.downloadState
    val selectedAsrModel: StateFlow<AsrModelConfig> = modelManager.selectedModel
    val activeAsrDownloadId: StateFlow<String?> = modelManager.activeDownloadId
    val availableAsrModels: List<AsrModelConfig> = modelManager.availableModels

    // False once the startup scan has run and the selected model is not on disk.
    // Starts true so the "no model installed" hint does not flash before the scan finishes.
    val isSelectedAsrModelInstalled: StateFlow<Boolean> = combine(
        modelManager.hasScannedModels,
        modelManager.readyModelIds,
        modelManager.selectedModel
    ) { scanned, ready, selected -> !scanned || selected.id in ready }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun initializeSpeechRecognizer() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!modelManager.isSelectedModelReady()) {
                Log.w("AudioTranslatorViewModel", "Cannot initialize speech recognizer: selected model ${modelManager.selectedModel.value.id} is not installed")
                return@launch
            }
            // Single-flight; a no-op if this model is already loaded or a capture is running.
            speechEngine.initEngine()
        }
    }

    // Translation engine state
    val sourceLanguage: StateFlow<SupportedLanguage> = translatorEngine.sourceLanguage
    val targetLanguage: StateFlow<SupportedLanguage> = translatorEngine.targetLanguage
    val translationPackStatus: StateFlow<TranslationPackStatus> = translatorEngine.packStatus
    val availableLanguages: List<SupportedLanguage> = translatorEngine.availableLanguages
    val targetAvailableLanguages: List<SupportedLanguage> = translatorEngine.targetAvailableLanguages
    val downloadedLanguages: StateFlow<Set<String>> = translatorEngine.downloadedLanguages
    val downloadingPacks: StateFlow<Map<String, Int>> = translatorEngine.downloadingPacks
    val lastDetectedLanguage: StateFlow<String?> = translatorEngine.lastDetectedLanguage
    val isTranslationModelMissing: StateFlow<Boolean> = translatorEngine.isModelMissing

    // Offloaded storage and readiness state flows (prevent Choreographer frame drops)
    val availableStorageMb: StateFlow<Long> = modelManager.availableStorageMb
    val readyModelIds: StateFlow<Set<String>> = modelManager.readyModelIds

    fun getAvailableStorageMb(): Long = modelManager.getAvailableStorageMb()

    fun downloadSingleLanguagePack(langCode: String) {
        val availableMb = getAvailableStorageMb()
        if (availableMb < 50L) {
            val error = "Insufficient storage: At least 50MB of free space is required to download this language pack (Available: ${availableMb}MB)."
            showError(error)
            return
        }
        viewModelScope.launch {
            translatorEngine.downloadSingleLanguagePack(langCode)
        }
    }

    fun deleteLanguageModel(langCode: String) {
        viewModelScope.launch {
            translatorEngine.deleteLanguageModel(langCode)
        }
    }

    fun isAsrModelReady(model: AsrModelConfig): Boolean {
        return readyModelIds.value.contains(model.id)
    }

    fun downloadAllModels() {
        val availableMb = getAvailableStorageMb()
        if (availableMb < 150L) {
            val error = "Insufficient storage: At least 150MB of free space is required to download offline models (Available: ${availableMb}MB)."
            showError(error)
            return
        }
        downloadAsrModel(selectedAsrModel.value)
        downloadTranslationModels()
    }

    // Live display (starts clean and empty, avoiding synthetic status items in the live feed)
    private val _currentOriginalSpeech = MutableStateFlow("")
    val currentOriginalSpeech: StateFlow<String> = _currentOriginalSpeech.asStateFlow()

    private val _currentTranslatedSpeech = MutableStateFlow("")
    val currentTranslatedSpeech: StateFlow<String> = _currentTranslatedSpeech.asStateFlow()

    // Translation of the sentence still being spoken (empty once that sentence is final)
    private val _liveDraftTranslation = MutableStateFlow("")
    val liveDraftTranslation: StateFlow<String> = _liveDraftTranslation.asStateFlow()

    val livePartialSpeech: StateFlow<String> = speechEngine.partialTextFlow

    // Floating overlay window state
    val isFloatingOverlayActive: StateFlow<Boolean> = com.example.overlay.FloatingSubtitleService.isOverlayActive
    val floatingSettings: StateFlow<com.example.overlay.OverlaySettings> = com.example.overlay.FloatingSubtitleService.currentSettings

    // DRM silence detection state
    val isDrmSilenceDetected: StateFlow<Boolean> = AudioCaptureService.isDrmSilenceDetected

    // Capture mode toggle (Internal Media vs Microphone)
    private val _isMicMode = MutableStateFlow(false)
    val isMicMode: StateFlow<Boolean> = _isMicMode.asStateFlow()

    // In-app error banner
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Rejects translation results that are older than what is shown, or a partial arriving
    // after the final of the same utterance.
    private val subtitleOrderGate = SubtitleOrderGate()

    // Persisted transcript history from Room (strictly validated to prevent non-speech status items)
    val transcriptHistory: StateFlow<List<TranscriptEntity>> = transcriptDao.getAllTranscripts()
        .map { list ->
            list.filter { item ->
                SpeechValidator.isGenuineSpeech(item.originalText) &&
                SpeechValidator.isGenuineSpeech(item.translatedText)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch { settingsRepository.applyTunedDefaultsOnce() }

        // Hydrate latest translation if service has already processed speech before ViewModel creation
        translatorEngine.latestTranslation.value?.takeIf { subtitleOrderGate.accept(it) }?.let { initial ->
            if (SpeechValidator.isGenuineSpeech(initial.originalText) && speechEngine.isConfirmedAsrEvent(initial.originalText)) {
                _currentOriginalSpeech.value = initial.originalText
            }
            if (SpeechValidator.isGenuineSpeech(initial.translatedText)) {
                _currentTranslatedSpeech.value = initial.translatedText
            }
        }

        // Collect recognized speech from ASR engine and feed to translator if capture service is not active
        viewModelScope.launch {
            var lastPartialDispatchTime = 0L
            val partialDebounceMs = 480L

            speechEngine.utteranceFlow.collect { event ->
                when (event) {
                    is com.example.asr.AsrUtteranceEvent.Partial -> {
                        if (SpeechValidator.isGenuineSpeech(event.text)) {
                            _currentOriginalSpeech.value = event.text
                            if (!isCapturing.value) {
                                val now = System.currentTimeMillis()
                                if (now - lastPartialDispatchTime >= partialDebounceMs) {
                                    lastPartialDispatchTime = now
                                    translatorEngine.translateText(event.text, isFinal = false, utteranceId = event.utteranceId)
                                }
                            }
                        }
                    }
                    is com.example.asr.AsrUtteranceEvent.Final -> {
                        if (SpeechValidator.isGenuineSpeech(event.text) && speechEngine.isConfirmedAsrEvent(event.text)) {
                            _currentOriginalSpeech.value = event.text
                            if (!isCapturing.value) {
                                translatorEngine.translateText(event.text, isFinal = true, utteranceId = event.utteranceId)
                            }
                        }
                    }
                }
            }
        }

        // Collect completed translations for live UI display.
        // Pure speech validation ensures status strings never pollute display states.
        viewModelScope.launch {
            translatorEngine.translationFlow.collect { result ->
                if (SpeechValidator.isGenuineSpeech(result.originalText) && subtitleOrderGate.accept(result)) {
                    _currentOriginalSpeech.value = result.originalText
                    if (SpeechValidator.isGenuineSpeech(result.translatedText)) {
                        _currentTranslatedSpeech.value = result.translatedText
                    }
                    _liveDraftTranslation.value = if (result.isFinal) "" else result.translatedText
                }
            }
        }

        // Collect speech recognizer engine states to show in-app error banners (e.g. uninitialized ASR model)
        viewModelScope.launch {
            speechEngine.engineState.collect { state ->
                when (state) {
                    is com.example.asr.RecognizerState.Error -> {
                        _errorMessage.value = state.message
                    }
                    else -> {
                        if (_errorMessage.value?.contains("ASR") == true) {
                            _errorMessage.value = null
                        }
                    }
                }
            }
        }

        // Collect service-level error messages to show in-app error banners
        viewModelScope.launch {
            com.example.service.AudioCaptureService.serviceErrorMessage.collect { error ->
                if (error != null) {
                    _errorMessage.value = error
                }
            }
        }

        // Keep local engines and floating overlay settings synchronized with persisted AppSettings
        viewModelScope.launch {
            appSettings.collect { settings ->
                speechEngine.silenceEndpointDelay = settings.silenceEndpointDelay
                speechEngine.maxUtteranceWindow = settings.maxUtteranceWindow
                speechEngine.noSpeechRestartDelay = settings.noSpeechRestartDelay
                speechEngine.minSentenceLength = settings.minSentenceLength
                speechEngine.singleCharacterMode = settings.singleCharacterMode
                speechEngine.isAntiFreezeWatchdogEnabled = settings.isAntiFreezeWatchdogEnabled
                translatorEngine.confidenceThreshold = settings.languageIdConfidence

                val currentFloating = FloatingSubtitleService.currentSettings.value
                val updatedFloating = currentFloating.copy(
                    fontSizeSp = settings.fontSizeSp,
                    opacity = settings.overlayOpacity,
                    maxSubtitleLines = settings.maxDisplayLines,
                    maxLines = settings.maxDisplayLines,
                    showOriginal = settings.showOriginalSpeech,
                    lingerTimeSeconds = settings.subtitleLingerTimeSeconds
                )
                FloatingSubtitleService.updateSettings(updatedFloating)
            }
        }
    }

    fun toggleMicMode(enabled: Boolean) {
        if (!isCapturing.value) {
            _isMicMode.value = enabled
        }
    }

    fun startInternalCapture(resultCode: Int, data: Intent) {
        val intent = Intent(context, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_START_INTERNAL_CAPTURE
            putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AudioCaptureService.EXTRA_RESULT_DATA, data)
            putExtra(AudioCaptureService.EXTRA_MEDIA_PROJECTION_DATA, data)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun startMicCapture() {
        val intent = Intent(context, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_START_MIC_CAPTURE
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun switchToMicMode() {
        _isMicMode.value = true
        val intent = Intent(context, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_SWITCH_TO_MIC
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopCapture() {
        val intent = Intent(context, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_STOP_CAPTURE
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun downloadAsrModel(model: AsrModelConfig) {
        val availableMb = modelManager.getAvailableStorageMb()
        if (availableMb < 150L) {
            val error = "Insufficient storage: At least 150MB of free space is required to download offline models (Available: ${availableMb}MB)."
            showError(error)
            return
        }
        viewModelScope.launch {
            modelManager.downloadModel(model)
            // Load it now if it is the selected model
            initializeSpeechRecognizer()
        }
    }

    fun deleteAsrModel(model: AsrModelConfig) {
        viewModelScope.launch {
            modelManager.deleteModel(model)
            speechEngine.onModelDeleted(model.id)
            // deleteModel may have switched the selection to another installed model
            initializeSpeechRecognizer()
        }
    }

    fun selectAsrModel(model: AsrModelConfig) {
        modelManager.selectModel(model)
        // Load the new selection now if it is installed (no-op during capture; it then
        // takes effect on the next start).
        initializeSpeechRecognizer()
    }

    fun downloadTranslationModels() {
        val availableMb = modelManager.getAvailableStorageMb()
        if (availableMb < 150L) {
            val error = "Insufficient storage: At least 150MB of free space is required to download offline translation packs (Available: ${availableMb}MB)."
            showError(error)
            return
        }
        viewModelScope.launch {
            translatorEngine.downloadRequiredModels()
        }
    }

    fun downloadModelPairExplicitly(srcCode: String, tgtCode: String) {
        val availableMb = modelManager.getAvailableStorageMb()
        if (availableMb < 150L) {
            val error = "Insufficient storage: At least 150MB of free space is required to download offline translation packs (Available: ${availableMb}MB)."
            showError(error)
            return
        }
        viewModelScope.launch {
            translatorEngine.downloadModelPairExplicitly(srcCode, tgtCode)
        }
    }

    fun setSourceLanguage(lang: SupportedLanguage) {
        translatorEngine.setSourceLanguage(lang)
    }

    fun setTargetLanguage(lang: SupportedLanguage) {
        translatorEngine.setTargetLanguage(lang)
    }

    fun swapLanguages() {
        translatorEngine.swapLanguages()
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            transcriptDao.clearAll()
        }
    }

    fun deleteHistoryItem(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            transcriptDao.deleteById(id)
        }
    }

    fun clearCurrentDisplay() {
        _currentOriginalSpeech.value = ""
        _currentTranslatedSpeech.value = ""
        translatorEngine.clearRecentTranslations()
    }

    /**
     * Strictly validates speech content and origin before saving to Room database.
     */
    fun saveTranscriptIfValid(entity: TranscriptEntity) {
        if (SpeechValidator.isGenuineSpeech(entity.originalText) &&
            SpeechValidator.isGenuineSpeech(entity.translatedText) &&
            speechEngine.isConfirmedAsrEvent(entity.originalText)
        ) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    transcriptDao.insert(entity)
                } catch (e: Exception) {
                    Log.w("AudioTranslatorVM", "Failed inserting transcript: ${e.message}")
                }
            }
        } else {
            Log.d("AudioTranslatorVM", "Suppressed non-speech transcript from manual save: ${entity.originalText}")
        }
    }

    fun startFloatingOverlay() {
        val intent = Intent(context, com.example.overlay.FloatingSubtitleService::class.java).apply {
            action = com.example.overlay.FloatingSubtitleService.ACTION_START_OVERLAY
        }
        context.startService(intent)
    }

    fun stopFloatingOverlay() {
        val intent = Intent(context, com.example.overlay.FloatingSubtitleService::class.java).apply {
            action = com.example.overlay.FloatingSubtitleService.ACTION_STOP_OVERLAY
        }
        context.startService(intent)
    }

    fun updateFloatingSettings(settings: com.example.overlay.OverlaySettings) {
        com.example.overlay.FloatingSubtitleService.updateSettings(settings)
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    fun showError(message: String) {
        _errorMessage.value = message
    }
}
