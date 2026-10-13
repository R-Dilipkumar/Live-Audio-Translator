package com.example

import com.example.translate.LanguageDetectionGate
import com.example.translate.SubtitleOrderGate
import com.example.translate.TranslationQueue
import com.example.translate.TranslationRequest
import com.example.translate.TranslationResult
import com.example.translate.TranslationStatus
import com.example.translate.translateWithFallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationPipelineTest {

    private fun fakeResult(request: TranslationRequest) = TranslationResult(
        originalText = request.text,
        translatedText = "T(${request.text})",
        sourceLangCode = "ja",
        targetLangCode = "en",
        isFinal = request.isFinal,
        utteranceId = request.utteranceId
    )

    // advanceUntilIdle() does not run backgroundScope work, so the queue gets an ordinary scope
    // on the test scheduler; it is cancelled when the test ends.
    private fun TestScope.queueScope(): CoroutineScope {
        val job = Job()
        backgroundScope.coroutineContext[Job]!!.invokeOnCompletion { job.cancel() }
        return this + job
    }

    private fun TranslationResult.label() = "${if (isFinal) "F" else "P"}$utteranceId:$originalText"

    // ---- Translation queue ordering ----

    @Test
    fun slowPartialFinishingAfterItsFinalIsDropped() = runTest {
        val emitted = mutableListOf<TranslationResult>()
        val queue = TranslationQueue(
            scope = queueScope(),
            process = { request ->
                // The first partial is slow; everything else is fast.
                delay(if (!request.isFinal && request.utteranceId == 1L) 3_000 else 10)
                fakeResult(request)
            },
            emit = { emitted += it }
        )

        queue.submit(TranslationRequest("hel", isFinal = false, utteranceId = 1))
        runCurrent() // consumer takes the slow partial and starts translating it
        queue.submit(TranslationRequest("hello there", isFinal = true, utteranceId = 1))
        queue.submit(TranslationRequest("how", isFinal = false, utteranceId = 2))
        queue.submit(TranslationRequest("how are you", isFinal = true, utteranceId = 2))
        advanceUntilIdle()

        assertEquals(listOf("F1:hello there", "F2:how are you"), emitted.map { it.label() })
    }

    @Test
    fun finalsAreEmittedInSubmissionOrderEvenWhenEarlierOnesAreSlower() = runTest {
        val emitted = mutableListOf<TranslationResult>()
        val queue = TranslationQueue(
            scope = queueScope(),
            process = { request ->
                delay(if (request.utteranceId == 1L) 2_000 else 5)
                fakeResult(request)
            },
            emit = { emitted += it }
        )

        queue.submit(TranslationRequest("one", isFinal = true, utteranceId = 1))
        queue.submit(TranslationRequest("two", isFinal = true, utteranceId = 2))
        queue.submit(TranslationRequest("three", isFinal = true, utteranceId = 3))
        advanceUntilIdle()

        assertEquals(listOf("F1:one", "F2:two", "F3:three"), emitted.map { it.label() })
    }

    @Test
    fun onlyNewestPartialIsTranslatedWhileOneIsInFlight() = runTest {
        val emitted = mutableListOf<TranslationResult>()
        val queue = TranslationQueue(
            scope = queueScope(),
            process = { request ->
                delay(500)
                fakeResult(request)
            },
            emit = { emitted += it }
        )

        queue.submit(TranslationRequest("a", isFinal = false, utteranceId = 1))
        runCurrent() // "a" is in flight
        queue.submit(TranslationRequest("ab", isFinal = false, utteranceId = 1))
        queue.submit(TranslationRequest("abc", isFinal = false, utteranceId = 1))
        advanceUntilIdle()

        assertEquals(listOf("P1:a", "P1:abc"), emitted.map { it.label() })
        assertEquals(1L, queue.droppedPartials.get())
    }

    @Test
    fun partialArrivingAfterItsFinalIsNeverTranslated() = runTest {
        val emitted = mutableListOf<TranslationResult>()
        val processed = mutableListOf<TranslationRequest>()
        val queue = TranslationQueue(
            scope = queueScope(),
            process = { request ->
                processed += request
                fakeResult(request)
            },
            emit = { emitted += it }
        )

        queue.submit(TranslationRequest("done", isFinal = true, utteranceId = 4))
        queue.submit(TranslationRequest("do", isFinal = false, utteranceId = 4))
        queue.submit(TranslationRequest("older", isFinal = false, utteranceId = 3))
        advanceUntilIdle()

        assertEquals(listOf("F4:done"), emitted.map { it.label() })
        assertEquals(1, processed.size)
    }

    @Test
    fun failingRequestDoesNotStopTheQueue() = runTest {
        val emitted = mutableListOf<TranslationResult>()
        val queue = TranslationQueue(
            scope = queueScope(),
            process = { request ->
                if (request.utteranceId == 1L) throw IllegalStateException("boom")
                fakeResult(request)
            },
            emit = { emitted += it }
        )

        queue.submit(TranslationRequest("bad", isFinal = true, utteranceId = 1))
        queue.submit(TranslationRequest("good", isFinal = true, utteranceId = 2))
        advanceUntilIdle()

        assertEquals(listOf("F2:good"), emitted.map { it.label() })
    }

    // ---- Timeout path ----

    @Test
    fun hungTranslationTimesOutAndUsesOfflineFallback() = runTest {
        val (text, status) = translateWithFallback(
            timeoutMs = 5_000,
            translate = {
                delay(60_000) // never answers in time
                "too late"
            },
            fallback = { "Hello" }
        )
        assertEquals("Hello", text)
        assertEquals(TranslationStatus.OFFLINE_FALLBACK, status)
        assertTrue(status.isTranslation)
    }

    @Test
    fun hungTranslationWithoutFallbackReportsFailure() = runTest {
        val (text, status) = translateWithFallback(
            timeoutMs = 5_000,
            translate = {
                delay(60_000)
                "too late"
            },
            fallback = { null }
        )
        assertNull(text)
        assertEquals(TranslationStatus.FAILED, status)
        assertFalse("A failure must not be saved as a translation", status.isTranslation)
    }

    @Test
    fun translationErrorUsesFallback() = runTest {
        val (text, status) = translateWithFallback(
            timeoutMs = 5_000,
            translate = { throw IllegalStateException("model crashed") },
            fallback = { "Thank you" }
        )
        assertEquals("Thank you", text)
        assertEquals(TranslationStatus.OFFLINE_FALLBACK, status)
    }

    @Test
    fun fastTranslationIsReturnedAsIs() = runTest {
        val (text, status) = translateWithFallback(
            timeoutMs = 5_000,
            translate = {
                delay(100)
                "Good morning"
            },
            fallback = { "unused" }
        )
        assertEquals("Good morning", text)
        assertEquals(TranslationStatus.TRANSLATED, status)
    }

    @Test
    fun statusResultsAreNotTranslations() {
        assertFalse(TranslationStatus.PACK_MISSING.isTranslation)
        assertFalse(TranslationStatus.LANGUAGE_UNKNOWN.isTranslation)
        assertTrue(TranslationStatus.TRANSLATED.isTranslation)
        assertTrue(TranslationStatus.SAME_LANGUAGE.isTranslation)
    }

    // ---- Language ID fallback ----

    @Test
    fun confidentDetectionIsUsed() {
        val gate = LanguageDetectionGate()
        assertEquals("ja", gate.resolve(listOf("ja" to 0.92f, "zh" to 0.05f), confidenceThreshold = 0.55f))
    }

    @Test
    fun lowConfidenceKeepsLastConfidentLanguageNotEnglish() {
        val gate = LanguageDetectionGate()
        gate.resolve(listOf("ja" to 0.9f), confidenceThreshold = 0.55f)

        // Short, ambiguous partial: best guess is English but below threshold.
        val resolved = gate.resolve(listOf("en" to 0.40f, "ja" to 0.30f), confidenceThreshold = 0.55f)
        assertEquals("ja", resolved)
    }

    @Test
    fun lowConfidenceWithNoHistoryIsUnknown() {
        val gate = LanguageDetectionGate()
        assertNull(gate.resolve(listOf("en" to 0.3f), confidenceThreshold = 0.55f))
        assertNull(gate.resolve(emptyList(), confidenceThreshold = 0.55f))
    }

    @Test
    fun thresholdIsAppliedPerCall() {
        val gate = LanguageDetectionGate()
        assertNull(gate.resolve(listOf("fr" to 0.6f), confidenceThreshold = 0.8f))
        assertEquals("fr", gate.resolve(listOf("fr" to 0.6f), confidenceThreshold = 0.5f))
    }

    @Test
    fun confidentSwitchToNewLanguage() {
        val gate = LanguageDetectionGate()
        gate.resolve(listOf("ja" to 0.9f), confidenceThreshold = 0.55f)
        assertEquals("ko", gate.resolve(listOf("ko" to 0.8f), confidenceThreshold = 0.55f))
        assertEquals("ko", gate.lastConfidentLanguage)
    }

    // ---- Subtitle ordering (overlay and ViewModel) ----

    @Test
    fun partialNeverReplacesFinalOfSameUtterance() {
        val gate = SubtitleOrderGate()
        assertTrue(gate.accept(utteranceId = 5, isFinal = false))
        assertTrue(gate.accept(utteranceId = 5, isFinal = true))
        assertFalse(gate.accept(utteranceId = 5, isFinal = false))
    }

    @Test
    fun olderUtteranceIsRejected() {
        val gate = SubtitleOrderGate()
        assertTrue(gate.accept(utteranceId = 7, isFinal = false))
        assertFalse(gate.accept(utteranceId = 6, isFinal = true))
        assertFalse(gate.accept(utteranceId = 6, isFinal = false))
    }

    @Test
    fun newerUtteranceIsAccepted() {
        val gate = SubtitleOrderGate()
        assertTrue(gate.accept(utteranceId = 7, isFinal = true))
        assertTrue(gate.accept(utteranceId = 8, isFinal = false))
        assertTrue(gate.accept(utteranceId = 8, isFinal = false))
        assertTrue(gate.accept(utteranceId = 8, isFinal = true))
    }
}
