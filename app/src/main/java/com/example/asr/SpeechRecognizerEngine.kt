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
import java.io.FileOutputStream
import kotlin.math.abs

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

    private var onlineRecognizer: OnlineRecognizer? = null
    private var onlineStream: OnlineStream? = null
    private val recognizerLock = Any()
    private var lastEmittedText = ""
    private var streamEmittedLength = 0
    private var isSherpaLoaded = false

    // Monotonically incrementing utterance epoch tag
    private val utteranceEpochCounter = java.util.concurrent.atomic.AtomicLong(1L)
    @Volatile
    private var currentUtteranceId: Long = 1L

    // Configurable endpointing parameters
    // rule1: 2.4f trailing silence for short phrases to prevent mid-sentence cuts
    // rule2: 1.4f trailing silence after continuous speech
    // rule3: 25.0f allow long continuous clauses before forcing an endpoint
    var silenceEndpointDelay: Float = 1.4f
    var maxUtteranceWindow: Float = 25.0f
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

        // Safety check: verify complete, non-empty ONNX model files (> 1MB) exist before calling native C++
        val resolved = resolveVerifiedModelFiles()
        if (resolved == null) {
            val error = "ASR Model not initialized. Please install model in Manage Models."
            Log.w(TAG, error)
            _engineState.value = RecognizerState.Error(error)
            return@runBlocking false
        }

        _engineState.value = RecognizerState.Initializing

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

            // Tuned endpointing parameters:
            // rule1: minTrailingSilence = 2.4f (trailing silence for short phrases to prevent mid-sentence cuts)
            // rule2: minTrailingSilence = 1.4f (trailing silence after continuous speech > 3.0s)
            // rule3: minUtteranceLength = 25.0f (allow long continuous clauses before forcing an endpoint)
            val endpointConfig = EndpointConfig(
                rule1 = com.k2fsa.sherpa.onnx.EndpointRule(mustContainNonSilence = false, minTrailingSilence = 2.4f, minUtteranceLength = 0.0f),
                rule2 = com.k2fsa.sherpa.onnx.EndpointRule(mustContainNonSilence = true, minTrailingSilence = 1.4f, minUtteranceLength = 3.0f),
                rule3 = com.k2fsa.sherpa.onnx.EndpointRule(mustContainNonSilence = false, minTrailingSilence = 0.0f, minUtteranceLength = 25.0f)
            )

            val recognizerConfig = OnlineRecognizerConfig(
                featConfig = featConfig,
                modelConfig = modelConfig,
                endpointConfig = endpointConfig,
                enableEndpoint = true,
                decodingMethod = "greedy_search"
            )

            // OnlineRecognizer(config) with absolute file paths rather than OnlineRecognizer(assetManager, config)
            val recognizer = OnlineRecognizer(
                config = recognizerConfig
            )

            synchronized(recognizerLock) {
                onlineRecognizer = recognizer
                onlineStream = recognizer.createStream()
            }
            _engineState.value = RecognizerState.Ready
            Log.i(TAG, "Sherpa-ONNX speech recognizer initialized successfully with model: ${resolved.modelId}")
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
     * Initializes the recognizer asynchronously in a background coroutine worker.
     * Safely checks that complete model files (> 1MB) exist on disk before invoking native C++ constructors.
     * If model files are missing or incomplete, cleanly sets [RecognizerState.NotReady].
     */
    fun initializeEngineAsync(onComplete: ((Boolean) -> Unit)? = null) {
        scope.launch(Dispatchers.IO) {
            val resolved = resolveVerifiedModelFiles()
            if (resolved == null) {
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
        val encoderFile: File,
        val decoderFile: File,
        val joinerFile: File,
        val tokensFile: File
    )

    /**
     * Checks if internal models directory contains complete, verified ONNX model files (> 1MB per file).
     */
    fun hasValidModelFilesOnDisk(): Boolean {
        return resolveVerifiedModelFiles() != null
    }

    /**
     * Resolves verified model files using Dual-Path loading:
     * 1. Primary path: internal app storage (`context.filesDir/models/sherpa-onnx`).
     * 2. Fallback path: Android assets only if present, copying to internal storage.
     * 3. Fallback path: ModelManager resolution with strict > 1MB validation.
     * Requires non-empty size validation (> 1MB per ONNX file, > 100 bytes for tokens)
     * to prevent native SIGABRT crashes caused by stub/truncated files.
     */
    private fun resolveVerifiedModelFiles(): LoadedModelFiles? {
        val sherpaDir = File(context.filesDir, "models/sherpa-onnx")

        // 1. Primary Path: Internal app storage models/sherpa-onnx
        resolveFromDirectory(sherpaDir, "sherpa-onnx-internal")?.let { return it }

        // 2. Fallback: Android assets only if present
        if (copyModelsFromAssetsIfPresent(sherpaDir)) {
            resolveFromDirectory(sherpaDir, "sherpa-onnx-assets")?.let { return it }
        }

        // 3. Fallback: ModelManager resolution with strict validation
        val resolved = modelManager.resolveModelFiles(modelManager.selectedModel.value)
        if (resolved != null &&
            resolved.encoderFile.length() > 5_000_000L &&
            resolved.decoderFile.length() > 500_000L &&
            resolved.joinerFile.length() > 50_000L &&
            resolved.tokensFile.length() > 50L
        ) {
            return LoadedModelFiles(
                modelId = resolved.model.id,
                encoderFile = resolved.encoderFile,
                decoderFile = resolved.decoderFile,
                joinerFile = resolved.joinerFile,
                tokensFile = resolved.tokensFile
            )
        }
        return null
    }

    /**
     * Copies Sherpa-ONNX model files from assets if present.
     * Scans candidate subdirectories and flattens into targetDir.
     */
    private fun copyModelsFromAssetsIfPresent(targetDir: File): Boolean {
        try {
            val assetManager = context.assets ?: return false
            val candidateDirs = listOf("sherpa-onnx", "models/sherpa-onnx", "")
            for (subDir in candidateDirs) {
                val list = assetManager.list(subDir) ?: continue
                val hasEncoder = list.any { it.contains("encoder", ignoreCase = true) && it.endsWith(".onnx", ignoreCase = true) }
                val hasDecoder = list.any { it.contains("decoder", ignoreCase = true) && it.endsWith(".onnx", ignoreCase = true) }
                val hasJoiner = list.any { it.contains("joiner", ignoreCase = true) && it.endsWith(".onnx", ignoreCase = true) }
                val hasTokens = list.any { it.contains("tokens", ignoreCase = true) && it.endsWith(".txt", ignoreCase = true) }

                if (hasEncoder && hasDecoder && hasJoiner && hasTokens) {
                    targetDir.mkdirs()
                    for (fileName in list) {
                        val assetPath = if (subDir.isEmpty()) fileName else "$subDir/$fileName"
                        val destFile = File(targetDir, fileName)
                        if (!destFile.exists() || destFile.length() == 0L) {
                            assetManager.open(assetPath).use { input ->
                                FileOutputStream(destFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                    modelManager.flattenSherpaModelDirectory(targetDir)
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Asset fallback check failed: ${e.message}")
        }
        return false
    }

    private fun resolveFromDirectory(dir: File, id: String): LoadedModelFiles? {
        if (!dir.exists() || !dir.isDirectory) return null

        val minEncoderSize = 5_000_000L // 5 MB
        val minDecoderSize = 500_000L   // 500 KB
        val minJoinerSize = 50_000L     // 50 KB (joiner is ~260 KB)
        val minTokensSize = 50L         // 50 bytes
        val enc = File(dir, "encoder.int8.onnx").let { if (it.exists() && it.length() > minEncoderSize) it else modelManager.findFileByKeyword(dir, "encoder") }
        val dec = File(dir, "decoder.int8.onnx").let { if (it.exists() && it.length() > minDecoderSize) it else modelManager.findFileByKeyword(dir, "decoder") }
        val joi = File(dir, "joiner.int8.onnx").let { if (it.exists() && it.length() > minJoinerSize) it else modelManager.findFileByKeyword(dir, "joiner") }
        val tok = File(dir, "tokens.txt").let { if (it.exists() && it.length() > minTokensSize) it else modelManager.findFileByKeyword(dir, "tokens") }

        if (enc != null && enc.length() > minEncoderSize &&
            dec != null && dec.length() > minDecoderSize &&
            joi != null && joi.length() > minJoinerSize &&
            tok != null && tok.length() > minTokensSize
        ) {
            return LoadedModelFiles(id, enc, dec, joi, tok)
        }
        return null
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
                        // Accumulate full clause in progress
                        val currentText = fullText.trim()
                        val sanitizedDraft = if (SpeechValidator.isGenuineSpeech(currentText)) currentText else ""

                        val isEndpoint = recognizer.isEndpoint(stream)
                        if (isEndpoint) {
                            val epochId = currentUtteranceId
                            if (sanitizedDraft.isNotBlank()) {
                                Log.d(TAG, "Speech endpoint reached (utteranceId=$epochId). Emitting Final: $sanitizedDraft")
                                lastOutputOrResetTime = System.currentTimeMillis()
                                emitFinalUtterance(sanitizedDraft, epochId)
                            }
                            _partialTextFlow.value = ""
                            lastEmittedText = ""
                            streamEmittedLength = 0
                            // Synchronously reset recognizer stream to be immediately ready for subsequent incoming audio frames
                            recognizer.reset(stream)
                            // Advance to next utterance epoch for subsequent speech
                            currentUtteranceId = utteranceEpochCounter.incrementAndGet()
                        } else {
                            if (sanitizedDraft.isNotBlank() && sanitizedDraft != _partialTextFlow.value) {
                                _partialTextFlow.value = sanitizedDraft
                                emitPartialUtterance(sanitizedDraft, currentUtteranceId)
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

    private fun emitPartialUtterance(draft: String, utteranceId: Long) {
        val trimmed = draft.trim()
        if (!SpeechValidator.isGenuineSpeech(trimmed)) return
        val event = AsrUtteranceEvent.Partial(trimmed, utteranceId = utteranceId)
        if (!_utteranceFlow.tryEmit(event)) {
            scope.launch { _utteranceFlow.emit(event) }
        }
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
        val event = AsrUtteranceEvent.Final(trimmed, utteranceId = utteranceId)
        if (!_utteranceFlow.tryEmit(event)) {
            scope.launch { _utteranceFlow.emit(event) }
        }
        if (!_recognizedTextFlow.tryEmit(trimmed)) {
            scope.launch {
                _recognizedTextFlow.emit(trimmed)
            }
        }
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
                lastEmittedText = ""
                streamEmittedLength = 0
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
