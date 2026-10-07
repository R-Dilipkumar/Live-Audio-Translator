package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.example.audio.AudioUtils
import com.example.asr.ModelManager
import com.example.asr.SpeechRecognizerEngine
import com.example.audio.VoiceIsolationProcessor
import com.example.data.AppDatabase
import com.example.data.SettingsRepository
import com.example.data.TranscriptEntity
import com.example.translate.LocalTranslatorEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AudioCaptureService : Service() {

    private val TAG = "AudioCaptureService"
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var captureJob: Job? = null
    private var recognitionForwardJob: Job? = null
    private var translationPersistJob: Job? = null

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var isCapturing = false
    private var wakeLock: PowerManager.WakeLock? = null

    // Audio Pre-processing: Voice isolation (IIR Biquad 150Hz - 3500Hz) & Noise Gate
    private val voiceIsolationProcessor = VoiceIsolationProcessor()
    @Volatile private var isVoiceIsolationActive = true
    @Volatile private var noiseGateLevel = 0.015f
    private var settingsObserverJob: Job? = null

    companion object {
        const val ACTION_START_INTERNAL_CAPTURE = "com.example.action.START_INTERNAL_CAPTURE"
        const val ACTION_START_MIC_CAPTURE = "com.example.action.START_MIC_CAPTURE"
        const val ACTION_SWITCH_TO_MIC = "com.example.action.SWITCH_TO_MIC"
        const val ACTION_STOP_CAPTURE = "com.example.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_MEDIA_PROJECTION_DATA = "extra_media_projection_data"

        // Global state for UI to observe
        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _liveAudioDb = MutableStateFlow(0f)
        val liveAudioDb: StateFlow<Float> = _liveAudioDb.asStateFlow()

        // DRM / continuous absolute silence detection state
        private val _isDrmSilenceDetected = MutableStateFlow(false)
        val isDrmSilenceDetected: StateFlow<Boolean> = _isDrmSilenceDetected.asStateFlow()

        // BUG 2 FIX: All shared singleton fields must be @Volatile to prevent instruction-reordering
        // NPE under concurrent access (e.g., Quick Settings tile + main app starting simultaneously).
        // Pattern mirrors AppDatabase.getInstance() which already does this correctly.
        @Volatile private var sharedModelManager: ModelManager? = null
        @Volatile private var sharedSpeechEngine: SpeechRecognizerEngine? = null
        @Volatile private var sharedTranslatorEngine: LocalTranslatorEngine? = null

        fun getModelManager(context: Context): ModelManager {
            return sharedModelManager ?: synchronized(this) {
                sharedModelManager ?: ModelManager(context.applicationContext).also {
                    sharedModelManager = it
                }
            }
        }

        fun getSpeechEngine(context: Context): SpeechRecognizerEngine {
            return sharedSpeechEngine ?: synchronized(this) {
                sharedSpeechEngine ?: SpeechRecognizerEngine(
                    context.applicationContext,
                    getModelManager(context)
                ).also {
                    sharedSpeechEngine = it
                }
            }
        }

        fun getTranslatorEngine(context: Context): LocalTranslatorEngine {
            return sharedTranslatorEngine ?: synchronized(this) {
                sharedTranslatorEngine ?: LocalTranslatorEngine(context.applicationContext).also {
                    sharedTranslatorEngine = it
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannels(this)

        val settingsRepo = SettingsRepository(this)
        val speechEngine = getSpeechEngine(this)
        val translatorEngine = getTranslatorEngine(this)

        settingsObserverJob?.cancel()
        settingsObserverJob = serviceScope.launch {
            settingsRepo.settingsFlow.collect { settings ->
                isVoiceIsolationActive = settings.isVoiceIsolationEnabled
                noiseGateLevel = settings.noiseGateThreshold

                speechEngine.silenceEndpointDelay = settings.silenceEndpointDelay
                speechEngine.maxUtteranceWindow = settings.maxUtteranceWindow
                speechEngine.isAntiFreezeWatchdogEnabled = settings.isAntiFreezeWatchdogEnabled

                translatorEngine.confidenceThreshold = settings.languageIdConfidence
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_STOP_CAPTURE -> {
                Log.d(TAG, "Stop capture requested via intent")
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START_INTERNAL_CAPTURE -> {
                // Safeguard Android 14+: startForeground MUST be called BEFORE acquiring MediaProjection
                startForegroundWithMediaProjection()

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, android.app.Activity.RESULT_OK)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_MEDIA_PROJECTION_DATA, Intent::class.java)
                        ?: intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_MEDIA_PROJECTION_DATA)
                        ?: @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultData != null) {
                    startInternalPlaybackCapture(resultCode, resultData)
                } else {
                    Log.e(TAG, "Invalid MediaProjection extras in intent")
                    NotificationHelper.showErrorNotification(
                        this,
                        "Capture Permission Error",
                        "Missing MediaProjection token to capture internal audio."
                    )
                    // BUG 9 FIX: Remove the foreground notification before stopping to avoid
                    // a notification flash when resultData is null
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                    stopSelf()
                }
            }

            ACTION_START_MIC_CAPTURE -> {
                // BUG 6 FIX: Use FOREGROUND_SERVICE_TYPE_MICROPHONE for mic capture,
                // NOT FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION (throws SecurityException on Android 14+)
                startForegroundForMic()
                _isDrmSilenceDetected.value = false
                startMicrophoneCapture()
            }

            ACTION_SWITCH_TO_MIC -> {
                Log.i(TAG, "Switching to Microphone capture mode on user request")
                _isDrmSilenceDetected.value = false
                // Stop current recording loop
                isCapturing = false
                captureJob?.cancel()
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (_: Exception) {}
                audioRecord = null
                try {
                    mediaProjection?.stop()
                } catch (_: Exception) {}
                mediaProjection = null

                // Start mic capture
                startMicrophoneCapture()
            }
        }

        return START_STICKY
    }

    private fun startForegroundWithMediaProjection() {
        val notification = NotificationHelper.buildCaptureNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.NOTIFICATION_CAPTURE_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_CAPTURE_ID, notification)
        }
        _isServiceRunning.value = true

        // Requirement 2: Acquire PARTIAL_WAKE_LOCK to prevent battery optimizer from killing ASR during gaming/video sessions
        acquireWakeLock()
    }

    /**
     * BUG 6 FIX: Mic capture must declare FOREGROUND_SERVICE_TYPE_MICROPHONE, NOT
     * FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION. Android 14+ enforces strict type matching
     * and throws SecurityException when the declared type doesn't match actual usage.
     */
    private fun startForegroundForMic() {
        val notification = NotificationHelper.buildCaptureNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.NOTIFICATION_CAPTURE_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_CAPTURE_ID, notification)
        }
        _isServiceRunning.value = true
        acquireWakeLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "LiveSub::AudioCaptureWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                    acquire(24 * 60 * 60 * 1000L) // 24-hour safeguard timeout
                    Log.i(TAG, "PARTIAL_WAKE_LOCK acquired for background audio capture & ASR")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to acquire PARTIAL_WAKE_LOCK: ${e.message}")
            }
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.i(TAG, "PARTIAL_WAKE_LOCK released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WakeLock: ${e.message}")
        } finally {
            wakeLock = null
        }
    }

    private fun startInternalPlaybackCapture(resultCode: Int, resultData: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            NotificationHelper.showErrorNotification(
                this,
                "Unsupported Android Version",
                "Internal audio playback capture requires Android 10 (API 29) or higher."
            )
            stopSelf()
            return
        }

        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            if (projectionManager == null) {
                NotificationHelper.showErrorNotification(this, "Capture Error", "MediaProjection service not available.")
                stopSelf()
                return
            }

            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
            if (mediaProjection == null) {
                NotificationHelper.showErrorNotification(this, "Capture Denied", "System denied access to screen/audio capture.")
                stopSelf()
                return
            }

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    Log.w(TAG, "MediaProjection session terminated by user or system")
                    stopCapture()
                    stopSelf()
                }
            }, null)

            // Setup AudioPlaybackCaptureConfiguration with USAGE_MEDIA, USAGE_GAME, USAGE_UNKNOWN
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            // Try standard sample rates (48000 Hz, fallback to 44100 Hz, fallback to 16000 Hz)
            val sampleRates = intArrayOf(48000, 44100, 16000)
            var selectedRecord: AudioRecord? = null
            var selectedRate = 48000
            val channelConfig = AudioFormat.CHANNEL_IN_STEREO
            val encoding = AudioFormat.ENCODING_PCM_16BIT

            for (rate in sampleRates) {
                val minBuf = AudioRecord.getMinBufferSize(rate, channelConfig, encoding)
                if (minBuf > 0) {
                    val bufferSize = (minBuf * 2).coerceAtLeast(4096)
                    try {
                        val record = AudioRecord.Builder()
                            .setAudioPlaybackCaptureConfig(captureConfig)
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setEncoding(encoding)
                                    .setSampleRate(rate)
                                    .setChannelMask(channelConfig)
                                    .build()
                            )
                            .setBufferSizeInBytes(bufferSize)
                            .build()

                        if (record.state == AudioRecord.STATE_INITIALIZED) {
                            selectedRecord = record
                            selectedRate = rate
                            Log.i(TAG, "AudioRecord successfully initialized at $rate Hz, buffer: $bufferSize")
                            break
                        } else {
                            record.release()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed creating AudioRecord for rate $rate: ${e.message}")
                    }
                }
            }

            if (selectedRecord == null || selectedRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Failed to initialize AudioRecord with playback capture config")
                NotificationHelper.showErrorNotification(
                    this,
                    "Audio Capture Blocked",
                    "Could not initialize internal audio capture. Target app may have FLAG_SECURE or disallow playback capture."
                )
                stopSelf()
                return
            }

            audioRecord = selectedRecord
            startAudioReadingLoop(selectedRecord, selectedRate, isStereo = true)

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting MediaProjection", e)
            NotificationHelper.showErrorNotification(
                this,
                "Security Restriction",
                "MediaProjection was rejected by the system: ${e.localizedMessage}"
            )
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Exception initializing internal capture", e)
            NotificationHelper.showErrorNotification(
                this,
                "Internal Capture Error",
                "Failed to capture internal audio: ${e.localizedMessage}"
            )
            stopSelf()
        }
    }

    private fun startMicrophoneCapture() {
        try {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
            val bufferSize = (minBuf * 2).coerceAtLeast(2048)

            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                encoding,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                NotificationHelper.showErrorNotification(
                    this,
                    "Microphone Error",
                    "Could not initialize microphone audio record."
                )
                stopSelf()
                return
            }

            audioRecord = record
            startAudioReadingLoop(record, sampleRate, isStereo = false)

        } catch (e: Exception) {
            Log.e(TAG, "Microphone capture init failed", e)
            NotificationHelper.showErrorNotification(
                this,
                "Microphone Capture Error",
                "Failed to start microphone: ${e.localizedMessage}"
            )
            stopSelf()
        }
    }

    private fun startAudioReadingLoop(
        record: AudioRecord,
        sampleRate: Int,
        isStereo: Boolean
    ) {
        val speechEngine = getSpeechEngine(this)
        val translatorEngine = getTranslatorEngine(this)
        val transcriptDao = AppDatabase.getInstance(this).transcriptDao()
        speechEngine.initEngine()

        // Continuous background bridge: forward recognized speech to local translator
        recognitionForwardJob?.cancel()
        recognitionForwardJob = serviceScope.launch(Dispatchers.Default) {
            speechEngine.recognizedTextFlow.collect { sentence ->
                if (sentence.isNotBlank()) {
                    Log.d(TAG, "Forwarding recognized speech to translator: $sentence")
                    translatorEngine.translateText(sentence)
                }
            }
        }

        // Persist transcripts to Room in background even when main app is closed
        translationPersistJob?.cancel()
        translationPersistJob = serviceScope.launch(Dispatchers.IO) {
            translatorEngine.translationFlow.collect { result ->
                try {
                    val entity = TranscriptEntity(
                        originalText = result.originalText,
                        translatedText = result.translatedText,
                        sourceLanguage = result.sourceLangCode,
                        targetLanguage = result.targetLangCode,
                        timestamp = result.timestamp
                    )
                    transcriptDao.insert(entity)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed inserting transcript in background: ${e.message}")
                }
            }
        }

        isCapturing = true
        captureJob?.cancel()

        captureJob = serviceScope.launch(Dispatchers.Default) {
            val shortBuffer = ShortArray(4096)
            var silenceStartMs = 0L

            try {
                record.startRecording()
                Log.i(TAG, "Audio capture loop started")

                while (isCapturing && isActive) {
                    val readShorts = record.read(shortBuffer, 0, shortBuffer.size)
                    if (readShorts > 0) {
                        // Downmix stereo to mono, resample to 16 kHz, normalize to [-1.0f, 1.0f]
                        val floatSamples = AudioUtils.processRawAudioTo16kMonoFloats(
                            stereoPcmBuffer = shortBuffer,
                            readShorts = readShorts,
                            sampleRate = sampleRate,
                            isStereo = isStereo
                        )

                        // Voice Isolation DSP Filter (150Hz - 3500Hz bandpass) + Noise Gate (RMS threshold)
                        val processedSamples = voiceIsolationProcessor.process(
                            input = floatSamples,
                            noiseGateThreshold = noiseGateLevel,
                            isFilterEnabled = isVoiceIsolationActive
                        )

                        // Feed to SpeechRecognizerEngine
                        speechEngine.feedAudioSamples(processedSamples)

                        // Calculate RMS dB for UI visualizer
                        val db = AudioUtils.calculateDbLevel(processedSamples)
                        _liveAudioDb.value = db

                        // Requirement 4: Detect continuous silence (>5s with RMS < 5 dB) during internal capture
                        if (isStereo) {
                            if (db < 5.0f) {
                                if (silenceStartMs == 0L) {
                                    silenceStartMs = System.currentTimeMillis()
                                } else if (System.currentTimeMillis() - silenceStartMs >= 5000L) {
                                    if (!_isDrmSilenceDetected.value) {
                                        Log.w(TAG, "Continuous silence > 5s detected during internal audio playback (possible DRM)")
                                        _isDrmSilenceDetected.value = true
                                    }
                                }
                            } else {
                                silenceStartMs = 0L
                                if (_isDrmSilenceDetected.value) {
                                    _isDrmSilenceDetected.value = false
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in audio reading loop", e)
                NotificationHelper.showErrorNotification(
                    this@AudioCaptureService,
                    "Audio Stream Interrupted",
                    "The audio capture stream encountered an error: ${e.localizedMessage}"
                )
            } finally {
                try {
                    record.stop()
                    record.release()
                } catch (_: Exception) {}
                speechEngine.flushCurrentSegment()
                _liveAudioDb.value = 0f
                _isDrmSilenceDetected.value = false
            }
        }
    }

    private fun stopCapture(calledFromDestroy: Boolean = false) {
        isCapturing = false
        captureJob?.cancel()
        captureJob = null
        recognitionForwardJob?.cancel()
        recognitionForwardJob = null
        translationPersistJob?.cancel()
        translationPersistJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null

        _liveAudioDb.value = 0f
        _isDrmSilenceDetected.value = false
        _isServiceRunning.value = false

        // Release WakeLock
        releaseWakeLock()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        // BUG 12 FIX: Stop the service so it doesn't linger as an invisible background service.
        // Guard against double-call when invoked from onDestroy() (Android already handles teardown).
        if (!calledFromDestroy) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        stopCapture(calledFromDestroy = true)
        serviceScope.cancel()
        super.onDestroy()
    }
}
