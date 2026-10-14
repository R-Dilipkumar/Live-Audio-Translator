package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.AppSettings
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Settings tab. The main page only has what a user needs to read subtitles comfortably.
 * Everything that changes how audio is recognized lives under Developer settings, with its own
 * reset, so a normal user cannot accidentally break transcription.
 */
@Composable
fun SettingsScreen(
    viewModel: AudioTranslatorViewModel,
    // Opens the Models tab (the "Get it" button when the chosen latency's model is missing)
    onOpenModels: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showDeveloper by rememberSaveable { mutableStateOf(false) }
    if (showDeveloper) {
        BackHandler { showDeveloper = false }
        DeveloperSettings(viewModel = viewModel, onBack = { showDeveloper = false }, modifier = modifier)
    } else {
        UserSettings(viewModel = viewModel, onOpenDeveloper = { showDeveloper = true }, onOpenModels = onOpenModels, modifier = modifier)
    }
}

@Composable
private fun UserSettings(
    viewModel: AudioTranslatorViewModel,
    onOpenDeveloper: () -> Unit,
    onOpenModels: (() -> Unit)?,
    modifier: Modifier
) {
    val settings by viewModel.appSettings.collectAsStateWithLifecycle()
    val repo = viewModel.settingsRepository
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("settings_screen_container"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsGroup(icon = Icons.Default.Subtitles, title = "Recognition") {
            val mode by viewModel.recognitionMode.collectAsStateWithLifecycle()
            val model by viewModel.selectedAsrModel.collectAsStateWithLifecycle()
            val ready by viewModel.readyModelIds.collectAsStateWithLifecycle()
            LatencyPreference(
                mode = mode,
                modelName = model.name,
                isModelInstalled = model.id in ready,
                onSelect = { viewModel.setRecognitionMode(it) },
                onOpenModels = onOpenModels
            )
        }

        SettingsGroup(icon = Icons.Default.Subtitles, title = "Subtitles") {
            SliderSetting(
                title = "Text size",
                value = settings.fontSizeSp,
                valueText = "${settings.fontSizeSp.toInt()}",
                range = 12f..26f,
                steps = 13,
                tag = "slider_font_size"
            ) { scope.launch { repo.updateFontSize(it) } }

            ChoiceSetting(
                title = "Lines shown",
                options = listOf(1 to "1", 2 to "2", 3 to "3", 4 to "4"),
                selected = settings.maxDisplayLines,
                tagPrefix = "chip_max_lines_"
            ) { scope.launch { repo.updateMaxDisplayLines(it) } }

            ChoiceSetting(
                title = "Subtitle style",
                subtitle = "Roll-up: lines stack and roll up, scroll back to reread. Movie: whole cards that hold. Live: one line rewritten as words arrive.",
                options = listOf(1 to "Roll-up", 0 to "Movie", 2 to "Live"),
                selected = settings.subtitleStyle,
                tagPrefix = "chip_subtitle_style_"
            ) { scope.launch { repo.updateSubtitleStyle(it) } }

            ChoiceSetting(
                title = "Keep subtitles on screen",
                options = listOf(2f to "2 s", 4f to "4 s", 7f to "7 s", 0f to "Until next"),
                selected = LINGER_CHOICES.minByOrNull { kotlin.math.abs(it - settings.subtitleLingerTimeSeconds) } ?: 4f,
                tagPrefix = "chip_linger_"
            ) { scope.launch { repo.updateSubtitleLingerTime(it) } }

            SliderSetting(
                title = "Background",
                value = settings.overlayOpacity,
                valueText = "${(settings.overlayOpacity * 100).toInt()}%",
                range = 0.2f..1f,
                tag = "slider_overlay_opacity"
            ) { scope.launch { repo.updateOverlayOpacity(it) } }

            SwitchSetting(
                title = "Open subtitles when starting",
                subtitle = "Show the floating subtitles over other apps as soon as you press Start",
                checked = settings.isAutoOverlayEnabled,
                tag = "toggle_auto_overlay"
            ) { scope.launch { repo.updateAutoOverlay(it) } }

            SwitchSetting(
                title = "Show original speech",
                subtitle = "Show what was said above the translation",
                checked = settings.showOriginalSpeech,
                tag = "toggle_show_original"
            ) { scope.launch { repo.updateShowOriginalSpeech(it) } }
        }

        ResetButton(text = "Reset to defaults", tag = "reset_settings_button") {
            scope.launch { repo.resetUserDefaults() }
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenDeveloper)
                .testTag("open_developer_settings")
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Build, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Developer settings", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Audio and recognition tuning. The defaults work for most audio.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun DeveloperSettings(
    viewModel: AudioTranslatorViewModel,
    onBack: () -> Unit,
    modifier: Modifier
) {
    val settings by viewModel.appSettings.collectAsStateWithLifecycle()
    val repo = viewModel.settingsRepository
    val scope = rememberCoroutineScope()
    val defaults = AppSettings()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("developer_settings_container"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("developer_settings_back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column {
                Text("Developer settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Changes apply the next time subtitles start.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SettingsGroup(icon = Icons.Default.GraphicEq, title = "Audio") {
            SwitchSetting(
                title = "Boost quiet voices",
                subtitle = "Raises soft, breathy or husky speech to a normal level (up to +18 dB). Default: on",
                checked = settings.isQuietVoiceBoostEnabled,
                tag = "toggle_quiet_boost"
            ) { scope.launch { repo.updateQuietVoiceBoost(it) } }

            SwitchSetting(
                title = "Remove low rumble",
                subtitle = "Filters hum and deep bass below 80 Hz. Speech is not affected. Default: on",
                checked = settings.isVoiceIsolationEnabled,
                tag = "toggle_voice_isolation"
            ) { scope.launch { repo.updateVoiceIsolation(it) } }

            SliderSetting(
                title = "Background noise gate",
                subtitle = "Turns down quiet sound between sentences. Can cut soft speakers. Default: off",
                value = settings.noiseGateThreshold,
                valueText = if (settings.noiseGateThreshold <= 0.0005f) "Off" else "${(settings.noiseGateThreshold * 1000).toInt()}",
                range = 0f..0.05f,
                tag = "slider_noise_gate"
            ) { scope.launch { repo.updateNoiseGateThreshold(if (it < 0.001f) 0f else it) } }
        }

        SettingsGroup(icon = Icons.Default.RecordVoiceOver, title = "Recognition and translation") {
            SliderSetting(
                title = "Pause that ends a sentence",
                subtitle = "Longer keeps sentences together; shorter shows translations sooner. Default: ${format1(defaults.silenceEndpointDelay)} s",
                value = settings.silenceEndpointDelay,
                valueText = "${format1(settings.silenceEndpointDelay)} s",
                range = 0.5f..2.5f,
                steps = 19,
                tag = "slider_endpoint_delay"
            ) { scope.launch { repo.updateSilenceEndpointDelay(it) } }

            SliderSetting(
                title = "Restart after no words",
                subtitle = "How long with no recognized words before listening starts fresh. Shorter recovers faster after music or noise; longer avoids cutting slow speakers. Default: ${format1(defaults.noSpeechRestartDelay)} s",
                value = settings.noSpeechRestartDelay,
                valueText = "${format1(settings.noSpeechRestartDelay)} s",
                range = 0.5f..3f,
                steps = 24,
                tag = "slider_no_speech_restart"
            ) { scope.launch { repo.updateNoSpeechRestartDelay(it) } }

            SliderSetting(
                title = "Shortest sentence",
                subtitle = "A pause only ends a sentence once it is at least this long, so short fragments are not translated on their own. Default: off",
                value = settings.minSentenceLength,
                valueText = if (settings.minSentenceLength < 0.05f) "Off" else "${format1(settings.minSentenceLength)} s",
                range = 0f..3f,
                steps = 29,
                tag = "slider_min_sentence"
            ) { scope.launch { repo.updateMinSentenceLength(it) } }

            ChoiceSetting(
                title = "One-character lines",
                subtitle = "When a sentence ends after one character: join it to the start of the next sentence so the meaning stays whole, drop it as noise, or keep it as its own line. Default: join",
                options = listOf(0 to "Join next", 1 to "Drop", 2 to "Keep"),
                selected = settings.singleCharacterMode,
                tagPrefix = "chip_single_char_"
            ) { scope.launch { repo.updateSingleCharacterMode(it) } }

            SwitchSetting(
                title = "Translate whole sentences",
                subtitle = "Hold the pieces of a sentence that is split by a short pause and translate it once it is finished. Better translations, slightly later. Default: on",
                checked = settings.isSentenceJoiningEnabled,
                tag = "toggle_sentence_joining"
            ) { scope.launch { repo.updateSentenceJoining(it) } }

            SwitchSetting(
                title = "Filter music and noise",
                subtitle = "Drop recognized text that looks like music or noise: filler sounds, repeated syllables, and stray characters in another script (for example Chinese characters during English speech). Default: on",
                checked = settings.isNoiseTextFilterEnabled,
                tag = "toggle_noise_text_filter"
            ) { scope.launch { repo.updateNoiseTextFilter(it) } }

            SliderSetting(
                title = "Longest sentence",
                subtitle = "Non-stop speech is split after this long. Default: ${defaults.maxUtteranceWindow.toInt()} s",
                value = settings.maxUtteranceWindow,
                valueText = "${settings.maxUtteranceWindow.toInt()} s",
                range = 5f..25f,
                steps = 19,
                tag = "slider_max_utterance"
            ) { scope.launch { repo.updateMaxUtteranceWindow(it) } }

            SliderSetting(
                title = "Auto-detect certainty",
                subtitle = "How sure language detection must be before switching language. Default: ${(defaults.languageIdConfidence * 100).toInt()}%",
                value = settings.languageIdConfidence,
                valueText = "${(settings.languageIdConfidence * 100).toInt()}%",
                range = 0.3f..0.9f,
                steps = 11,
                tag = "slider_lang_confidence"
            ) { scope.launch { repo.updateLanguageIdConfidence(it) } }

            SwitchSetting(
                title = "Recover from stalls",
                subtitle = "Restart recognition if audio flows but no new words appear for 8 s. Default: on",
                checked = settings.isAntiFreezeWatchdogEnabled,
                tag = "toggle_anti_freeze"
            ) { scope.launch { repo.updateAntiFreezeWatchdog(it) } }

            SwitchSetting(
                title = "Save recognizer audio",
                subtitle = "Records up to 2 minutes of the audio the speech engine receives, for troubleshooting. Default: off",
                checked = settings.isDebugAudioRecordingEnabled,
                tag = "toggle_debug_record"
            ) { scope.launch { repo.updateDebugAudioRecording(it) } }
        }

        SettingsGroup(icon = Icons.Default.TextFields, title = "Subtitle rendering") {
            SwitchSetting(
                title = "Text shadow",
                subtitle = "Keeps text readable over bright video. Default: on",
                checked = settings.isTextOutlineShadowEnabled,
                tag = "toggle_text_shadow"
            ) { scope.launch { repo.updateTextOutlineShadow(it) } }

            SliderSetting(
                title = "Shadow softness",
                value = settings.textShadowRadius,
                valueText = format1(settings.textShadowRadius),
                range = 0f..10f,
                steps = 9,
                tag = "slider_shadow_radius"
            ) { scope.launch { repo.updateTextShadowRadius(it) } }
        }

        ResetButton(text = "Reset developer settings", tag = "reset_developer_settings_button") {
            scope.launch { repo.resetDeveloperDefaults() }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

private val LINGER_CHOICES = listOf(2f, 4f, 7f, 0f)

private fun format1(value: Float) = String.format(Locale.US, "%.1f", value)

@Composable
private fun SettingsGroup(
    icon: ImageVector,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SettingTitle(title: String, valueText: String?, subtitle: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (valueText != null) {
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
    if (subtitle != null) {
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SliderSetting(
    title: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    tag: String,
    subtitle: String? = null,
    steps: Int = 0,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        SettingTitle(title, valueText, subtitle)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(tag)
        )
    }
}

@Composable
private fun <T> ChoiceSetting(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    tagPrefix: String,
    subtitle: String? = null,
    onSelect: (T) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        SettingTitle(title, null, subtitle)
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            options.forEach { (value, label) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(label) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("$tagPrefix$value")
                )
            }
        }
    }
}

@Composable
private fun SwitchSetting(
    title: String,
    subtitle: String?,
    checked: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            SettingTitle(title, null, subtitle)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
private fun ResetButton(text: String, tag: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag(tag)
    ) {
        Icon(Icons.Default.Restore, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text)
    }
}
