package com.example.asr

/**
 * Validates whether a text string represents genuine spoken utterance or a system status,
 * initialization placeholder, or diagnostic notification.
 */
object SpeechValidator {

    private val STATUS_PHRASES = listOf(
        "translating internal media stream",
        "awaiting audio stream",
        "live audio stream active",
        "live audio stream",
        "listening...",
        "listening",
        "waiting for speech",
        "waiting for audio",
        "waiting for live speech",
        "translation will appear here",
        "subtitles will stream here",
        "subtitles will stream",
        "[translation paused]",
        "ready to transcribe",
        "asr model not initialized",
        "failed to initialize",
        "speech recognizer runtime error",
        "internal media stream",
        "audio stream active",
        "internal audio capture active",
        "audio capture active",
        "ready to transcribe & translate audio"
    )

    /**
     * Checks if the given text is a genuine spoken utterance.
     * Returns false if the text is null, blank, matches a known system status phrase,
     * or contains bracketed status annotations.
     */
    fun isGenuineSpeech(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false

        val lower = trimmed.lowercase()
        for (phrase in STATUS_PHRASES) {
            if (lower == phrase || lower.startsWith(phrase)) {
                return false
            }
        }

        // Diagnostic brackets such as [Translation Paused], [Status], etc.
        // Allows model downloading indicators (e.g., [Downloading En-Ja dictionary...])
        if (trimmed.startsWith("[") && trimmed.endsWith("]") && !trimmed.contains("downloading", ignoreCase = true)) {
            return false
        }

        return true
    }
}
