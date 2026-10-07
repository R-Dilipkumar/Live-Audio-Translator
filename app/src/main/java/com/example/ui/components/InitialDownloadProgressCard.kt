package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.asr.AsrModelConfig
import com.example.asr.ModelDownloadState
import com.example.translate.SupportedLanguage
import com.example.translate.TranslationPackStatus

@Composable
fun InitialDownloadProgressCard(
    asrState: ModelDownloadState,
    transPackStatus: TranslationPackStatus,
    selectedAsrModel: AsrModelConfig,
    sourceLang: SupportedLanguage,
    targetLang: SupportedLanguage,
    availableStorageMb: Long,
    onStartDownloads: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isAsrDownloading = asrState is ModelDownloadState.Downloading
    val isAsrExtracting = asrState is ModelDownloadState.Extracting
    val isTransDownloading = transPackStatus is TranslationPackStatus.Downloading

    val isFirstRunNeeded = (asrState is ModelDownloadState.NotDownloaded) &&
            (transPackStatus is TranslationPackStatus.NeedsDownload || transPackStatus is TranslationPackStatus.Checking)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("initial_download_container"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 1. DETERMINISTIC PROGRESS BAR FOR ASR MODEL
        AnimatedVisibility(visible = isAsrDownloading || isAsrExtracting) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("asr_deterministic_progress_card"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                ),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isAsrExtracting) "Verifying Speech Model" else "Downloading Speech Model",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        if (isAsrDownloading) {
                            val state = asrState as ModelDownloadState.Downloading
                            val percent = (state.progress * 100).toInt().coerceIn(0, 100)
                            Text(
                                text = "$percent%",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (isAsrDownloading) {
                        val state = asrState as ModelDownloadState.Downloading
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .testTag("asr_linear_progress_bar"),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val dlMb = state.bytesDownloaded / (1024 * 1024)
                            val totMb = state.totalBytes / (1024 * 1024)
                            Text(
                                text = "$dlMb MB / $totMb MB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                            Text(
                                text = "Auto-cleaning on disconnect",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                fontSize = 10.sp
                            )
                        }
                    } else if (isAsrExtracting) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // 2. DETERMINISTIC PROGRESS BAR FOR TRANSLATION PACK
        AnimatedVisibility(visible = isTransDownloading) {
            val state = transPackStatus as? TranslationPackStatus.Downloading
            val progressPercent = state?.progressPercent ?: 0
            val progressFloat = (progressPercent / 100f).coerceIn(0f, 1f)

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("trans_deterministic_progress_card"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f)
                ),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Translate,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Downloading ${sourceLang.displayName} → ${targetLang.displayName}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }

                        Text(
                            text = "$progressPercent%",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    LinearProgressIndicator(
                        progress = { progressFloat },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .testTag("trans_linear_progress_bar"),
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Downloading offline translation pack (~30 MB)...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }

        // 3. FIRST-RUN ONBOARDING SETUP CARD
        AnimatedVisibility(visible = isFirstRunNeeded && !isAsrDownloading && !isTransDownloading) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("first_run_setup_card"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                ),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "First-Run Setup: Offline Models Required",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "To translate audio 100% offline without internet, download the speech model (~42 MB) and translation pack (~30 MB).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Storage,
                                contentDescription = null,
                                tint = if (availableStorageMb >= 150L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Free space: $availableStorageMb MB (min 150 MB)",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (availableStorageMb >= 150L) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Button(
                            onClick = onStartDownloads,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("download_all_models_button"),
                            contentPadding = ButtonDefaults.TextButtonContentPadding
                        ) {
                            Text("Download Models", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}
