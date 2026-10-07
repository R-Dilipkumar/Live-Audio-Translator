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
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "live_sub_settings")

data class AppSettings(
    // Audio Pre-processing & Voice Isolation
    val isVoiceIsolationEnabled: Boolean = true,
    val noiseGateThreshold: Float = 0.015f, // Range: 0.000f to 0.050f (or 0% to 100%)

    // Transcription & ASR Parameters
    val silenceEndpointDelay: Float = 1.1f, // Range: 0.5s to 2.5s
    val maxUtteranceWindow: Float = 15.0f,  // Range: 5.0s to 25.0s
    val languageIdConfidence: Float = 0.55f, // Range: 0.30 to 0.90
    val isAntiFreezeWatchdogEnabled: Boolean = true,

    // Subtitle Display & Timing Behavior
    val subtitleLingerTimeSeconds: Float = 3.5f, // 1.0s to 10.0s, or 0f for 'Keep until next speech'
    val overlayOpacity: Float = 0.95f, // 0.20 to 1.00
    val fontSizeSp: Float = 17.0f, // 12sp to 24sp
    val maxDisplayLines: Int = 2, // 1, 2, or 3
    val showOriginalSpeech: Boolean = true,
    val isTextOutlineShadowEnabled: Boolean = true,
    val textShadowRadius: Float = 4.0f
)

class SettingsRepository(private val context: Context) {

    private object PreferencesKeys {
        val KEY_VOICE_ISOLATION = booleanPreferencesKey("pref_voice_isolation")
        val KEY_NOISE_GATE = floatPreferencesKey("pref_noise_gate")
        val KEY_ENDPOINT_DELAY = floatPreferencesKey("pref_endpoint_delay")
        val KEY_MAX_UTTERANCE = floatPreferencesKey("pref_max_utterance")
        val KEY_LANG_CONFIDENCE = floatPreferencesKey("pref_lang_confidence")
        val KEY_ANTI_FREEZE = booleanPreferencesKey("pref_anti_freeze")
        val KEY_LINGER_TIME = floatPreferencesKey("pref_linger_time")
        val KEY_OPACITY = floatPreferencesKey("pref_opacity")
        val KEY_FONT_SIZE = floatPreferencesKey("pref_font_size")
        val KEY_MAX_LINES = intPreferencesKey("pref_max_lines")
        val KEY_SHOW_ORIGINAL = booleanPreferencesKey("pref_show_original")
        val KEY_TEXT_OUTLINE_SHADOW = booleanPreferencesKey("pref_text_outline_shadow")
        val KEY_TEXT_SHADOW_RADIUS = floatPreferencesKey("pref_text_shadow_radius")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { preferences ->
        AppSettings(
            isVoiceIsolationEnabled = preferences[PreferencesKeys.KEY_VOICE_ISOLATION] ?: true,
            noiseGateThreshold = preferences[PreferencesKeys.KEY_NOISE_GATE] ?: 0.015f,
            silenceEndpointDelay = preferences[PreferencesKeys.KEY_ENDPOINT_DELAY] ?: 1.1f,
            maxUtteranceWindow = preferences[PreferencesKeys.KEY_MAX_UTTERANCE] ?: 15.0f,
            languageIdConfidence = preferences[PreferencesKeys.KEY_LANG_CONFIDENCE] ?: 0.55f,
            isAntiFreezeWatchdogEnabled = preferences[PreferencesKeys.KEY_ANTI_FREEZE] ?: true,
            subtitleLingerTimeSeconds = preferences[PreferencesKeys.KEY_LINGER_TIME] ?: 3.5f,
            overlayOpacity = preferences[PreferencesKeys.KEY_OPACITY] ?: 0.95f,
            fontSizeSp = preferences[PreferencesKeys.KEY_FONT_SIZE] ?: 17.0f,
            maxDisplayLines = preferences[PreferencesKeys.KEY_MAX_LINES] ?: 2,
            showOriginalSpeech = preferences[PreferencesKeys.KEY_SHOW_ORIGINAL] ?: true,
            isTextOutlineShadowEnabled = preferences[PreferencesKeys.KEY_TEXT_OUTLINE_SHADOW] ?: true,
            textShadowRadius = preferences[PreferencesKeys.KEY_TEXT_SHADOW_RADIUS] ?: 4.0f
        )
    }

    suspend fun updateVoiceIsolation(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.KEY_VOICE_ISOLATION] = enabled }
    }

    suspend fun updateNoiseGateThreshold(threshold: Float) {
        context.dataStore.edit { it[PreferencesKeys.KEY_NOISE_GATE] = threshold }
    }

    suspend fun updateSilenceEndpointDelay(delay: Float) {
        context.dataStore.edit { it[PreferencesKeys.KEY_ENDPOINT_DELAY] = delay }
    }

    suspend fun updateMaxUtteranceWindow(window: Float) {
        context.dataStore.edit { it[PreferencesKeys.KEY_MAX_UTTERANCE] = window }
    }

    suspend fun updateLanguageIdConfidence(confidence: Float) {
        context.dataStore.edit { it[PreferencesKeys.KEY_LANG_CONFIDENCE] = confidence }
    }

    suspend fun updateAntiFreezeWatchdog(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.KEY_ANTI_FREEZE] = enabled }
    }

    suspend fun updateSubtitleLingerTime(seconds: Float) {
        context.dataStore.edit { it[PreferencesKeys.KEY_LINGER_TIME] = seconds }
    }

    suspend fun updateOverlayOpacity(opacity: Float) {
        context.dataStore.edit { it[PreferencesKeys.KEY_OPACITY] = opacity }
    }

    suspend fun updateFontSize(sizeSp: Float) {
        context.dataStore.edit { it[PreferencesKeys.KEY_FONT_SIZE] = sizeSp }
    }

    suspend fun updateMaxDisplayLines(lines: Int) {
        context.dataStore.edit { it[PreferencesKeys.KEY_MAX_LINES] = lines }
    }

    suspend fun updateShowOriginalSpeech(show: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.KEY_SHOW_ORIGINAL] = show }
    }

    suspend fun updateTextOutlineShadow(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.KEY_TEXT_OUTLINE_SHADOW] = enabled }
    }

    suspend fun updateTextShadowRadius(radius: Float) {
        context.dataStore.edit { it[PreferencesKeys.KEY_TEXT_SHADOW_RADIUS] = radius }
    }

    suspend fun resetToDefaults() {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.KEY_VOICE_ISOLATION] = true
            preferences[PreferencesKeys.KEY_NOISE_GATE] = 0.015f
            preferences[PreferencesKeys.KEY_ENDPOINT_DELAY] = 1.1f
            preferences[PreferencesKeys.KEY_MAX_UTTERANCE] = 15.0f
            preferences[PreferencesKeys.KEY_LANG_CONFIDENCE] = 0.55f
            preferences[PreferencesKeys.KEY_ANTI_FREEZE] = true
            preferences[PreferencesKeys.KEY_LINGER_TIME] = 3.5f
            preferences[PreferencesKeys.KEY_OPACITY] = 0.95f
            preferences[PreferencesKeys.KEY_FONT_SIZE] = 17.0f
            preferences[PreferencesKeys.KEY_MAX_LINES] = 2
            preferences[PreferencesKeys.KEY_SHOW_ORIGINAL] = true
            preferences[PreferencesKeys.KEY_TEXT_OUTLINE_SHADOW] = true
            preferences[PreferencesKeys.KEY_TEXT_SHADOW_RADIUS] = 4.0f
        }
    }
}
