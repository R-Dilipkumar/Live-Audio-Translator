package com.example.asr

/**
 * Notices when a whole-phrase model can't keep up with live speech: recognizing a phrase takes
 * longer than the phrase itself, so subtitles fall further behind and audio gets dropped.
 * A couple of slow phrases in a row are needed, so one long pause or hiccup doesn't count.
 */
class SlowModelWatch(private val slowPhrasesToWarn: Int = 2) {
    private var slowStreak = 0

    /** Records one phrase; returns true while the model is falling behind. */
    fun record(audioSec: Float, decodeSec: Float): Boolean {
        // Too short to judge (the model's fixed start-up cost dominates)
        if (audioSec >= MIN_AUDIO_SEC) {
            slowStreak = if (decodeSec > audioSec) slowStreak + 1 else 0
        }
        return slowStreak >= slowPhrasesToWarn
    }

    fun reset() {
        slowStreak = 0
    }

    private companion object {
        const val MIN_AUDIO_SEC = 1f
    }
}
