package com.example.translate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * What a [TranslationResult] actually contains. Only [isTranslation] results hold a translation
 * of the speech; the others carry a short status message in translatedText for display and must
 * not be saved to history.
 */
enum class TranslationStatus(val isTranslation: Boolean) {
    TRANSLATED(true),
    // Source and target language are the same; the text is shown as-is.
    SAME_LANGUAGE(true),
    // ML Kit failed or timed out and the built-in phrase dictionary had a match.
    OFFLINE_FALLBACK(true),
    // The language pack for this pair is not downloaded.
    PACK_MISSING(false),
    // Auto-detect could not identify the spoken language yet.
    LANGUAGE_UNKNOWN(false),
    // ML Kit failed or timed out and no fallback was available.
    FAILED(false)
}

data class TranslationRequest(
    val text: String,
    val isFinal: Boolean,
    val utteranceId: Long,
    // Language the speech model heard (from the sound), or null
    val languageHint: String? = null
)

/**
 * Runs translation requests one at a time, in order, on a single consumer.
 *
 * - Finals are processed in the order they were submitted.
 * - Only the newest partial is kept; an older queued partial is replaced (item: never translate
 *   a backlog of stale drafts).
 * - A partial is dropped if a final for the same or a newer utterance has been submitted,
 *   whether it is still queued or already translating.
 */
class TranslationQueue(
    scope: CoroutineScope,
    private val process: suspend (TranslationRequest) -> TranslationResult?,
    private val emit: suspend (TranslationResult) -> Unit
) {
    private val lock = Any()
    private val pendingFinals = ArrayDeque<TranslationRequest>()
    private var pendingPartial: TranslationRequest? = null
    private var highestFinalId = Long.MIN_VALUE
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    /** Partials skipped because a newer partial or a final replaced them. */
    val droppedPartials = AtomicLong(0)

    init {
        scope.launch {
            for (signal in wakeUp) {
                while (true) {
                    val request = next() ?: break
                    val result = try {
                        process(request)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // process() logs its own failures; one bad request must not stop the queue.
                        null
                    } ?: continue
                    if (!request.isFinal && isSuperseded(request)) {
                        droppedPartials.incrementAndGet()
                        continue
                    }
                    emit(result)
                }
            }
        }
    }

    fun submit(request: TranslationRequest) {
        synchronized(lock) {
            if (request.isFinal) {
                pendingFinals.addLast(request)
                if (request.utteranceId > highestFinalId) highestFinalId = request.utteranceId
                val partial = pendingPartial
                if (partial != null && partial.utteranceId <= request.utteranceId) {
                    pendingPartial = null
                    droppedPartials.incrementAndGet()
                }
            } else {
                if (request.utteranceId <= highestFinalId) {
                    droppedPartials.incrementAndGet()
                    return
                }
                if (pendingPartial != null) droppedPartials.incrementAndGet()
                pendingPartial = request
            }
        }
        wakeUp.trySend(Unit)
    }

    private fun next(): TranslationRequest? = synchronized(lock) {
        pendingFinals.removeFirstOrNull() ?: pendingPartial.also { pendingPartial = null }
    }

    private fun isSuperseded(partial: TranslationRequest): Boolean = synchronized(lock) {
        partial.utteranceId <= highestFinalId
    }
}

/**
 * Runs [translate] with a time limit. On timeout or error, uses [fallback] if it returns text.
 * Returns the text to show (null if nothing is available) and what kind of result it is.
 */
suspend fun translateWithFallback(
    timeoutMs: Long,
    translate: suspend () -> String,
    fallback: () -> String?
): Pair<String?, TranslationStatus> {
    val translated = try {
        withTimeoutOrNull(timeoutMs) { translate() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    if (translated != null) return translated to TranslationStatus.TRANSLATED
    val fallbackText = fallback()
    return if (fallbackText != null) {
        fallbackText to TranslationStatus.OFFLINE_FALLBACK
    } else {
        null to TranslationStatus.FAILED
    }
}

/**
 * Picks the source language in auto-detect mode from language-ID candidates
 * (language code to confidence, codes already mapped to translatable languages).
 *
 * The best candidate is used when its confidence reaches the threshold. Otherwise the last
 * confidently detected language is kept. Returns null if nothing has been detected confidently yet.
 */
class LanguageDetectionGate {
    @Volatile var lastConfidentLanguage: String? = null
        private set

    /** Remembers [code] as the current language (e.g. one heard by the speech model). */
    fun remember(code: String) {
        lastConfidentLanguage = code
    }

    fun resolve(candidates: List<Pair<String, Float>>, confidenceThreshold: Float): String? {
        val best = candidates.maxByOrNull { it.second }
        if (best != null && best.second >= confidenceThreshold) {
            lastConfidentLanguage = best.first
            return best.first
        }
        return lastConfidentLanguage
    }

    /**
     * Like [resolve], but only languages for which [isUsable] is true (pack downloaded, or the
     * target language) can be chosen or remembered.
     *
     * A line that is confidently in an unusable language:
     * - uses the remembered language if [text] is written in that language's script. The
     *   recognizer often writes misheard Japanese with Chinese characters (ありがとう as 谢谢);
     *   such lines are translated as Japanese instead of being skipped.
     * - otherwise returns the unusable language for this line only, so it is reported as missing
     *   and not shown. Music and noise read as Chinese during English speech end up here.
     * The remembered language is never replaced by an unusable one.
     */
    fun resolveAmong(
        candidates: List<Pair<String, Float>>,
        confidenceThreshold: Float,
        text: String,
        isUsable: (String) -> Boolean
    ): String? {
        val usable = candidates.filter { isUsable(it.first) }
        val bestUsable = usable.maxByOrNull { it.second }
        if (bestUsable == null || bestUsable.second < confidenceThreshold) {
            val bestOverall = candidates.maxByOrNull { it.second }
            if (bestOverall != null && bestOverall.second >= confidenceThreshold && !isUsable(bestOverall.first)) {
                val remembered = lastConfidentLanguage
                if (remembered != null && isUsable(remembered) && writingSystemOf(text) == writingSystemOfLanguage(remembered)) {
                    return remembered
                }
                return bestOverall.first
            }
        }
        return resolve(usable, confidenceThreshold)
    }

    private fun writingSystemOf(text: String): String? =
        text.filter { it.isLetter() }
            .groupingBy { writingSystemOfChar(it) }
            .eachCount()
            .filterKeys { it != null }
            .maxByOrNull { it.value }
            ?.key

    private fun writingSystemOfChar(c: Char): String? = when (Character.UnicodeScript.of(c.code)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA -> "cjk"
        Character.UnicodeScript.HANGUL -> "hangul"
        Character.UnicodeScript.CYRILLIC -> "cyrillic"
        Character.UnicodeScript.ARABIC -> "arabic"
        Character.UnicodeScript.DEVANAGARI -> "devanagari"
        Character.UnicodeScript.THAI -> "thai"
        Character.UnicodeScript.LATIN -> "latin"
        else -> null
    }

    private fun writingSystemOfLanguage(code: String): String = when (code) {
        "ja", "zh" -> "cjk"
        "ko" -> "hangul"
        "ru", "uk", "bg", "be" -> "cyrillic"
        "ar", "fa", "ur" -> "arabic"
        "hi", "mr", "ne" -> "devanagari"
        "th" -> "thai"
        else -> "latin"
    }

    fun reset() {
        lastConfidentLanguage = null
    }
}

/**
 * Decides whether a translation result may replace what is on screen.
 * A result is accepted only if its utterance is the same or newer than the last shown one,
 * and a partial never replaces the final of the same utterance.
 */
class SubtitleOrderGate {
    private var lastUtteranceId = Long.MIN_VALUE
    private var lastWasFinal = false

    @Synchronized
    fun accept(utteranceId: Long, isFinal: Boolean): Boolean {
        if (utteranceId < lastUtteranceId) return false
        if (utteranceId == lastUtteranceId && lastWasFinal && !isFinal) return false
        lastUtteranceId = utteranceId
        lastWasFinal = isFinal
        return true
    }

    fun accept(result: TranslationResult): Boolean = accept(result.utteranceId, result.isFinal)
}
