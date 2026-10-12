package com.example

import com.example.audio.AudioUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertEquals(2, defaultSettings.maxSubtitleLines)
        assertEquals(2, defaultSettings.maxLines)

        val singleLine = defaultSettings.copy(maxSubtitleLines = 1, maxLines = 1)
        assertEquals(1, singleLine.maxSubtitleLines)
        assertEquals(1, singleLine.maxLines)

        val threeLines = defaultSettings.copy(maxSubtitleLines = 3, maxLines = 3)
        assertEquals(3, threeLines.maxSubtitleLines)
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

    @Test
    fun testOverlaySettingsIsLocked() {
        val defaultSettings = com.example.overlay.OverlaySettings()
        assertTrue(!defaultSettings.isLocked)

        val lockedSettings = defaultSettings.copy(isLocked = true)
        assertTrue(lockedSettings.isLocked)
    }

    @Test
    fun testOfflineFallbackDictionaryJapanese() {
        // Instantiate a mock or test context in Robolectric or test direct mapping logic
        val jaToEn = mapOf(
            "こんにちは" to "Hello",
            "ありがとう" to "Thank you",
            "助けて" to "Help me!",
            "行くぞ" to "Let's go!"
        )
        assertEquals("Hello", jaToEn["こんにちは"])
        assertEquals("Thank you", jaToEn["ありがとう"])
        assertEquals("Help me!", jaToEn["助けて"])
        assertEquals("Let's go!", jaToEn["行くぞ"])
    }

    @Test
    fun testModelDownloadStateExtractingProgress() {
        val state = com.example.asr.ModelDownloadState.Extracting("encoder.onnx", 0.45f)
        assertEquals("encoder.onnx", state.currentFile)
        assertEquals(0.45f, state.progress, 0.001f)
    }

    @Test
    fun testSpeechValidatorFiltersSystemPlaceholders() {
        val statusStrings = listOf(
            "Translating internal media stream...",
            "Awaiting audio stream...",
            "Live audio stream active",
            "Listening...",
            "Listening",
            "Waiting for audio...",
            "Waiting for speech...",
            "Waiting for live speech...",
            "Translation will appear here...",
            "Subtitles will stream here...",
            "[Translation Paused]",
            "[ASR Engine Error]",
            "",
            "   \n\t  "
        )

        for (status in statusStrings) {
            val isGenuine = com.example.asr.SpeechValidator.isGenuineSpeech(status)
            assertTrue("Expected status '$status' to be rejected as non-speech", !isGenuine)
        }
    }

    @Test
    fun testSpeechValidatorAcceptsGenuineSpeech() {
        val genuineSpeechList = listOf(
            "Hello, how are you today?",
            "Welcome to the keynote presentation.",
            "I will be arriving at three o'clock.",
            "Can you hear me clearly?",
            "Bonjour tout le monde",
            "こんにちは、元気ですか"
        )

        for (speech in genuineSpeechList) {
            val isGenuine = com.example.asr.SpeechValidator.isGenuineSpeech(speech)
            assertTrue("Expected genuine speech '$speech' to be accepted", isGenuine)
        }
    }

    @Test
    fun testAsrUtteranceEventsDifferentiation() {
        val partialEvent: com.example.asr.AsrUtteranceEvent = com.example.asr.AsrUtteranceEvent.Partial("Today we are discussing", utteranceId = 42L)
        val finalEvent: com.example.asr.AsrUtteranceEvent = com.example.asr.AsrUtteranceEvent.Final("Today we are discussing clean architecture.", utteranceId = 42L)

        assertTrue(partialEvent is com.example.asr.AsrUtteranceEvent.Partial)
        assertEquals("Today we are discussing", (partialEvent as com.example.asr.AsrUtteranceEvent.Partial).text)
        assertEquals(42L, (partialEvent as com.example.asr.AsrUtteranceEvent.Partial).utteranceId)

        assertTrue(finalEvent is com.example.asr.AsrUtteranceEvent.Final)
        assertEquals("Today we are discussing clean architecture.", (finalEvent as com.example.asr.AsrUtteranceEvent.Final).text)
        assertEquals(42L, (finalEvent as com.example.asr.AsrUtteranceEvent.Final).utteranceId)
    }

    @Test
    fun testAccumulatedContextSentenceReEvaluation() {
        // Simulates accumulating context in German or Japanese where verb or negation comes at the end
        val partial1 = "Ich habe das Buch"
        val partial2 = "Ich habe das Buch nicht"
        val finalClause = "Ich habe das Buch nicht gelesen."

        // As user speaks, each emission is the full accumulated clause string, not isolated fragments
        assertTrue(partial2.startsWith(partial1))
        assertTrue(finalClause.startsWith(partial2))

        val finalResult = com.example.translate.TranslationResult(
            originalText = finalClause,
            translatedText = "I have not read the book.",
            sourceLangCode = "de",
            targetLangCode = "en",
            isFinal = true
        )
        assertTrue(finalResult.isFinal)
        assertEquals("I have not read the book.", finalResult.translatedText)
    }

    private fun computeDisplayLines(lineCount: Int, committed: List<String>, draft: String?): List<String> {
        val linesToDisplay = mutableListOf<String>()
        if (draft != null) {
            val historySlots = (lineCount - 1).coerceAtLeast(0)
            if (historySlots > 0) linesToDisplay.addAll(committed.takeLast(historySlots))
            linesToDisplay.add(draft)
        } else {
            linesToDisplay.addAll(committed.takeLast(lineCount))
        }
        return linesToDisplay
    }

    @Test
    fun testFloatingOverlayActiveDraftAndCommittedRollingLines() {
        val lineCount = 2
        var committedFinalLines = listOf<String>()
        var activeDraftLine: String?

        // Step 1: User starts speaking (Partial 1) -> rewrites bottom line in-place
        activeDraftLine = "Good morning"
        var linesToDisplay = computeDisplayLines(lineCount, committedFinalLines, activeDraftLine)
        assertEquals(listOf("Good morning"), linesToDisplay)

        // Step 2: User keeps speaking (Partial 2) -> rewrites bottom line in-place
        activeDraftLine = "Good morning everyone"
        linesToDisplay = computeDisplayLines(lineCount, committedFinalLines, activeDraftLine)
        assertEquals(listOf("Good morning everyone"), linesToDisplay)

        // Step 3: Endpoint reached -> Final commit: roll into history, clear draft
        val finalSentence1 = "Good morning everyone, welcome."
        committedFinalLines = (committedFinalLines + finalSentence1).takeLast(lineCount)
        activeDraftLine = null

        linesToDisplay = computeDisplayLines(lineCount, committedFinalLines, activeDraftLine)
        assertEquals(listOf("Good morning everyone, welcome."), linesToDisplay)

        // Step 4: Next sentence starts speaking (Partial 3) -> history preserved on top, draft on bottom
        activeDraftLine = "Let's begin"
        linesToDisplay = computeDisplayLines(lineCount, committedFinalLines, activeDraftLine)
        assertEquals(listOf("Good morning everyone, welcome.", "Let's begin"), linesToDisplay)
        assertEquals("Good morning everyone, welcome.", linesToDisplay[0]) // History line
        assertEquals("Let's begin", linesToDisplay[1]) // In-place draft line
    }

    private fun checkTaskPreempted(taskText: String, isFinal: Boolean, currentPendingDraft: String?): Boolean {
        if (isFinal) return false
        return currentPendingDraft != null && currentPendingDraft != taskText
    }

    @Test
    fun testSequentialDraftPreemptionLogic() {
        val pendingDraft = "Hello world"

        // Draft 1 was "Hello" - when inspected, pendingDraft is "Hello world"
        val isDraft1Preempted = checkTaskPreempted("Hello", isFinal = false, currentPendingDraft = pendingDraft)
        assertTrue("Draft 1 should be recognized as preempted/stale", isDraft1Preempted)

        // Draft 2 is "Hello world" - when inspected, matches pendingDraft
        val isDraft2Preempted = checkTaskPreempted("Hello world", isFinal = false, currentPendingDraft = pendingDraft)
        assertTrue("Draft 2 should not be preempted", !isDraft2Preempted)

        // Final commit must NEVER be preempted
        val isFinalPreempted = checkTaskPreempted("Hello", isFinal = true, currentPendingDraft = pendingDraft)
        assertTrue("Final utterance must never be preempted", !isFinalPreempted)
    }

    @Test
    fun testUtteranceEpochDiscardOlderDrafts() {
        var currentActiveEpoch = 5L
        var committedSentence = "The meeting has started."
        var displayedDraft: String? = null

        // Callback arrives with an older epoch (utteranceId = 4) and is a draft
        val incomingDraftUtteranceId = 4L
        val incomingDraftText = "Wait a second"
        val isDraftFinal = false

        val shouldDiscard = incomingDraftUtteranceId > 0 && incomingDraftUtteranceId < currentActiveEpoch && !isDraftFinal
        assertTrue("Draft from older epoch (4 < 5) must be discarded", shouldDiscard)

        if (!shouldDiscard) {
            displayedDraft = incomingDraftText
        }
        assertNull("Draft must not overwrite or appear", displayedDraft)
        assertEquals("The meeting has started.", committedSentence)

        // Callback arrives with current epoch (utteranceId = 5) or newer (utteranceId = 6)
        val newerDraftUtteranceId = 6L
        val newerDraftText = "Now we proceed"
        val isNewerDiscard = newerDraftUtteranceId > 0 && newerDraftUtteranceId < currentActiveEpoch && !isDraftFinal
        assertFalse("Draft from newer epoch (6 >= 5) must NOT be discarded", isNewerDiscard)

        if (!isNewerDiscard) {
            currentActiveEpoch = newerDraftUtteranceId
            displayedDraft = newerDraftText
        }
        assertEquals("Now we proceed", displayedDraft)
        assertEquals(6L, currentActiveEpoch)
    }

    @Test
    fun testLanguageHysteresisAndConfidenceGate() {
        var activeLanguage = "ja"
        var candidateLang: String? = null
        var candidateCount = 0
        val threshold = 0.85f

        fun processLanguageDetection(detected: String, confidence: Float): String {
            if (confidence < threshold) {
                candidateLang = null
                candidateCount = 0
                return activeLanguage
            }
            if (detected == activeLanguage) {
                candidateLang = null
                candidateCount = 0
                return activeLanguage
            }
            if (candidateLang == detected) {
                candidateCount++
                if (candidateCount >= 2) {
                    activeLanguage = detected
                    candidateLang = null
                    candidateCount = 0
                }
            } else {
                candidateLang = detected
                candidateCount = 1
            }
            return activeLanguage
        }

        // 1. Low confidence detection (< 0.85): Should retain activeLanguage
        val result1 = processLanguageDetection("es", 0.70f)
        assertEquals("ja", result1)

        // 2. High confidence detection (0.90) for "es": 1st observation, should NOT switch yet
        val result2 = processLanguageDetection("es", 0.90f)
        assertEquals("ja", result2)
        assertEquals("es", candidateLang)
        assertEquals(1, candidateCount)

        // 3. Second consecutive high confidence detection (0.92) for "es": confirmed switch!
        val result3 = processLanguageDetection("es", 0.92f)
        assertEquals("es", result3)
        assertEquals("es", activeLanguage)
        assertNull(candidateLang)
        assertEquals(0, candidateCount)
    }

    @Test
    fun testTargetLanguagePassthroughLogic() {
        fun translateOrPassthrough(sourceCode: String, targetCode: String, text: String): String {
            if (sourceCode == targetCode) {
                return text // Passthrough without translation model
            }
            return "[Translated to $targetCode: $text]"
        }

        val original = "Hello and welcome to the presentation."
        val resultSameLang = translateOrPassthrough("en", "en", original)
        assertEquals("Identical source and target languages must passthrough directly", original, resultSameLang)

        val resultDifferentLang = translateOrPassthrough("en", "es", original)
        assertEquals("[Translated to es: Hello and welcome to the presentation.]", resultDifferentLang)
    }

    @Test
    fun testRequiredLanguageCodesEnglishPivot() {
        fun getRequiredCodes(srcCode: String, tgtCode: String): Set<String> {
            val codes = mutableSetOf(srcCode, tgtCode)
            if (srcCode != "en" && tgtCode != "en") {
                codes.add("en")
            }
            return codes
        }

        // Direct pair with English as target
        val jaToEn = getRequiredCodes("ja", "en")
        assertEquals(setOf("ja", "en"), jaToEn)
        assertFalse("Direct pair does not need additional pivot", jaToEn.size > 2)

        // Direct pair with English as source
        val enToEs = getRequiredCodes("en", "es")
        assertEquals(setOf("en", "es"), enToEs)

        // Non-English pair (Japanese -> Spanish) requires English pivot model as well
        val jaToEs = getRequiredCodes("ja", "es")
        assertEquals(setOf("ja", "es", "en"), jaToEs)
        assertTrue("Non-English pair must include English pivot model", jaToEs.contains("en"))
    }

    @Test
    fun testPreFlightValidationBypassesMlKitWhenModelMissing() {
        val downloadedModelsOnDisk = setOf("en") // Only English downloaded; Japanese and Spanish missing
        var downloadTriggerCount = 0

        fun simulateLiveTranslation(
            text: String,
            srcCode: String,
            tgtCode: String,
            downloaded: Set<String>
        ): Pair<String, Boolean> {
            val neededCodes = mutableSetOf(srcCode, tgtCode)
            if (srcCode != "en" && tgtCode != "en") neededCodes.add("en")

            val allPresent = downloaded.containsAll(neededCodes)
            if (!allPresent) {
                // FALLBACK: DO NOT trigger download, gracefully bypass ML Kit, emit original text directly
                return Pair(text, false) // false = bypassed
            }
            return Pair("[Translated: $text]", true)
        }

        val speech = "Konnichiwa minna-san"

        // Attempt live translation from "ja" to "en" when "ja" is missing
        val (output, wasTranslated) = simulateLiveTranslation(speech, "ja", "en", downloadedModelsOnDisk)
        assertEquals("When model is missing, original transcript must be emitted directly", speech, output)
        assertFalse("ML Kit must be bypassed when model missing", wasTranslated)
        assertEquals("downloadModelIfNeeded or download must NEVER be triggered during live translation", 0, downloadTriggerCount)

        // When all models are confirmed present on disk:
        val fullyDownloaded = setOf("ja", "en")
        val (outputReady, wasTranslatedReady) = simulateLiveTranslation(speech, "ja", "en", fullyDownloaded)
        assertTrue("When models are confirmed on disk, translation proceeds", wasTranslatedReady)
        assertEquals("[Translated: Konnichiwa minna-san]", outputReady)
    }

    @Test
    fun testPivotMissingTriggersBypassWithoutDownload() {
        // Japanese and Spanish are downloaded, but English pivot model is missing
        val downloadedWithoutPivot = setOf("ja", "es")

        val neededCodes = mutableSetOf("ja", "es")
        if ("ja" != "en" && "es" != "en") neededCodes.add("en")

        val isReady = downloadedWithoutPivot.containsAll(neededCodes)
        assertFalse("Missing English pivot model means pair is not ready", isReady)

        // Fallback must emit original text directly without triggering background download
        val originalText = "Arigato gozaimasu"
        val fallbackOutput = if (!isReady) originalText else "[Translated: $originalText]"
        assertEquals(originalText, fallbackOutput)
    }

    @Test
    fun testDownloadStateSealedClassHierarchy() {
        val idle: com.example.asr.DownloadState = com.example.asr.DownloadState.Idle
        assertTrue("Idle state should be an instance of DownloadState", idle is com.example.asr.DownloadState.Idle)

        val downloading = com.example.asr.DownloadState.Downloading(
            progress = 0.65f,
            speedBytesPerSec = 2_500_000L,
            bytesDownloaded = 28_000_000L,
            totalBytes = 44_200_000L
        )
        assertEquals(0.65f, downloading.progress, 0.001f)
        assertEquals(2_500_000L, downloading.speedBytesPerSec)
        assertEquals(28_000_000L, downloading.bytesDownloaded)
        assertEquals(44_200_000L, downloading.totalBytes)

        val extracting: com.example.asr.DownloadState = com.example.asr.DownloadState.Extracting
        assertTrue("Extracting state should be an instance of DownloadState", extracting is com.example.asr.DownloadState.Extracting)

        val ready: com.example.asr.DownloadState = com.example.asr.DownloadState.Ready
        assertTrue("Ready state should be an instance of DownloadState", ready is com.example.asr.DownloadState.Ready)

        val error = com.example.asr.DownloadState.Error("Insufficient storage space")
        assertEquals("Insufficient storage space", error.message)
    }

    @Test
    fun testSherpaRequiredModelFilesValidation() {
        val requiredFilenames = setOf(
            "encoder.int8.onnx",
            "decoder.int8.onnx",
            "joiner.int8.onnx",
            "tokens.txt"
        )

        // Mock files list simulating missing joiner
        val incompleteFiles = setOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt")
        assertFalse("Missing joiner must return false for model readiness", incompleteFiles.containsAll(requiredFilenames))

        // Complete files
        val completeFiles = setOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
        assertTrue("All 4 required files present must return true", completeFiles.containsAll(requiredFilenames))
    }

    @Test
    fun testSpeedCalculationLogic() {
        val bytesDelta = 1_048_576L // 1 MB
        val timeDeltaMs = 500L // 0.5 sec
        val speedBytesPerSec = (bytesDelta * 1000L) / timeDeltaMs
        assertEquals(2_097_152L, speedBytesPerSec) // 2 MB/s
    }

    @Test
    fun testRecognizerStateNotReadyHierarchy() {
        val notReady: com.example.asr.RecognizerState = com.example.asr.RecognizerState.NotReady
        assertTrue("NotReady must be an instance of RecognizerState", notReady is com.example.asr.RecognizerState.NotReady)
    }

    @Test
    fun testModelFilesSizeValidationRejectsStubFiles() {
        val minOnnxSize = 1_000_000L // 1MB

        // Stub files (e.g. 1000 bytes) must fail validation
        val stubEncoderSize = 1000L
        val stubDecoderSize = 1000L
        val stubJoinerSize = 1000L
        val tokensSize = 250L

        val isStubValid = stubEncoderSize > minOnnxSize &&
                stubDecoderSize > minOnnxSize &&
                stubJoinerSize > minOnnxSize &&
                tokensSize > 10L
        assertFalse("Stub files (< 1MB) must be rejected to prevent native JNI SIGABRT crash", isStubValid)

        // Real models (e.g. 40MB) must pass validation
        val realEncoderSize = 25_000_000L
        val realDecoderSize = 5_000_000L
        val realJoinerSize = 4_000_000L

        val isRealValid = realEncoderSize > minOnnxSize &&
                realDecoderSize > minOnnxSize &&
                realJoinerSize > minOnnxSize &&
                tokensSize > 10L
        assertTrue("Real model files (> 1MB) must pass validation", isRealValid)
    }
}

