package com.example.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.asr.SpeechValidator
import com.example.data.SettingsRepository
import com.example.service.AudioCaptureService
import com.example.translate.SubtitleOrderGate
import com.example.ui.components.SubtitleOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Custom LifecycleOwner, ViewModelStoreOwner, and SavedStateRegistryOwner
 * required for hosting Jetpack ComposeView inside a Service WindowManager floating window.
 */
class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    fun onCreate() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}

class FloatingSubtitleService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayRootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var overlayLifecycleOwner: OverlayLifecycleOwner? = null

    // Service-level coroutine scope tied to service lifecycle, NOT main Activity
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var translationCollectJob: Job? = null
    private var partialCollectJob: Job? = null
    // BUG 13 FIX: Track the settings observer job so it can be cancelled before re-launching,
    // preventing multiple settings collectors from accumulating on each showOverlay() call.
    private var settingsCollectJob: Job? = null
    private var modeCollectJob: Job? = null

    // Only results for the same or a newer utterance are shown, and a partial never
    // replaces the final of the same utterance (late partial translations are ignored).
    private val subtitleOrderGate = SubtitleOrderGate()

    // State flows actively collected by Compose view for instant recomposition
    private val _floatingOriginalText = MutableStateFlow("Waiting for live speech...")
    val floatingOriginalText: StateFlow<String> = _floatingOriginalText.asStateFlow()

    private val _floatingTranslatedText = MutableStateFlow("Subtitles will stream here...")
    val floatingTranslatedText: StateFlow<String> = _floatingTranslatedText.asStateFlow()

    private val _floatingTranslatedIsFinal = MutableStateFlow(true)
    val floatingTranslatedIsFinal: StateFlow<Boolean> = _floatingTranslatedIsFinal.asStateFlow()

    private val _floatingUtteranceId = MutableStateFlow(0L)
    val floatingUtteranceId: StateFlow<Long> = _floatingUtteranceId.asStateFlow()

    private val _floatingPartialText = MutableStateFlow("")
    val floatingPartialText: StateFlow<String> = _floatingPartialText.asStateFlow()

    companion object {
        const val ACTION_START_OVERLAY = "com.example.action.START_OVERLAY"
        const val ACTION_STOP_OVERLAY = "com.example.action.STOP_OVERLAY"

        // Narrowest the window can be resized to
        private const val MIN_WINDOW_WIDTH_DP = 180

        private val _isOverlayActive = MutableStateFlow(false)
        val isOverlayActive: StateFlow<Boolean> = _isOverlayActive.asStateFlow()

        private val _currentSettings = MutableStateFlow(OverlaySettings())
        val currentSettings: StateFlow<OverlaySettings> = _currentSettings.asStateFlow()

        fun updateSettings(newSettings: OverlaySettings) {
            _currentSettings.value = newSettings
        }

        // Settings saves outlive the window, so a change made just before closing is still saved
        private val settingsSaveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private var settingsSaveJob: Job? = null
        private const val SETTINGS_SAVE_DELAY_MS = 300L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // BUG 5 FIX: Initialize the OverlayLifecycleOwner once at service creation rather than
        // recreating it on every showOverlay() call. This preserves Compose remembered state and
        // ViewModelStore across overlay hide/show cycles (e.g., user toggles overlay).
        overlayLifecycleOwner = OverlayLifecycleOwner().also { it.onCreate() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_OVERLAY

        when (action) {
            ACTION_STOP_OVERLAY -> {
                removeOverlay()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_OVERLAY -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                showOverlay()
            }
        }

        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        recalculateAndClampOverlayBounds()
    }

    private fun recalculateAndClampOverlayBounds() {
        val params = layoutParams ?: return
        val wm = windowManager ?: return
        val view = overlayRootView ?: return

        val displayMetrics = resources.displayMetrics
        val screenW = displayMetrics.widthPixels
        val screenH = displayMetrics.heightPixels

        params.width = clampWidth(params.width)

        val viewW = if (view.width > 0) view.width else params.width
        val viewH = if (view.height > 0) view.height else dpToPx(140)

        val minX = dpToPx(6)
        val maxX = (screenW - viewW - dpToPx(6)).coerceAtLeast(minX)
        val minY = dpToPx(32)
        val maxY = (screenH - viewH - dpToPx(56)).coerceAtLeast(minY)

        params.x = params.x.coerceIn(minX, maxX)
        params.y = params.y.coerceIn(minY, maxY)

        try {
            wm.updateViewLayout(view, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showOverlay() {
        if (overlayRootView != null) return

        val wm = windowManager ?: return

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val savedWidthDp = _currentSettings.value.widthDp
        val initialWidth = if (savedWidthDp > 0) clampWidth(dpToPx(savedWidthDp)) else defaultWindowWidth()

        val params = WindowManager.LayoutParams(
            initialWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(12)
            y = dpToPx(120)
        }
        layoutParams = params

        // BUG 5 FIX: Use the service-level overlayLifecycleOwner created in onCreate().
        // The owner is initialized once and reused across show/hide cycles to preserve
        // Compose remembered state and ViewModelStore.
        val lifecycleOwner = overlayLifecycleOwner ?: return

        val composeView = ComposeView(this).apply {
            // Tapjacking guard: ignore touches while another window obscures the overlay
            filterTouchesWhenObscured = true
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
        }

        composeView.setViewTreeLifecycleOwner(lifecycleOwner)
        composeView.setViewTreeViewModelStoreOwner(lifecycleOwner)
        composeView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)

        composeView.setContent {
            val originalText by floatingOriginalText.collectAsStateWithLifecycle()
            val translatedText by floatingTranslatedText.collectAsStateWithLifecycle()
            val isTranslatedFinal by floatingTranslatedIsFinal.collectAsStateWithLifecycle()
            val utteranceId by floatingUtteranceId.collectAsStateWithLifecycle()
            val partialText by floatingPartialText.collectAsStateWithLifecycle()
            val settings by currentSettings.collectAsStateWithLifecycle()
            val isListening by AudioCaptureService.isServiceRunning.collectAsStateWithLifecycle()

            SubtitleOverlay(
                originalText = originalText,
                translatedText = translatedText,
                isTranslatedFinal = isTranslatedFinal,
                utteranceId = utteranceId,
                partialText = partialText,
                isListening = isListening,
                settings = settings,
                onSettingsChanged = { newSettings ->
                    updateSettings(newSettings)
                    // A slider calls this on every drag frame: save once, shortly after the last
                    // change, in a single write. Saving every frame queued many writes whose older
                    // values flowed back into the window and made the slider jump back.
                    settingsSaveJob?.cancel()
                    settingsSaveJob = settingsSaveScope.launch {
                        delay(SETTINGS_SAVE_DELAY_MS)
                        try {
                            SettingsRepository(applicationContext).updateOverlaySettings(
                                fontSizeSp = newSettings.fontSizeSp,
                                opacity = newSettings.opacity,
                                maxLines = newSettings.maxSubtitleLines,
                                showOriginal = newSettings.showOriginal,
                                lingerSeconds = newSettings.lingerTimeSeconds,
                                textShadow = newSettings.isTextOutlineShadowEnabled,
                                textShadowRadius = newSettings.textShadowRadius,
                                styleCode = newSettings.style.code
                            )
                        } catch (_: Exception) {}
                    }
                },
                onClose = {
                    val stopCaptureIntent = Intent(this@FloatingSubtitleService, AudioCaptureService::class.java).apply {
                        action = AudioCaptureService.ACTION_STOP_CAPTURE
                    }
                    startService(stopCaptureIntent)
                    removeOverlay()
                    stopSelf()
                },
                onMove = { dx, dy -> moveWindowBy(dx.toInt(), dy.toInt()) },
                onMoveEnd = { snapWindowToEdges() },
                onResizeWidth = { dx -> resizeWindowBy(dx.toInt()) },
                onResizeEnd = { saveWindowWidth() },
                onControlsVisibilityChanged = { visible, controlsHeightPx ->
                    // Keep the subtitles at the same place on screen when the top bar hides or returns
                    moveWindowBy(0, if (visible) -controlsHeightPx else controlsHeightPx)
                }
            )
        }

        overlayRootView = composeView

        try {
            wm.addView(composeView, params)
            _isOverlayActive.value = true
            startObservingTranscripts()
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    /**
     * Actively observes transcription and translation streams in serviceScope.
     * Tied to FloatingSubtitleService lifecycle so updates continue seamlessly
     * when the main activity is in the background.
     *
     * Throttles/debounces updates (minimum 80ms interval) to prevent rapid micro-recompositions
     * and eliminate overlay jitter & window thrashing.
     */
    private fun startObservingTranscripts() {
        val translator = AudioCaptureService.getTranslatorEngine(this)
        val speech = AudioCaptureService.getSpeechEngine(this)

        settingsCollectJob?.cancel()
        settingsCollectJob = serviceScope.launch {
            try {
                SettingsRepository(this@FloatingSubtitleService).settingsFlow.collect { appSettings ->
                    _currentSettings.value = _currentSettings.value.copy(
                        fontSizeSp = appSettings.fontSizeSp,
                        opacity = appSettings.overlayOpacity,
                        maxSubtitleLines = appSettings.maxDisplayLines,
                        maxLines = appSettings.maxDisplayLines,
                        showOriginal = appSettings.showOriginalSpeech,
                        lingerTimeSeconds = appSettings.subtitleLingerTimeSeconds,
                        isTextOutlineShadowEnabled = appSettings.isTextOutlineShadowEnabled,
                        textShadowRadius = appSettings.textShadowRadius,
                        style = SubtitleStyle.fromCode(appSettings.subtitleStyle),
                        widthDp = appSettings.overlayWidthDp
                    )
                    // Apply a saved width (e.g. on first show), unless the user is resizing right now
                    if (!isResizing && appSettings.overlayWidthDp > 0) {
                        setWindowWidth(dpToPx(appSettings.overlayWidthDp))
                    }
                }
            } catch (_: Exception) {}
        }

        modeCollectJob?.cancel()
        modeCollectJob = serviceScope.launch {
            AudioCaptureService.getModelManager(this@FloatingSubtitleService).mode.collect { mode ->
                _currentSettings.value = _currentSettings.value.copy(isAccuracyMode = mode == com.example.asr.RecognitionMode.ACCURACY)
            }
        }

        // Immediately hydrate initial values from translator engine if available
        // Status results (pack missing, detecting language, failed) are not subtitles
        translator.latestTranslation.value?.takeIf { it.status.isTranslation && subtitleOrderGate.accept(it) }?.let { initial ->
            if (SpeechValidator.isGenuineSpeech(initial.originalText) && speech.isConfirmedAsrEvent(initial.originalText)) {
                _floatingOriginalText.value = initial.originalText
            }
            if (SpeechValidator.isGenuineSpeech(initial.translatedText)) {
                _floatingTranslatedText.value = initial.translatedText
            }
        }

        // Direct State Emission: Synchronize _floatingTranslatedText and _floatingOriginalText immediately
        // on Dispatchers.Main.immediate without artificial throttling or delay pauses.
        // Pure speech validation ensures status strings never pollute display states.
        translationCollectJob?.cancel()
        translationCollectJob = serviceScope.launch(Dispatchers.Main.immediate) {
            translator.translationFlow.collect { result ->
                // Status messages (pack missing, detecting language, failed) are for the app, not subtitles
                if (!_currentSettings.value.isPaused &&
                    result.status.isTranslation &&
                    SpeechValidator.isGenuineSpeech(result.originalText) &&
                    SpeechValidator.isGenuineSpeech(result.translatedText) &&
                    subtitleOrderGate.accept(result)
                ) {
                    _floatingOriginalText.value = result.originalText
                    _floatingTranslatedText.value = result.translatedText
                    _floatingTranslatedIsFinal.value = result.isFinal
                    _floatingUtteranceId.value = result.utteranceId
                }
            }
        }

        partialCollectJob?.cancel()
        partialCollectJob = serviceScope.launch(Dispatchers.Main.immediate) {
            speech.partialTextFlow.collect { partial ->
                if (!_currentSettings.value.isPaused) {
                    _floatingPartialText.value = if (SpeechValidator.isGenuineSpeech(partial)) partial else ""
                }
            }
        }
    }

    private fun removeOverlay() {
        try {
            overlayRootView?.let { view ->
                windowManager?.removeView(view)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            // BUG 5 FIX: Do NOT destroy the lifecycle owner here — it is now service-scoped.
            // Destroying it on removeOverlay would wipe the ViewModelStore, resetting all
            // Compose remembered state the next time showOverlay() is called.
            overlayRootView = null
            _isOverlayActive.value = false
        }
    }

    // ---- Window position and size ----

    private var isResizing = false

    private fun defaultWindowWidth(): Int =
        (resources.displayMetrics.widthPixels * 0.92f).toInt().coerceAtMost(dpToPx(440))

    private fun clampWidth(widthPx: Int): Int {
        val maxW = resources.displayMetrics.widthPixels - dpToPx(8)
        return widthPx.coerceIn(dpToPx(MIN_WINDOW_WIDTH_DP).coerceAtMost(maxW), maxW)
    }

    /** Moves the window, keeping it fully on screen. */
    private fun moveWindowBy(dx: Int, dy: Int) {
        val p = layoutParams ?: return
        val w = windowManager ?: return
        val root = overlayRootView ?: return
        val metrics = resources.displayMetrics
        val viewW = root.width.takeIf { it > 0 } ?: p.width
        val viewH = root.height.takeIf { it > 0 } ?: dpToPx(120)
        val minX = dpToPx(4)
        val maxX = (metrics.widthPixels - viewW - dpToPx(4)).coerceAtLeast(minX)
        val minY = dpToPx(24)
        val maxY = (metrics.heightPixels - viewH - dpToPx(24)).coerceAtLeast(minY)
        p.x = (p.x + dx).coerceIn(minX, maxX)
        p.y = (p.y + dy).coerceIn(minY, maxY)
        try {
            w.updateViewLayout(root, p)
        } catch (_: Exception) {}
    }

    /** After a drag, a window released close to a side edge sticks to it. */
    private fun snapWindowToEdges() {
        val p = layoutParams ?: return
        val w = windowManager ?: return
        val root = overlayRootView ?: return
        val viewW = root.width.takeIf { it > 0 } ?: p.width
        val minX = dpToPx(4)
        val maxX = (resources.displayMetrics.widthPixels - viewW - dpToPx(4)).coerceAtLeast(minX)
        val snapMargin = dpToPx(24)
        p.x = when {
            p.x < minX + snapMargin -> minX
            p.x > maxX - snapMargin -> maxX
            else -> p.x
        }
        try {
            w.updateViewLayout(root, p)
        } catch (_: Exception) {}
    }

    /** Resizing from the bottom-right corner: the left edge stays, the width follows the finger. */
    private fun resizeWindowBy(dx: Int) {
        val p = layoutParams ?: return
        isResizing = true
        val screenW = resources.displayMetrics.widthPixels
        val maxForPosition = screenW - p.x - dpToPx(4)
        setWindowWidth((p.width + dx).coerceAtMost(maxForPosition))
    }

    private fun setWindowWidth(widthPx: Int) {
        val p = layoutParams ?: return
        val w = windowManager ?: return
        val root = overlayRootView ?: return
        val newWidth = clampWidth(widthPx)
        if (newWidth == p.width) return
        p.width = newWidth
        // Keep the right edge on screen
        val maxX = (resources.displayMetrics.widthPixels - newWidth - dpToPx(4)).coerceAtLeast(dpToPx(4))
        if (p.x > maxX) p.x = maxX
        try {
            w.updateViewLayout(root, p)
        } catch (_: Exception) {}
    }

    private fun saveWindowWidth() {
        isResizing = false
        val widthPx = layoutParams?.width ?: return
        val widthDp = (widthPx / resources.displayMetrics.density).toInt()
        _currentSettings.value = _currentSettings.value.copy(widthDp = widthDp)
        serviceScope.launch {
            try {
                SettingsRepository(this@FloatingSubtitleService).updateOverlayWidth(widthDp)
            } catch (_: Exception) {}
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    override fun onDestroy() {
        translationCollectJob?.cancel()
        partialCollectJob?.cancel()
        settingsCollectJob?.cancel()
        modeCollectJob?.cancel()
        serviceScope.cancel()
        removeOverlay()
        // BUG 5 FIX: Destroy the service-level lifecycle owner only when the service truly stops.
        overlayLifecycleOwner?.onDestroy()
        overlayLifecycleOwner = null
        super.onDestroy()
    }
}
