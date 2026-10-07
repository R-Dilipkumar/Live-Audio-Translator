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
import com.example.service.AudioCaptureService
import com.example.ui.components.DraggableSubtitleBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var translationCollectJob: Job? = null
    private var partialCollectJob: Job? = null
    private var drmCollectJob: Job? = null

    // State flows actively collected by Compose view for instant recomposition
    private val _floatingOriginalText = MutableStateFlow("Waiting for live speech...")
    val floatingOriginalText: StateFlow<String> = _floatingOriginalText.asStateFlow()

    private val _floatingTranslatedText = MutableStateFlow("Subtitles will stream here...")
    val floatingTranslatedText: StateFlow<String> = _floatingTranslatedText.asStateFlow()

    private val _floatingPartialText = MutableStateFlow("")
    val floatingPartialText: StateFlow<String> = _floatingPartialText.asStateFlow()

    private val _floatingDrmSilence = MutableStateFlow(false)
    val floatingDrmSilence: StateFlow<Boolean> = _floatingDrmSilence.asStateFlow()

    companion object {
        const val ACTION_START_OVERLAY = "com.example.action.START_OVERLAY"
        const val ACTION_STOP_OVERLAY = "com.example.action.STOP_OVERLAY"

        private val _isOverlayActive = MutableStateFlow(false)
        val isOverlayActive: StateFlow<Boolean> = _isOverlayActive.asStateFlow()

        private val _currentSettings = MutableStateFlow(OverlaySettings())
        val currentSettings: StateFlow<OverlaySettings> = _currentSettings.asStateFlow()

        fun updateSettings(newSettings: OverlaySettings) {
            _currentSettings.value = newSettings
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
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

        val maxBoxW = (screenW * 0.90f).toInt().coerceAtMost(dpToPx(520))
        if (params.width > maxBoxW) {
            params.width = maxBoxW
        }

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

        val displayMetrics = resources.displayMetrics
        val initialWidth = (displayMetrics.widthPixels * 0.92f).toInt().coerceAtMost(dpToPx(440))

        val params = WindowManager.LayoutParams(
            initialWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(12)
            y = dpToPx(120)
        }
        layoutParams = params

        // Initialize ComposeView with custom lifecycle controllers
        val lifecycleOwner = OverlayLifecycleOwner()
        lifecycleOwner.onCreate()
        overlayLifecycleOwner = lifecycleOwner

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
        }

        composeView.setViewTreeLifecycleOwner(lifecycleOwner)
        composeView.setViewTreeViewModelStoreOwner(lifecycleOwner)
        composeView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)

        composeView.setContent {
            val originalText by floatingOriginalText.collectAsStateWithLifecycle()
            val translatedText by floatingTranslatedText.collectAsStateWithLifecycle()
            val partialText by floatingPartialText.collectAsStateWithLifecycle()
            val settings by currentSettings.collectAsStateWithLifecycle()
            val isDrm by floatingDrmSilence.collectAsStateWithLifecycle()

            DraggableSubtitleBox(
                originalText = originalText,
                translatedText = translatedText,
                partialText = partialText,
                settings = settings,
                isDrmSilenceDetected = isDrm,
                onSwitchToMic = {
                    val micIntent = Intent(this@FloatingSubtitleService, AudioCaptureService::class.java).apply {
                        action = AudioCaptureService.ACTION_SWITCH_TO_MIC
                    }
                    startService(micIntent)
                },
                onSettingsChanged = { newSettings ->
                    updateSettings(newSettings)
                },
                onClose = {
                    val stopCaptureIntent = Intent(this@FloatingSubtitleService, AudioCaptureService::class.java).apply {
                        action = AudioCaptureService.ACTION_STOP_CAPTURE
                    }
                    startService(stopCaptureIntent)
                    removeOverlay()
                    stopSelf()
                },
                onDragDelta = { dx, dy ->
                    val p = layoutParams ?: return@DraggableSubtitleBox
                    val w = windowManager ?: return@DraggableSubtitleBox
                    val metrics = resources.displayMetrics
                    val screenW = metrics.widthPixels
                    val screenH = metrics.heightPixels

                    val viewW = overlayRootView?.width ?: dpToPx(340)
                    val viewH = overlayRootView?.height ?: dpToPx(140)

                    val minX = dpToPx(6)
                    val maxX = (screenW - viewW - dpToPx(6)).coerceAtLeast(minX)
                    val minY = dpToPx(32)
                    val maxY = (screenH - viewH - dpToPx(56)).coerceAtLeast(minY)

                    p.x = (p.x + dx.toInt()).coerceIn(minX, maxX)
                    p.y = (p.y + dy.toInt()).coerceIn(minY, maxY)
                    try {
                        w.updateViewLayout(overlayRootView, p)
                    } catch (_: Exception) {}
                },
                onDragEnd = {
                    val p = layoutParams ?: return@DraggableSubtitleBox
                    val w = windowManager ?: return@DraggableSubtitleBox
                    val metrics = resources.displayMetrics
                    val screenW = metrics.widthPixels
                    val viewW = overlayRootView?.width ?: dpToPx(340)
                    val minX = dpToPx(6)
                    val maxX = (screenW - viewW - dpToPx(6)).coerceAtLeast(minX)

                    val centerBoxX = p.x + (viewW / 2)
                    p.x = if (centerBoxX < screenW / 2) minX else maxX
                    try {
                        w.updateViewLayout(overlayRootView, p)
                    } catch (_: Exception) {}
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

        var lastTranslationUpdateTime = 0L
        var lastPartialUpdateTime = 0L
        val minUpdateIntervalMs = 80L

        translationCollectJob?.cancel()
        translationCollectJob = serviceScope.launch {
            translator.translationFlow.collect { result ->
                if (!_currentSettings.value.isPaused) {
                    val now = System.currentTimeMillis()
                    val timeSinceLast = now - lastTranslationUpdateTime
                    if (timeSinceLast < minUpdateIntervalMs) {
                        kotlinx.coroutines.delay(minUpdateIntervalMs - timeSinceLast)
                    }
                    lastTranslationUpdateTime = System.currentTimeMillis()

                    _floatingOriginalText.value = result.originalText
                    _floatingTranslatedText.value = result.translatedText

                    // Force redraw and measure in WindowManager hierarchy
                    overlayRootView?.post {
                        overlayRootView?.requestLayout()
                    }
                }
            }
        }

        partialCollectJob?.cancel()
        partialCollectJob = serviceScope.launch {
            speech.partialTextFlow.collect { partial ->
                if (!_currentSettings.value.isPaused) {
                    val now = System.currentTimeMillis()
                    val timeSinceLast = now - lastPartialUpdateTime
                    if (timeSinceLast < minUpdateIntervalMs) {
                        kotlinx.coroutines.delay(minUpdateIntervalMs - timeSinceLast)
                    }
                    lastPartialUpdateTime = System.currentTimeMillis()

                    _floatingPartialText.value = partial
                    overlayRootView?.post {
                        overlayRootView?.requestLayout()
                    }
                }
            }
        }

        drmCollectJob?.cancel()
        drmCollectJob = serviceScope.launch {
            AudioCaptureService.isDrmSilenceDetected.collect { isDrm ->
                _floatingDrmSilence.value = isDrm
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
            overlayLifecycleOwner?.onDestroy()
            overlayLifecycleOwner = null
            overlayRootView = null
            _isOverlayActive.value = false
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    override fun onDestroy() {
        translationCollectJob?.cancel()
        partialCollectJob?.cancel()
        drmCollectJob?.cancel()
        serviceScope.cancel()
        removeOverlay()
        super.onDestroy()
    }
}
