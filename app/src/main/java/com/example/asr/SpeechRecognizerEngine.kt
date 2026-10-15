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
    // language: the language a whole-phrase model heard (null for streaming models), so drafts
    // are translated from the right language too, not guessed from the text
    data class Partial(val text: String, val utteranceId: Long = 0L, val timestamp: Long = System.currentTimeMillis(), val language: String? = null) : AsrUtteranceEvent()
    // language: two-letter code of the language the model heard (Accuracy mode), or null
    data class Final(val text: String, val utteranceId: Long = 0L, val timestamp: Long = System.currentTimeMillis(), val language: String? = null) : AsrUtteranceEvent()
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

    // True while the whole-phrase model takes longer to recognize phrases than they last, so
    // subtitles fall behind and audio gets dropped (e.g. Whisper Small on many phones)
    private val _isFallingBehind = MutableStateFlow(false)
    val isFallingBehind: StateFlow<Boolean> = _isFallingBehind.asStateFlow()
    private val slowModelWatch = SlowModelWatch()

    // Structured utterance event stream differentiating Partial (draft) and Final (committed)
    private val _utteranceFlow = MutableSharedFlow<AsrUtteranceEvent>(extraBufferCapacity = 64)
    val utteranceFlow: SharedFlow<AsrUtteranceEvent> = _utteranceFlow.asSharedFlow()

    // Emits live partial recognized text
    private val _partialTextFlow = MutableStateFlow("")
    val partialTextFlow: StateFlow<String> = _partialTextFlow.asStateFlow()

    // Every Partial and Final goes through this one channel and is re-emitted by a single
    // coroutine, so collectors always see events in the order the recognizer produced them.
    private val utteranceEvents = Channel<AsrUtteranceEvent>(Channel.UNLIMITED)

    private var onlineRecognizer: OnlineRecognizer? = null
    private var onlineStream: OnlineStream? = null
    // Accuracy mode: speech detector + whole-phrase model. Set instead of onlineRecognizer.
    private var offlineRecognizer: OfflinePhraseRecognizer? = null
    private val recognizerLock = Any()

    /**
     * The language being listened to (two-letter code), or null for auto-detect. Accuracy-mode
     * models that support it (SenseVoice, Whisper) are locked to it, which stops e.g. Japanese
     * being heard as Chinese. Applied the next time the recognizer is built.
     */
    @Volatile var spokenLanguageHint: String? = null

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
    // noSpeechRestartDelay -> rule1.minTrailingSilence: seconds with no recognized words before
    //   a fresh stream starts. Shorter recovers faster after noise; longer avoids cutting slow starts.
    @Volatile var noSpeechRestartDelay: Float = DEFAULT_NO_SPEECH_RESTART_DELAY
    // minSentenceLength -> rule2.minUtteranceLength: a pause only ends a sentence once it is at
    //   least this long, so short fragments are not cut off and translated on their own.
    @Volatile var minSentenceLength: Float = DEFAULT_MIN_SENTENCE_LENGTH
    // What to do with a finished line of a single character: join it to the start of the next
    // sentence (SINGLE_CHAR_JOIN_NEXT), drop it, or keep it as its own line.
    @Volatile var singleCharacterMode: Int = SINGLE_CHAR_JOIN_NEXT

    // A one-character line waiting to be joined to the next sentence, and when it was heard
    private var pendingFragment: String? = null
    private var pendingFragmentTime = 0L

    // Holds finished pieces until the sentence looks complete, so whole sentences are translated
    private val sentenceAssembler = SentenceAssembler()
    // Accuracy mode: phrases come with punctuation, so most complete at once; the waits only
    // matter for a phrase cut at a comma or without punctuation
    private val phraseAssembler = SentenceAssembler(pauseMs = 600L, continuationPauseMs = 1_200L)
    // Language of the last recognized phrase (Accuracy mode)
    private var lastPhraseLanguage: String? = null
    var joinSentencePieces: Boolean
        get() = sentenceAssembler.isEnabled
        set(value) {
            sentenceAssembler.isEnabled = value
            phraseAssembler.isEnabled = value
        }
    // The recognizer's own text for the piece in progress (partialTextFlow also shows waiting pieces)
    private var currentRawDraft = ""

    // Drops text that is most likely music or noise before it is shown or translated
    private val noiseTextFilter = NoiseTextFilter()
    var filterNoiseText: Boolean
        get() = noiseTextFilter.isEnabled
        set(value) { noiseTextFilter.isEnabled = value }
    @Volatile var isAntiFreezeWatchdogEnabled: Boolean = true

    // Anti-freeze watchdog: time of the last partial change, final, or stream reset.
    private var lastOutputOrResetTime = 0L
    // Watchdog timestamps are initialized on the first real audio frame rather than at
    // construction time, so model-load lag cannot trigger the watchdog.
    private var audioFeedingStarted = false

    // Consistent buffer size of 0.1s (1600 samples at 16kHz 16-bit mono) aligned to 2-byte boundaries
    companion object {
        const val CHUNK_SIZE_SAMPLES = 1600
        const val DEFAULT_SILENCE_ENDPOINT_DELAY = 0.8f
        const val DEFAULT_MAX_UTTERANCE_WINDOW = 15.0f
        const val DEFAULT_NO_SPEECH_RESTART_DELAY = 1.2f
        const val DEFAULT_MIN_SENTENCE_LENGTH = 0.0f

        const val SINGLE_CHAR_JOIN_NEXT = 0
        const val SINGLE_CHAR_DROP = 1
        const val SINGLE_CHAR_KEEP = 2
        // A held character is only joined if the next sentence starts within this time.
        // Longer gaps glued stray mishearings onto unrelated lines and changed their meaning
        // (e.g. "N" + "どこで乗りますか" became "N Where do you ride").
        const val PENDING_FRAGMENT_MAX_AGE_MS = 1_200L

        private val CUT_PIECE_ENDINGS = charArrayOf('.', '!', '?', '。', '．', '！', '？', '…')

        /** Text of a piece cut mid-speech, without the sentence-ending mark the model added. */
        fun trimCutPieceEnding(text: String): String = text.trimEnd().trimEnd(*CUT_PIECE_ENDINGS).trimEnd()

        /** Joins two pieces of recognized text: no space between CJK/Thai text, a space otherwise. */
        fun joinFragments(first: String, second: String): String {
            if (first.isEmpty()) return second
            if (second.isEmpty()) return first
            val a = first.last()
            val b = second.first()
            val needsSpace = (a.isLetterOrDigit() && a.code < 0x2E80) && (b.isLetterOrDigit() && b.code < 0x2E80)
            return if (needsSpace) "$first $second" else first + second
        }
        // If audio keeps flowing but the recognizer produces no new text and no endpoint for
        // this long, the watchdog commits whatever is pending and resets the stream.
        const val ANTI_FREEZE_TIMEOUT_MS = 8_000L

        fun buildEndpointConfig(
            silenceEndpointDelay: Float,
            maxUtteranceWindow: Float,
            noSpeechRestartDelay: Float = DEFAULT_NO_SPEECH_RESTART_DELAY,
            minSentenceLength: Float = DEFAULT_MIN_SENTENCE_LENGTH
        ): EndpointConfig {
            return EndpointConfig(
                // rule1: this long with nothing recognized starts a fresh stream
                rule1 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = noSpeechRestartDelay.coerceIn(0.5f, 5.0f), minUtteranceLength = 0.0f),
                // rule2: after speech, this much trailing silence ends the utterance
                rule2 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = silenceEndpointDelay.coerceIn(0.3f, 3.0f), minUtteranceLength = minSentenceLength.coerceIn(0f, 10f)),
                // rule3: force an endpoint once the utterance is this long, even without silence
                rule3 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 0.0f, minUtteranceLength = maxUtteranceWindow.coerceIn(5.0f, 30.0f))
            )
        }
    }

    private data class LoadedConfig(
        val modelId: String,
        val silenceEndpointDelay: Float,
        val maxUtteranceWindow: Float,
        val noSpeechRestartDelay: Float,
        val minSentenceLength: Float,
        // Accuracy mode only: the spoken language the model is locked to (null = detect)
        val languageHint: String? = null
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
            }
        }
    }

    /** Called by the capture service right before it starts feeding audio. */
    fun beginCapture() {
        slowModelWatch.reset()
        _isFallingBehind.value = false
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
            val hasRecognizer = synchronized(recognizerLock) {
                (onlineRecognizer != null && onlineStream != null) || offlineRecognizer != null
            }

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

            val isAccuracyModel = resolved.model.mode == RecognitionMode.ACCURACY
            val wanted = if (isAccuracyModel) {
                // Endpoint settings don't apply: the speech detector finds the phrases
                LoadedConfig(resolved.modelId, 0f, 0f, 0f, 0f, languageHintFor(resolved.model))
            } else {
                LoadedConfig(resolved.modelId, silenceEndpointDelay, maxUtteranceWindow, noSpeechRestartDelay, minSentenceLength)
            }
            if (hasRecognizer && loadedConfig == wanted) {
                if (_engineState.value !is RecognizerState.Listening) {
                    _engineState.value = RecognizerState.Ready
                }
                return@withLock true
            }

            _engineState.value = RecognizerState.Initializing
            if (isAccuracyModel) {
                return@withLock try {
                    val phraseRecognizer = loadNative(resolved.modelId) {
                        OfflinePhraseRecognizer.create(resolved.model, resolved.files, wanted.languageHint)
                    }
                    synchronized(recognizerLock) {
                        releaseRecognizersLocked()
                        offlineRecognizer = phraseRecognizer
                        loadedConfig = wanted
                        resetUtteranceState()
                    }
                    _engineState.value = RecognizerState.Ready
                    Log.i(TAG, "Accuracy-mode recognizer ready: model=${resolved.modelId} language=${wanted.languageHint ?: "auto"}")
                    true
                } catch (t: Throwable) {
                    val error = "Failed to load ${resolved.model.name}: ${t.localizedMessage}"
                    Log.e(TAG, error, t)
                    _engineState.value = RecognizerState.Error(error)
                    NotificationHelper.showErrorNotification(context, "ASR Engine Crash", error)
                    false
                }
            }
            try {
                val modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = resolved.encoderFile.absolutePath,
                        decoder = resolved.decoderFile!!.absolutePath,
                        joiner = resolved.joinerFile!!.absolutePath
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
                    endpointConfig = buildEndpointConfig(wanted.silenceEndpointDelay, wanted.maxUtteranceWindow, wanted.noSpeechRestartDelay, wanted.minSentenceLength),
                    enableEndpoint = true,
                    decodingMethod = "greedy_search"
                )

                // OnlineRecognizer(config) with absolute file paths rather than OnlineRecognizer(assetManager, config)
                val recognizer = loadNative(resolved.modelId) { OnlineRecognizer(config = recognizerConfig) }
                val stream = recognizer.createStream()

                synchronized(recognizerLock) {
                    releaseRecognizersLocked()
                    onlineRecognizer = recognizer
                    onlineStream = stream
                    loadedConfig = wanted
                    resetUtteranceState()
                }
                _engineState.value = RecognizerState.Ready
                Log.i(TAG, "Sherpa-ONNX recognizer ready: model=${resolved.modelId} type='${resolved.modelType}' " +
                        "silence=${wanted.silenceEndpointDelay}s maxUtterance=${wanted.maxUtteranceWindow}s " +
                        "restartAfterNoWords=${wanted.noSpeechRestartDelay}s minSentence=${wanted.minSentenceLength}s")
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
     * Runs a native model load under ModelManager's crash guard: if a damaged file makes the
     * native code abort the app, that model is removed on the next start instead of crashing
     * again on every launch.
     */
    private inline fun <T> loadNative(modelId: String, load: () -> T): T {
        modelManager.beginNativeLoad(modelId)
        try {
            return load()
        } finally {
            modelManager.endNativeLoad()
        }
    }

    data class LoadedModelFiles(
        val model: AsrModelConfig,
        val files: ModelManager.ResolvedModelFiles
    ) {
        val modelId: String get() = model.id
        val modelType: String get() = model.modelType
        val encoderFile: File get() = files.encoderFile
        val decoderFile: File? get() = files.decoderFile
        val joinerFile: File? get() = files.joinerFile
        val tokensFile: File get() = files.tokensFile
    }

    /** The spoken language to lock [model] to, if it supports locking; null = detect. */
    private fun languageHintFor(model: AsrModelConfig): String? {
        val hint = spokenLanguageHint?.lowercase() ?: return null
        return when {
            // Only a language Whisper knows; otherwise it detects the language itself
            model.languageHints == ModelManager.WHISPER_ANY_LANGUAGE ->
                hint.takeIf { it in ModelManager.WHISPER_LANGUAGE_CODES }
            hint in model.languageHints -> hint
            else -> null
        }
    }

    /**
     * Resolves the files to load: exactly the selected model, from its own folder
     * (models/<model id>/). Returns null if that model is not installed; never falls back
     * to another model. ModelManager's size checks (> 1MB encoder etc.) guard against
     * truncated files that would crash the native loader.
     */
    internal fun resolveModelForLoad(): LoadedModelFiles? {
        val resolved = modelManager.resolveSelectedModelFiles() ?: return null
        return LoadedModelFiles(model = resolved.model, files = resolved)
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
        slowModelWatch.reset()
        _isFallingBehind.value = false
        _partialTextFlow.value = ""
        currentRawDraft = ""
        sentenceAssembler.clear()
        phraseAssembler.clear()
        lastPhraseLanguage = null
        noiseTextFilter.reset()
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
        // Accuracy mode: the speech detector buffers audio itself
        synchronized(recognizerLock) {
            val phraseRecognizer = offlineRecognizer
            if (phraseRecognizer != null) {
                feedPhraseRecognizer(phraseRecognizer, samples)
                return
            }
        }
        synchronized(pcmChunkAccumulator) {
            for (sample in samples) {
                pcmChunkAccumulator.addLast(sample)
            }
        }

        synchronized(recognizerLock) {
            val recognizer = onlineRecognizer
            val initialStream = onlineStream

            if (recognizer != null && initialStream != null) {
                var stream: OnlineStream = initialStream
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
                        // Live text without music/noise scraps (finished pieces are filtered in handleFinishedText)
                        val liveText = noiseTextFilter.filter(sanitizedDraft, learn = false).orEmpty()
                        val now = System.currentTimeMillis()

                        if (recognizer.isEndpoint(stream)) {
                            val epochId = currentUtteranceId
                            handleFinishedText(sanitizedDraft, epochId, now)
                            stream = finishUtterance(recognizer, stream, now)
                        } else if (isAntiFreezeWatchdogEnabled && now - lastOutputOrResetTime >= ANTI_FREEZE_TIMEOUT_MS) {
                            Log.w(TAG, "Anti-freeze watchdog: no recognizer output for ${now - lastOutputOrResetTime} ms while audio flows; resetting stream")
                            handleFinishedText(sanitizedDraft, currentUtteranceId, now)
                            stream = finishUtterance(recognizer, stream, now)
                        } else if (liveText.isNotBlank()) {
                            // The recognizer's own text: on flush, handleFinishedText adds a held
                            // piece itself, so storing it with the piece added doubled that piece
                            currentRawDraft = liveText
                            // Show a held one-character piece in front of the new sentence
                            val shown = withPendingFragment(liveText, now, consume = false)
                            // ...and any finished pieces still waiting for the sentence to end
                            val draft = sentenceAssembler.onDraft(shown, currentUtteranceId)
                            if (draft.text != _partialTextFlow.value) {
                                lastOutputOrResetTime = now
                                _partialTextFlow.value = draft.text
                                emitPartialUtterance(draft.text, draft.utteranceId)
                            }
                        }

                        // A waiting sentence ends once the speaker has been quiet long enough
                        sentenceAssembler.onTick(now, isSpeaking = liveText.isNotBlank())?.let { sentence ->
                            Log.d(TAG, "Sentence complete after pause (utteranceId=${sentence.utteranceId}): ${sentence.text}")
                            emitFinalUtterance(sentence.text, sentence.utteranceId)
                            _partialTextFlow.value = ""
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

    /**
     * Must hold [recognizerLock]. Clears the partial and starts the next utterance on a fresh stream.
     *
     * A fresh stream is used instead of recognizer.reset(stream): with the multilingual model,
     * reset streams often stopped producing any text after a few sentences (checked offline on a
     * recording from a device: reset gave 3 phrases in 33 s, fresh streams gave 7).
     */
    private fun finishUtterance(recognizer: OnlineRecognizer, stream: OnlineStream, now: Long): OnlineStream {
        // Keep showing pieces that are waiting for the rest of their sentence
        _partialTextFlow.value = sentenceAssembler.pendingText()
        currentRawDraft = ""
        val next = recognizer.createStream()
        onlineStream = next
        try {
            stream.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed releasing finished stream: ${t.message}")
        }
        lastOutputOrResetTime = now
        // Advance to next utterance epoch for subsequent speech
        currentUtteranceId = utteranceEpochCounter.incrementAndGet()
        return next
    }

    /**
     * Must hold [recognizerLock]. Emits a finished sentence. A one-character result is held and
     * joined to the start of the next sentence (default), dropped, or kept, per [singleCharacterMode].
     * Splitting a sentence after its first character would otherwise change its meaning.
     */
    private fun handleFinishedText(rawText: String, utteranceId: Long, now: Long) {
        if (rawText.isBlank()) return
        val text = noiseTextFilter.filter(rawText, learn = true)
        if (text == null) {
            Log.d(TAG, "Dropped likely music/noise text: $rawText")
            return
        }
        val isSingleCharacter = text.codePointCount(0, text.length) == 1
        if (isSingleCharacter && singleCharacterMode == SINGLE_CHAR_JOIN_NEXT) {
            // Two lone characters in a row are noise, not a split sentence: keep only the newest
            pendingFragment?.let { Log.d(TAG, "Dropped one-character result (another followed it): $it") }
            pendingFragment = text
            pendingFragmentTime = now
            Log.d(TAG, "Holding one-character result to join with the next sentence: $text")
            return
        }
        if (isSingleCharacter && singleCharacterMode == SINGLE_CHAR_DROP) {
            Log.d(TAG, "Dropped one-character result: $text")
            return
        }
        val full = withPendingFragment(text, now, consume = true)
        val sentence = sentenceAssembler.onPiece(full, utteranceId, now)
        if (sentence.isFinal) {
            Log.d(TAG, "Speech endpoint reached (utteranceId=${sentence.utteranceId}). Emitting Final: ${sentence.text}")
            emitFinalUtterance(sentence.text, sentence.utteranceId)
        } else {
            Log.d(TAG, "Piece held until the sentence ends (utteranceId=${sentence.utteranceId}): ${sentence.text}")
            emitPartialUtterance(sentence.text, sentence.utteranceId)
        }
    }

    /** Puts a recent held fragment in front of [text]. With [consume], the fragment is used up. */
    private fun withPendingFragment(text: String, now: Long, consume: Boolean): String {
        val fragment = pendingFragment ?: return text
        if (now - pendingFragmentTime > PENDING_FRAGMENT_MAX_AGE_MS) {
            Log.d(TAG, "Dropped one-character result (no speech right after it): $fragment")
            pendingFragment = null
            return text
        }
        if (consume) pendingFragment = null
        return joinFragments(fragment, text)
    }

    private fun emitPartialUtterance(draft: String, utteranceId: Long, language: String? = null) {
        val trimmed = draft.trim()
        if (!SpeechValidator.isGenuineSpeech(trimmed)) return
        utteranceEvents.trySend(AsrUtteranceEvent.Partial(trimmed, utteranceId = utteranceId, language = language))
    }

    /** Must hold [recognizerLock]. Accuracy mode: detect phrases, recognize, join, emit. */
    private fun feedPhraseRecognizer(phraseRecognizer: OfflinePhraseRecognizer, samples: FloatArray) {
        try {
            _engineState.value = RecognizerState.Listening
            val phrases = phraseRecognizer.accept(samples)
            val now = System.currentTimeMillis()
            phrases.forEach {
                _isFallingBehind.value = slowModelWatch.record(it.durationSec, it.decodeSec)
                handlePhrase(it, now)
            }
            // Pieces were skipped because the model is slower than the speech
            if (phraseRecognizer.takeSkippedCount() > 0) _isFallingBehind.value = true
            // A phrase cut at a comma waits briefly for the rest of its sentence
            phraseAssembler.onTick(now, isSpeaking = phraseRecognizer.isSpeaking)?.let { sentence ->
                emitFinalUtterance(sentence.text, sentence.utteranceId, lastPhraseLanguage)
                _partialTextFlow.value = ""
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Runtime error in phrase recognizer", t)
            _engineState.value = RecognizerState.Error("Speech recognizer runtime error: ${t.localizedMessage}")
        }
    }

    /** Must hold [recognizerLock]. */
    private fun handlePhrase(phrase: OfflinePhraseRecognizer.Phrase, now: Long) {
        if (!phrase.isSpeech) {
            Log.d(TAG, "Dropped music/laughter: ${phrase.text}")
            return
        }
        if (!SpeechValidator.isGenuineSpeech(phrase.text)) return
        val text = noiseTextFilter.filter(phrase.text, learn = true)
        if (text == null) {
            Log.d(TAG, "Dropped likely music/noise text: ${phrase.text}")
            return
        }
        // A piece cut while the speaker kept talking: its full stop is the model's guess, not
        // the end of the sentence, so it must not end the sentence
        val piece = if (phrase.cutMidSpeech) trimCutPieceEnding(text) else text
        if (piece.isBlank()) return
        phrase.language?.let { lastPhraseLanguage = it }
        // Each sentence in the piece becomes its own subtitle (line, card and history entry);
        // an unfinished last part waits for the rest of its sentence
        for (part in SentenceAssembler.splitSentences(piece)) {
            val utteranceId = currentUtteranceId
            currentUtteranceId = utteranceEpochCounter.incrementAndGet()
            val sentence = phraseAssembler.onPiece(part, utteranceId, now)
            if (sentence.isFinal) {
                emitFinalUtterance(sentence.text, sentence.utteranceId, lastPhraseLanguage)
                _partialTextFlow.value = ""
            } else {
                // Translated right away as a draft, so subtitles show up every few seconds; the
                // whole sentence replaces it once complete (and only that is saved to history)
                _partialTextFlow.value = sentence.text
                emitPartialUtterance(sentence.text, sentence.utteranceId, lastPhraseLanguage)
            }
        }
    }

    private fun emitFinalUtterance(clause: String, utteranceId: Long, language: String? = null) {
        val trimmed = clause.trim()
        if (!SpeechValidator.isGenuineSpeech(trimmed)) {
            Log.d(TAG, "Suppressed non-speech status/diagnostic phrase from ASR emission: $trimmed")
            return
        }
        synchronized(confirmedAsrEvents) {
            // Re-adding doesn't move an entry in a LinkedHashSet: remove first, so a sentence
            // said again ("はい。") counts as newest and isn't evicted while being translated
            confirmedAsrEvents.remove(trimmed)
            confirmedAsrEvents.add(trimmed)
            if (confirmedAsrEvents.size > 200) {
                val it = confirmedAsrEvents.iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
        }
        utteranceEvents.trySend(AsrUtteranceEvent.Final(trimmed, utteranceId = utteranceId, language = language))
    }

    /**
     * Forces flushing the current partial text as an endpoint sentence.
     */
    fun flushCurrentSegment() {
        synchronized(recognizerLock) {
            offlineRecognizer?.let { phraseRecognizer ->
                val now = System.currentTimeMillis()
                phraseRecognizer.flush().forEach { handlePhrase(it, now) }
                phraseAssembler.flush()?.let { emitFinalUtterance(it.text, it.utteranceId, lastPhraseLanguage) }
                _partialTextFlow.value = ""
                return
            }
            val recognizer = onlineRecognizer
            val stream = onlineStream
            val current = currentRawDraft.trim()
            if (current.isNotEmpty()) {
                val epochId = currentUtteranceId
                handleFinishedText(current, epochId, System.currentTimeMillis())
                _partialTextFlow.value = ""
                if (recognizer != null && stream != null) {
                    try {
                        finishUtterance(recognizer, stream, System.currentTimeMillis())
                    } catch (_: Exception) {}
                } else {
                    currentUtteranceId = utteranceEpochCounter.incrementAndGet()
                }
            }
            // Don't leave the last pieces waiting when capture stops
            sentenceAssembler.flush()?.let { emitFinalUtterance(it.text, it.utteranceId) }
            // A held one-character piece only joins speech that follows it, and none will now
            // (same rule as withPendingFragment's time limit); don't carry it into the next capture
            pendingFragment?.let { Log.d(TAG, "Dropped one-character result (capture stopped): $it") }
            pendingFragment = null
            _partialTextFlow.value = ""
            currentRawDraft = ""
        }
        synchronized(pcmChunkAccumulator) {
            pcmChunkAccumulator.clear()
        }
    }

    fun release() {
        synchronized(recognizerLock) {
            releaseRecognizersLocked()
            loadedConfig = null
            resetUtteranceState()
            _engineState.value = RecognizerState.Uninitialized
        }
    }

    /** Must hold [recognizerLock]. Frees whichever recognizer is loaded. */
    private fun releaseRecognizersLocked() {
        try {
            onlineStream?.release()
            onlineRecognizer?.release()
        } catch (t: Throwable) {
            Log.e(TAG, "Error releasing recognizer native pointers", t)
        }
        offlineRecognizer?.release()
        onlineStream = null
        onlineRecognizer = null
        offlineRecognizer = null
    }
}
