package com.example.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * Writes mono 16 kHz float samples to a 16-bit PCM WAV file, for debugging what the speech
 * engine actually receives. Stops writing after [maxSeconds]. Call [close] to finish the header.
 */
class WavRecorder(
    file: File,
    private val sampleRate: Int = 16000,
    maxSeconds: Int = 120
) {
    private val out = RandomAccessFile(file, "rw")
    private val maxSamples = sampleRate.toLong() * maxSeconds
    private var written = 0L
    private val bytes = ByteArray(2 * 4096)

    init {
        out.setLength(0)
        out.write(ByteArray(44)) // header is filled in by close()
    }

    fun write(samples: FloatArray) {
        if (written >= maxSamples) return
        val n = minOf(samples.size.toLong(), maxSamples - written).toInt()
        var i = 0
        while (i < n) {
            val count = minOf(n - i, bytes.size / 2)
            for (k in 0 until count) {
                val v = (samples[i + k].coerceIn(-1f, 1f) * 32767f).toInt()
                bytes[2 * k] = (v and 0xFF).toByte()
                bytes[2 * k + 1] = ((v shr 8) and 0xFF).toByte()
            }
            out.write(bytes, 0, count * 2)
            i += count
        }
        written += n
    }

    fun close() {
        val dataBytes = written * 2
        out.seek(0)
        out.write(header(dataBytes))
        out.close()
    }

    private fun header(dataBytes: Long): ByteArray {
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        h.putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(dataBytes.toInt())
        return h.array()
    }
}
