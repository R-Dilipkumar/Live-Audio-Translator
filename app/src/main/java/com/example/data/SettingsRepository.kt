package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "live_sub_settings")

/**
 * Defaults are tuned so quiet, soft or husky voices are still recognized and translated:
 * rumble filter on, quiet-voice boost on, noise gate off.
 */
data class AppSettings(
    // ---- Developer: audio ----
    val isVoiceIsolationEnabled: Boolean = true, // 80 Hz rumble filter
    val isQuietVoiceBoostEnabled: Boolean = true, // automatic gain for soft voices
    val noiseGateThreshold: Float = 0f, // 0 = off. Range 0.000 to 0.050

    // ---- Developer: recognition and translation ----
    val silenceEndpointDelay: Float = 0.8f, // seconds, 0.5 to 2.5
    val maxUtteranceWindow: Float = 15.0f,  // seconds, 5 to 25
    val noSpeechRestartDelay: Float = 1.2f, // seconds, 0.5 to 3.0: no words -> fresh stream
    val minSentenceLength: Float = 0.0f,    // seconds, 0 to 3: shortest sentence a pause may end
    val singleCharacterMode: Int = 0, // 0 join to next sentence, 1 drop, 2 keep as its own line
    val isSentenceJoiningEnabled: Boolean = true, // hold pieces until the sentence ends, then translate it whole
    val isNoiseTextFilterEnabled: Boolean = true, // drop text that looks like music or noise
    val languageIdConfidence: Float = 0.5f, // 0.30 to 0.90
    val isAntiFreezeWatchdogEnabled: Boolean = true,
    val isDebugAudioRecordingEnabled: Boolean = false, // saves recognizer input to files/debug

    // ---- User: subtitles ----
    val subtitleLingerTimeSeconds: Float = 4.0f, // 1 to 10 s, or 0 for "until the next sentence"
    val overlayOpacity: Float = 0.9f, // 0.20 to 1.00
    val fontSizeSp: Float = 18.0f, // 12 to 26
    val maxDisplayLines: Int = 2, // 1 to MAX_OVERLAY_LINES
    // Roll-up style: past subtitles kept to scroll back to
    val rollUpHistory: Int = com.example.overlay.DEFAULT_ROLL_UP_HISTORY,
    val showOriginalSpeech: Boolean = true,
    val subtitleStyle: Int = 1, // SubtitleStyle code: 0 movie cards, 1 roll-up, 2 live
    val isAutoOverlayEnabled: Boolean = true, // open the floating subtitles when capture starts
    val overlayWidthDp: Int = 0, // floating window width set by resizing; 0 = automatic

    // ---- Developer: subtitle rendering ----
    val isTextOutlineShadowEnabled: Boolean = true,
    val textShadowRadius: Float = 4.0f
)

class SettingsRepository(context: Context) {

    // Hold the application context so a repository created from a Service or Activity never keeps it alive
    private val appContext: Context = context.applicationContext ?: context

    companion object {
        private const val SETTINGS_VERSION = 3
    }

    private object PreferencesKeys {
        val KEY_VOICE_ISOLATION = booleanPreferencesKey("pref_voice_isolation")
        val KEY_NOISE_GATE = floatPreferencesKey("pref_noise_gate")
        val KEY_QUIET_BOOST = booleanPreferencesKey("pref_quiet_voice_boost")
        val KEY_NO_SPEECH_RESTART = floatPreferencesKey("pref_no_speech_restart")
        val KEY_MIN_SENTENCE = floatPreferencesKey("pref_min_sentence_length")
        val KEY_SINGLE_CHAR_MODE = intPreferencesKey("pref_single_char_mode")
        val KEY_SENTENCE_JOINING = booleanPreferencesKey("pref_sentence_joining")
        val KEY_NOISE_TEXT_FILTER = booleanPreferencesKey("pref_noise_text_filter")
        val KEY_DEBUG_RECORD = booleanPreferencesKey("pref_debug_record_audio")
        val KEY_SETTINGS_VERSION = intPreferencesKey("pref_settings_version")
        val KEY_ENDPOINT_DELAY = floatPreferencesKey("pref_endpoint_delay")
        val KEY_MAX_UTTERANCE = floatPreferencesKey("pref_max_utterance")
        val KEY_LANG_CONFIDENCE = floatPreferencesKey("pref_lang_confidence")
        val KEY_ANTI_FREEZE = booleanPreferencesKey("pref_anti_freeze")
        val KEY_LINGER_TIME = floatPreferencesKey("pref_linger_time")
        val KEY_OPACITY = floatPreferencesKey("pref_opacity")
        val KEY_FONT_SIZE = floatPreferencesKey("pref_font_size")
        val KEY_MAX_LINES = intPreferencesKey("pref_max_lines")
        val KEY_SHOW_ORIGINAL = booleanPreferencesKey("pref_show_original")
        // Older on/off setting (movie vs live); only read to carry a saved choice over
        val KEY_MOVIE_STYLE = booleanPreferencesKey("pref_movie_subtitle_style")
        val KEY_SUBTITLE_STYLE = intPreferencesKey("pref_subtitle_style")
        val KEY_AUTO_OVERLAY = booleanPreferencesKey("pref_auto_overlay")
        val KEY_OVERLAY_WIDTH = intPreferencesKey("pref_overlay_width_dp")
        val KEY_ROLL_UP_HISTORY = intPreferencesKey("pref_roll_up_history")
        val KEY_TEXT_OUTLINE_SHADOW = booleanPreferencesKey("pref_text_outline_shadow")
        val KEY_TEXT_SHADOW_RADIUS = floatPreferencesKey("pref_text_shadow_radius")
    }

    val settingsFlow: Flow<AppSettings> = appContext.dataStore.data.map { preferences ->
        val d = AppSettings()
        AppSettings(
            isVoiceIsolationEnabled = preferences[PreferencesKeys.KEY_VOICE_ISOLATION] ?: d.isVoiceIsolationEnabled,
            isQuietVoiceBoostEnabled = preferences[PreferencesKeys.KEY_QUIET_BOOST] ?: d.isQuietVoiceBoostEnabled,
            noiseGateThreshold = preferences[PreferencesKeys.KEY_NOISE_GATE] ?: d.noiseGateThreshold,
            silenceEndpointDelay = preferences[PreferencesKeys.KEY_ENDPOINT_DELAY] ?: d.silenceEndpointDelay,
            maxUtteranceWindow = preferences[PreferencesKeys.KEY_MAX_UTTERANCE] ?: d.maxUtteranceWindow,
            noSpeechRestartDelay = preferences[PreferencesKeys.KEY_NO_SPEECH_RESTART] ?: d.noSpeechRestartDelay,
            minSentenceLength = preferences[PreferencesKeys.KEY_MIN_SENTENCE] ?: d.minSentenceLength,
            singleCharacterMode = preferences[PreferencesKeys.KEY_SINGLE_CHAR_MODE] ?: d.singleCharacterMode,
            isSentenceJoiningEnabled = preferences[PreferencesKeys.KEY_SENTENCE_JOINING] ?: d.isSentenceJoiningEnabled,
            isNoiseTextFilterEnabled = preferences[PreferencesKeys.KEY_NOISE_TEXT_FILTER] ?: d.isNoiseTextFilterEnabled,
            languageIdConfidence = preferences[PreferencesKeys.KEY_LANG_CONFIDENCE] ?: d.languageIdConfidence,
            isAntiFreezeWatchdogEnabled = preferences[PreferencesKeys.KEY_ANTI_FREEZE] ?: d.isAntiFreezeWatchdogEnabled,
            isDebugAudioRecordingEnabled = preferences[PreferencesKeys.KEY_DEBUG_RECORD] ?: d.isDebugAudioRecordingEnabled,
            subtitleLingerTimeSeconds = preferences[PreferencesKeys.KEY_LINGER_TIME] ?: d.subtitleLingerTimeSeconds,
            overlayOpacity = preferences[PreferencesKeys.KEY_OPACITY] ?: d.overlayOpacity,
            fontSizeSp = preferences[PreferencesKeys.KEY_FONT_SIZE] ?: d.fontSizeSp,
            maxDisplayLines = (preferences[PreferencesKeys.KEY_MAX_LINES] ?: d.maxDisplayLines)
                .coerceIn(1, com.example.overlay.MAX_OVERLAY_LINES),
            rollUpHistory = (preferences[PreferencesKeys.KEY_ROLL_UP_HISTORY] ?: d.rollUpHistory)
                .coerceIn(com.example.overlay.ROLL_UP_HISTORY_CHOICES.first(), com.example.overlay.ROLL_UP_HISTORY_CHOICES.last()),
            showOriginalSpeech = preferences[PreferencesKeys.KEY_SHOW_ORIGINAL] ?: d.showOriginalSpeech,
            subtitleStyle = preferences[PreferencesKeys.KEY_SUBTITLE_STYLE]
                ?: preferences[PreferencesKeys.KEY_MOVIE_STYLE]?.let { movie -> if (movie) 0 else 2 }
                ?: d.subtitleStyle,
            isAutoOverlayEnabled = preferences[PreferencesKeys.KEY_AUTO_OVERLAY] ?: d.isAutoOverlayEnabled,
            overlayWidthDp = preferences[PreferencesKeys.KEY_OVERLAY_WIDTH] ?: d.overlayWidthDp,
            isTextOutlineShadowEnabled = preferences[PreferencesKeys.KEY_TEXT_OUTLINE_SHADOW] ?: d.isTextOutlineShadowEnabled,
            textShadowRadius = preferences[PreferencesKeys.KEY_TEXT_SHADOW_RADIUS] ?: d.textShadowRadius
        )
    }

    suspend fun updateVoiceIsolation(enabled: Boolean) {
        appContext.dataStore.edit { it[PreferencesKeys.KEY_VOICE_ISOLATION] = enabled }
    }

    suspend fun updateQuietVoiceBoost(enabled: Boolean) {
        appContext.dataStore.edit { it[PreferencesKeys.KEY_QUIET_BOOST] = enabled }
    }

    suspend fun updateDebugAudioRecording(enabled: Boolean) {
        appContext.dataStore.edit { it[PreferencesKeys.KEY_DEBUG_RECORD] = enabled }
    }

    suspend fun updateNoSpeechRestartDelay(seconds: Float) {
        appContext.dataStore.edit { it[PreferencesKeys.KEY_NO_SPEECH_RESTART] = seconds }
    }

    suspend fun updateMinSentenceLength(seconds: Float) {
        appContext.dataStore.edit { it[PreferencesKeys.KEY_MIN_SENTENCE] = seconds }
    }

    suspend fun updateSingleCharacterMode(mode: Int) {
        appContext.dataStore.edit { it[PreferencesKeys.KEY_SINGLE_CHAR_MODE] = mode }
    }

    suspend fun updateSentenceJoining(enabled: Boolean) {
        appContext.dataStore.edit { it[PreferencesKeys.KEY_SENTENCE_JOINING] = enabled }
    }

    suspend fun updateNoiseTextFilter(enabled: Boolean) {
        appContext.dataStore.edit { it[PreferencesKeys.KEY_NOISE_TEXT_FILTER] = enabled }
    }

    suspend fun updateNoiseGateThreshold(threshold: Float) {
        appContext.dataStore.edit { it[PreferencesKeys.KEY_NOISE_GATE] = threshold }
    }

    suspend fun updateSilenceEndpointDelay(delay: Float) {
        appContext.dataStore.edit { it[PreferencesKeys.KEY_ENDPOINT_DELAY] = delay }
    }

    suspend fun updateMaxUtteranceWindow(window: Float) {
        appContext.dataStore.edit { it[PreferencesKeys.KEY_MAX_UTTERANCE] = window }
    }

    suspend fun updateLanguageIdConfidence(confidence: Float) {
        appContext.dataStore.edit { it[PreferencesKeys.KEY_LANG_CONFIDENCE] = confidence }
    }

    suspend fun updateAntiFreezeWatchdog(enabled: Boolean) {
        appContext.dataStore.edit { it[PreferencesKeys.KEY_ANTI_FREEZE] = enabled }
    }

    suspend fun updateSubtitleLingerTime(seconds: Float) {
        appContext.dataStore.edit { it[PreferencesKeys.KEY_LINGER_TIME] = seconds }
    }

    suspend fun updateOverlayOpacity(opacity: Float) {
        appContext.dataStore.edit { it[PreferencesKeys.KEY_OPACITY] = opacity }
    }

    suspend fun updateFontSize(sizeSp: Float) {
        appContext.dataStore.edit { it[PreferencesKeys.KEY_FONT_SIZE] = sizeSp }
    }

    suspend fun updateMaxDisplayLines(lines: Int) {
        appContext.dataStore.edit { it[PreferencesKeys.KEY_MAX_LINES] = lines }
    }

    suspend fun updateShowOriginalSpeech(show: Boolean) {
        appContext.dataStore.edit { it[PreferencesKeys.KEY_SHOW_ORIGINAL] = show }
    }

    suspend fun updateTextOutlineShadow(enabled: Boolean) {
        appContext.dataStore.edit { it[PreferencesKeys.KEY_TEXT_OUTLINE_SHADOW] = enabled }
    }

    suspend fun updateTextShadowRadius(radius: Float) {
        appContext.dataStore.edit { it[PreferencesKeys.KEY_TEXT_SHADOW_RADIUS] = radius }
    }

    suspend fun updateSubtitleStyle(styleCode: Int) {
        appContext.dataStore.edit { it[PreferencesKeys.KEY_SUBTITLE_STYLE] = styleCode }
    }

    /**
     * Saves everything the floating window's own settings panel can change, in one write. One
     * write means one settingsFlow emission, so no half-saved state flows back into the window.
     */
    suspend fun updateOverlaySettings(
        fontSizeSp: Float,
        opacity: Float,
        maxLines: Int,
        showOriginal: Boolean,
        lingerSeconds: Float,
        textShadow: Boolean,
        textShadowRadius: Float,
        styleCode: Int,
        rollUpHistory: Int
    ) {
        appContext.dataStore.edit {
            it[PreferencesKeys.KEY_FONT_SIZE] = fontSizeSp
            it[PreferencesKeys.KEY_OPACITY] = opacity
            it[PreferencesKeys.KEY_MAX_LINES] = maxLines
            it[PreferencesKeys.KEY_SHOW_ORIGINAL] = showOriginal
            it[PreferencesKeys.KEY_LINGER_TIME] = lingerSeconds
            it[PreferencesKeys.KEY_TEXT_OUTLINE_SHADOW] = textShadow
            it[PreferencesKeys.KEY_TEXT_SHADOW_RADIUS] = textShadowRadius
            it[PreferencesKeys.KEY_SUBTITLE_STYLE] = styleCode
            it[PreferencesKeys.KEY_ROLL_UP_HISTORY] = rollUpHistory
        }
    }

    suspend fun updateRollUpHistory(count: Int) {
        appContext.dataStore.edit { it[PreferencesKeys.KEY_ROLL_UP_HISTORY] = count }
    }

    suspend fun updateAutoOverlay(enabled: Boolean) {
        appContext.dataStore.edit { it[PreferencesKeys.KEY_AUTO_OVERLAY] = enabled }
    }

    suspend fun updateOverlayWidth(widthDp: Int) {
        appContext.dataStore.edit { it[PreferencesKeys.KEY_OVERLAY_WIDTH] = widthDp }
    }

    /**
     * Older versions saved audio settings that cut quiet speech (a noise gate and a 3.5 kHz
     * filter). Resets the developer settings once so existing installs get the tuned defaults.
     */
    suspend fun applyTunedDefaultsOnce() {
        val alreadyApplied = appContext.dataStore.data.first()[PreferencesKeys.KEY_SETTINGS_VERSION] ?: 0
        if (alreadyApplied >= SETTINGS_VERSION) return
        resetDeveloperDefaults()
        appContext.dataStore.edit { it[PreferencesKeys.KEY_SETTINGS_VERSION] = SETTINGS_VERSION }
    }

    /** Resets the user-facing subtitle settings. Developer settings are left as they are. */
    suspend fun resetUserDefaults() {
        appContext.dataStore.edit { preferences ->
            listOf(
                PreferencesKeys.KEY_LINGER_TIME,
                PreferencesKeys.KEY_OPACITY,
                PreferencesKeys.KEY_FONT_SIZE,
                PreferencesKeys.KEY_MAX_LINES,
                PreferencesKeys.KEY_SHOW_ORIGINAL,
                PreferencesKeys.KEY_MOVIE_STYLE,
                PreferencesKeys.KEY_SUBTITLE_STYLE,
                PreferencesKeys.KEY_AUTO_OVERLAY,
                PreferencesKeys.KEY_OVERLAY_WIDTH,
                PreferencesKeys.KEY_ROLL_UP_HISTORY
            ).forEach { preferences.remove(it) }
        }
    }

    /** Resets the developer settings (audio, recognition, text rendering) to the tuned defaults. */
    suspend fun resetDeveloperDefaults() {
        appContext.dataStore.edit { preferences ->
            listOf(
                PreferencesKeys.KEY_VOICE_ISOLATION,
                PreferencesKeys.KEY_QUIET_BOOST,
                PreferencesKeys.KEY_NOISE_GATE,
                PreferencesKeys.KEY_ENDPOINT_DELAY,
                PreferencesKeys.KEY_MAX_UTTERANCE,
                PreferencesKeys.KEY_NO_SPEECH_RESTART,
                PreferencesKeys.KEY_MIN_SENTENCE,
                PreferencesKeys.KEY_SINGLE_CHAR_MODE,
                PreferencesKeys.KEY_SENTENCE_JOINING,
                PreferencesKeys.KEY_NOISE_TEXT_FILTER,
                PreferencesKeys.KEY_LANG_CONFIDENCE,
                PreferencesKeys.KEY_ANTI_FREEZE,
                PreferencesKeys.KEY_DEBUG_RECORD,
                PreferencesKeys.KEY_TEXT_OUTLINE_SHADOW,
                PreferencesKeys.KEY_TEXT_SHADOW_RADIUS
            ).forEach { preferences.remove(it) }
        }
    }
}
