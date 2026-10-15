package com.example

import com.example.asr.PieceTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How speech is cut into pieces: from the model's speed and the speaker's rhythm. */
class PieceTimingTest {

    @Test
    fun fasterModelsMayUseLongerPieces() {
        // Speeds seen on the phone: SenseVoice about 0.07, Whisper Base about 0.3
        val senseVoice = PieceTiming(initialSpeed = 0.07f)
        val whisperBase = PieceTiming(initialSpeed = 0.3f)
        assertEquals(5f / 1.07f, senseVoice.maxPieceSec, 0.01f)
        assertEquals(5f / 1.3f, whisperBase.maxPieceSec, 0.01f)
        assertTrue(senseVoice.maxPieceSec > whisperBase.maxPieceSec)
    }

    @Test
    fun longestPieceStaysWithinLimits() {
        // A model slower than speech still gets pieces long enough to make sense
        assertEquals(PieceTiming.MIN_MAX_PIECE_SEC, PieceTiming(initialSpeed = 3f).maxPieceSec, 0.001f)
        // A very fast one doesn't wait too long for a break
        assertEquals(5f, PieceTiming(initialSpeed = 0f).maxPieceSec, 0.001f)
        assertTrue(PieceTiming(initialSpeed = 0f).maxPieceSec <= PieceTiming.MAX_MAX_PIECE_SEC)
    }

    @Test
    fun speedFollowsMeasuredPiecesButIgnoresTinyOnes() {
        val timing = PieceTiming(initialSpeed = 0.4f)
        // A 0.4 s piece taking 0.3 s is mostly start-up cost: ignored
        timing.recordDecode(audioSec = 0.4f, decodeSec = 0.3f)
        assertEquals(0.4f, timing.speed, 0.001f)
        // Whisper Base on the phone: about 0.3
        repeat(20) { timing.recordDecode(audioSec = 2.0f, decodeSec = 0.6f) }
        assertEquals(0.3f, timing.speed, 0.01f)
        // Nonsense times are ignored
        timing.recordDecode(audioSec = 2f, decodeSec = -1f)
        assertEquals(0.3f, timing.speed, 0.01f)
    }

    @Test
    fun sentenceBreakFollowsTheSpeakersRhythm() {
        val timing = PieceTiming(initialSpeed = 0.1f)
        // Until enough gaps are heard, the default is used
        repeat(7) { timing.recordWordGap(0.09f) }
        assertEquals(PieceTiming.DEFAULT_BREAK_SEC, timing.sentenceBreakSec, 0.001f)
        // A fast talker (gaps of about 0.09 s): breaks about twice as long count
        timing.recordWordGap(0.1f)
        assertEquals(0.18f, timing.sentenceBreakSec, 0.001f)

        // A slow, deliberate talker: their long word gaps aren't sentence breaks
        val slow = PieceTiming(initialSpeed = 0.1f)
        repeat(10) { slow.recordWordGap(0.2f) }
        assertEquals(0.4f, slow.sentenceBreakSec, 0.001f)

        // Never longer than the detector's own pause (which ends the phrase anyway), never tiny
        val verySlow = PieceTiming(initialSpeed = 0.1f)
        repeat(10) { verySlow.recordWordGap(0.4f) }
        assertEquals(PieceTiming.MAX_BREAK_SEC, verySlow.sentenceBreakSec, 0.001f)
        val tiny = PieceTiming(initialSpeed = 0.1f)
        repeat(10) { tiny.recordWordGap(0.064f) }
        assertEquals(PieceTiming.MIN_BREAK_SEC, tiny.sentenceBreakSec, 0.001f)
    }

    @Test
    fun aFewSentenceBreaksDontSkewTheRhythm() {
        val timing = PieceTiming(initialSpeed = 0.1f)
        // Mostly word gaps, some longer breaks between sentences: the median ignores those
        repeat(12) { timing.recordWordGap(0.1f) }
        repeat(4) { timing.recordWordGap(0.4f) }
        assertEquals(0.2f, timing.sentenceBreakSec, 0.001f)
    }

    @Test
    fun rhythmIsRelearnedForANewCaptureButSpeedIsKept() {
        val timing = PieceTiming(initialSpeed = 0.1f)
        repeat(10) { timing.recordWordGap(0.2f) }
        repeat(10) { timing.recordDecode(audioSec = 2f, decodeSec = 1f) }
        val speed = timing.speed
        timing.resetRhythm()
        assertEquals(PieceTiming.DEFAULT_BREAK_SEC, timing.sentenceBreakSec, 0.001f)
        assertEquals(speed, timing.speed, 0.0001f)
    }

    @Test
    fun rhythmUsesOnlyRecentGaps() {
        val timing = PieceTiming(initialSpeed = 0.1f)
        repeat(40) { timing.recordWordGap(0.2f) }
        // A new, faster speaker takes over
        repeat(40) { timing.recordWordGap(0.09f) }
        assertEquals(0.18f, timing.sentenceBreakSec, 0.001f)
    }
}
