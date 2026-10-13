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
import com.example.asr.SpeechValidator
import com.example.audio.StreamingResampler
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
import kotlinx.coroutines.flow.first
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
    @Volatile private var noiseGateLevel = 0f
    @Volatile private var isQuietVoiceBoostActive = true
    @Volatile private var isDebugRecordingActive = false
    private var settingsObserverJob: Job? = null

    companion object {
        const val ACTION_START_INTERNAL_CAPTURE = "com.example.action.START_INTERNAL_CAPTURE"
        const val ACTION_START_MIC_CAPTURE = "com.example.action.START_MIC_CAPTURE"
        const val ACTION_SWITCH_TO_MIC = "com.example.action.SWITCH_TO_MIC"
        const val ACTION_STOP_CAPTURE = "com.example.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_MEDIA_PROJECTION_DATA = "extra_media_projection_data"

        // Audio chunks waiting for the recognizer. One chunk is up to 4096 shorts read from
        // AudioRecord (~43 ms at 48 kHz stereo, ~256 ms at 16 kHz mono), so this holds
        // roughly 11-60 s of audio. When full, the oldest chunk is dropped to keep recent audio.
        const val AUDIO_BUFFER_CAPACITY = 256

        // Global state for UI to observe
        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _liveAudioDb = MutableStateFlow(0f)
        val liveAudioDb: StateFlow<Float> = _liveAudioDb.asStateFlow()

        // In-app error banner message for UI
        private val _serviceErrorMessage = MutableStateFlow<String?>(null)
        val serviceErrorMessage: StateFlow<String?> = _serviceErrorMessage.asStateFlow()

        fun clearServiceError() {
            _serviceErrorMessage.value = null
        }

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
            settingsRepo.applyTunedDefaultsOnce()
            settingsRepo.settingsFlow.collect { settings ->
                isVoiceIsolationActive = settings.isVoiceIsolationEnabled
                isQuietVoiceBoostActive = settings.isQuietVoiceBoostEnabled
                isDebugRecordingActive = settings.isDebugAudioRecordingEnabled
                noiseGateLevel = settings.noiseGateThreshold

                speechEngine.silenceEndpointDelay = settings.silenceEndpointDelay
                speechEngine.maxUtteranceWindow = settings.maxUtteranceWindow
                speechEngine.noSpeechRestartDelay = settings.noSpeechRestartDelay
                speechEngine.minSentenceLength = settings.minSentenceLength
                speechEngine.singleCharacterMode = settings.singleCharacterMode
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
                // Android 14 requirement: Promote to foreground service of type MEDIA_PROJECTION FIRST
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

                if (resultData == null) {
                    Log.e(TAG, "Invalid MediaProjection extras in intent")
                    NotificationHelper.showErrorNotification(
                        this,
                        "Capture Permission Error",
                        "Missing MediaProjection token to capture internal audio."
                    )
                    stopCapture()
                    stopSelf()
                    return START_NOT_STICKY
                }

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    NotificationHelper.showErrorNotification(
                        this,
                        "Unsupported Android Version",
                        "Internal audio playback capture requires Android 10 (API 29) or higher."
                    )
                    stopCapture()
                    stopSelf()
                    return START_NOT_STICKY
                }

                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                if (projectionManager == null) {
                    Log.e(TAG, "MediaProjectionManager not available")
                    NotificationHelper.showErrorNotification(this, "Capture Error", "MediaProjection service not available.")
                    stopCapture()
                    stopSelf()
                    return START_NOT_STICKY
                }

                val projection = try {
                    projectionManager.getMediaProjection(resultCode, resultData)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get MediaProjection token: ${e.message}", e)
                    null
                }

                if (projection == null) {
                    Log.e(TAG, "MediaProjection token denied or null")
                    NotificationHelper.showErrorNotification(this, "Capture Denied", "System denied access to screen/audio capture.")
                    stopCapture()
                    stopSelf()
                    return START_NOT_STICKY
                }

                mediaProjection = projection

                startInternalPlaybackCaptureWithProjection(projection)
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

    private fun startInternalPlaybackCaptureWithProjection(projection: MediaProjection) {
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
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    Log.w(TAG, "MediaProjection session terminated by user or system")
                    stopCapture()
                    stopSelf()
                }
            }, null)

            // Setup AudioPlaybackCaptureConfiguration with USAGE_MEDIA, USAGE_GAME, USAGE_UNKNOWN
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
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
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO permission missing for microphone capture")
            NotificationHelper.showErrorNotification(
                this,
                "Microphone Permission Required",
                "Audio recording permission is required to capture microphone audio."
            )
            stopSelf()
            return
        }

        try {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
            val bufferSize = (minBuf * 2).coerceAtLeast(2048)

            @android.annotation.SuppressLint("MissingPermission")
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
        val settingsRepo = SettingsRepository(this)

        isCapturing = true
        captureJob?.cancel()

        captureJob = serviceScope.launch(Dispatchers.Default) {
            // Apply the saved endpoint settings before the recognizer is built; the settings
            // observer in onCreate() may not have delivered its first value yet.
            try {
                settingsRepo.applyTunedDefaultsOnce()
                val settings = settingsRepo.settingsFlow.first()
                isVoiceIsolationActive = settings.isVoiceIsolationEnabled
                isQuietVoiceBoostActive = settings.isQuietVoiceBoostEnabled
                isDebugRecordingActive = settings.isDebugAudioRecordingEnabled
                noiseGateLevel = settings.noiseGateThreshold
                speechEngine.silenceEndpointDelay = settings.silenceEndpointDelay
                speechEngine.maxUtteranceWindow = settings.maxUtteranceWindow
                speechEngine.noSpeechRestartDelay = settings.noSpeechRestartDelay
                speechEngine.minSentenceLength = settings.minSentenceLength
                speechEngine.singleCharacterMode = settings.singleCharacterMode
                speechEngine.isAntiFreezeWatchdogEnabled = settings.isAntiFreezeWatchdogEnabled
                translatorEngine.confidenceThreshold = settings.languageIdConfidence
            } catch (e: Exception) {
                Log.w(TAG, "Could not read settings before engine start: ${e.message}")
            }

            // Await speechEngine.initEngine() completion before calling record.startRecording().
            // initEngine() is single-flight and returns at once if the selected model is already loaded.
            Log.i(TAG, "Initializing Sherpa-ONNX speech engine on background dispatcher before audio capture...")
            val isEngineReady = speechEngine.initEngine()
            if (!isEngineReady) {
                val errorMsg = (speechEngine.engineState.value as? com.example.asr.RecognizerState.Error)?.message
                    ?: "ASR Model not initialized. Please install model in Manage Models."
                Log.e(TAG, errorMsg)
                _serviceErrorMessage.value = errorMsg
                NotificationHelper.showErrorNotification(
                    this@AudioCaptureService,
                    "ASR Model Missing",
                    errorMsg
                )
                try {
                    record.stop()
                    record.release()
                } catch (_: Exception) {}
                audioRecord = null
                isCapturing = false
                stopCapture()
                stopSelf()
                return@launch
            }
            _serviceErrorMessage.value = null

            // Continuous background bridge: forward recognized speech to local translator
            // Throttles Partial ASR draft emissions (~480ms) and immediately dispatches Final commits.
            // The translator queue keeps only the newest partial, so a slow translation never
            // builds a backlog of stale drafts.
            recognitionForwardJob?.cancel()
            recognitionForwardJob = serviceScope.launch(Dispatchers.Default) {
                var lastPartialDispatchTime = 0L
                val partialDebounceMs = 480L

                speechEngine.utteranceFlow.collect { event ->
                    when (event) {
                        is com.example.asr.AsrUtteranceEvent.Partial -> {
                            val now = System.currentTimeMillis()
                            if (now - lastPartialDispatchTime >= partialDebounceMs) {
                                if (SpeechValidator.isGenuineSpeech(event.text)) {
                                    lastPartialDispatchTime = now
                                    Log.d(TAG, "Forwarding throttled partial draft (epoch=${event.utteranceId}) to translator: \"${event.text}\"")
                                    translatorEngine.translateText(event.text, isFinal = false, utteranceId = event.utteranceId)
                                }
                            }
                        }
                        is com.example.asr.AsrUtteranceEvent.Final -> {
                            if (SpeechValidator.isGenuineSpeech(event.text) && speechEngine.isConfirmedAsrEvent(event.text)) {
                                Log.d(TAG, "Forwarding confirmed Final utterance (epoch=${event.utteranceId}) to translator: \"${event.text}\"")
                                translatorEngine.translateText(event.text, isFinal = true, utteranceId = event.utteranceId)
                            }
                        }
                    }
                }
            }

            // Persist transcripts to Room in background even when main app is closed.
            // Strictly verifies that the transcript contains genuine spoken dialogue, confirmed ASR events,
            // and is a committed Final translation (never active partial drafts).
            translationPersistJob?.cancel()
            translationPersistJob = serviceScope.launch(Dispatchers.IO) {
                translatorEngine.translationFlow.collect { result ->
                    if (result.isFinal &&
                        result.status.isTranslation &&
                        SpeechValidator.isGenuineSpeech(result.originalText) &&
                        SpeechValidator.isGenuineSpeech(result.translatedText) &&
                        speechEngine.isConfirmedAsrEvent(result.originalText)
                    ) {
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
                    } else if (!result.isFinal) {
                        // Draft translation: active UI display only, not persisted to Room
                    } else {
                        Log.d(TAG, "Suppressed non-speech transcript from Room persistence: ${result.originalText}")
                    }
                }
            }

            // Dedicated High-Priority Audio Buffer:
            // Push raw PCM chunks immediately into a buffered Kotlin Channel<FloatArray>(capacity = AUDIO_BUFFER_CAPACITY).
            // The audio read loop runs on a dedicated high-priority thread (THREAD_PRIORITY_URGENT_AUDIO)
            // and never waits on decoding, translation, or locks.
            val audioBufferChannel = kotlinx.coroutines.channels.Channel<FloatArray>(capacity = AUDIO_BUFFER_CAPACITY)
            var droppedAudioChunks = 0L

            // From here on audio is fed to the recognizer; it must not be rebuilt or released
            // until this capture ends (see SpeechRecognizerEngine.initEngine).
            speechEngine.beginCapture()

            // Consumer coroutine: pulls audio buffers and feeds to SpeechRecognizerEngine
            val decodingConsumerJob = serviceScope.launch(Dispatchers.Default) {
                for (chunk in audioBufferChannel) {
                    if (!isCapturing || !isActive) break
                    speechEngine.feedAudioSamples(chunk)
                }
            }

            val audioCaptureThread = kotlin.concurrent.thread(
                name = "AudioCaptureThread",
                priority = Thread.MAX_PRIORITY,
                start = false
            ) {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed setting urgent audio thread priority: ${e.message}")
                }

                val shortBuffer = ShortArray(4096)
                // One resampler per capture: it carries filter state across buffers
                val resampler = StreamingResampler(inRate = sampleRate, outRate = 16000)
                voiceIsolationProcessor.reset()
                // Developer option: save what the speech engine receives (files/debug/asr_input.wav)
                val debugRecorder = if (isDebugRecordingActive) {
                    try {
                        val dir = java.io.File(filesDir, "debug").apply { mkdirs() }
                        com.example.audio.WavRecorder(java.io.File(dir, "asr_input.wav")).also {
                            Log.i(TAG, "Recording recognizer input to ${dir.absolutePath}/asr_input.wav")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not start debug recording: ${e.message}")
                        null
                    }
                } else null
                // Input level statistics, logged every 5 seconds
                var statSamples = 0
                var statRawSq = 0.0
                var statSentSq = 0.0
                var statPeak = 0f
                var statClipped = 0
                try {
                    record.startRecording()
                    Log.i(TAG, "Audio capture loop started successfully on dedicated thread with initialized ASR engine")

                    while (isCapturing && isActive) {
                        val readShorts = record.read(shortBuffer, 0, shortBuffer.size)
                        if (readShorts > 0) {
                            // Downmix stereo to mono floats, then resample to 16 kHz with anti-aliasing
                            val floatSamples = resampler.process(
                                AudioUtils.toMonoFloats(shortBuffer, readShorts, isStereo)
                            )
                            if (floatSamples.isEmpty()) continue

                            // Optional rumble filter and noise gate (see VoiceIsolationProcessor)
                            val processedSamples = voiceIsolationProcessor.process(
                                input = floatSamples,
                                noiseGateThreshold = noiseGateLevel,
                                isFilterEnabled = isVoiceIsolationActive,
                                boostQuietVoices = isQuietVoiceBoostActive
                            )

                            // Non-blocking push. If the recognizer has fallen behind and the buffer is full,
                            // drop the oldest chunk so the newest audio is kept, and count the drop.
                            if (!audioBufferChannel.trySend(processedSamples).isSuccess) {
                                audioBufferChannel.tryReceive()
                                audioBufferChannel.trySend(processedSamples)
                                droppedAudioChunks++
                                if (droppedAudioChunks == 1L || droppedAudioChunks % 50L == 0L) {
                                    Log.w(TAG, "Audio buffer full: dropped $droppedAudioChunks oldest chunk(s) so far; recognizer is falling behind")
                                }
                            }

                            debugRecorder?.write(processedSamples)
                            for (i in processedSamples.indices) {
                                val raw = floatSamples[i]
                                val sent = processedSamples[i]
                                statRawSq += raw * raw
                                statSentSq += sent * sent
                                if (kotlin.math.abs(raw) > statPeak) statPeak = kotlin.math.abs(raw)
                                if (kotlin.math.abs(sent) >= 0.98f) statClipped++
                            }
                            statSamples += processedSamples.size
                            if (statSamples >= 16000 * 5) {
                                fun dbfs(sq: Double) = if (sq <= 0.0) -120.0 else 10 * kotlin.math.log10(sq / statSamples)
                                Log.i(TAG, String.format(java.util.Locale.US,
                                    "Input level (5 s): captured %.1f dBFS (peak %.1f), sent to recognizer %.1f dBFS, clipped %.2f%%, dropped chunks %d",
                                    dbfs(statRawSq),
                                    if (statPeak > 0f) 20 * kotlin.math.log10(statPeak.toDouble()) else -120.0,
                                    dbfs(statSentSq),
                                    100.0 * statClipped / statSamples,
                                    droppedAudioChunks))
                                statSamples = 0; statRawSq = 0.0; statSentSq = 0.0; statPeak = 0f; statClipped = 0
                            }

                            // Calculate RMS dB for UI visualizer
                            val db = AudioUtils.calculateDbLevel(processedSamples)
                            _liveAudioDb.value = db
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
                    try { debugRecorder?.close() } catch (_: Exception) {}
                    audioBufferChannel.close()
                    speechEngine.flushCurrentSegment()
                    _liveAudioDb.value = 0f
                    _isDrmSilenceDetected.value = false
                }
            }

            audioCaptureThread.start()

            try {
                // Keep the captureJob coroutine active while capturing
                while (isCapturing && isActive) {
                    kotlinx.coroutines.delay(200)
                }
            } finally {
                audioCaptureThread.interrupt()
                decodingConsumerJob.cancel()
                speechEngine.endCapture()
                if (droppedAudioChunks > 0) {
                    Log.w(TAG, "Capture ended with $droppedAudioChunks dropped audio chunk(s)")
                }
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
