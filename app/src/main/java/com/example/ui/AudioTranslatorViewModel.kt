package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.asr.AsrModelConfig
import com.example.asr.ModelDownloadState
import com.example.asr.ModelManager
import com.example.asr.SpeechRecognizerEngine
import com.example.data.AppDatabase
import com.example.data.TranscriptEntity
import com.example.overlay.FloatingSubtitleService
import com.example.service.AudioCaptureService
import com.example.translate.LocalTranslatorEngine
import com.example.translate.SupportedLanguage
import com.example.translate.TranslationPackStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val availableAsrModels: List<AsrModelConfig> = modelManager.availableModels

    // Translation engine state
    val sourceLanguage: StateFlow<SupportedLanguage> = translatorEngine.sourceLanguage
    val targetLanguage: StateFlow<SupportedLanguage> = translatorEngine.targetLanguage
    val translationPackStatus: StateFlow<TranslationPackStatus> = translatorEngine.packStatus
    val availableLanguages: List<SupportedLanguage> = translatorEngine.availableLanguages
    val targetAvailableLanguages: List<SupportedLanguage> = translatorEngine.targetAvailableLanguages
    val downloadedLanguages: StateFlow<Set<String>> = translatorEngine.downloadedLanguages
    val downloadingPacks: StateFlow<Map<String, Int>> = translatorEngine.downloadingPacks
    val lastDetectedLanguage: StateFlow<String?> = translatorEngine.lastDetectedLanguage

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
        return modelManager.isModelReady(model)
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

    // Live display
    private val _currentOriginalSpeech = MutableStateFlow("Waiting for audio...")
    val currentOriginalSpeech: StateFlow<String> = _currentOriginalSpeech.asStateFlow()

    private val _currentTranslatedSpeech = MutableStateFlow("Translation will appear here...")
    val currentTranslatedSpeech: StateFlow<String> = _currentTranslatedSpeech.asStateFlow()

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

    // Persisted transcript history from Room
    val transcriptHistory: StateFlow<List<TranscriptEntity>> = transcriptDao.getAllTranscripts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Collect recognized speech from ASR engine and feed to translator if capture service is not active
        viewModelScope.launch {
            speechEngine.recognizedTextFlow.collect { text ->
                if (text.isNotBlank()) {
                    _currentOriginalSpeech.value = text
                    if (!isCapturing.value) {
                        translatorEngine.translateText(text)
                    }
                }
            }
        }

        // Collect completed translations for live UI display only.
        // BUG 1 FIX: Room persistence is handled exclusively by AudioCaptureService.translationPersistJob.
        // Having both the service AND the ViewModel insert into Room caused duplicate transcript rows
        // every time a translation was emitted while the service was running.
        viewModelScope.launch {
            translatorEngine.translationFlow.collect { result ->
                _currentOriginalSpeech.value = result.originalText
                _currentTranslatedSpeech.value = result.translatedText
            }
        }

        // Keep local engines and floating overlay settings synchronized with persisted AppSettings
        viewModelScope.launch {
            appSettings.collect { settings ->
                speechEngine.silenceEndpointDelay = settings.silenceEndpointDelay
                speechEngine.maxUtteranceWindow = settings.maxUtteranceWindow
                speechEngine.isAntiFreezeWatchdogEnabled = settings.isAntiFreezeWatchdogEnabled
                translatorEngine.confidenceThreshold = settings.languageIdConfidence

                val currentFloating = FloatingSubtitleService.currentSettings.value
                val updatedFloating = currentFloating.copy(
                    fontSizeSp = settings.fontSizeSp,
                    opacity = settings.overlayOpacity,
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
        }
    }

    fun deleteAsrModel(model: AsrModelConfig) {
        viewModelScope.launch {
            modelManager.deleteModel(model)
        }
    }

    fun selectAsrModel(model: AsrModelConfig) {
        modelManager.selectModel(model)
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
        _currentOriginalSpeech.value = "Waiting for audio..."
        _currentTranslatedSpeech.value = "Translation will appear here..."
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
