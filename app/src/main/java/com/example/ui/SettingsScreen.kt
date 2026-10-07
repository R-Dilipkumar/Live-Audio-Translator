package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.AppSettings
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: AudioTranslatorViewModel,
    modifier: Modifier = Modifier
) {
    val settings by viewModel.appSettings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("settings_screen_container")
    ) {
        // Section 1: Audio Pre-Processing & Voice Isolation
        SettingsSectionHeader(
            icon = Icons.Default.GraphicEq,
            title = "Audio Pre-Processing & Voice Isolation"
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Voice Isolation Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "IIR Biquad Voice Isolation",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Applies 150Hz high-pass filter to strip BGM rumble and 3500Hz low-pass filter to remove sound effects before speech recognition.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.isVoiceIsolationEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { viewModel.settingsRepository.updateVoiceIsolation(enabled) }
                        },
                        modifier = Modifier.testTag("toggle_voice_isolation")
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Noise Gate Slider
                Text(
                    text = "Noise Gate Threshold: ${(settings.noiseGateThreshold * 1000).toInt()} mRMS",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Zeros out incoming audio chunks below this energy level so quiet background music or ambient noise does not trigger false speech recognition.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = settings.noiseGateThreshold,
                    onValueChange = { value ->
                        scope.launch { viewModel.settingsRepository.updateNoiseGateThreshold(value) }
                    },
                    valueRange = 0.000f..0.050f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("slider_noise_gate")
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section 2: Transcription & Translation Parameters
        SettingsSectionHeader(
            icon = Icons.Default.RecordVoiceOver,
            title = "Transcription & Translation Parameters"
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Silence Endpointing Delay Slider
                Text(
                    text = "Silence Endpointing Delay: ${"%.1f".format(settings.silenceEndpointDelay)}s",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Controls how long a speaker must pause before finalizing a sentence clause. Higher values (e.g. 1.1s - 1.5s) prevent cutting off Japanese sentence-ending verbs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = settings.silenceEndpointDelay,
                    onValueChange = { value ->
                        scope.launch { viewModel.settingsRepository.updateSilenceEndpointDelay(value) }
                    },
                    valueRange = 0.5f..2.5f,
                    steps = 19,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("slider_endpoint_delay")
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Max Utterance Window Slider
                Text(
                    text = "Max Utterance Window: ${settings.maxUtteranceWindow.toInt()}s",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "The maximum continuous speech duration allowed before forcing an utterance split, ensuring prompt translation for non-stop dialogue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = settings.maxUtteranceWindow,
                    onValueChange = { value ->
                        scope.launch { viewModel.settingsRepository.updateMaxUtteranceWindow(value) }
                    },
                    valueRange = 5.0f..25.0f,
                    steps = 19,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("slider_max_utterance")
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Language ID Confidence Slider
                Text(
                    text = "Language ID Confidence: ${(settings.languageIdConfidence * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Minimum confidence threshold required by ML Kit before switching source languages in Auto-Detect mode, avoiding jittery false language hops.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = settings.languageIdConfidence,
                    onValueChange = { value ->
                        scope.launch { viewModel.settingsRepository.updateLanguageIdConfidence(value) }
                    },
                    valueRange = 0.30f..0.90f,
                    steps = 11,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("slider_lang_confidence")
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Anti-Freeze Watchdog Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Anti-Freeze Stream Watchdog",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Monitors speech recognition and automatically resets the decoder stream if no output occurs after 6 seconds of continuous audio, preventing frozen subtitles.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.isAntiFreezeWatchdogEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { viewModel.settingsRepository.updateAntiFreezeWatchdog(enabled) }
                        },
                        modifier = Modifier.testTag("toggle_anti_freeze")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section 3: Subtitle Timing & Display Behavior
        SettingsSectionHeader(
            icon = Icons.Default.Subtitles,
            title = "Subtitle Timing & Display Behavior"
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Subtitle Linger Time
                Text(
                    text = "Subtitle Linger Time: ${if (settings.subtitleLingerTimeSeconds <= 0f) "Keep until next speech" else "${"%.1f".format(settings.subtitleLingerTimeSeconds)}s"}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Controls how long translated subtitles stay visible on screen before fading away when speech stops, giving you comfortable reading time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = settings.subtitleLingerTimeSeconds,
                    onValueChange = { value ->
                        scope.launch { viewModel.settingsRepository.updateSubtitleLingerTime(value) }
                    },
                    valueRange = 1.0f..10.0f,
                    steps = 17,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("slider_linger_time")
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Background Window Opacity Slider
                Text(
                    text = "Background Window Opacity: ${(settings.overlayOpacity * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Adjusts transparency of the floating overlay window so video or anime underneath remains visible without obscuring text readability.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = settings.overlayOpacity,
                    onValueChange = { value ->
                        scope.launch { viewModel.settingsRepository.updateOverlayOpacity(value) }
                    },
                    valueRange = 0.20f..1.0f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("slider_overlay_opacity")
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Font Size Slider
                Text(
                    text = "Subtitle Font Size: ${settings.fontSizeSp.toInt()} sp",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Changes the reading size of translated text in the floating overlay and in-app preview.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = settings.fontSizeSp,
                    onValueChange = { value ->
                        scope.launch { viewModel.settingsRepository.updateFontSize(value) }
                    },
                    valueRange = 12.0f..24.0f,
                    steps = 11,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("slider_font_size")
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Max Display Lines Control
                Text(
                    text = "Max Display Lines: ${settings.maxDisplayLines} Line${if (settings.maxDisplayLines > 1) "s" else ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Limits the maximum number of simultaneous subtitle lines visible on screen. Older lines roll off automatically as new speech arrives.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(1, 2, 3).forEach { lines ->
                        val isSelected = settings.maxDisplayLines == lines
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                scope.launch { viewModel.settingsRepository.updateMaxDisplayLines(lines) }
                            },
                            label = { Text("$lines Line${if (lines > 1) "s" else ""}") },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chip_max_lines_$lines")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Show Original Speech Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Show Original Foreign Text",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Displays original recognized Japanese/foreign clauses above the English translation in the floating window.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.showOriginalSpeech,
                        onCheckedChange = { show ->
                            scope.launch { viewModel.settingsRepository.updateShowOriginalSpeech(show) }
                        },
                        modifier = Modifier.testTag("toggle_show_original")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Reset to Defaults Button
        OutlinedButton(
            onClick = {
                scope.launch { viewModel.settingsRepository.resetToDefaults() }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("reset_settings_button"),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(imageVector = Icons.Default.Restore, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Reset All Settings to Defaults")
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun SettingsSectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
