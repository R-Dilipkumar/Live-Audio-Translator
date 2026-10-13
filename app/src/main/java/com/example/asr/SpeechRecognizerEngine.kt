package com.example.asr

import android.content.Context
import android.util.Log
import com.example.service.NotificationHelper
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

sealed class AsrUtteranceEvent {
    data class Partial(val text: String, val utteranceId: Long = 0L, val timestamp: Long = System.currentTimeMillis()) : AsrUtteranceEvent()
    data class Final(val text: String, val utteranceId: Long = 0L, val timestamp: Long = System.currentTimeMillis()) : AsrUtteranceEvent()
}

sealed class RecognizerState {
    object Uninitialized : RecognizerState()
    object NotReady : RecognizerState()
    object Initializing : RecognizerState()
    object Ready : RecognizerState()
    object Listening : RecognizerState()
    data class Error(val message: String) : RecognizerState()
}

class SpeechRecognizerEngine(
    private val context: Context,
    private val modelManager: ModelManager
) {
    private val TAG = "SpeechRecognizerEngine"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _engineState = MutableStateFlow<RecognizerState>(RecognizerState.NotReady)
    val engineState: StateFlow<RecognizerState> = _engineState.asStateFlow()

    // Structured utterance event stream differentiating Partial (draft) and Final (committed)
    private val _utteranceFlow = MutableSharedFlow<AsrUtteranceEvent>(extraBufferCapacity = 64)
    val utteranceFlow: SharedFlow<AsrUtteranceEvent> = _utteranceFlow.asSharedFlow()

    // Emits completed or segmented recognized text strings (Final commits)
    private val _recognizedTextFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val recognizedTextFlow: SharedFlow<String> = _recognizedTextFlow.asSharedFlow()

    // Emits live partial recognized text
    private val _partialTextFlow = MutableStateFlow("")
    val partialTextFlow: StateFlow<String> = _partialTextFlow.asStateFlow()

    // Every Partial and Final goes through this one channel and is re-emitted by a single
    // coroutine, so collectors always see events in the order the recognizer produced them.
    private val utteranceEvents = Channel<AsrUtteranceEvent>(Channel.UNLIMITED)

    private var onlineRecognizer: OnlineRecognizer? = null
    private var onlineStream: OnlineStream? = null
    private val recognizerLock = Any()

    // Single-flight guard for initEngine(): only one build at a time.
    private val initMutex = Mutex()
    // What the current recognizer was built from. Null when nothing is loaded.
    @Volatile private var loadedConfig: LoadedConfig? = null
    // Number of capture sessions currently feeding audio. The recognizer is never rebuilt
    // or released while this is above zero.
    private val activeCaptureSessions = AtomicInteger(0)

    // Monotonically incrementing utterance epoch tag
    private val utteranceEpochCounter = java.util.concurrent.atomic.AtomicLong(1L)
    @Volatile
    private var currentUtteranceId: Long = 1L

    // Endpoint settings, applied the next time the recognizer is built (initEngine).
    // silenceEndpointDelay -> rule2.minTrailingSilence: seconds of trailing silence after speech
    //   that end an utterance.
    // maxUtteranceWindow -> rule3.minUtteranceLength: in sherpa-onnx, rule3 fires once the
    //   utterance is this many seconds long, with no silence needed. It is the longest a single
    //   subtitle can stay open during continuous speech or music.
    @Volatile var silenceEndpointDelay: Float = DEFAULT_SILENCE_ENDPOINT_DELAY
    @Volatile var maxUtteranceWindow: Float = DEFAULT_MAX_UTTERANCE_WINDOW
    @Volatile var isAntiFreezeWatchdogEnabled: Boolean = true

    // Anti-freeze watchdog: time of the last partial change, final, or stream reset.
    private var lastOutputOrResetTime = 0L
    // Watchdog timestamps are initialized on the first real audio frame rather than at
    // construction time, so model-load lag cannot trigger the watchdog.
    private var audioFeedingStarted = false

    // Consistent buffer size of 0.1s (1600 samples at 16kHz 16-bit mono) aligned to 2-byte boundaries
    companion object {
        const val CHUNK_SIZE_SAMPLES = 1600
        const val DEFAULT_SILENCE_ENDPOINT_DELAY = 1.1f
        const val DEFAULT_MAX_UTTERANCE_WINDOW = 15.0f
        // If audio keeps flowing but the recognizer produces no new text and no endpoint for
        // this long, the watchdog commits whatever is pending and resets the stream.
        const val ANTI_FREEZE_TIMEOUT_MS = 8_000L

        fun buildEndpointConfig(silenceEndpointDelay: Float, maxUtteranceWindow: Float): EndpointConfig {
            return EndpointConfig(
                // rule1: 2.4 s of silence with nothing recognized resets the stream
                rule1 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 2.4f, minUtteranceLength = 0.0f),
                // rule2: after speech (utterances over 3 s), this much trailing silence ends the utterance
                rule2 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = silenceEndpointDelay.coerceIn(0.3f, 3.0f), minUtteranceLength = 3.0f),
                // rule3: force an endpoint once the utterance is this long, even without silence
                rule3 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 0.0f, minUtteranceLength = maxUtteranceWindow.coerceIn(5.0f, 30.0f))
            )
        }
    }

    private data class LoadedConfig(
        val modelId: String,
        val silenceEndpointDelay: Float,
        val maxUtteranceWindow: Float
    )

    // BUG 7 FIX: Use ArrayDeque instead of MutableList<Float>. The old subList(0, n).clear()
    // on an ArrayList is O(n) because all remaining elements must be shifted left. At 10 chunks/sec
    // over a long session, this causes significant GC pressure. ArrayDeque.removeFirst() is O(1).
    private val pcmChunkAccumulator = ArrayDeque<Float>(CHUNK_SIZE_SAMPLES * 4)
    // Pre-allocated chunk buffer to eliminate allocations on the audio processing thread (prevents GC churn)
    private val reusableChunk = FloatArray(CHUNK_SIZE_SAMPLES)

    // Tracks verified native ASR recognitions to ensure events originate from the native recognizer
    private val confirmedAsrEvents = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    /**
     * Confirms that [text] originated from a verified native ASR recognition event
     * and is not an empty string or system diagnostic/status message.
     */
    fun isConfirmedAsrEvent(text: String?): Boolean {
        if (!SpeechValidator.isGenuineSpeech(text)) return false
        val normalized = text!!.trim()
        return synchronized(confirmedAsrEvents) {
            confirmedAsrEvents.contains(normalized)
        }
    }

    init {
        scope.launch {
            for (event in utteranceEvents) {
                _utteranceFlow.emit(event)
                if (event is AsrUtteranceEvent.Final) {
                    _recognizedTextFlow.emit(event.text)
                }
            }
        }
    }

    /** The id of the model the recognizer was built from, or null if none is loaded. */
    val loadedModelId: String? get() = loadedConfig?.modelId

    /** Called by the capture service right before it starts feeding audio. */
    fun beginCapture() {
        activeCaptureSessions.incrementAndGet()
    }

    /** Called by the capture service when it stops feeding audio. */
    fun endCapture() {
        activeCaptureSessions.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    private val isCapturing: Boolean get() = activeCaptureSessions.get() > 0

    /**
     * Loads the selected model into a Sherpa-ONNX online recognizer.
     *
     * Single-flight: concurrent callers (capture service, ViewModel) are serialized.
     * - If a recognizer is already built for the same model and endpoint settings, returns true
     *   without rebuilding.
     * - While audio is being captured the running recognizer is never rebuilt or released;
     *   model or setting changes apply on the next capture start.
     * - A rebuild creates the new recognizer first and swaps it in, so audio feeding never
     *   sees a missing recognizer.
     */
    suspend fun initEngine(): Boolean = withContext(Dispatchers.IO) {
        initMutex.withLock {
            val hasRecognizer = synchronized(recognizerLock) { onlineRecognizer != null && onlineStream != null }

            if (hasRecognizer && isCapturing) {
                Log.d(TAG, "initEngine during capture: keeping the running recognizer (${loadedConfig?.modelId})")
                return@withLock true
            }

            val resolved = resolveModelForLoad()
            if (resolved == null) {
                if (hasRecognizer) release()
                val modelName = modelManager.selectedModel.value.name
                val error = "ASR model not installed: $modelName. Please install it in Manage Models."
                Log.w(TAG, error)
                _engineState.value = RecognizerState.Error(error)
                return@withLock false
            }

            val wanted = LoadedConfig(resolved.modelId, silenceEndpointDelay, maxUtteranceWindow)
            if (hasRecognizer && loadedConfig == wanted) {
                if (_engineState.value !is RecognizerState.Listening) {
                    _engineState.value = RecognizerState.Ready
                }
                return@withLock true
            }

            _engineState.value = RecognizerState.Initializing
            try {
                val modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = resolved.encoderFile.absolutePath,
                        decoder = resolved.decoderFile.absolutePath,
                        joiner = resolved.joinerFile.absolutePath
                    ),
                    tokens = resolved.tokensFile.absolutePath,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                    modelType = resolved.modelType
                )

                val recognizerConfig = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = modelConfig,
                    endpointConfig = buildEndpointConfig(wanted.silenceEndpointDelay, wanted.maxUtteranceWindow),
                    enableEndpoint = true,
                    decodingMethod = "greedy_search"
                )

                // OnlineRecognizer(config) with absolute file paths rather than OnlineRecognizer(assetManager, config)
                val recognizer = OnlineRecognizer(config = recognizerConfig)
                val stream = recognizer.createStream()

                synchronized(recognizerLock) {
                    val oldStream = onlineStream
                    val oldRecognizer = onlineRecognizer
                    onlineRecognizer = recognizer
                    onlineStream = stream
                    loadedConfig = wanted
                    resetUtteranceState()
                    try {
                        oldStream?.release()
                        oldRecognizer?.release()
                    } catch (t: Throwable) {
                        Log.e(TAG, "Error releasing previous recognizer", t)
                    }
                }
                _engineState.value = RecognizerState.Ready
                Log.i(TAG, "Sherpa-ONNX recognizer ready: model=${resolved.modelId} type='${resolved.modelType}' " +
                        "silence=${wanted.silenceEndpointDelay}s maxUtterance=${wanted.maxUtteranceWindow}s")
                true
            } catch (t: Throwable) {
                val error = "Failed to initialize ASR engine: ${t.localizedMessage}"
                Log.e(TAG, error, t)
                _engineState.value = RecognizerState.Error(error)
                NotificationHelper.showErrorNotification(context, "ASR Engine Crash", error)
                false
            }
        }
    }

    /**
     * Initializes the recognizer asynchronously in a background coroutine worker.
     * If the selected model is not installed, sets [RecognizerState.NotReady].
     */
    fun initializeEngineAsync(onComplete: ((Boolean) -> Unit)? = null) {
        scope.launch(Dispatchers.IO) {
            if (resolveModelForLoad() == null) {
                _engineState.value = RecognizerState.NotReady
                onComplete?.invoke(false)
                return@launch
            }
            val success = initEngine()
            onComplete?.invoke(success)
        }
    }

    data class LoadedModelFiles(
        val modelId: String,
        val modelType: String,
        val encoderFile: File,
        val decoderFile: File,
        val joinerFile: File,
        val tokensFile: File
    )

    /**
     * Checks if the selected model has complete, verified ONNX files on disk.
     */
    fun hasValidModelFilesOnDisk(): Boolean {
        return resolveModelForLoad() != null
    }

    /**
     * Resolves the files to load: exactly the selected model, from its own folder
     * (models/<model id>/). Returns null if that model is not installed; never falls back
     * to another model. ModelManager's size checks (> 1MB encoder etc.) guard against
     * truncated files that would crash the native loader.
     */
    internal fun resolveModelForLoad(): LoadedModelFiles? {
        val resolved = modelManager.resolveSelectedModelFiles() ?: return null
        return LoadedModelFiles(
            modelId = resolved.model.id,
            modelType = resolved.model.modelType,
            encoderFile = resolved.encoderFile,
            decoderFile = resolved.decoderFile,
            joinerFile = resolved.joinerFile,
            tokensFile = resolved.tokensFile
        )
    }

    /**
     * Releases the recognizer if it was built from [modelId] and no capture is running.
     * Called after a model is deleted so the deleted model is not kept in use.
     */
    fun onModelDeleted(modelId: String) {
        if (loadedConfig?.modelId == modelId && !isCapturing) {
            release()
            _engineState.value = RecognizerState.NotReady
        }
    }

    /** Must hold [recognizerLock]. */
    private fun resetUtteranceState() {
        _partialTextFlow.value = ""
        audioFeedingStarted = false
        synchronized(pcmChunkAccumulator) {
            pcmChunkAccumulator.clear()
        }
    }

    /**
     * Feeds 16 kHz float PCM samples to the speech recognizer in consistent 0.1s chunks (1600 samples).
     * Uses in-place reusable buffer with ArrayDeque O(1) removal to eliminate allocation GC overhead.
     */
    fun feedAudioSamples(samples: FloatArray) {
        synchronized(pcmChunkAccumulator) {
            for (sample in samples) {
                pcmChunkAccumulator.addLast(sample)
            }
        }

        synchronized(recognizerLock) {
            val recognizer = onlineRecognizer
            val stream = onlineStream

            if (recognizer != null && stream != null) {
                try {
                    _engineState.value = RecognizerState.Listening

                    if (!audioFeedingStarted) {
                        lastOutputOrResetTime = System.currentTimeMillis()
                        audioFeedingStarted = true
                    }

                    while (true) {
                        var chunkAvailable = false
                        synchronized(pcmChunkAccumulator) {
                            if (pcmChunkAccumulator.size >= CHUNK_SIZE_SAMPLES) {
                                for (i in 0 until CHUNK_SIZE_SAMPLES) {
                                    reusableChunk[i] = pcmChunkAccumulator.removeFirst()
                                }
                                chunkAvailable = true
                            }
                        }

                        if (!chunkAvailable) break

                        stream.acceptWaveform(reusableChunk, 16000)

                        while (recognizer.isReady(stream)) {
                            recognizer.decode(stream)
                        }

                        val result = recognizer.getResult(stream)
                        // Accumulate full clause in progress
                        val currentText = result.text.trim()
                        val sanitizedDraft = if (SpeechValidator.isGenuineSpeech(currentText)) currentText else ""
                        val now = System.currentTimeMillis()

                        if (recognizer.isEndpoint(stream)) {
                            val epochId = currentUtteranceId
                            if (sanitizedDraft.isNotBlank()) {
                                Log.d(TAG, "Speech endpoint reached (utteranceId=$epochId). Emitting Final: $sanitizedDraft")
                                emitFinalUtterance(sanitizedDraft, epochId)
                            }
                            finishUtterance(recognizer, stream, now)
                        } else if (isAntiFreezeWatchdogEnabled && now - lastOutputOrResetTime >= ANTI_FREEZE_TIMEOUT_MS) {
                            Log.w(TAG, "Anti-freeze watchdog: no recognizer output for ${now - lastOutputOrResetTime} ms while audio flows; resetting stream")
                            if (sanitizedDraft.isNotBlank()) {
                                emitFinalUtterance(sanitizedDraft, currentUtteranceId)
                            }
                            finishUtterance(recognizer, stream, now)
                        } else if (sanitizedDraft.isNotBlank() && sanitizedDraft != _partialTextFlow.value) {
                            lastOutputOrResetTime = now
                            _partialTextFlow.value = sanitizedDraft
                            emitPartialUtterance(sanitizedDraft, currentUtteranceId)
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Runtime error in speech recognizer loop", t)
                    val error = "Speech recognizer runtime error: ${t.localizedMessage}"
                    _engineState.value = RecognizerState.Error(error)
                    NotificationHelper.showErrorNotification(context, "ASR Runtime Error", error)
                }
            } else {
                // If recognizer is not initialized, only set Error if NOT currently Initializing
                val state = _engineState.value
                if (state !is RecognizerState.Initializing && state !is RecognizerState.Error) {
                    val errorMsg = "ASR Model not initialized. Please install model in Manage Models."
                    _engineState.value = RecognizerState.Error(errorMsg)
                }
            }
        }
    }

    /** Must hold [recognizerLock]. Clears the partial, resets the stream and starts a new utterance. */
    private fun finishUtterance(recognizer: OnlineRecognizer, stream: OnlineStream, now: Long) {
        _partialTextFlow.value = ""
        // Synchronously reset recognizer stream to be immediately ready for subsequent incoming audio frames
        recognizer.reset(stream)
        lastOutputOrResetTime = now
        // Advance to next utterance epoch for subsequent speech
        currentUtteranceId = utteranceEpochCounter.incrementAndGet()
    }

    private fun emitPartialUtterance(draft: String, utteranceId: Long) {
        val trimmed = draft.trim()
        if (!SpeechValidator.isGenuineSpeech(trimmed)) return
        utteranceEvents.trySend(AsrUtteranceEvent.Partial(trimmed, utteranceId = utteranceId))
    }

    private fun emitFinalUtterance(clause: String, utteranceId: Long) {
        val trimmed = clause.trim()
        if (!SpeechValidator.isGenuineSpeech(trimmed)) {
            Log.d(TAG, "Suppressed non-speech status/diagnostic phrase from ASR emission: $trimmed")
            return
        }
        synchronized(confirmedAsrEvents) {
            confirmedAsrEvents.add(trimmed)
            if (confirmedAsrEvents.size > 200) {
                val it = confirmedAsrEvents.iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
        }
        utteranceEvents.trySend(AsrUtteranceEvent.Final(trimmed, utteranceId = utteranceId))
    }

    /**
     * Forces flushing the current partial text as an endpoint sentence.
     */
    fun flushCurrentSegment() {
        synchronized(recognizerLock) {
            val recognizer = onlineRecognizer
            val stream = onlineStream
            val current = _partialTextFlow.value.trim()
            if (current.isNotEmpty()) {
                val epochId = currentUtteranceId
                emitFinalUtterance(current, epochId)
                _partialTextFlow.value = ""
                if (recognizer != null && stream != null) {
                    try {
                        recognizer.reset(stream)
                    } catch (_: Exception) {}
                }
                currentUtteranceId = utteranceEpochCounter.incrementAndGet()
            }
        }
        synchronized(pcmChunkAccumulator) {
            pcmChunkAccumulator.clear()
        }
    }

    fun release() {
        synchronized(recognizerLock) {
            try {
                onlineStream?.release()
                onlineRecognizer?.release()
            } catch (t: Throwable) {
                Log.e(TAG, "Error releasing recognizer native pointers", t)
            } finally {
                onlineStream = null
                onlineRecognizer = null
                loadedConfig = null
                resetUtteranceState()
                _engineState.value = RecognizerState.Uninitialized
            }
        }
    }

    /**
     * Terminates the speech recognizer, releases all native resources,
     * and cancels internal coroutines.
     */
    fun terminate() {
        release()
        try {
            utteranceEvents.close()
            scope.coroutineContext[Job]?.cancelChildren()
            scope.cancel()
        } catch (_: Exception) {}
    }
}
