package com.example.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Direct Form I IIR Biquad Filter (2nd-order Butterworth high-pass / low-pass).
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
    }
}

/**
 * Light clean-up before speech recognition.
 *
 * - Quiet-voice boost (optional): slow automatic gain that lifts soft, breathy or husky speech
 *   towards a normal speaking level (up to [maxBoost]). It only ever raises the level, never
 *   lowers it, and does not raise near-silence, so background hiss is not pumped up.
 * - Filter (optional): a gentle 80 Hz high-pass that removes rumble and hum. It keeps the full
 *   speech band up to 8 kHz; the models are trained on it and need the high frequencies for
 *   consonants (an older 3.5 kHz low-pass removed them).
 * - Noise gate (optional, off when the threshold is 0): silences background sound between
 *   sentences. Once speech opens the gate it stays open for [gateHoldMs], so quiet word endings,
 *   short pauses and a softer second speaker are not cut. Below the threshold the audio is
 *   attenuated rather than zeroed, so the start of a word is still heard.
 */
class VoiceIsolationProcessor(
    private val sampleRate: Float = 16000f,
    hpCutoff: Float = 80f,
    private val gateHoldMs: Int = 700,
    private val closedGateGain: Float = 0.1f,
    private val targetRms: Float = 0.1f,   // about -20 dBFS, a normal speaking level
    private val maxBoost: Float = 8f,      // at most +18 dB
    private val silenceRms: Float = 0.002f // below this (about -54 dBFS) the gain is not raised
) {
    private val highPass = BiquadFilter.createHighPass(sampleRate, hpCutoff)
    private var gateOpenSamplesLeft = 0
    private var speechLevel = 0f
    private var boostGain = 1f

    /**
     * Filters the input samples and applies the noise gate.
     * @param input 16kHz normalized float PCM samples
     * @param noiseGateThreshold RMS threshold; 0 turns the gate off.
     * @param isFilterEnabled whether the high-pass filter is active
     */
    fun process(
        input: FloatArray,
        noiseGateThreshold: Float = 0f,
        isFilterEnabled: Boolean = true,
        boostQuietVoices: Boolean = false
    ): FloatArray {
        if (input.isEmpty()) return FloatArray(0)

        val output = FloatArray(input.size)

        var sumSquare = 0.0
        for (i in input.indices) {
            val filtered = if (isFilterEnabled) highPass.process(input[i]) else input[i]
            output[i] = filtered
            sumSquare += (filtered * filtered).toDouble()
        }

        if (boostQuietVoices) {
            val chunkRms = kotlin.math.sqrt(sumSquare / input.size).toFloat()
            if (chunkRms >= silenceRms) {
                // Follow speech quickly when it gets louder, slowly when it gets softer
                val rate = if (chunkRms > speechLevel) 0.3f else 0.03f
                speechLevel += (chunkRms - speechLevel) * rate
                val wanted = (targetRms / speechLevel.coerceAtLeast(silenceRms)).coerceIn(1f, maxBoost)
                boostGain += (wanted - boostGain) * 0.2f
            }
            if (boostGain > 1.001f) {
                sumSquare = 0.0
                for (i in output.indices) {
                    val v = (output[i] * boostGain).coerceIn(-0.99f, 0.99f)
                    output[i] = v
                    sumSquare += (v * v).toDouble()
                }
            }
        }

        if (noiseGateThreshold <= 0f) {
            gateOpenSamplesLeft = 0
            return output
        }

        val rms = kotlin.math.sqrt(sumSquare / input.size).toFloat()
        if (rms >= noiseGateThreshold) {
            gateOpenSamplesLeft = (sampleRate * gateHoldMs / 1000f).toInt()
        } else {
            gateOpenSamplesLeft -= input.size
        }
        if (gateOpenSamplesLeft <= 0) {
            gateOpenSamplesLeft = 0
            for (i in output.indices) output[i] *= closedGateGain
        }
        return output
    }

    fun reset() {
        highPass.reset()
        gateOpenSamplesLeft = 0
        speechLevel = 0f
        boostGain = 1f
    }
}
