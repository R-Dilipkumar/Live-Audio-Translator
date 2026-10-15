package com.example.asr

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Accuracy mode ("About 1 second" latency): the Silero speech detector finds speech, and an
 * offline model (SenseVoice, Whisper, Moonshine, or a NeMo/Zipformer transducer) recognizes it
 * in pieces, with punctuation and far fewer mistakes than streaming models.
 *
 * A piece ends at a pause, so subtitles come sentence by sentence:
 * - at a sentence break: a quiet gap clearly longer than this speaker's usual gap between
 *   words (shorter than the detector's 0.5 s, which fast speech rarely leaves), once the piece
 *   is at least [CHUNK_MIN_SEC] long;
 * - when someone talks without such a break for longer than this model can keep up with: at
 *   the longest gap between words in that stretch, so a word is never cut in half.
 * [PieceTiming] measures both live (the speaker's rhythm and the model's speed on this phone).
 * Pieces cut between words drop the model's full stop, so they don't end the sentence.
 *
 * Pieces are recognized on a background thread while the next one is being recorded, so audio
 * is never held up. If the model is slower than the speech, the oldest waiting piece is skipped
 * to keep subtitles current (see [takeSkippedCount]).
 *
 * [accept] and [flush] must be called from one thread (the speech engine's audio consumer).
 */
class OfflinePhraseRecognizer private constructor(
    private val vad: Vad,
    private val recognizer: OfflineRecognizer,
    private val family: OfflineFamily
) {
    /**
     * A recognized piece. [language] is the language the model heard (SenseVoice, Whisper), as a
     * two-letter code, or null. [isSpeech] is false for music, laughter or applause.
     */
    data class Phrase(
        val text: String,
        val language: String?,
        val isSpeech: Boolean,
        val durationSec: Float,
        // How long recognizing it took; longer than durationSec means the model can't keep up
        val decodeSec: Float = 0f,
        // Cut while the speaker kept talking, so its end is not a sentence end
        val cutMidSpeech: Boolean = false
    )

    private class Piece(val samples: FloatArray, val cutMidSpeech: Boolean)

    private val window = FloatArray(VAD_WINDOW)
    private var windowFill = 0

    // The last few seconds given to the speech detector: pieces are cut from it, and a phrase
    // gets a bit of audio from just before the detector noticed it (quiet first sounds, like
    // the "e" of 駅, are otherwise cut off)
    private val recent = FloatArray(RECENT_SAMPLES)
    private var samplesFedToVad = 0L

    // The piece being cut from ongoing speech: where it starts (-1 while nobody is talking), where
    // the last cut was, and the typical loudness of the speech, to find the quiet gaps
    private var pieceStart = -1L
    private var lastCut = -1L
    private var speechLevel = 0f
    // The quiet gap going on now (in windows, and where it began), and the longest gap between
    // words in this piece so far (its middle), for when the piece must be cut without a break
    private var quietRun = 0
    private var quietRunStart = 0L
    private var bestGapRun = 0
    private var bestGapMiddle = -1L

    // Sentence-break length and longest piece, learned from this speaker and this model
    private val timing = PieceTiming(initialSpeed = if (family == OfflineFamily.WHISPER) 0.4f else 0.15f)

    // Background recognition: pieces waiting, results ready, and pieces not yet finished
    private val waiting = LinkedBlockingDeque<Piece>()
    private val results = ConcurrentLinkedQueue<Phrase>()
    private val unfinished = AtomicInteger(0)
    private val skipped = AtomicInteger(0)
    @Volatile private var closed = false
    private val worker = Thread({ recognizeLoop() }, "PhraseRecognizer").apply {
        isDaemon = true
        start()
    }

    /** True while the speech detector hears someone talking. */
    val isSpeaking: Boolean get() = vad.isSpeechDetected()

    /** Feeds 16 kHz mono samples. Returns the pieces recognized since the last call. */
    fun accept(samples: FloatArray): List<Phrase> {
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(VAD_WINDOW - windowFill, samples.size - offset)
            System.arraycopy(samples, offset, window, windowFill, count)
            windowFill += count
            offset += count
            if (windowFill == VAD_WINDOW) {
                // The detector works on fixed 512-sample windows
                remember(window)
                vad.acceptWaveform(window)
                windowFill = 0
                cutLongSpeech(window)
                drainSegments()
            }
        }
        return takeResults()
    }

    /**
     * Ends the speech in progress (capture stopped), waits for the waiting pieces to be
     * recognized (up to [FLUSH_WAIT_MS]) and returns them.
     */
    fun flush(): List<Phrase> {
        vad.flush()
        drainSegments()
        vad.reset()
        windowFill = 0
        // The detector counts samples from zero again after a reset
        samplesFedToVad = 0L
        pieceStart = -1L
        lastCut = -1L
        speechLevel = 0f
        quietRun = 0
        bestGapRun = 0
        bestGapMiddle = -1L
        // The next capture may be another video or speaker
        timing.resetRhythm()
        // Called from the capture thread, which is interrupted when capture ends: keep waiting
        // for the last pieces anyway (an escaping InterruptedException would crash the app),
        // then restore the interrupt for the caller
        val deadline = System.currentTimeMillis() + FLUSH_WAIT_MS
        var interrupted = false
        while (unfinished.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        return takeResults()
    }

    /** Pieces skipped since the last call because the model couldn't keep up. */
    fun takeSkippedCount(): Int = skipped.getAndSet(0)

    fun release() {
        closed = true
        worker.interrupt()
        try {
            worker.join(RELEASE_WAIT_MS)
        } catch (_: InterruptedException) {
        }
        try {
            vad.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Error releasing speech detector: ${t.message}")
        }
        if (worker.isAlive) {
            // Still inside the model: freeing its native memory now would crash the app
            Log.w(TAG, "Recognizer still busy after ${RELEASE_WAIT_MS} ms; not freeing it")
            return
        }
        try {
            recognizer.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Error releasing offline recognizer: ${t.message}")
        }
    }

    /**
     * While someone keeps talking, sends a piece at each sentence break, or once it reaches the
     * longest piece this model keeps up with, at the longest gap between words (see [PieceTiming]).
     */
    private fun cutLongSpeech(window: FloatArray) {
        if (!vad.isSpeechDetected()) {
            quietRun = 0
            return
        }
        val now = samplesFedToVad
        if (pieceStart < 0) {
            // Speech just began; the detector notices it a little late, so start a bit earlier
            pieceStart = maxOf(0L, lastCut, now - SPEECH_ONSET_SAMPLES - LEAD_IN_SAMPLES)
            speechLevel = 0f
            quietRun = 0
            bestGapRun = 0
            bestGapMiddle = -1L
        }
        val level = rms(window)
        val isQuiet = speechLevel > 0f && level < speechLevel * GAP_LEVEL
        if (isQuiet) {
            if (quietRun == 0) quietRunStart = now - VAD_WINDOW
            quietRun++
            val gapMiddle = (quietRunStart + now) / 2
            val longEnough = gapMiddle - pieceStart >= CHUNK_MIN_SAMPLES
            if (longEnough && quietRun > bestGapRun) {
                bestGapRun = quietRun
                bestGapMiddle = gapMiddle
            }
            if (longEnough && quietRun >= windowsFor(timing.sentenceBreakSec)) {
                // A sentence break: keep the model's punctuation
                cutAt(gapMiddle, cutMidSpeech = false, reason = "sentence break")
                return
            }
        } else {
            // The speaker went on after a gap: part of their rhythm (gaps that ended the
            // phrase are not, they never reach here)
            if (quietRun >= WORD_GAP_WINDOWS) timing.recordWordGap(quietRun * WINDOW_SEC)
            // Only speech sets the usual loudness, so gaps don't pull it down
            speechLevel = if (speechLevel == 0f) level else speechLevel * 0.95f + level * 0.05f
            quietRun = 0
        }
        if (now - pieceStart >= (timing.maxPieceSec * SAMPLE_RATE).toLong()) {
            // No sentence break for as long as this model keeps up with: cut between words,
            // never inside one
            val atWordGap = bestGapRun >= WORD_GAP_WINDOWS
            cutAt(if (atWordGap) bestGapMiddle else now, cutMidSpeech = true,
                reason = if (atWordGap) "longest word gap" else "no gap found")
        }
    }

    private fun cutAt(position: Long, cutMidSpeech: Boolean, reason: String) {
        Log.d(TAG, "Cut a ${"%.1f".format((position - pieceStart) / SAMPLE_RATE.toFloat())} s piece at $reason " +
            "(break ${"%.2f".format(timing.sentenceBreakSec)} s, longest piece ${"%.1f".format(timing.maxPieceSec)} s, " +
            "speed ${"%.2f".format(timing.speed)})")
        enqueue(copyRecent(pieceStart, position), cutMidSpeech)
        lastCut = position
        pieceStart = position
        bestGapRun = 0
        bestGapMiddle = -1L
    }

    /** Speech that ended at a pause: sends what wasn't already sent as cut pieces. */
    private fun drainSegments() {
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            val start = segment.start.toLong()
            val end = start + segment.samples.size
            when {
                lastCut >= end -> Unit // all of it already went out in cut pieces
                lastCut > start -> {
                    val rest = segment.samples.copyOfRange((lastCut - start).toInt(), segment.samples.size)
                    // Often just the pause after a sentence break: Whisper invents text for silence
                    if (speechLevel == 0f || rms(rest) >= speechLevel * GAP_LEVEL) enqueue(rest, cutMidSpeech = false)
                }
                else -> enqueue(withLeadIn(segment.start, segment.samples), cutMidSpeech = false)
            }
            pieceStart = -1L
            speechLevel = 0f
            quietRun = 0
            bestGapRun = 0
            bestGapMiddle = -1L
        }
    }

    private fun enqueue(samples: FloatArray, cutMidSpeech: Boolean) {
        if (samples.size < MIN_PHRASE_SAMPLES) return
        // The model is slower than the speech: drop the oldest waiting piece so subtitles stay current
        while (waiting.size >= MAX_WAITING_PIECES) {
            if (waiting.pollFirst() != null) {
                unfinished.decrementAndGet()
                skipped.incrementAndGet()
                Log.w(TAG, "Skipped a piece: the model is slower than the speech")
            }
        }
        unfinished.incrementAndGet()
        waiting.addLast(Piece(samples, cutMidSpeech))
    }

    private fun recognizeLoop() {
        while (!closed) {
            val piece = try {
                waiting.pollFirst(200, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            } ?: continue
            val started = System.currentTimeMillis()
            try {
                decode(piece.samples, piece.cutMidSpeech)?.let { results.add(it) }
            } catch (t: Throwable) {
                // Nothing may escape this thread: an uncaught exception would crash the app
                Log.e(TAG, "Recognizing a piece failed", t)
            } finally {
                // Every piece counts toward the model's speed, also ones with no words in them
                timing.recordDecode(piece.samples.size / SAMPLE_RATE.toFloat(), (System.currentTimeMillis() - started) / 1000f)
                unfinished.decrementAndGet()
            }
        }
    }

    private fun takeResults(): List<Phrase> = generateSequence { results.poll() }.toList()

    private fun remember(samples: FloatArray) {
        for (sample in samples) {
            recent[(samplesFedToVad % RECENT_SAMPLES).toInt()] = sample
            samplesFedToVad++
        }
    }

    /** Samples [from, to) given to the detector (as far back as [recent] reaches). */
    private fun copyRecent(from: Long, to: Long): FloatArray {
        val first = maxOf(from, samplesFedToVad - RECENT_SAMPLES, 0L)
        if (to <= first) return FloatArray(0)
        return FloatArray((to - first).toInt()) { i -> recent[((first + i) % RECENT_SAMPLES).toInt()] }
    }

    /** [samples] (starting at sample [start]) with up to [LEAD_IN_SAMPLES] of audio before them. */
    private fun withLeadIn(start: Int, samples: FloatArray): FloatArray {
        val leadInStart = maxOf(0L, start - LEAD_IN_SAMPLES.toLong(), samplesFedToVad - RECENT_SAMPLES)
        val leadIn = (start - leadInStart).toInt()
        if (leadIn <= 0) return samples
        val out = FloatArray(leadIn + samples.size)
        for (i in 0 until leadIn) {
            out[i] = recent[((leadInStart + i) % RECENT_SAMPLES).toInt()]
        }
        System.arraycopy(samples, 0, out, leadIn, samples.size)
        return out
    }

    /** Whole detector windows covering [seconds], at least one. */
    private fun windowsFor(seconds: Float): Int = maxOf(1, kotlin.math.round(seconds / WINDOW_SEC).toInt())

    private fun rms(samples: FloatArray): Float {
        var sum = 0.0
        for (sample in samples) sum += sample * sample
        return kotlin.math.sqrt(sum / samples.size).toFloat()
    }

    /** Runs on the recognizer thread. */
    private fun decode(samples: FloatArray, cutMidSpeech: Boolean): Phrase? {
        if (samples.size < MIN_PHRASE_SAMPLES) return null
        val started = System.currentTimeMillis()
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            val durationSec = samples.size / SAMPLE_RATE.toFloat()
            // Byte markers from the safe token list (Moonshine, Whisper) back into characters
            val decoded = tidyJapanese(JniSafeTokens.decodeText(result.text).trim())
            val text = if (family == OfflineFamily.WHISPER) removeSoundLabels(decoded) else decoded
            val language = normalizeLanguageTag(result.lang)
            val isSpeech = isSpeechResult(text, result.event)
            val elapsedMs = System.currentTimeMillis() - started
            Log.d(TAG, "Phrase (${"%.1f".format(durationSec)} s audio${if (cutMidSpeech) ", cut" else ""}, $elapsedMs ms, " +
                "lang=$language event=${result.event}): $text")
            if (text.isEmpty()) return null
            return Phrase(text, language, isSpeech, durationSec, elapsedMs / 1000f, cutMidSpeech)
        } catch (t: Throwable) {
            Log.e(TAG, "Offline recognition failed", t)
            return null
        } finally {
            stream.release()
        }
    }

    /** Music, laughter or applause with (almost) no words, or a known Whisper hallucination. */
    private fun isSpeechResult(text: String, event: String?): Boolean {
        val tag = event.orEmpty().trim('<', '|', '>', ' ').lowercase()
        if (tag.isNotEmpty() && tag != "speech" && text.length < 4) return false
        if (family == OfflineFamily.WHISPER && isWhisperHallucination(text)) return false
        return true
    }

    companion object {
        private const val TAG = "OfflinePhraseRecognizer"
        private const val SAMPLE_RATE = 16000
        private const val VAD_WINDOW = 512
        // Shorter than 0.25 s is a click or breath, not a phrase
        private const val MIN_PHRASE_SAMPLES = SAMPLE_RATE / 4

        // Speech detector tuning for subtitles: a pause of 0.5 s ends a phrase (short breaths
        // inside a sentence don't). Long non-stop speech is cut into pieces by cutLongSpeech.
        private const val VAD_MIN_SILENCE_SEC = 0.5f
        private const val VAD_MIN_SPEECH_SEC = 0.25f
        private const val VAD_MAX_SPEECH_SEC = 8f

        // Speech is sent piece by piece. Quiet = below GAP_LEVEL of the speech's usual loudness.
        // A sentence break or the longest piece (both from PieceTiming) ends a piece, once it is
        // at least CHUNK_MIN_SEC long.
        const val CHUNK_MIN_SEC = 1f
        private const val CHUNK_MIN_SAMPLES = (CHUNK_MIN_SEC * SAMPLE_RATE).toLong()
        private const val WINDOW_SEC = VAD_WINDOW / SAMPLE_RATE.toFloat()
        // A dip of two windows (64 ms) is a gap between words: enough to cut there without
        // splitting a word, and the shortest gap counted in the speaker's rhythm
        private const val WORD_GAP_WINDOWS = 2
        private const val GAP_LEVEL = 0.35f
        // How late the detector notices speech: two windows plus its minimum speech length
        private const val SPEECH_ONSET_SAMPLES = 2 * VAD_WINDOW + (VAD_MIN_SPEECH_SEC * SAMPLE_RATE).toInt()
        // Pieces allowed to wait while one is being recognized; beyond that the oldest is skipped
        private const val MAX_WAITING_PIECES = 2
        // At Stop, how long to wait for the last pieces; at release, for a piece being recognized
        private const val FLUSH_WAIT_MS = 15_000L
        private const val RELEASE_WAIT_MS = 60_000L

        // Audio kept before each phrase: 0.25 s (pauses between phrases are at least 0.5 s)
        private const val LEAD_IN_SAMPLES = SAMPLE_RATE / 4
        // Enough for the longest phrase plus its lead-in and the pause after it
        private const val RECENT_SAMPLES = SAMPLE_RATE * 12

        private val CJK = Regex("[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}ー]")
        private val SPACE_BETWEEN_CJK = Regex("(?<=[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}ー])\\s+(?=[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}ー])")
        // A full stop or comma inside a word: before ます/ました/ません (which never start a
        // sentence), or inside ございます ("ありがとうござ。います")
        private val BREAK_BEFORE_MASU = Regex("[。、]\\s*(?=ま(?:す|した|せん))")
        private val BREAK_INSIDE_GOZAIMASU = Regex("(?<=ござ)[。、]\\s*(?=い)")

        // Whisper writes sounds as text: "[Music]", "(dramatic music", "*laughs*", "*c"
        private val SOUND_LABEL = Regex("""\[[^\]]*\]?|\([^)]*\)?|\*[^*]*\*?|♪+""")
        private val EXTRA_SPACES = Regex("""\s{2,}""")

        /** Whisper's sound labels removed, leaving only what was said. */
        fun removeSoundLabels(text: String): String =
            text.replace(SOUND_LABEL, " ").replace(EXTRA_SPACES, " ").trim()

        /** Removes stray spaces and full stops that SenseVoice sometimes puts inside Japanese words. */
        fun tidyJapanese(text: String): String {
            if (!CJK.containsMatchIn(text)) return text
            return text.replace(SPACE_BETWEEN_CJK, "")
                .replace(BREAK_BEFORE_MASU, "")
                .replace(BREAK_INSIDE_GOZAIMASU, "")
        }

        /**
         * Loads the speech detector and the offline model.
         * [languageHint] locks SenseVoice/Whisper to one spoken language (two-letter code);
         * null lets them detect it.
         */
        fun create(model: AsrModelConfig, files: ModelManager.ResolvedModelFiles, languageHint: String?): OfflinePhraseRecognizer {
            val family = model.offlineFamily ?: error("${model.id} is not an Accuracy-mode model")
            val vadFile = files.vadFile ?: error("Speech detector is not installed")

            val vad = Vad(
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = vadFile.absolutePath,
                        threshold = 0.5f,
                        minSilenceDuration = VAD_MIN_SILENCE_SEC,
                        minSpeechDuration = VAD_MIN_SPEECH_SEC,
                        windowSize = VAD_WINDOW,
                        maxSpeechDuration = VAD_MAX_SPEECH_SEC
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu"
                )
            )

            // Moonshine and Whisper split characters into byte pieces; the safe token list keeps
            // those from crashing the library (see JniSafeTokens)
            val tokens = when (family) {
                OfflineFamily.MOONSHINE, OfflineFamily.WHISPER -> JniSafeTokens.safeTokensFile(files.tokensFile)
                else -> files.tokensFile
            }.absolutePath
            // A phrase is decoded in one go, so more cores finish it sooner (Whisper needs them)
            val numThreads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
            val modelConfig = when (family) {
                OfflineFamily.SENSE_VOICE -> OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = files.encoderFile.absolutePath,
                        language = languageHint?.takeIf { it in model.languageHints } ?: "auto",
                        // Numbers and punctuation written out properly ("3番線" instead of "さんばんせん")
                        useInverseTextNormalization = true
                    ),
                    tokens = tokens,
                    numThreads = numThreads
                )
                OfflineFamily.WHISPER -> OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = files.encoderFile.absolutePath,
                        decoder = files.decoderFile!!.absolutePath,
                        // Blank = detect the language. Only a language Whisper knows: an unknown
                        // one can make the native library exit the app
                        language = languageHint?.takeIf { it in ModelManager.WHISPER_LANGUAGE_CODES }.orEmpty(),
                        task = "transcribe"
                    ),
                    tokens = tokens,
                    numThreads = numThreads
                )
                OfflineFamily.MOONSHINE -> OfflineModelConfig(
                    moonshine = OfflineMoonshineModelConfig(
                        encoder = files.encoderFile.absolutePath,
                        mergedDecoder = files.decoderFile!!.absolutePath
                    ),
                    tokens = tokens,
                    numThreads = numThreads
                )
                OfflineFamily.TRANSDUCER -> OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = files.encoderFile.absolutePath,
                        decoder = files.decoderFile!!.absolutePath,
                        joiner = files.joinerFile!!.absolutePath
                    ),
                    tokens = tokens,
                    numThreads = numThreads,
                    modelType = model.modelType
                )
            }

            val recognizer = try {
                OfflineRecognizer(
                    config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                        modelConfig = modelConfig,
                        decodingMethod = "greedy_search"
                    )
                )
            } catch (t: Throwable) {
                vad.release()
                throw t
            }
            return OfflinePhraseRecognizer(vad, recognizer, family)
        }

        /** "<|ja|>", "ja" or "yue" -> a two-letter translator code ("ja", "zh"), or null. */
        fun normalizeLanguageTag(tag: String?): String? {
            val code = tag.orEmpty().trim('<', '|', '>', ' ').lowercase()
            return when {
                code == "yue" -> "zh" // Cantonese is written in Chinese characters
                code.length == 2 && code.all { it in 'a'..'z' } -> code
                else -> null
            }
        }

        // Whisper often "hears" these during music or silence (it learned them from video subtitles)
        private val WHISPER_HALLUCINATIONS = listOf(
            "thank you for watching", "thanks for watching", "please subscribe", "subtitles by",
            "ご視聴ありがとうございました", "チャンネル登録", "字幕", "請不吝點贊", "订阅"
        )

        fun isWhisperHallucination(text: String): Boolean {
            val normalized = text.lowercase().trim()
            if (normalized.isEmpty()) return true
            // Only music notes, dots and brackets
            if (normalized.none { it.isLetterOrDigit() }) return true
            return WHISPER_HALLUCINATIONS.any { normalized.contains(it) } && normalized.length < 40
        }
    }
}
