package com.example.asr

/**
 * Decides how a whole-phrase model's speech is cut into pieces, from two things measured live,
 * so it suits any model, phone and speaker instead of fixed numbers:
 *
 * - **Speed** of the model on this phone: seconds spent recognizing per second of speech
 *   (SenseVoice about 0.07, Whisper Base about 0.3 on a recent phone). A piece of L seconds is
 *   shown about L × (1 + speed) after it began, so the longest piece is
 *   [TARGET_DELAY_SEC] / (1 + speed). A slower model gets somewhat shorter pieces to stay
 *   within the delay; it never goes below [MIN_MAX_PIECE_SEC] (shorter pieces lose too much
 *   context; a model that slow is reported as too slow instead).
 * - **Rhythm** of the speaker: their usual gap between words. A gap [BREAK_FACTOR] times longer
 *   counts as a sentence break, so a fast talker's short breaks are caught and a slow talker's
 *   word gaps aren't mistaken for breaks.
 *
 * Speed is updated from the recognizer thread and read from the audio thread; the rhythm is
 * used only on the audio thread.
 */
class PieceTiming(initialSpeed: Float) {

    /** Seconds of recognizing per second of speech (smoothed). */
    @Volatile var speed: Float = initialSpeed
        private set

    private val wordGaps = ArrayDeque<Float>()

    /** Quiet gap, in seconds, that counts as a sentence break for this speaker. */
    var sentenceBreakSec: Float = DEFAULT_BREAK_SEC
        private set

    /** Longest piece, in seconds, that still keeps subtitles within the target delay. */
    val maxPieceSec: Float
        get() = (TARGET_DELAY_SEC / (1f + speed)).coerceIn(MIN_MAX_PIECE_SEC, MAX_MAX_PIECE_SEC)

    /**
     * One piece was recognized. Very short pieces are left out: the model's fixed start-up
     * cost makes them look much slower than speech really is.
     */
    fun recordDecode(audioSec: Float, decodeSec: Float) {
        if (audioSec < MIN_TIMED_AUDIO_SEC || decodeSec < 0f) return
        speed = speed * (1f - SPEED_WEIGHT) + (decodeSec / audioSec) * SPEED_WEIGHT
    }

    /** A quiet gap after which the speaker went on talking. */
    fun recordWordGap(gapSec: Float) {
        if (gapSec <= 0f) return
        wordGaps.addLast(gapSec)
        if (wordGaps.size > RHYTHM_HISTORY) wordGaps.removeFirst()
        sentenceBreakSec = if (wordGaps.size < RHYTHM_MIN_GAPS) {
            DEFAULT_BREAK_SEC
        } else {
            (median(wordGaps) * BREAK_FACTOR).coerceIn(MIN_BREAK_SEC, MAX_BREAK_SEC)
        }
    }

    /** New capture (maybe another video or speaker): learn the rhythm again. The speed stays. */
    fun resetRhythm() {
        wordGaps.clear()
        sentenceBreakSec = DEFAULT_BREAK_SEC
    }

    companion object {
        // Aim to show each piece within about 5 s of when it began
        const val TARGET_DELAY_SEC = 5f
        const val MIN_MAX_PIECE_SEC = 2.5f
        const val MAX_MAX_PIECE_SEC = 8f
        private const val SPEED_WEIGHT = 0.3f
        private const val MIN_TIMED_AUDIO_SEC = 1f

        // Used until the speaker's rhythm is known
        const val DEFAULT_BREAK_SEC = 0.22f
        const val BREAK_FACTOR = 2f
        const val MIN_BREAK_SEC = 0.16f
        // Below the speech detector's own 0.5 s pause, which ends the phrase anyway
        const val MAX_BREAK_SEC = 0.45f
        private const val RHYTHM_HISTORY = 40
        private const val RHYTHM_MIN_GAPS = 8

        private fun median(values: Collection<Float>): Float {
            val sorted = values.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2f
        }
    }
}
