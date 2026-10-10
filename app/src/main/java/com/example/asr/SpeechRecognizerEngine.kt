package com.example.asr

import android.content.Context
import android.util.Log
import com.example.service.NotificationHelper
import com.k2fsa.sherpa.onnx.EndpointConfig
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

sealed class RecognizerState {
    object Uninitialized : RecognizerState()
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

    private val _engineState = MutableStateFlow<RecognizerState>(RecognizerState.Uninitialized)
    val engineState: StateFlow<RecognizerState> = _engineState.asStateFlow()

    // Emits completed or segmented recognized text strings
    private val _recognizedTextFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val recognizedTextFlow: SharedFlow<String> = _recognizedTextFlow.asSharedFlow()

    // Emits live partial recognized text
    private val _partialTextFlow = MutableStateFlow("")
    val partialTextFlow: StateFlow<String> = _partialTextFlow.asStateFlow()

    private var onlineRecognizer: OnlineRecognizer? = null
    private var onlineStream: OnlineStream? = null
    private val recognizerLock = Any()
    private var lastEmittedText = ""
    private var streamEmittedLength = 0
    private var isSherpaLoaded = false

    // Configurable endpointing parameters
    var silenceEndpointDelay: Float = 1.1f
    var maxUtteranceWindow: Float = 15.0f
    var isAntiFreezeWatchdogEnabled: Boolean = true

    // Anti-freeze watchdog tracking
    private var continuousAudioStartTime = 0L
    private var lastOutputOrResetTime = System.currentTimeMillis()
    // BUG 3 FIX: Track whether audio feeding has started so watchdog timestamps are initialized
    // on the first real audio frame rather than at construction time (which can cause premature
    // watchdog firing if there is lag between construction and the first feedAudioSamples() call).
    private var audioFeedingStarted = false

    // Consistent buffer size of 0.1s (1600 samples at 16kHz 16-bit mono) aligned to 2-byte boundaries
    companion object {
        const val CHUNK_SIZE_SAMPLES = 1600
    }
    // BUG 7 FIX: Use ArrayDeque instead of MutableList<Float>. The old subList(0, n).clear()
    // on an ArrayList is O(n) because all remaining elements must be shifted left. At 10 chunks/sec
    // over a long session, this causes significant GC pressure. ArrayDeque.removeFirst() is O(1).
    private val pcmChunkAccumulator = ArrayDeque<Float>(CHUNK_SIZE_SAMPLES * 4)
    // Pre-allocated chunk buffer to eliminate allocations on the audio processing thread (prevents GC churn)
    private val reusableChunk = FloatArray(CHUNK_SIZE_SAMPLES)

    init {
        try {
            // Test if sherpa-onnx native library is loadable
            isSherpaLoaded = true
        } catch (t: Throwable) {
            Log.e(TAG, "Native library load failed", t)
            isSherpaLoaded = false
        }
    }

    /**
     * Initializes the Sherpa-ONNX online recognizer from the model directory.
     * Verifies modelManager.isModelReady() is true, and automatically loads the selected model on a background dispatcher.
     */
    fun initEngine(): Boolean = runBlocking(Dispatchers.IO) {
        synchronized(recognizerLock) {
            if (onlineRecognizer != null && onlineStream != null && _engineState.value is RecognizerState.Ready) {
                return@runBlocking true
            }
        }

        _engineState.value = RecognizerState.Initializing

        // Verify that model files are downloaded and verified ready asynchronously across disk
        val resolved = modelManager.resolveModelFiles(modelManager.selectedModel.value)
        if (resolved == null) {
            val error = "ASR Model not initialized. Please install model in Manage Models."
            Log.w(TAG, error)
            _engineState.value = RecognizerState.Error(error)
            return@runBlocking false
        }

        try {
            release()

            val encoderFile = resolved.encoderFile
            val decoderFile = resolved.decoderFile
            val joinerFile = resolved.joinerFile
            val tokensFile = resolved.tokensFile

            val featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80)
            val transducerConfig = OnlineTransducerModelConfig(
                encoder = encoderFile.absolutePath,
                decoder = decoderFile.absolutePath,
                joiner = joinerFile.absolutePath
            )

            val modelConfig = OnlineModelConfig(
                transducer = transducerConfig,
                tokens = tokensFile.absolutePath,
                numThreads = 2,
                debug = false,
                provider = "cpu",
                modelType = "zipformer2"
            )

            val endpointConfig = EndpointConfig(
                rule1 = com.k2fsa.sherpa.onnx.EndpointRule(mustContainNonSilence = false, minTrailingSilence = 1.2f, minUtteranceLength = 0.0f),
                rule2 = com.k2fsa.sherpa.onnx.EndpointRule(mustContainNonSilence = true, minTrailingSilence = silenceEndpointDelay, minUtteranceLength = 1.5f),
                rule3 = com.k2fsa.sherpa.onnx.EndpointRule(mustContainNonSilence = false, minTrailingSilence = 0.0f, minUtteranceLength = maxUtteranceWindow)
            )

            val recognizerConfig = OnlineRecognizerConfig(
                featConfig = featConfig,
                modelConfig = modelConfig,
                endpointConfig = endpointConfig,
                enableEndpoint = true,
                decodingMethod = "greedy_search"
            )

            val recognizer = OnlineRecognizer(
                assetManager = null,
                config = recognizerConfig
            )

            synchronized(recognizerLock) {
                onlineRecognizer = recognizer
                onlineStream = recognizer.createStream()
            }
            _engineState.value = RecognizerState.Ready
            Log.i(TAG, "Sherpa-ONNX speech recognizer initialized successfully with model: ${resolved.model.id}")
            return@runBlocking true

        } catch (t: Throwable) {
            val error = "Failed to initialize ASR engine: ${t.localizedMessage}"
            Log.e(TAG, error, t)
            _engineState.value = RecognizerState.Error(error)
            NotificationHelper.showErrorNotification(context, "ASR Engine Crash", error)
            return@runBlocking false
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

                    // BUG 3 FIX: Initialize watchdog timestamps on the very first audio frame
                    // to avoid premature watchdog firing caused by model-load lag.
                    if (!audioFeedingStarted) {
                        val now = System.currentTimeMillis()
                        continuousAudioStartTime = now
                        lastOutputOrResetTime = now
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
                        val fullText = result.text

                        // Sentence-level buffering: find punctuation boundaries (. , ? ! etc.)
                        // to extract complete clauses before passing to translation
                        val punctuationMarks = charArrayOf('.', ',', '?', '!', '。', '，', '？', '！', ';', '；', ':')
                        var uncommitted = if (streamEmittedLength < fullText.length) {
                            fullText.substring(streamEmittedLength)
                        } else {
                            ""
                        }

                        var punctIndex = uncommitted.indexOfAny(punctuationMarks)
                        while (punctIndex >= 0) {
                            val completeClause = uncommitted.substring(0, punctIndex + 1).trim()
                            if (completeClause.isNotBlank()) {
                                Log.d(TAG, "Punctuation boundary sentence detected: $completeClause")
                                lastOutputOrResetTime = System.currentTimeMillis()
                                emitSentence(completeClause)
                            }
                            streamEmittedLength += (punctIndex + 1)
                            uncommitted = if (streamEmittedLength < fullText.length) {
                                fullText.substring(streamEmittedLength)
                            } else {
                                ""
                            }
                            punctIndex = uncommitted.indexOfAny(punctuationMarks)
                        }

                        // Any trailing in-progress clause is shown as partial
                        val currentPartial = if (streamEmittedLength < fullText.length) {
                            fullText.substring(streamEmittedLength).trim()
                        } else {
                            ""
                        }

                        if (currentPartial != _partialTextFlow.value) {
                            _partialTextFlow.value = currentPartial
                        }

                        val isEndpoint = recognizer.isEndpoint(stream)
                        if (isEndpoint) {
                            val finalClause = if (streamEmittedLength < fullText.length) {
                                fullText.substring(streamEmittedLength).trim()
                            } else {
                                ""
                            }
                            if (finalClause.isNotBlank()) {
                                Log.d(TAG, "Speech endpoint reached: $finalClause")
                                emitSentence(finalClause)
                            }
                            streamEmittedLength = 0
                            lastEmittedText = ""
                            _partialTextFlow.value = ""
                            lastOutputOrResetTime = System.currentTimeMillis()
                            recognizer.reset(stream)
                        }

                        // Anti-Freeze Watchdog:
                        // If continuous audio processing occurs for > 6 seconds without any output or endpointing,
                        // automatically flush/reset the stream to recover from stalled decoder states
                        if (isAntiFreezeWatchdogEnabled) {
                            val timeSinceLast = System.currentTimeMillis() - lastOutputOrResetTime
                            if (timeSinceLast >= 6000L) {
                                Log.w(TAG, "Anti-Freeze Watchdog: 6 seconds of audio with no output. Auto-recovering stream.")
                                val stalledText = fullText.substring(streamEmittedLength.coerceAtMost(fullText.length)).trim()
                                if (stalledText.isNotBlank()) {
                                    emitSentence(stalledText)
                                }
                                streamEmittedLength = 0
                                lastEmittedText = ""
                                _partialTextFlow.value = ""
                                lastOutputOrResetTime = System.currentTimeMillis()
                                recognizer.reset(stream)
                            }
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

    private fun emitSentence(clause: String) {
        if (!_recognizedTextFlow.tryEmit(clause)) {
            scope.launch {
                _recognizedTextFlow.emit(clause)
            }
        }
    }

    /**
     * Forces flushing the current partial text as an endpoint sentence.
     */
    fun flushCurrentSegment() {
        val current = _partialTextFlow.value.trim()
        if (current.isNotEmpty()) {
            emitSentence(current)
            _partialTextFlow.value = ""
            lastEmittedText = ""
            streamEmittedLength = 0
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
                streamEmittedLength = 0
                audioFeedingStarted = false
                synchronized(pcmChunkAccumulator) {
                    pcmChunkAccumulator.clear()
                }
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
            scope.coroutineContext[Job]?.cancelChildren()
            scope.cancel()
        } catch (_: Exception) {}
    }
}
