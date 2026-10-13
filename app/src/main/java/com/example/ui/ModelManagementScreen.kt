package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Storage
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.asr.AsrModelConfig
import com.example.asr.ModelDownloadState
import com.example.translate.SupportedLanguage

@Composable
fun ModelManagementScreen(
    viewModel: AudioTranslatorViewModel,
    modifier: Modifier = Modifier
) {
    val downloadState by viewModel.asrDownloadState.collectAsStateWithLifecycle()
    val activeDownloadId by viewModel.activeAsrDownloadId.collectAsStateWithLifecycle()
    val selectedAsrModel by viewModel.selectedAsrModel.collectAsStateWithLifecycle()
    val readyModelIds by viewModel.readyModelIds.collectAsStateWithLifecycle()
    val availableStorageMb by viewModel.availableStorageMb.collectAsStateWithLifecycle()

    val packLanguages = viewModel.targetAvailableLanguages
    val downloadedLanguages by viewModel.downloadedLanguages.collectAsStateWithLifecycle()
    val downloadingPacks by viewModel.downloadingPacks.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("model_management_screen"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
            StorageSummary(freeMb = availableStorageMb)
        }

        item {
            SectionHeader(
                icon = Icons.Default.Mic,
                title = "Speech recognition",
                subtitle = "Pick the language you will listen to. One model is used at a time."
            )
        }

        items(viewModel.availableAsrModels, key = { it.id }) { model ->
            val isDownloadingThis = activeDownloadId == model.id
            SpeechModelCard(
                model = model,
                isSelected = model.id == selectedAsrModel.id,
                isInstalled = model.id in readyModelIds,
                downloadState = if (isDownloadingThis || model.id == selectedAsrModel.id) downloadState else null,
                isDownloadingThis = isDownloadingThis,
                anotherDownloadRunning = activeDownloadId != null && !isDownloadingThis,
                onSelect = { viewModel.selectAsrModel(model) },
                onDownload = {
                    viewModel.selectAsrModel(model)
                    viewModel.downloadAsrModel(model)
                },
                onDelete = { viewModel.deleteAsrModel(model) }
            )
        }

        item {
            SectionHeader(
                icon = Icons.Default.Translate,
                title = "Translation packs",
                subtitle = "Google ML Kit, about 30 MB each. Download the language you listen to and the one you read."
            )
        }

        items(packLanguages, key = { it.code }) { lang ->
            LanguagePackRow(
                language = lang,
                isDownloaded = lang.code in downloadedLanguages,
                downloadProgress = downloadingPacks[lang.code],
                onDownload = { viewModel.downloadSingleLanguagePack(lang.code) },
                onDelete = { viewModel.deleteLanguageModel(lang.code) }
            )
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun StorageSummary(freeMb: Long) {
    val low = freeMb in 1 until 500
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("storage_overview_card")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Storage,
                contentDescription = null,
                tint = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (freeMb > 0) "${formatMb(freeMb)} free on this device" else "Checking free space…",
                style = MaterialTheme.typography.bodyMedium,
                color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun SectionHeader(icon: ImageVector, title: String, subtitle: String) {
    Column(modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, start = 28.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeechModelCard(
    model: AsrModelConfig,
    isSelected: Boolean,
    isInstalled: Boolean,
    downloadState: ModelDownloadState?,
    isDownloadingThis: Boolean,
    anotherDownloadRunning: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    val isActive = isSelected && isInstalled
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("asr_card_${model.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = if (isActive) 1.5.dp else 1.dp,
            color = if (isActive) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LanguageBadge(text = if (model.isMultilingual) "ALL" else model.languageCode.uppercase())
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = model.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (isActive) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Pill(text = "In use", container = MaterialTheme.colorScheme.primary, content = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                    Text(
                        text = model.languages.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = model.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                InfoChip("Download ${formatMb(model.totalSizeBytes / (1024L * 1024L))}")
                InfoChip("~${model.memoryMb} MB memory")
                if (model.hasPunctuation) InfoChip("Punctuation")
                if (model.license != "Apache-2.0") InfoChip(model.license)
            }

            // Status and actions
            when {
                isDownloadingThis && downloadState is ModelDownloadState.Downloading -> {
                    ProgressBlock(
                        progress = downloadState.progress,
                        left = "${downloadState.bytesDownloaded / (1024 * 1024)} of ${downloadState.totalBytes / (1024 * 1024)} MB",
                        right = "${(downloadState.progress * 100).toInt()}%"
                    )
                }
                isDownloadingThis && downloadState is ModelDownloadState.Extracting -> {
                    ProgressBlock(progress = null, left = "Unpacking…", right = "")
                }
                isInstalled -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Installed",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        if (!isSelected) {
                            OutlinedButton(
                                onClick = onSelect,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.testTag("select_asr_${model.id}")
                            ) { Text("Use this model") }
                        }
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.testTag("delete_asr_${model.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Delete ${model.name} model",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                else -> {
                    Button(
                        onClick = onDownload,
                        enabled = !anotherDownloadRunning,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .testTag("download_asr_${model.id}"),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (anotherDownloadRunning) "Another download is running" else "Download")
                    }
                }
            }

            if (isSelected && !isDownloadingThis && downloadState is ModelDownloadState.Error) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = downloadState.errorMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguagePackRow(
    language: SupportedLanguage,
    isDownloaded: Boolean,
    downloadProgress: Int?,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("lang_pack_${language.code}")
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = language.flagEmoji, fontSize = 22.sp)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = language.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                when {
                    downloadProgress != null -> CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp
                    )
                    isDownloaded -> {
                        Text(
                            text = "Installed",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.testTag("delete_lang_${language.code}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Delete ${language.displayName} pack",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    else -> OutlinedButton(
                        onClick = onDownload,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("download_lang_${language.code}")
                    ) { Text("Download") }
                }
            }
            if (downloadProgress != null) {
                LinearProgressIndicator(
                    progress = { (downloadProgress / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 2.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

@Composable
private fun LanguageBadge(text: String) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun Pill(text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(50), color = container) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = content,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun InfoChip(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun ProgressBlock(progress: Float?, left: String, right: String) {
    Column(modifier = Modifier.padding(top = 10.dp)) {
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(left, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(right, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun formatMb(mb: Long): String =
    if (mb >= 1024) String.format(java.util.Locale.US, "%.1f GB", mb / 1024f) else "$mb MB"
