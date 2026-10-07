package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.asr.AsrModelConfig
import com.example.asr.ModelDownloadState
import com.example.translate.SupportedLanguage
import com.example.translate.TranslationPackStatus

@Composable
fun ModelStatusCard(
    asrModel: AsrModelConfig,
    asrState: ModelDownloadState,
    sourceLang: SupportedLanguage,
    targetLang: SupportedLanguage,
    transPackStatus: TranslationPackStatus,
    onDownloadAsr: () -> Unit,
    onDeleteAsr: () -> Unit,
    onDownloadTransPack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("model_status_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Offline AI Model Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 1. ASR Recognizer Model Item
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = "ASR Model",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "ASR Engine (${asrModel.languageCode.uppercase()})",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        val statusText = when (asrState) {
                            is ModelDownloadState.Ready -> "Model Ready (Offline)"
                            is ModelDownloadState.Downloading -> "Downloading ${(asrState.progress * 100).toInt()}%"
                            is ModelDownloadState.Extracting -> asrState.currentFile
                            is ModelDownloadState.Error -> "Error: ${asrState.errorMsg}"
                            ModelDownloadState.NotDownloaded -> "Not Downloaded (~${asrModel.totalSizeBytes / (1024 * 1024)} MB)"
                        }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = when (asrState) {
                                is ModelDownloadState.Ready -> MaterialTheme.colorScheme.primary
                                is ModelDownloadState.Error -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                // Action button for ASR
                when (asrState) {
                    is ModelDownloadState.Ready -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Ready",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = onDeleteAsr,
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("delete_asr_model_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Delete Model",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    is ModelDownloadState.Downloading -> {
                        CircularProgressIndicator(
                            progress = { asrState.progress },
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp
                        )
                    }

                    is ModelDownloadState.Extracting -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp
                        )
                    }

                    else -> {
                        Button(
                            onClick = onDownloadAsr,
                            modifier = Modifier.testTag("download_asr_model_button"),
                            contentPadding = ButtonDefaults.TextButtonContentPadding
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Download", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // Progress bar if ASR downloading
            AnimatedVisibility(visible = asrState is ModelDownloadState.Downloading) {
                if (asrState is ModelDownloadState.Downloading) {
                    Column(modifier = Modifier.padding(top = 6.dp)) {
                        LinearProgressIndicator(
                            progress = { asrState.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${asrState.bytesDownloaded / (1024 * 1024)} MB / ${asrState.totalBytes / (1024 * 1024)} MB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${(asrState.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 2. Translation Pack Item
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Translate,
                        contentDescription = "Translation Model",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "${sourceLang.displayName} → ${targetLang.displayName}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        val transStatusText = when (transPackStatus) {
                            TranslationPackStatus.Ready -> "Pack Ready (Offline)"
                            is TranslationPackStatus.Downloading -> "Downloading translation pack (${transPackStatus.progressPercent}%)..."
                            is TranslationPackStatus.NeedsDownload -> "Pack needed for offline use (~30 MB)"
                            is TranslationPackStatus.Error -> "Failed to download"
                            TranslationPackStatus.Checking -> "Checking models..."
                        }
                        Text(
                            text = transStatusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = when (transPackStatus) {
                                TranslationPackStatus.Ready -> MaterialTheme.colorScheme.secondary
                                is TranslationPackStatus.Error -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                when (transPackStatus) {
                    TranslationPackStatus.Ready -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Ready",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    is TranslationPackStatus.Downloading -> {
                        CircularProgressIndicator(
                            progress = { (transPackStatus.progressPercent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp
                        )
                    }

                    TranslationPackStatus.Checking -> {
                        Icon(
                            imageVector = Icons.Default.HourglassTop,
                            contentDescription = "Checking",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    else -> {
                        OutlinedButton(
                            onClick = onDownloadTransPack,
                            modifier = Modifier.testTag("download_trans_pack_button"),
                            contentPadding = ButtonDefaults.TextButtonContentPadding
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Download", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // Progress bar if Translation Pack downloading
            AnimatedVisibility(visible = transPackStatus is TranslationPackStatus.Downloading) {
                if (transPackStatus is TranslationPackStatus.Downloading) {
                    Column(modifier = Modifier.padding(top = 6.dp)) {
                        val progress = (transPackStatus.progressPercent / 100f).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Downloading language pack (~30 MB)...",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${transPackStatus.progressPercent}%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }
        }
    }
}
