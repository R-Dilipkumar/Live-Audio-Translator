package com.example.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Direct Form I / Transposed Direct Form II IIR Biquad Filter.
 * Used for audio voice isolation:
 * - High-pass 150 Hz cutoff (cleans out rumble, mechanical hum, and heavy bass BGM)
 * - Low-pass 3500 Hz cutoff (strips high-frequency sound effects, sizzle, and cymbal noise)
 */
class BiquadFilter(
    private val b0: Float,
    private val b1: Float,
    private val b2: Float,
    private val a1: Float,
    private val a2: Float
) {
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    fun process(sample: Float): Float {
        val out = b0 * sample + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = sample
        y2 = y1
        y1 = out
        return out
    }

    fun reset() {
        x1 = 0f
        x2 = 0f
        y1 = 0f
        y2 = 0f
    }

    companion object {
        /**
         * Creates a 2nd-order Butterworth High-Pass filter.
         * Default Q = 0.7071
         */
        fun createHighPass(sampleRate: Float, cutoffFreq: Float, q: Float = 0.7071f): BiquadFilter {
            val omega = (2.0 * PI * cutoffFreq / sampleRate).toFloat()
            val cosOmega = cos(omega)
            val sinOmega = sin(omega)
            val alpha = sinOmega / (2.0f * q)

            val a0 = 1.0f + alpha
            val b0 = ((1.0f + cosOmega) / 2.0f) / a0
            val b1 = (-(1.0f + cosOmega)) / a0
            val b2 = ((1.0f + cosOmega) / 2.0f) / a0
            val a1 = (-2.0f * cosOmega) / a0
            val a2 = (1.0f - alpha) / a0

            return BiquadFilter(b0, b1, b2, a1, a2)
        }

        /**
         * Creates a 2nd-order Butterworth Low-Pass filter.
         * Default Q = 0.7071
         */
        fun createLowPass(sampleRate: Float, cutoffFreq: Float, q: Float = 0.7071f): BiquadFilter {
            val omega = (2.0 * PI * cutoffFreq / sampleRate).toFloat()
            val cosOmega = cos(omega)
            val sinOmega = sin(omega)
            val alpha = sinOmega / (2.0f * q)

            val a0 = 1.0f + alpha
            val b0 = ((1.0f - cosOmega) / 2.0f) / a0
            val b1 = (1.0f - cosOmega) / a0
            val b2 = ((1.0f - cosOmega) / 2.0f) / a0
            val a1 = (-2.0f * cosOmega) / a0
            val a2 = (1.0f - alpha) / a0

            return BiquadFilter(b0, b1, b2, a1, a2)
        }
    }
}

/**
 * Bandpass voice isolation processor cascading High-pass (150 Hz) and Low-pass (3500 Hz) filters
 * followed by an RMS Energy Noise Gate.
 */
class VoiceIsolationProcessor(
    sampleRate: Float = 16000f,
    hpCutoff: Float = 150f,
    lpCutoff: Float = 3500f
) {
    private val highPass = BiquadFilter.createHighPass(sampleRate, hpCutoff)
    private val lowPass = BiquadFilter.createLowPass(sampleRate, lpCutoff)

    /**
     * Filters the input samples and applies a noise gate.
     * @param input 16kHz normalized float PCM samples
     * @param noiseGateThreshold RMS threshold (typically 0.005f to 0.05f). If RMS is below threshold, samples are zeroed.
     * @param isFilterEnabled whether DSP bandpass filter is active
     */
    fun process(
        input: FloatArray,
        noiseGateThreshold: Float = 0.015f,
        isFilterEnabled: Boolean = true
    ): FloatArray {
        val output = FloatArray(input.size)

        var sumSquare = 0.0
        for (i in input.indices) {
            val filtered = if (isFilterEnabled) {
                lowPass.process(highPass.process(input[i]))
            } else {
                input[i]
            }
            output[i] = filtered
            sumSquare += (filtered * filtered).toDouble()
        }

        val rms = sqrt(sumSquare / input.size).toFloat()

        // Noise gate: if chunk energy is below threshold, return silence to prevent BGM false triggers
        if (noiseGateThreshold > 0f && rms < noiseGateThreshold) {
            output.fill(0f)
        }

        return output
    }

    fun reset() {
        highPass.reset()
        lowPass.reset()
    }
}
