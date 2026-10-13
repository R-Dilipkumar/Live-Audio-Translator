package com.example.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.LinearProgressIndicator
import com.example.asr.DownloadState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.asr.SpeechValidator
import com.example.ui.components.AudioVisualizer
import com.example.ui.components.DraggableSubtitleBox
import com.example.ui.components.HistoryBottomSheet
import com.example.ui.components.LanguageSelectorRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: AudioTranslatorViewModel,
    onRequestCapturePermission: (isMic: Boolean) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isCapturing by viewModel.isCapturing.collectAsStateWithLifecycle()
    val audioDb by viewModel.audioLevelDb.collectAsStateWithLifecycle()
    val sourceLang by viewModel.sourceLanguage.collectAsStateWithLifecycle()
    val targetLang by viewModel.targetLanguage.collectAsStateWithLifecycle()
    val availableLanguages = viewModel.availableLanguages
    val targetLanguages = viewModel.targetAvailableLanguages
    val originalSpeech by viewModel.currentOriginalSpeech.collectAsStateWithLifecycle()
    val translatedSpeech by viewModel.currentTranslatedSpeech.collectAsStateWithLifecycle()
    val partialSpeech by viewModel.livePartialSpeech.collectAsStateWithLifecycle()
    val isMicMode by viewModel.isMicMode.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val history by viewModel.transcriptHistory.collectAsStateWithLifecycle()
    val lastDetectedLanguage by viewModel.lastDetectedLanguage.collectAsStateWithLifecycle()

    val isOverlayActive by viewModel.isFloatingOverlayActive.collectAsStateWithLifecycle()
    val overlaySettings by viewModel.floatingSettings.collectAsStateWithLifecycle()
    val isDrmSilenceDetected by viewModel.isDrmSilenceDetected.collectAsStateWithLifecycle()
    val setupDownloadState by viewModel.setupDownloadState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Translate, 1: Manage Models

    val context = LocalContext.current

    var showHistorySheet by remember { mutableStateOf(false) }

    // Guarded First-Launch Model Setup Flow:
    // On cold launch, load the selected model if installed; if no model is installed at all,
    // download the selected one (into its own folder) and show the onboarding dialog.
    // The ViewModel loads the recognizer once the download finishes.
    LaunchedEffect(Unit) {
        viewModel.prepareAsrOnLaunch()
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "LiveSub",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = when (selectedTab) {
                                    0 -> "Floating Audio Translator"
                                    1 -> "Offline Engine Manager"
                                    else -> "Settings & DSP Tuning"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                actions = {
                    if (selectedTab == 0) {
                        IconButton(
                            onClick = { viewModel.clearCurrentDisplay() },
                            modifier = Modifier.testTag("clear_transcript_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteSweep,
                                contentDescription = "Clear display"
                            )
                        }
                        IconButton(
                            onClick = { showHistorySheet = true },
                            modifier = Modifier.testTag("history_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = "Transcript History"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier.testTag("main_navigation_bar"),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Translate,
                            contentDescription = "Translate Tab"
                        )
                    },
                    label = {
                        Text(
                            text = "Translate",
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    modifier = Modifier.testTag("tab_translate")
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.SettingsSuggest,
                            contentDescription = "Manage Models Tab"
                        )
                    },
                    label = {
                        Text(
                            text = "Manage Models",
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    modifier = Modifier.testTag("tab_manage_models")
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = "Settings Tab"
                        )
                    },
                    label = {
                        Text(
                            text = "Settings",
                            fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    modifier = Modifier.testTag("tab_settings")
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Error Banner (Synced with Fallback Notification)
            AnimatedVisibility(visible = errorMessage != null) {
                errorMessage?.let { error ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                            .testTag("error_banner"),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { viewModel.dismissError() },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss",
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            }

            // Tab Content Switcher
            if (selectedTab == 0) {
                // TAB 1: TRANSLATE SCREEN
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                ) {
                    // Compact Controls Row: Audio Mode Filter Chips + Floating Overlay Button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = !isMicMode,
                                onClick = { viewModel.toggleMicMode(false) },
                                label = { Text("Internal", style = MaterialTheme.typography.labelSmall) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.VolumeUp,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                },
                                enabled = !isCapturing,
                                modifier = Modifier.testTag("chip_internal_audio")
                            )

                            FilterChip(
                                selected = isMicMode,
                                onClick = { viewModel.toggleMicMode(true) },
                                label = { Text("Mic", style = MaterialTheme.typography.labelSmall) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                },
                                enabled = !isCapturing,
                                modifier = Modifier.testTag("chip_microphone")
                            )
                        }

                        // Dedicated Floating Overlay Toggle Button with Active Indicator Badge
                        Button(
                            onClick = {
                                if (isOverlayActive) {
                                    viewModel.stopFloatingOverlay()
                                } else {
                                    onRequestOverlayPermission()
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = if (isOverlayActive) {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            } else {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .height(34.dp)
                                .testTag("toggle_floating_overlay_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Subtitles,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isOverlayActive) "Overlay Active" else "Launch Overlay",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            if (isOverlayActive) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(RoundedCornerShape(3.5.dp))
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                        }
                    }

                    // Language Selector Row with Auto-Detect option at the top
                    LanguageSelectorRow(
                        sourceLang = sourceLang,
                        targetLang = targetLang,
                        availableLanguages = availableLanguages,
                        targetLanguages = targetLanguages,
                        detectedLanguageHint = lastDetectedLanguage,
                        onSourceSelected = { viewModel.setSourceLanguage(it) },
                        onTargetSelected = { viewModel.setTargetLanguage(it) },
                        onSwap = { viewModel.swapLanguages() }
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Audio Visualizer VU Meter
                    AudioVisualizer(
                        isCapturing = isCapturing,
                        audioDb = audioDb
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Live Transcription & Translation History Section
                    // Replaces in-app floating box with a live-updating, auto-scrolling conversation feed
                    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

                    // Auto-scroll to newest item as history grows
                    LaunchedEffect(history.size, originalSpeech, partialSpeech) {
                        if (history.isNotEmpty()) {
                            listState.animateScrollToItem(history.size - 1)
                        }
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .testTag("live_transcript_history_container"),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        ),
                        border = CardDefaults.outlinedCardBorder()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            // Section Header with item count and live indicator
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.History,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Live Transcription & Translation",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                if (isCapturing) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .clip(RoundedCornerShape(3.dp))
                                                    .background(MaterialTheme.colorScheme.primary)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "LISTENING",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                }
                            }

                            val hasActiveSpeech = isCapturing && (SpeechValidator.isGenuineSpeech(partialSpeech) || SpeechValidator.isGenuineSpeech(originalSpeech))

                            if (history.isEmpty() && !hasActiveSpeech) {
                                // Clean Empty State UI:
                                // If no speech has been captured yet, show a clean, centered Compose empty-state placeholder
                                // rather than inserting synthetic status items into the list.
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(24.dp)
                                        .testTag("empty_state_container"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(24.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                            modifier = Modifier.size(64.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.Hearing,
                                                    contentDescription = "Waiting for speech",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(32.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(14.dp))
                                        Text(
                                            text = "Waiting for speech... Speak into mic or play audio",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = if (isCapturing) "Listening for incoming dialogue..." else "Press 'Start Subtitle Translation' below to begin",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            } else {
                                androidx.compose.foundation.lazy.LazyColumn(
                                    state = listState,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .testTag("live_history_feed"),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(
                                        items = history,
                                        key = { it.id }
                                    ) { item ->
                                        val timeStr = remember(item.timestamp) {
                                            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                                .format(java.util.Date(item.timestamp))
                                        }

                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable {
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                                    val clip = android.content.ClipData.newPlainText(
                                                        "Translation",
                                                        "${item.originalText}\n${item.translatedText}"
                                                    )
                                                    clipboard?.setPrimaryClip(clip)
                                                    android.widget.Toast.makeText(context, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                                .testTag("transcript_history_item_${item.id}"),
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surface
                                            ),
                                            shape = RoundedCornerShape(12.dp),
                                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(10.dp)
                                            ) {
                                                // Header: Language badge & Timestamp
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Surface(
                                                        shape = RoundedCornerShape(6.dp),
                                                        color = MaterialTheme.colorScheme.secondaryContainer
                                                    ) {
                                                        Text(
                                                            text = "${item.sourceLanguage.uppercase()} → ${item.targetLanguage.uppercase()}",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }

                                                    Text(
                                                        text = timeStr,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        fontSize = 10.sp
                                                    )
                                                }

                                                Spacer(modifier = Modifier.height(4.dp))

                                                // 1. Original Transcription
                                                Text(
                                                    text = item.originalText,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                                )

                                                Spacer(modifier = Modifier.height(2.dp))

                                                // 2. Translated Text
                                                Text(
                                                    text = item.translatedText,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }

                                    // Currently streaming live speech card (partial & latest)
                                    val isLiveSpeechActive = isCapturing && (SpeechValidator.isGenuineSpeech(partialSpeech) || SpeechValidator.isGenuineSpeech(originalSpeech))
                                    if (isLiveSpeechActive) {
                                        item {
                                            Card(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .testTag("live_streaming_transcript_card"),
                                                colors = CardDefaults.cardColors(
                                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                                ),
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Column(modifier = Modifier.padding(10.dp)) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.padding(bottom = 2.dp)
                                                    ) {
                                                        Text(
                                                            text = "Live Stream",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = "Transcribing…",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            fontSize = 10.sp
                                                        )
                                                    }

                                                    val liveOriginal = if (SpeechValidator.isGenuineSpeech(partialSpeech)) {
                                                        if (SpeechValidator.isGenuineSpeech(originalSpeech)) {
                                                            "$originalSpeech $partialSpeech"
                                                        } else {
                                                            partialSpeech
                                                        }
                                                    } else {
                                                        originalSpeech
                                                    }

                                                    if (SpeechValidator.isGenuineSpeech(liveOriginal)) {
                                                        Text(
                                                            text = liveOriginal,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                                        )
                                                    }

                                                    if (SpeechValidator.isGenuineSpeech(translatedSpeech)) {
                                                        Spacer(modifier = Modifier.height(2.dp))
                                                        Text(
                                                            text = translatedSpeech,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Primary Audio Capture Start / Stop Action Bar
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isCapturing) {
                            Button(
                                onClick = { viewModel.stopCapture() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                ),
                                shape = RoundedCornerShape(28.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                                    .testTag("stop_capture_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = "Stop",
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Stop Subtitle Translation",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            Button(
                                onClick = {
                                    onRequestCapturePermission(isMicMode)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                ),
                                shape = RoundedCornerShape(28.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                                    .testTag("start_capture_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Start",
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isMicMode) "Start Mic Subtitles" else "Start Internal Audio Subtitles",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            } else if (selectedTab == 1) {
                // TAB 2: MODEL MANAGEMENT SCREEN
                ModelManagementScreen(viewModel = viewModel)
            } else {
                // TAB 3: SETTINGS & PARAMETERS SCREEN
                SettingsScreen(viewModel = viewModel)
            }
        }
    }

    // Saved History Bottom Sheet
    if (showHistorySheet) {
        HistoryBottomSheet(
            transcripts = history,
            onDismiss = { showHistorySheet = false },
            onClearAll = { viewModel.clearHistory() },
            onDeleteItem = { id -> viewModel.deleteHistoryItem(id) }
        )
    }

    // First-Launch Onboarding Modal: Non-dismissible Material 3 Dialog
    // Shown only while the first-launch download is running or has failed.
    val isSetupInProgress = setupDownloadState is DownloadState.Downloading ||
        setupDownloadState is DownloadState.Extracting ||
        setupDownloadState is DownloadState.Error
    if (isSetupInProgress) {
        Dialog(
            onDismissRequest = { /* Non-dismissible: required for offline transcription */ },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .testTag("first_launch_model_setup_dialog"),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Hearing,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Download Speech Recognition Engine",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "This is a one-time ~40MB download required for offline transcription. All voice recognition runs directly on your device with complete privacy.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    when (val state = setupDownloadState) {
                        is DownloadState.Downloading -> {
                            val animatedProgress by animateFloatAsState(
                                targetValue = state.progress,
                                animationSpec = tween(durationMillis = 200, easing = LinearEasing),
                                label = "model_download_progress"
                            )

                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .testTag("model_linear_progress_indicator"),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val percent = (state.progress * 100).toInt()
                                Text(
                                    text = "$percent%",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                val speedText = if (state.speedBytesPerSec > 1024 * 1024) {
                                    String.format("%.1f MB/s", state.speedBytesPerSec / (1024f * 1024f))
                                } else if (state.speedBytesPerSec > 1024) {
                                    "${state.speedBytesPerSec / 1024} KB/s"
                                } else {
                                    "Downloading…"
                                }

                                Text(
                                    text = speedText,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("model_download_speed")
                                )
                            }

                            if (state.totalBytes > 0) {
                                Spacer(modifier = Modifier.height(4.dp))
                                val downloadedMb = state.bytesDownloaded / (1024f * 1024f)
                                val totalMb = state.totalBytes / (1024f * 1024f)
                                Text(
                                    text = String.format("%.1f MB / %.1f MB", downloadedMb, totalMb),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        is DownloadState.Extracting -> {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .testTag("model_extracting_progress_indicator"),
                                color = MaterialTheme.colorScheme.tertiary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = "Extracting & configuring models…",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        is DownloadState.Error -> {
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = { viewModel.downloadInitialAsrModel() },
                                modifier = Modifier.testTag("retry_model_download_button")
                            ) {
                                Text("Retry Download")
                            }
                        }

                        is DownloadState.Idle -> {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = "Preparing download…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        is DownloadState.Ready -> {
                            // Automatically dismissed
                        }
                    }
                }
            }
        }
    }
}
