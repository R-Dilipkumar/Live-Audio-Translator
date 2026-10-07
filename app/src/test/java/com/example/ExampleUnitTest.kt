package com.example

import com.example.audio.AudioUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun testStereoToMonoDownmix() {
        // Interleaved stereo samples: [left0, right0, left1, right1]
        val stereo = shortArrayOf(1000, 3000, -2000, -4000)
        val mono = AudioUtils.downmixStereoToMono(stereo, stereo.size)

        assertEquals(2, mono.size)
        assertEquals(2000.toShort(), mono[0]) // (1000 + 3000) / 2
        assertEquals((-3000).toShort(), mono[1]) // (-2000 + -4000) / 2
    }

    @Test
    fun testNormalizeToFloat() {
        val shorts = shortArrayOf(0, 32767, -32768)
        val floats = AudioUtils.normalizeToFloat(shorts)

        assertEquals(3, floats.size)
        assertEquals(0f, floats[0], 0.001f)
        assertTrue(floats[1] > 0.99f && floats[1] <= 1.0f)
        assertEquals(-1.0f, floats[2], 0.001f)
    }

    @Test
    fun testResampleLinear() {
        // Simple ramp at 48000 Hz downsampled to 16000 Hz (3x downsampling)
        val input = ShortArray(48) { (it * 100).toShort() }
        val resampled = AudioUtils.resampleLinear(input, 48000, 16000)

        assertEquals(16, resampled.size)
        assertEquals(0.toShort(), resampled[0])
    }

    @Test
    fun testDbLevelCalculation() {
        val silentFloats = FloatArray(160) { 0f }
        val silentDb = AudioUtils.calculateDbLevel(silentFloats)
        assertEquals(0f, silentDb, 0.001f)

        val loudFloats = FloatArray(160) { 0.8f }
        val loudDb = AudioUtils.calculateDbLevel(loudFloats)
        assertTrue(loudDb > 50f)
    }

    @Test
    fun testSilenceThresholdForDrm() {
        val silentFloats = FloatArray(160) { 0.00001f }
        val db = AudioUtils.calculateDbLevel(silentFloats)
        assertTrue(db < 5.0f)
    }

    @Test
    fun testOverlaySettingsMaxLines() {
        val defaultSettings = com.example.overlay.OverlaySettings()
        assertEquals(2, defaultSettings.maxLines)

        val singleLine = defaultSettings.copy(maxLines = 1)
        assertEquals(1, singleLine.maxLines)

        val threeLines = defaultSettings.copy(maxLines = 3)
        assertEquals(3, threeLines.maxLines)
    }

    @Test
    fun testSentenceBoundaryExtraction() {
        val punctuationMarks = charArrayOf('.', ',', '?', '!', '。', '，', '？', '！', ';', '；', ':')
        val streamText = "Hello world! This is a test. How are you?"

        val clauses = mutableListOf<String>()
        var offset = 0
        var uncommitted = streamText.substring(offset)
        var pIndex = uncommitted.indexOfAny(punctuationMarks)

        while (pIndex >= 0) {
            val clause = uncommitted.substring(0, pIndex + 1).trim()
            if (clause.isNotBlank()) {
                clauses.add(clause)
            }
            offset += (pIndex + 1)
            uncommitted = streamText.substring(offset)
            pIndex = uncommitted.indexOfAny(punctuationMarks)
        }

        assertEquals(3, clauses.size)
        assertEquals("Hello world!", clauses[0])
        assertEquals("This is a test.", clauses[1])
        assertEquals("How are you?", clauses[2])
    }

    @Test
    fun testRollingSentencesRollOff() {
        val sentences = listOf("First sentence.", "Second sentence.", "Third sentence.", "Fourth sentence.")

        // Max lines = 1 should only keep the single most recent sentence
        val max1 = sentences.takeLast(1)
        assertEquals(1, max1.size)
        assertEquals("Fourth sentence.", max1[0])

        // Max lines = 2 should keep the 2 most recent sentences (1st and 2nd roll off)
        val max2 = sentences.takeLast(2)
        assertEquals(2, max2.size)
        assertEquals("Third sentence.", max2[0])
        assertEquals("Fourth sentence.", max2[1])

        // Max lines = 3 should keep the 3 most recent sentences (1st rolls off)
        val max3 = sentences.takeLast(3)
        assertEquals(3, max3.size)
        assertEquals("Second sentence.", max3[0])
        assertEquals("Third sentence.", max3[1])
        assertEquals("Fourth sentence.", max3[2])
    }

    @Test
    fun testAudioUtilsResampleEdgeCases() {
        // Empty input returns empty/same
        val empty = ShortArray(0)
        assertEquals(0, AudioUtils.resampleLinear(empty, 48000, 16000).size)

        // Invalid sample rates should return original without crashing
        val sample = shortArrayOf(10, 20, 30)
        assertEquals(3, AudioUtils.resampleLinear(sample, 0, 16000).size)
        assertEquals(3, AudioUtils.resampleLinear(sample, 48000, 0).size)
        assertEquals(3, AudioUtils.resampleLinear(sample, -48000, 16000).size)

        // Same sample rate returns input directly
        val same = AudioUtils.resampleLinear(sample, 16000, 16000)
        assertEquals(sample, same)

        // Single sample should not throw IndexOutOfBoundsException
        val single = shortArrayOf(100)
        val downsampledSingle = AudioUtils.resampleLinear(single, 48000, 16000)
        assertEquals(0, downsampledSingle.size) // 1 * 16000 / 48000 = 0
    }

    @Test
    fun testAudioUtilsCalculateDbLevelRobustness() {
        // Empty array returns 0
        assertEquals(0f, AudioUtils.calculateDbLevel(FloatArray(0)), 0.0001f)

        // Array with NaNs or Infinities should not produce NaN result
        val dirtyFloats = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, 0.5f, Float.NEGATIVE_INFINITY)
        val db = AudioUtils.calculateDbLevel(dirtyFloats)
        assertTrue(!db.isNaN() && !db.isInfinite())
        assertTrue(db >= 0f)
    }

    @Test
    fun testStereoToMonoDownmixBounds() {
        val stereo = shortArrayOf(100, 200, 300, 400)
        // Zero read shorts
        assertEquals(0, AudioUtils.downmixStereoToMono(stereo, 0).size)
        // Negative read shorts
        assertEquals(0, AudioUtils.downmixStereoToMono(stereo, -4).size)
        // Read shorts exceeding buffer size clamped safely
        val monoClamped = AudioUtils.downmixStereoToMono(stereo, 10)
        assertEquals(2, monoClamped.size)
        assertEquals(150.toShort(), monoClamped[0])
    }

    @Test
    fun testVoiceIsolationEmptyInput() {
        val processor = com.example.audio.VoiceIsolationProcessor()
        val emptyResult = processor.process(FloatArray(0))
        assertEquals(0, emptyResult.size)
    }

    @Test
    fun testZipSlipPathContainmentCheck() {
        val targetDir = java.io.File("/data/user/0/com.example/models/model1")
        val safeCanonicalPath = java.io.File(targetDir, "sub/file.onnx").path
        val unsafeEscapePath = java.io.File(targetDir, "../evil.sh").canonicalPath
        val prefixTrapPath = "/data/user/0/com.example/models/model1_fake/evil.onnx"

        val targetCanonicalDirPath = targetDir.canonicalPath + java.io.File.separator

        // Safe inside directory
        assertTrue(safeCanonicalPath.startsWith(targetCanonicalDirPath) || safeCanonicalPath == targetDir.canonicalPath)
        // Traversal escape
        assertTrue(!unsafeEscapePath.startsWith(targetCanonicalDirPath) && unsafeEscapePath != targetDir.canonicalPath)
        // Suffix / prefix collision trap (same prefix letters but different folder)
        assertTrue(!prefixTrapPath.startsWith(targetCanonicalDirPath) && prefixTrapPath != targetDir.canonicalPath)
    }
}

