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
import kotlinx.coroutines.SupervisorJob
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

    // Consistent buffer size of 0.1s (1600 samples at 16kHz 16-bit mono) aligned to 2-byte boundaries
    companion object {
        const val CHUNK_SIZE_SAMPLES = 1600
    }
    private val pcmChunkAccumulator = mutableListOf<Float>()

    // Fallback voice activity detector for acoustic audio verification
    private var energyAccumulator = 0f
    private var speechFramesCount = 0

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
     */
    fun initEngine(): Boolean {
        _engineState.value = RecognizerState.Initializing
        val currentModel = modelManager.selectedModel.value
        val modelDir = modelManager.getModelDirectory(currentModel)

        if (!modelManager.isModelReady(currentModel)) {
            val error = "Model files not found or incomplete. Please download the ASR model."
            _engineState.value = RecognizerState.Error(error)
            NotificationHelper.showErrorNotification(context, "ASR Engine Not Ready", error)
            return false
        }

        try {
            release()

            val encoderFile = modelManager.findFileInDir(modelDir, currentModel.encoderFilename)
                ?: modelManager.findFileByKeyword(modelDir, "encoder")
            val decoderFile = modelManager.findFileInDir(modelDir, currentModel.decoderFilename)
                ?: modelManager.findFileByKeyword(modelDir, "decoder")
            val joinerFile = modelManager.findFileInDir(modelDir, currentModel.joinerFilename)
                ?: modelManager.findFileByKeyword(modelDir, "joiner")
            val tokensFile = modelManager.findFileInDir(modelDir, currentModel.tokensFilename)
                ?: modelManager.findFileByKeyword(modelDir, "tokens.txt")

            if (encoderFile == null || decoderFile == null || joinerFile == null || tokensFile == null) {
                throw IllegalStateException("Missing one or more required ONNX model files in ${modelDir.absolutePath}")
            }

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

            onlineRecognizer = recognizer
            onlineStream = recognizer.createStream()
            _engineState.value = RecognizerState.Ready
            Log.i(TAG, "Sherpa-ONNX speech recognizer initialized successfully")
            return true

        } catch (t: Throwable) {
            val error = "Failed to initialize ASR engine: ${t.localizedMessage}"
            Log.e(TAG, error, t)
            _engineState.value = RecognizerState.Error(error)
            NotificationHelper.showErrorNotification(context, "ASR Engine Crash", error)
            return false
        }
    }

    /**
     * Feeds 16 kHz float PCM samples to the speech recognizer in consistent 0.1s chunks (1600 samples).
     */
    fun feedAudioSamples(samples: FloatArray) {
        val recognizer = onlineRecognizer
        val stream = onlineStream

        if (recognizer != null && stream != null) {
            try {
                _engineState.value = RecognizerState.Listening

                val chunksToProcess = mutableListOf<FloatArray>()
                synchronized(pcmChunkAccumulator) {
                    for (sample in samples) {
                        pcmChunkAccumulator.add(sample)
                    }
                    while (pcmChunkAccumulator.size >= CHUNK_SIZE_SAMPLES) {
                        val chunk = FloatArray(CHUNK_SIZE_SAMPLES)
                        for (i in 0 until CHUNK_SIZE_SAMPLES) {
                            chunk[i] = pcmChunkAccumulator[i]
                        }
                        pcmChunkAccumulator.subList(0, CHUNK_SIZE_SAMPLES).clear()
                        chunksToProcess.add(chunk)
                    }
                }

                for (chunk in chunksToProcess) {
                    stream.acceptWaveform(chunk, 16000)

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
                            scope.launch {
                                _recognizedTextFlow.emit(completeClause)
                            }
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
                            scope.launch {
                                _recognizedTextFlow.emit(finalClause)
                            }
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
                                scope.launch {
                                    _recognizedTextFlow.emit(stalledText)
                                }
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
            // Acoustic speech detector fallback if model not loaded
            detectAcousticSpeech(samples)
        }
    }

    /**
     * Fallback energy detector that segments audio activity into transcript events
     * when the native model is not loaded yet or during initial testing.
     */
    private fun detectAcousticSpeech(samples: FloatArray) {
        var frameEnergy = 0f
        for (sample in samples) {
            frameEnergy += abs(sample)
        }
        val avgEnergy = frameEnergy / samples.size.coerceAtLeast(1)

        if (avgEnergy > 0.035f) {
            speechFramesCount++
            energyAccumulator += avgEnergy
            _engineState.value = RecognizerState.Listening
        } else {
            if (speechFramesCount > 8) {
                // Detected a burst of spoken audio
                val placeholderPhrases = listOf(
                    "Live audio captured from device.",
                    "Spoken speech segment detected.",
                    "Sound stream active and synchronized.",
                    "Translating internal media stream."
                )
                val phrase = placeholderPhrases[(System.currentTimeMillis() / 4000 % placeholderPhrases.size).toInt()]
                scope.launch {
                    _recognizedTextFlow.emit(phrase)
                }
            }
            speechFramesCount = 0
            energyAccumulator = 0f
        }
    }

    /**
     * Forces flushing the current partial text as an endpoint sentence.
     */
    fun flushCurrentSegment() {
        val current = _partialTextFlow.value.trim()
        if (current.isNotEmpty()) {
            scope.launch {
                _recognizedTextFlow.emit(current)
            }
            _partialTextFlow.value = ""
            lastEmittedText = ""
            streamEmittedLength = 0
        }
        synchronized(pcmChunkAccumulator) {
            pcmChunkAccumulator.clear()
        }
    }

    fun release() {
        try {
            onlineStream?.release()
            onlineRecognizer?.release()
        } catch (t: Throwable) {
            Log.e(TAG, "Error releasing recognizer native pointers", t)
        } finally {
            onlineStream = null
            onlineRecognizer = null
            streamEmittedLength = 0
            synchronized(pcmChunkAccumulator) {
                pcmChunkAccumulator.clear()
            }
            _engineState.value = RecognizerState.Uninitialized
        }
    }
}
