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

/**
 * Accuracy mode: the Silero speech detector cuts the audio into phrases, and each finished
 * phrase is recognized at once by an offline model (SenseVoice, Whisper, Moonshine, or a
 * NeMo/Zipformer transducer). Text appears when the speaker pauses, not word by word, but it is
 * much more accurate and comes with punctuation.
 *
 * Not thread-safe: the speech engine calls it from its single audio consumer.
 */
class OfflinePhraseRecognizer private constructor(
    private val vad: Vad,
    private val recognizer: OfflineRecognizer,
    private val family: OfflineFamily
) {
    /**
     * A recognized phrase. [language] is the language the model heard (SenseVoice, Whisper),
     * as a two-letter code, or null. [isSpeech] is false for music, laughter or applause.
     */
    data class Phrase(val text: String, val language: String?, val isSpeech: Boolean, val durationSec: Float)

    private val window = FloatArray(VAD_WINDOW)
    private var windowFill = 0

    // The last few seconds given to the speech detector, so a phrase can be decoded with a bit
    // of audio from just before the detector noticed it (quiet first sounds, like the "e" of
    // 駅, are otherwise cut off)
    private val recent = FloatArray(RECENT_SAMPLES)
    private var samplesFedToVad = 0L

    /** True while the speech detector hears someone talking. */
    val isSpeaking: Boolean get() = vad.isSpeechDetected()

    /** Feeds 16 kHz mono samples. Returns phrases that finished during this audio. */
    fun accept(samples: FloatArray): List<Phrase> {
        val phrases = mutableListOf<Phrase>()
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
                drain(phrases)
            }
        }
        return phrases
    }

    /** Ends the phrase in progress (capture stopped) and returns what is left. */
    fun flush(): List<Phrase> {
        val phrases = mutableListOf<Phrase>()
        vad.flush()
        drain(phrases)
        vad.reset()
        windowFill = 0
        // The detector counts samples from zero again after a reset
        samplesFedToVad = 0L
        return phrases
    }

    fun release() {
        try {
            vad.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Error releasing speech detector: ${t.message}")
        }
        try {
            recognizer.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Error releasing offline recognizer: ${t.message}")
        }
    }

    private fun drain(into: MutableList<Phrase>) {
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            decode(withLeadIn(segment.start, segment.samples))?.let { into.add(it) }
        }
    }

    private fun remember(samples: FloatArray) {
        for (sample in samples) {
            recent[(samplesFedToVad % RECENT_SAMPLES).toInt()] = sample
            samplesFedToVad++
        }
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

    private fun decode(samples: FloatArray): Phrase? {
        if (samples.size < MIN_PHRASE_SAMPLES) return null
        val started = System.currentTimeMillis()
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            val durationSec = samples.size / SAMPLE_RATE.toFloat()
            // Byte markers from the safe token list (Moonshine, Whisper) back into characters
            val text = tidyJapanese(JniSafeTokens.decodeText(result.text).trim())
            val language = normalizeLanguageTag(result.lang)
            val isSpeech = isSpeechResult(text, result.event)
            Log.d(TAG, "Phrase (${"%.1f".format(durationSec)} s audio, ${System.currentTimeMillis() - started} ms, " +
                "lang=$language event=${result.event}): $text")
            if (text.isEmpty()) return null
            return Phrase(text, language, isSpeech, durationSec)
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

        // Speech detector tuning for subtitles:
        // a pause of 0.5 s ends a phrase (short breaths inside a sentence don't), and a phrase is
        // cut after 8 s of non-stop speech so subtitles never lag far behind
        private const val VAD_MIN_SILENCE_SEC = 0.5f
        private const val VAD_MIN_SPEECH_SEC = 0.25f
        private const val VAD_MAX_SPEECH_SEC = 8f

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
            val numThreads = 2
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
                        // Blank = detect the language
                        language = languageHint.orEmpty(),
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
