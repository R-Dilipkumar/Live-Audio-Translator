package com.example.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.asr.SpeechValidator
import com.example.data.TranscriptEntity
import com.example.ui.components.AudioVisualizer
import com.example.ui.components.HistoryBottomSheet
import com.example.ui.components.LanguageSelectorRow
import kotlinx.coroutines.flow.StateFlow

private const val TAB_TRANSLATE = 0
private const val TAB_MODELS = 1
private const val TAB_SETTINGS = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: AudioTranslatorViewModel,
    onRequestCapturePermission: (isMic: Boolean) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val history by viewModel.transcriptHistory.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(TAB_TRANSLATE) }
    var showHistorySheet by remember { mutableStateOf(false) }

    // On cold launch, load the selected speech model if it is installed. Nothing is downloaded
    // automatically; if no model is installed, the Translate tab points to Models.
    LaunchedEffect(Unit) {
        viewModel.initializeSpeechRecognizer()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("LiveSub", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            text = when (selectedTab) {
                                TAB_TRANSLATE -> "Live subtitles for any app"
                                TAB_MODELS -> "Speech models and language packs"
                                else -> "How subtitles look and behave"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (selectedTab == TAB_TRANSLATE) {
                        IconButton(onClick = { showHistorySheet = true }, modifier = Modifier.testTag("history_button")) {
                            Icon(Icons.Default.History, contentDescription = "History")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            NavigationBar(modifier = Modifier.testTag("main_navigation_bar")) {
                NavTab(selectedTab == TAB_TRANSLATE, Icons.Default.Translate, "Translate", "tab_translate") { selectedTab = TAB_TRANSLATE }
                NavTab(selectedTab == TAB_MODELS, Icons.Default.Download, "Models", "tab_manage_models") { selectedTab = TAB_MODELS }
                NavTab(selectedTab == TAB_SETTINGS, Icons.Outlined.Settings, "Settings", "tab_settings") { selectedTab = TAB_SETTINGS }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ErrorBanner(message = errorMessage, onDismiss = { viewModel.dismissError() })

            when (selectedTab) {
                TAB_TRANSLATE -> TranslateTab(
                    viewModel = viewModel,
                    history = history,
                    onRequestCapturePermission = onRequestCapturePermission,
                    onRequestOverlayPermission = onRequestOverlayPermission,
                    onOpenModels = { selectedTab = TAB_MODELS }
                )
                TAB_MODELS -> ModelManagementScreen(viewModel = viewModel)
                else -> SettingsScreen(viewModel = viewModel, onOpenModels = { selectedTab = TAB_MODELS })
            }
        }
    }

    if (showHistorySheet) {
        HistoryBottomSheet(
            transcripts = history,
            onDismiss = { showHistorySheet = false },
            onClearAll = { viewModel.clearHistory() },
            onDeleteItem = { id -> viewModel.deleteHistoryItem(id) }
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavTab(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    tag: String,
    onClick: () -> Unit
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
        modifier = Modifier.testTag(tag)
    )
}

@Composable
private fun ErrorBanner(message: String?, onDismiss: () -> Unit) {
    AnimatedVisibility(visible = message != null) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .testTag("error_banner")
        ) {
            Row(modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = message.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}

@Composable
private fun TranslateTab(
    viewModel: AudioTranslatorViewModel,
    history: List<TranscriptEntity>,
    onRequestCapturePermission: (isMic: Boolean) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onOpenModels: () -> Unit
) {
    val isCapturing by viewModel.isCapturing.collectAsStateWithLifecycle()
    val sourceLang by viewModel.sourceLanguage.collectAsStateWithLifecycle()
    val targetLang by viewModel.targetLanguage.collectAsStateWithLifecycle()
    val translatedSpeech by viewModel.liveDraftTranslation.collectAsStateWithLifecycle()
    val partialSpeech by viewModel.livePartialSpeech.collectAsStateWithLifecycle()
    val isMicMode by viewModel.isMicMode.collectAsStateWithLifecycle()
    val lastDetectedLanguage by viewModel.lastDetectedLanguage.collectAsStateWithLifecycle()
    val isOverlayActive by viewModel.isFloatingOverlayActive.collectAsStateWithLifecycle()
    val isModelInstalled by viewModel.isSelectedAsrModelInstalled.collectAsStateWithLifecycle()
    val settings by viewModel.appSettings.collectAsStateWithLifecycle()
    val speechModel by viewModel.selectedAsrModel.collectAsStateWithLifecycle()
    val latencyMode by viewModel.recognitionMode.collectAsStateWithLifecycle()
    val packWarning by viewModel.translationPackWarning.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        if (!isModelInstalled) {
            SetupCard(onOpenModels = onOpenModels)
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Speech is recognized but can't be translated: say why, instead of silently showing nothing
        packWarning?.let { warning ->
            PackMissingCard(message = warning, onOpenModels = onOpenModels)
            Spacer(modifier = Modifier.height(10.dp))
        }

        LanguageSelectorRow(
            sourceLang = sourceLang,
            targetLang = targetLang,
            availableLanguages = viewModel.availableLanguages,
            targetLanguages = viewModel.targetAvailableLanguages,
            detectedLanguageHint = lastDetectedLanguage,
            onSourceSelected = { viewModel.setSourceLanguage(it) },
            onTargetSelected = { viewModel.setTargetLanguage(it) },
            onSwap = { viewModel.swapLanguages() }
        )

        Spacer(modifier = Modifier.height(8.dp))

        SpeechModelLine(
            modelName = speechModel.name,
            latency = latencyLabel(latencyMode),
            isInstalled = isModelInstalled,
            help = languageHelp(sourceLang, speechModel),
            onChange = onOpenModels
        )

        Spacer(modifier = Modifier.height(10.dp))

        AudioSourceSelector(
            isMicMode = isMicMode,
            enabled = !isCapturing,
            onSelect = { viewModel.toggleMicMode(it) }
        )

        Spacer(modifier = Modifier.height(10.dp))

        LiveFeedCard(
            history = history,
            isCapturing = isCapturing,
            partialSpeech = partialSpeech,
            translatedSpeech = translatedSpeech,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.height(10.dp))

        CaptureControls(
            isCapturing = isCapturing,
            isMicMode = isMicMode,
            isOverlayActive = isOverlayActive,
            opensOverlayOnStart = settings.isAutoOverlayEnabled,
            audioLevel = viewModel.audioLevelDb,
            onStart = { onRequestCapturePermission(isMicMode) },
            onStop = { viewModel.stopCapture() },
            onToggleOverlay = {
                if (isOverlayActive) viewModel.stopFloatingOverlay() else onRequestOverlayPermission()
            }
        )

        Spacer(modifier = Modifier.height(12.dp))
    }
}

/**
 * Which speech model is transcribing, at which latency, and what the spoken-language choice
 * does with it. Tap to change the model.
 */
@Composable
private fun SpeechModelLine(
    modelName: String,
    latency: String,
    isInstalled: Boolean,
    help: LanguageHelp,
    onChange: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onChange)
            .testTag("speech_model_line")
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Speech model · $latency",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = modelName + if (isInstalled) "" else " (not downloaded)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        color = if (isInstalled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    text = "Change",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = help.text,
                style = MaterialTheme.typography.bodySmall,
                color = if (help.isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (help.isWarning) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.padding(top = 4.dp, start = 28.dp)
            )
        }
    }
}

/** A translation pack is missing: subtitles can't appear until it is downloaded. */
@Composable
private fun PackMissingCard(message: String, onOpenModels: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("translation_pack_missing_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Translate, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = "Speech is heard but can't be translated. Download the pack in Models.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            TextButton(onClick = onOpenModels) { Text("Models") }
        }
    }
}

@Composable
private fun SetupCard(onOpenModels: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("asr_model_missing_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Download, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Get set up", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    "Download a speech model for the language you'll listen to. It runs on your phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            TextButton(onClick = onOpenModels) { Text("Models") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioSourceSelector(isMicMode: Boolean, enabled: Boolean, onSelect: (isMic: Boolean) -> Unit) {
    Column {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !isMicMode,
                onClick = { onSelect(false) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                icon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = { Text("Phone audio") },
                modifier = Modifier.testTag("chip_internal_audio")
            )
            SegmentedButton(
                selected = isMicMode,
                onClick = { onSelect(true) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                icon = { Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = { Text("Microphone") },
                modifier = Modifier.testTag("chip_microphone")
            )
        }
        Text(
            text = if (isMicMode) "Listens to people talking around you" else "Listens to videos, games and other apps on this phone",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp)
        )
    }
}

@Composable
private fun LiveFeedCard(
    history: List<TranscriptEntity>,
    isCapturing: Boolean,
    partialSpeech: String,
    translatedSpeech: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    // History is stored newest first; the feed reads top to bottom like a chat
    val feed = remember(history) { history.take(100).asReversed() }
    val showLiveCard = isCapturing && SpeechValidator.isGenuineSpeech(partialSpeech)

    // Keep the newest line in view
    LaunchedEffect(feed.size, partialSpeech, translatedSpeech, showLiveCard) {
        val lastIndex = feed.size - 1 + (if (showLiveCard) 1 else 0)
        if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("live_transcript_history_container"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)) {
                Text("Subtitles", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (isCapturing) ListeningPill()
            }

            if (feed.isEmpty() && !showLiveCard) {
                EmptyFeed(isCapturing = isCapturing)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().testTag("live_history_feed"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items = feed, key = { it.id }) { item ->
                        FeedItem(
                            original = item.originalText,
                            translation = item.translatedText,
                            meta = remember(item.timestamp, item.sourceLanguage, item.targetLanguage) {
                                val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                    .format(java.util.Date(item.timestamp))
                                "$time · ${item.sourceLanguage.uppercase()} → ${item.targetLanguage.uppercase()}"
                            },
                            isLive = false,
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                clipboard?.setPrimaryClip(
                                    android.content.ClipData.newPlainText("Translation", "${item.originalText}\n${item.translatedText}")
                                )
                                android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.testTag("transcript_history_item_${item.id}")
                        )
                    }
                    if (showLiveCard) {
                        item {
                            FeedItem(
                                // showLiveCard already requires partialSpeech to be real speech
                                original = partialSpeech,
                                translation = translatedSpeech.takeIf { SpeechValidator.isGenuineSpeech(it) }.orEmpty(),
                                meta = "Now",
                                isLive = true,
                                onClick = null,
                                modifier = Modifier.testTag("live_streaming_transcript_card")
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedItem(
    original: String,
    translation: String,
    meta: String,
    isLive: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isLive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick) else Modifier)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (translation.isNotBlank()) {
                Text(
                    text = translation,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isLive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                )
            }
            if (original.isNotBlank()) {
                Text(
                    text = original,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = if (isLive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun EmptyFeed(isCapturing: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag("empty_state_container"),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isCapturing) Icons.Default.GraphicEq else Icons.Default.Subtitles,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp)
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = if (isCapturing) "Listening…" else "No subtitles yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isCapturing) "Play a video or start talking. Subtitles appear here and over your other apps."
                else "Press Start, then open any video or game. Tap a line later to copy it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ListeningPill() {
    val transition = rememberInfiniteTransition(label = "listening")
    val dotAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "listening_dot"
    )
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .alpha(dotAlpha)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text("Listening", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun CaptureControls(
    isCapturing: Boolean,
    isMicMode: Boolean,
    isOverlayActive: Boolean,
    opensOverlayOnStart: Boolean,
    audioLevel: StateFlow<Float>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onToggleOverlay: () -> Unit
) {
    Column {
        if (isCapturing) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Text(
                    text = if (isMicMode) "Microphone" else "Phone audio",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(92.dp)
                )
                AudioVisualizer(isCapturing = true, audioDbFlow = audioLevel, modifier = Modifier.weight(1f))
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isCapturing) {
                Button(
                    onClick = onStop,
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .testTag("stop_capture_button")
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Stop", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onStart,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .testTag("start_capture_button")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start subtitles", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            // Floating subtitles on/off
            FilledTonalIconToggleButton(
                checked = isOverlayActive,
                onCheckedChange = { onToggleOverlay() },
                modifier = Modifier
                    .size(56.dp)
                    .testTag("toggle_floating_overlay_button")
            ) {
                Icon(
                    Icons.Default.Subtitles,
                    contentDescription = if (isOverlayActive) "Hide floating subtitles" else "Show floating subtitles"
                )
            }
        }

        if (!isCapturing) {
            Text(
                text = if (opensOverlayOnStart) "Subtitles float over your other apps once you start"
                else "Use the subtitles button to float them over other apps",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )
        }
    }
}
