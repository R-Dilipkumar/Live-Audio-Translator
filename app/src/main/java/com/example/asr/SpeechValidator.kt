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

    private val STATUS_PHRASE_SET = STATUS_PHRASES.map { normalize(it) }.toSet()

    // Lowercase, collapse whitespace, and drop trailing ellipsis/punctuation so
    // "Listening...", "Listening…" and "listening" compare equal.
    private fun normalize(text: String): String {
        return text.trim()
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trimEnd('.', '…', '!', '?', ' ')
    }

    /**
     * Checks if the given text is a genuine spoken utterance.
     * Returns false if the text is null, blank, is exactly a known system status phrase
     * (ignoring case and trailing punctuation), or is a bracketed status annotation.
     * Only whole phrases are matched, so real speech that merely starts with a status word
     * ("Listening to music is fun") is kept.
     */
    fun isGenuineSpeech(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false

        if (normalize(trimmed) in STATUS_PHRASE_SET) {
            return false
        }

        // Diagnostic brackets such as [Translation Paused], [Downloading ...], [Status]
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return false
        }

        return true
    }
}
