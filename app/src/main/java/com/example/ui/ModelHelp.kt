package com.example.ui

import com.example.asr.AsrModelConfig
import com.example.asr.ModelManager
import com.example.asr.RecognitionMode
import com.example.translate.SupportedLanguage
import com.example.translate.languageDisplayName

/** Short name of a latency, as shown in Settings → Preferred latency. */
fun latencyLabel(mode: RecognitionMode): String = when (mode) {
    RecognitionMode.SPEED -> "Instant"
    RecognitionMode.ACCURACY -> "About 1 second"
}

/**
 * Whether [model] understands the language [code] (two-letter). Null when the catalog does not
 * list all of the model's languages (then it is not flagged).
 */
fun AsrModelConfig.understands(code: String): Boolean? {
    if (languageHints == ModelManager.WHISPER_ANY_LANGUAGE) return code in ModelManager.WHISPER_LANGUAGE_CODES
    if (code in languageHints || languageCode == code) return true
    val name = languageDisplayName(code)
    if (languages.any { it.equals(name, ignoreCase = true) }) return true
    // "and 15 more", "99 languages": the list is not complete
    if (languages.any { it.contains("more") || it.contains("languages") }) return null
    return false
}

/** Whether [model] can be told to listen for one language only (SenseVoice, Whisper). */
fun AsrModelConfig.canLockTo(code: String): Boolean =
    mode == RecognitionMode.ACCURACY &&
        (if (languageHints == ModelManager.WHISPER_ANY_LANGUAGE) code in ModelManager.WHISPER_LANGUAGE_CODES else code in languageHints)

data class LanguageHelp(val text: String, val isWarning: Boolean)

/** One line explaining what the spoken-language choice does with [model]. */
fun languageHelp(source: SupportedLanguage, model: AsrModelConfig): LanguageHelp {
    val name = model.name
    if (source.isAutoDetect) {
        return if (!model.isMultilingual) {
            LanguageHelp("$name only understands ${model.languages.joinToString(" and ")}.", isWarning = false)
        } else {
            LanguageHelp(
                "$name works out the language by itself. If it mixes languages up, pick the spoken language.",
                isWarning = false
            )
        }
    }
    val language = source.displayName
    return when {
        model.understands(source.code) == false ->
            LanguageHelp("$name can't understand $language. Pick a $language model in Models.", isWarning = true)
        model.canLockTo(source.code) ->
            LanguageHelp("$name listens for $language only, so it won't mistake it for another language.", isWarning = false)
        model.isMultilingual ->
            LanguageHelp(
                "$language is used for translation. $name can't be locked to one language, so mix-ups can still happen.",
                isWarning = false
            )
        else -> LanguageHelp("$name is made for $language.", isWarning = false)
    }
}
