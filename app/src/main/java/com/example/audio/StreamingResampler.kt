package com.example.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Converts a continuous mono stream from [inRate] to [outRate] (16 kHz for the speech models).
 *
 * Unlike resampling each buffer on its own, this keeps its position and the last input samples
 * between calls, so buffer boundaries add no clicks and no samples are skipped. A windowed-sinc
 * low-pass filter removes everything above the new Nyquist frequency before samples are taken,
 * so music and sibilants above 8 kHz do not fold back into the speech band as noise.
 */
class StreamingResampler(
    private val inRate: Int,
    private val outRate: Int = 16000,
    zeroCrossings: Int = 16,
    private val phases: Int = 256
) {
    // Input samples advanced per output sample
    private val step = inRate.toDouble() / outRate
    // Low-pass cutoff as a fraction of the input Nyquist frequency (slightly below the output Nyquist)
    private val cutoff = min(1.0, outRate.toDouble() / inRate) * 0.92
    // Kernel half-width in input samples
    private val half = ceil(zeroCrossings / cutoff).toInt()
    private val taps = 2 * half
    // kernels[p][k]: weight of input sample (center - half + 1 + k) for an output at center + p / phases
    private val kernels: Array<FloatArray> = Array(phases + 1) { p -> buildKernel(p.toDouble() / phases) }

    // Pending input; starts with zeros so the first output sample has a full left context
    private var buffer = FloatArray(half)
    private var bufferLen = half
    private var position = half.toDouble()

    private fun buildKernel(frac: Double): FloatArray {
        val kernel = FloatArray(taps)
        var sum = 0.0
        for (k in 0 until taps) {
            val x = (k - (half - 1)) - frac // distance from the output position, in input samples
            val sinc = if (x == 0.0) 1.0 else sin(PI * cutoff * x) / (PI * cutoff * x)
            // Blackman window over [-half, half]
            val w = 0.42 + 0.5 * cos(PI * x / half) + 0.08 * cos(2 * PI * x / half)
            val v = if (kotlin.math.abs(x) >= half) 0.0 else sinc * w
            kernel[k] = v.toFloat()
            sum += v
        }
        if (sum != 0.0) for (k in 0 until taps) kernel[k] = (kernel[k] / sum).toFloat()
        return kernel
    }

    fun process(input: FloatArray): FloatArray {
        if (inRate == outRate) return input
        if (input.isEmpty()) return FloatArray(0)

        ensureCapacity(bufferLen + input.size)
        System.arraycopy(input, 0, buffer, bufferLen, input.size)
        bufferLen += input.size

        val maxOut = max(0, ceil((bufferLen - half - position) / step).toInt() + 1)
        val out = FloatArray(maxOut)
        var count = 0
        while (true) {
            val center = floor(position).toInt()
            if (center + half >= bufferLen) break
            val phase = ((position - center) * phases).roundToInt()
            val kernel = kernels[phase]
            val start = center - half + 1
            var acc = 0f
            for (k in 0 until taps) {
                acc += buffer[start + k] * kernel[k]
            }
            if (count < out.size) out[count++] = acc
            position += step
        }

        // Keep only the samples the next output still needs
        val keepFrom = max(0, floor(position).toInt() - half + 1)
        if (keepFrom > 0) {
            System.arraycopy(buffer, keepFrom, buffer, 0, bufferLen - keepFrom)
            bufferLen -= keepFrom
            position -= keepFrom
        }
        return if (count == out.size) out else out.copyOf(count)
    }

    private fun ensureCapacity(size: Int) {
        if (size > buffer.size) buffer = buffer.copyOf(max(size, buffer.size * 2))
    }
}
