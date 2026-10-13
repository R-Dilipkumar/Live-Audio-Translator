package com.example

import com.example.audio.StreamingResampler
import com.example.audio.VoiceIsolationProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class AudioProcessingTest {

    private fun sine(freq: Double, rate: Int, samples: Int, amplitude: Float = 0.5f, offset: Int = 0) =
        FloatArray(samples) { i -> (amplitude * sin(2 * PI * freq * (i + offset) / rate)).toFloat() }

    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var sum = 0.0
        for (i in from until to) sum += x[i] * x[i]
        return sqrt(sum / (to - from))
    }

    // Amplitude of [freq] in [x] (single-bin DFT)
    private fun toneAmplitude(x: FloatArray, freq: Double, rate: Int, from: Int, to: Int): Double {
        var re = 0.0
        var im = 0.0
        for (i in from until to) {
            val a = 2 * PI * freq * i / rate
            re += x[i] * cos(a)
            im += x[i] * sin(a)
        }
        return 2 * sqrt(re * re + im * im) / (to - from)
    }

    /** Feeds [input] in uneven buffers, as AudioRecord does, and joins the output. */
    private fun resampleInChunks(resampler: StreamingResampler, input: FloatArray, chunk: Int): FloatArray {
        val out = ArrayList<Float>()
        var i = 0
        while (i < input.size) {
            val end = minOf(input.size, i + chunk)
            resampler.process(input.copyOfRange(i, end)).forEach { out.add(it) }
            i = end
        }
        return out.toFloatArray()
    }

    @Test
    fun outputLengthMatchesRateRatioAcrossBuffers() {
        val input = sine(440.0, 48000, 48000)
        val out = resampleInChunks(StreamingResampler(48000, 16000), input, chunk = 2048)
        // One second in, one second out (minus the filter delay held back for the next buffer)
        assertTrue("got ${out.size}", out.size in 15900..16000)
    }

    @Test
    fun speechBandToneKeepsItsLevel() {
        val input = sine(1000.0, 48000, 48000, amplitude = 0.5f)
        val out = resampleInChunks(StreamingResampler(48000, 16000), input, chunk = 2048)
        val level = toneAmplitude(out, 1000.0, 16000, 2000, 14000)
        assertEquals(0.5, level, 0.02)
    }

    @Test
    fun highFrequenciesDoNotAliasIntoSpeechBand() {
        // 13 kHz would fold to 3 kHz with plain decimation
        val input = sine(13000.0, 48000, 48000, amplitude = 0.5f)
        val out = resampleInChunks(StreamingResampler(48000, 16000), input, chunk = 2048)
        assertTrue("alias level ${rms(out, 2000, 14000)}", rms(out, 2000, 14000) < 0.01)
    }

    @Test
    fun bufferBoundariesAddNoClicks() {
        // Same signal, fed in one piece vs. in odd-sized buffers, must give the same output
        val input = sine(700.0, 44100, 44100)
        val whole = StreamingResampler(44100, 16000).process(input)
        val chunked = resampleInChunks(StreamingResampler(44100, 16000), input, chunk = 1777)
        val n = minOf(whole.size, chunked.size)
        var maxDiff = 0f
        for (i in 0 until n) maxDiff = maxOf(maxDiff, abs(whole[i] - chunked[i]))
        assertTrue("max difference $maxDiff", maxDiff < 1e-4f)
    }

    @Test
    fun sameRatePassesThrough() {
        val input = sine(300.0, 16000, 1600)
        val out = StreamingResampler(16000, 16000).process(input)
        assertTrue(input.contentEquals(out))
    }

    @Test
    fun gateOffLeavesQuietSpeechUntouched() {
        val processor = VoiceIsolationProcessor()
        val quiet = sine(500.0, 16000, 640, amplitude = 0.005f)
        val out = processor.process(quiet, noiseGateThreshold = 0f, isFilterEnabled = false)
        assertTrue(quiet.contentEquals(out))
    }

    @Test
    fun gateStaysOpenForQuietSpeechRightAfterLoudSpeech() {
        val processor = VoiceIsolationProcessor()
        // Loud speaker, then a softer one 40 ms later: the hold keeps the gate open
        processor.process(sine(500.0, 16000, 640, amplitude = 0.3f), noiseGateThreshold = 0.02f, isFilterEnabled = false)
        val soft = sine(500.0, 16000, 640, amplitude = 0.01f, offset = 640)
        val out = processor.process(soft, noiseGateThreshold = 0.02f, isFilterEnabled = false)
        assertEquals(rms(soft), rms(out), 1e-6)
    }

    @Test
    fun quietVoiceIsBoostedTowardsNormalLevel() {
        val processor = VoiceIsolationProcessor()
        var out = FloatArray(0)
        // About 2 s of soft speech at -46 dBFS, in 40 ms buffers
        for (k in 0 until 50) {
            out = processor.process(sine(300.0, 16000, 640, amplitude = 0.007f, offset = k * 640), isFilterEnabled = false, boostQuietVoices = true)
        }
        val boost = rms(out) / (0.007 / sqrt(2.0))
        assertTrue("boost was ${"%.1f".format(boost)}x", boost > 6.0 && boost <= 8.01)
    }

    @Test
    fun normalSpeechIsNotChangedByBoost() {
        val processor = VoiceIsolationProcessor()
        var input = FloatArray(0)
        var out = FloatArray(0)
        for (k in 0 until 50) {
            input = sine(300.0, 16000, 640, amplitude = 0.3f, offset = k * 640)
            out = processor.process(input, isFilterEnabled = false, boostQuietVoices = true)
        }
        assertEquals(rms(input), rms(out), 1e-6)
    }

    @Test
    fun nearSilenceIsNotAmplified() {
        val processor = VoiceIsolationProcessor()
        var input = FloatArray(0)
        var out = FloatArray(0)
        for (k in 0 until 50) {
            input = sine(300.0, 16000, 640, amplitude = 0.001f, offset = k * 640)
            out = processor.process(input, isFilterEnabled = false, boostQuietVoices = true)
        }
        assertEquals(rms(input), rms(out), 1e-6)
    }

    @Test
    fun gateAttenuatesButDoesNotErase() {
        val processor = VoiceIsolationProcessor()
        val quiet = sine(500.0, 16000, 640, amplitude = 0.01f)
        val out = processor.process(quiet, noiseGateThreshold = 0.02f, isFilterEnabled = false)
        assertTrue(rms(out) > 0.0)
        assertEquals(rms(quiet) * 0.1, rms(out), 1e-6)
    }
}
