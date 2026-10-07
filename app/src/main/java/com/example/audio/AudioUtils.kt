package com.example.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object AudioUtils {

    /**
     * Downmixes interleaved stereo 16-bit PCM (shorts) to a mono short array.
     * Each pair [left, right] is averaged: (left + right) / 2.
     */
    fun downmixStereoToMono(stereoBuffer: ShortArray, readShorts: Int): ShortArray {
        val validRead = min(readShorts, stereoBuffer.size)
        if (validRead <= 0) return ShortArray(0)
        val monoLength = validRead / 2
        val mono = ShortArray(monoLength)
        for (i in 0 until monoLength) {
            val left = stereoBuffer[i * 2].toInt()
            val right = stereoBuffer[i * 2 + 1].toInt()
            mono[i] = ((left + right) / 2).toShort()
        }
        return mono
    }

    /**
     * Resamples mono 16-bit PCM audio from inSampleRate (e.g. 48000 Hz or 44100 Hz)
     * down to outSampleRate (typically 16000 Hz) using linear interpolation.
     */
    fun resampleLinear(
        input: ShortArray,
        inSampleRate: Int,
        outSampleRate: Int = 16000
    ): ShortArray {
        if (inSampleRate <= 0 || outSampleRate <= 0 || inSampleRate == outSampleRate || input.isEmpty()) {
            return input
        }

        val ratio = inSampleRate.toDouble() / outSampleRate.toDouble()
        val outLength = (input.size.toLong() * outSampleRate / inSampleRate).toInt()
        if (outLength <= 0) return ShortArray(0)
        val output = ShortArray(outLength)

        for (i in 0 until outLength) {
            val srcIndex = i * ratio
            val indexFloor = min(srcIndex.toInt(), input.size - 1)
            val indexCeil = min(indexFloor + 1, input.size - 1)
            val fraction = (srcIndex - indexFloor).toFloat()

            val sample0 = input[indexFloor].toFloat()
            val sample1 = input[indexCeil].toFloat()
            val interpolated = sample0 + fraction * (sample1 - sample0)

            output[i] = min(32767f, max(-32768f, interpolated)).toInt().toShort()
        }
        return output
    }

    /**
     * Normalizes 16-bit integer PCM shorts (-32768 to 32767) into 32-bit floats (-1.0f to 1.0f).
     */
    fun normalizeToFloat(input: ShortArray): FloatArray {
        val output = FloatArray(input.size)
        for (i in input.indices) {
            output[i] = input[i] / 32768.0f
        }
        return output
    }

    /**
     * Converts a raw stereo PCM byte/short buffer directly to 16 kHz normalized mono floats
     * for direct consumption by speech recognition models.
     */
    fun processRawAudioTo16kMonoFloats(
        stereoPcmBuffer: ShortArray,
        readShorts: Int,
        sampleRate: Int,
        isStereo: Boolean
    ): FloatArray {
        val monoShorts = if (isStereo) {
            downmixStereoToMono(stereoPcmBuffer, readShorts)
        } else {
            val validRead = min(readShorts, stereoPcmBuffer.size)
            if (validRead <= 0) ShortArray(0) else stereoPcmBuffer.copyOf(validRead)
        }

        val resampledShorts = if (sampleRate > 0 && sampleRate != 16000) {
            resampleLinear(monoShorts, sampleRate, 16000)
        } else {
            monoShorts
        }

        return normalizeToFloat(resampledShorts)
    }

    /**
     * Calculates the Root Mean Square (RMS) in decibels (0 to 100 dB normalized scale)
     * from a float PCM buffer for live visualizer meters.
     */
    fun calculateDbLevel(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sumSquares = 0.0
        for (s in samples) {
            val v = s.toDouble()
            if (!v.isNaN() && !v.isInfinite()) {
                sumSquares += (v * v)
            }
        }
        if (sumSquares <= 0.0 || sumSquares.isNaN() || sumSquares.isInfinite()) return 0f
        val rms = sqrt(sumSquares / samples.size)
        if (rms < 0.0001 || rms.isNaN() || rms.isInfinite()) return 0f
        val db = 20.0 * log10(rms) // Typically -60dB to 0dB
        if (db.isNaN() || db.isInfinite()) return 0f
        // Normalize -60dB -> 0%, 0dB -> 100%
        val normalized = ((db + 60.0) / 60.0).toFloat().coerceIn(0f, 1f)
        return normalized * 100f
    }
}
