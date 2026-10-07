package com.example.ui

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FitScreen
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.AudioVisualizer
import com.example.ui.components.DraggableSubtitleBox
import com.example.ui.components.HistoryBottomSheet
import com.example.ui.components.LanguageSelectorRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: AudioTranslatorViewModel,
    onRequestCapturePermission: () -> Unit,
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

    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Translate, 1: Manage Models

    val context = LocalContext.current

    // MediaProjection token launcher directly within Composable
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.startInternalCapture(result.resultCode, result.data!!)
        } else {
            viewModel.showError("Audio capture permission was denied or cancelled.")
        }
    }

    var showHistorySheet by remember { mutableStateOf(false) }
    var showInAppFloatingBox by remember { mutableStateOf(true) }

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
                    // Audio Mode Selection Chips
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
                                label = { Text("Internal Audio", style = MaterialTheme.typography.labelSmall) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.VolumeUp,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
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
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                enabled = !isCapturing,
                                modifier = Modifier.testTag("chip_microphone")
                            )
                        }

                        // Direct shortcut to Manage Models Tab
                        OutlinedButton(
                            onClick = { selectedTab = 1 },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .height(32.dp)
                                .testTag("shortcut_manage_models_button"),
                            contentPadding = ButtonDefaults.TextButtonContentPadding
                        ) {
                            Icon(
                                imageVector = Icons.Default.SettingsSuggest,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Models", style = MaterialTheme.typography.labelSmall)
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

                    Spacer(modifier = Modifier.height(6.dp))

                    // Floating Window Overlay Control Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("floating_window_control_card"),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                        ),
                        border = CardDefaults.outlinedCardBorder()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(overlaySettings.theme.backgroundColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Subtitles,
                                        contentDescription = null,
                                        tint = overlaySettings.theme.textColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "Floating Subtitle Window",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        if (isOverlayActive) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = MaterialTheme.colorScheme.primary
                                            ) {
                                                Text(
                                                    text = "ACTIVE",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = "Floats over YouTube, games & video apps",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            // Action to activate system overlay
                            OutlinedButton(
                                onClick = {
                                    if (isOverlayActive) {
                                        viewModel.stopFloatingOverlay()
                                    } else {
                                        onRequestOverlayPermission()
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.testTag("toggle_system_overlay_button")
                            ) {
                                Icon(
                                    imageVector = if (isOverlayActive) Icons.Default.Close else Icons.Default.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isOverlayActive) "Close" else "Overlay",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Audio Visualizer VU Meter
                    AudioVisualizer(
                        isCapturing = isCapturing,
                        audioDb = audioDb
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Draggable & Adjustable Subtitle Window
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        if (showInAppFloatingBox) {
                            DraggableSubtitleBox(
                                originalText = originalSpeech,
                                translatedText = translatedSpeech,
                                partialText = partialSpeech,
                                settings = overlaySettings,
                                isDrmSilenceDetected = isDrmSilenceDetected,
                                onSwitchToMic = { viewModel.switchToMicMode() },
                                onSettingsChanged = { viewModel.updateFloatingSettings(it) },
                                onClose = {
                                    viewModel.stopCapture()
                                    viewModel.stopFloatingOverlay()
                                    showInAppFloatingBox = false
                                }
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                OutlinedButton(
                                    onClick = { showInAppFloatingBox = true },
                                    modifier = Modifier.testTag("restore_subtitle_box_button")
                                ) {
                                    Icon(imageVector = Icons.Default.FitScreen, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Show Draggable Subtitle Window")
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
                                    if (isMicMode) {
                                        viewModel.startMicCapture()
                                    } else {
                                        // Requirement 1: Call MediaProjectionManager.createScreenCaptureIntent() and launch it via rememberLauncherForActivityResult
                                        val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                                        if (mediaProjectionManager != null) {
                                            try {
                                                mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                                            } catch (e: Exception) {
                                                viewModel.showError("Failed to launch screen capture: ${e.localizedMessage}")
                                            }
                                        } else {
                                            onRequestCapturePermission()
                                        }
                                    }
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
}
