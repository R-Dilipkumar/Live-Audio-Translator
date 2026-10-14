package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.overlay.OverlaySettings
import com.example.overlay.SubtitleStyle
import com.example.overlay.OverlayTheme
import com.example.overlay.SubtitleFrame
import com.example.overlay.SubtitleLayout
import com.example.overlay.SubtitlePager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Most subtitle lines the window can be resized to. */
const val MAX_OVERLAY_LINES = 4

/**
 * The floating subtitle window drawn over other apps by FloatingSubtitleService.
 *
 * - Controls shown: a slim top bar (drag handle, lock, pause, settings, close) and a resize
 *   grip in the bottom-right corner.
 * - Subtitles only: double-tap the window to hide the controls and keep just the text on its
 *   background. Double-tap again to bring them back.
 * - Drag anywhere to move the window (unless locked). Drag the grip sideways to change the
 *   width, up or down to change the number of lines.
 *
 * Window position and size are owned by the service; this composable reports gestures.
 */
@Composable
fun SubtitleOverlay(
    originalText: String,
    translatedText: String,
    isTranslatedFinal: Boolean,
    utteranceId: Long,
    partialText: String,
    isListening: Boolean,
    settings: OverlaySettings,
    onSettingsChanged: (OverlaySettings) -> Unit,
    onClose: () -> Unit,
    onMove: (dx: Float, dy: Float) -> Unit,
    onMoveEnd: () -> Unit,
    onResizeWidth: (dx: Float) -> Unit,
    onResizeEnd: () -> Unit,
    onControlsVisibilityChanged: (visible: Boolean, controlsHeightPx: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = settings.theme
    val lineCount = settings.maxSubtitleLines.coerceIn(1, MAX_OVERLAY_LINES)
    val isLocked = settings.isLocked

    var controlsVisible by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var headerHeightPx by remember { mutableIntStateOf(0) }

    val currentSettings by rememberUpdatedState(settings)
    val currentOnSettingsChanged by rememberUpdatedState(onSettingsChanged)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnMoveEnd by rememberUpdatedState(onMoveEnd)
    val currentOnControlsChanged by rememberUpdatedState(onControlsVisibilityChanged)
    val currentOnResizeWidth by rememberUpdatedState(onResizeWidth)
    val currentOnResizeEnd by rememberUpdatedState(onResizeEnd)

    // Faint double-tap reminder: always while the controls show, and for a few seconds after
    // hiding them (so it is clear how to get them back)
    var showHiddenHint by remember { mutableStateOf(false) }
    LaunchedEffect(controlsVisible) {
        showHiddenHint = !controlsVisible
        if (!controlsVisible) {
            delay(DOUBLE_TAP_HINT_AFTER_HIDE_MS)
            showHiddenHint = false
        }
    }
    val hintAlpha by animateFloatAsState(
        targetValue = if (controlsVisible || showHiddenHint) 1f else 0f,
        animationSpec = tween(durationMillis = 600),
        label = "double_tap_hint"
    )

    fun toggleControls() {
        val visible = !controlsVisible
        // Report before hiding, while the bar's height is still known
        currentOnControlsChanged(visible, headerHeightPx)
        controlsVisible = visible
        if (!visible) showSettings = false
    }

    val shape = RoundedCornerShape(if (controlsVisible) 18.dp else 14.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(4.dp)
            .clip(shape)
            .background(theme.backgroundColor.copy(alpha = settings.opacity.coerceIn(0.2f, 1f)))
            .then(
                if (controlsVisible) Modifier.border(1.dp, theme.borderColor.copy(alpha = 0.5f), shape)
                else Modifier
            )
            // Double-tap anywhere (outside the buttons) switches between controls and subtitles only
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { toggleControls() })
            }
            // Drag anywhere to move, except in Roll-up, where dragging the text scrolls it
            // (the top bar still moves the window)
            .pointerInput(isLocked, settings.style) {
                if (!isLocked && settings.style != SubtitleStyle.ROLL_UP) {
                    detectDragGestures(
                        onDragEnd = { currentOnMoveEnd() },
                        onDrag = { change, drag ->
                            change.consume()
                            currentOnMove(drag.x, drag.y)
                        }
                    )
                }
            }
            .testTag("floating_subtitle_window")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Shown/hidden instantly: the service shifts the window by the bar's height at the
            // same time, so the subtitles stay where they are on screen
            if (controlsVisible) {
                OverlayHeader(
                    theme = theme,
                    isListening = isListening,
                    isPaused = settings.isPaused,
                    isLocked = isLocked,
                    isSettingsOpen = showSettings,
                    onToggleLock = { onSettingsChanged(settings.copy(isLocked = !isLocked)) },
                    onTogglePause = { onSettingsChanged(settings.copy(isPaused = !settings.isPaused)) },
                    onToggleSettings = { showSettings = !showSettings },
                    onClose = onClose,
                    modifier = Modifier
                        .onSizeChanged { headerHeightPx = it.height }
                        .pointerInput(isLocked) {
                            if (!isLocked) {
                                detectDragGestures(
                                    onDragEnd = { currentOnMoveEnd() },
                                    onDrag = { change, drag ->
                                        change.consume()
                                        currentOnMove(drag.x, drag.y)
                                    }
                                )
                            }
                        }
                )
            }

            SubtitleContent(
                originalText = originalText,
                translatedText = translatedText,
                isTranslatedFinal = isTranslatedFinal,
                utteranceId = utteranceId,
                partialText = partialText,
                isListening = isListening,
                settings = settings,
                lineCount = lineCount,
                modifier = Modifier.padding(
                    start = 14.dp,
                    end = 14.dp,
                    top = if (controlsVisible) 4.dp else 10.dp,
                    bottom = 10.dp
                )
            )

            AnimatedVisibility(visible = controlsVisible && showSettings) {
                OverlaySettingsPanel(settings = settings, onSettingsChanged = onSettingsChanged)
            }
        }

        if (hintAlpha > 0f && !showSettings) {
            Text(
                text = if (controlsVisible) "Double-tap to hide controls" else "Double-tap to show controls",
                fontSize = 9.sp,
                lineHeight = 10.sp,
                color = theme.textColor.copy(alpha = 0.3f * hintAlpha),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 1.dp)
                    .testTag("double_tap_hint")
            )
        }

        // Resize grip: sideways changes the width, up/down changes the number of lines
        if (controlsVisible && !isLocked && !showSettings) {
            val lineHeightPx = with(LocalDensity.current) { (settings.fontSizeSp * 1.3f).sp.toPx() }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(30.dp)
                    .pointerInput(lineHeightPx) {
                        var verticalTravel = 0f
                        detectDragGestures(
                            onDragStart = { verticalTravel = 0f },
                            onDragEnd = { currentOnResizeEnd() },
                            onDrag = { change, drag ->
                                change.consume()
                                currentOnResizeWidth(drag.x)
                                verticalTravel += drag.y
                                val lines = currentSettings.maxSubtitleLines.coerceIn(1, MAX_OVERLAY_LINES)
                                val step = lineHeightPx * 0.8f
                                val newLines = when {
                                    verticalTravel > step && lines < MAX_OVERLAY_LINES -> lines + 1
                                    verticalTravel < -step && lines > 1 -> lines - 1
                                    else -> lines
                                }
                                if (newLines != lines) {
                                    verticalTravel = 0f
                                    currentOnSettingsChanged(
                                        currentSettings.copy(maxSubtitleLines = newLines, maxLines = newLines)
                                    )
                                }
                            }
                        )
                    }
                    .testTag("overlay_resize_grip"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.OpenInFull,
                    contentDescription = "Resize",
                    tint = theme.textColor.copy(alpha = 0.45f),
                    modifier = Modifier
                        .size(14.dp)
                        .graphicsLayer { rotationZ = 90f }
                )
            }
        }
    }
}

@Composable
private fun OverlayHeader(
    theme: OverlayTheme,
    isListening: Boolean,
    isPaused: Boolean,
    isLocked: Boolean,
    isSettingsOpen: Boolean,
    onToggleLock: () -> Unit,
    onTogglePause: () -> Unit,
    onToggleSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val status = when {
        !isListening -> "Not listening"
        isPaused -> "Paused"
        else -> "Live"
    }
    val statusColor = when {
        !isListening -> theme.textColor.copy(alpha = 0.35f)
        isPaused -> Color(0xFFF59E0B)
        else -> Color(0xFF22C55E)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(theme.headerColor.copy(alpha = 0.55f))
            .padding(start = 6.dp, end = 2.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.DragIndicator,
            contentDescription = null,
            tint = theme.textColor.copy(alpha = if (isLocked) 0.2f else 0.5f),
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(statusColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = status,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = theme.textColor.copy(alpha = 0.75f),
            modifier = Modifier.weight(1f)
        )
        HeaderButton(
            icon = if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
            description = if (isLocked) "Unlock position" else "Lock position",
            tint = if (isLocked) MaterialTheme.colorScheme.primary else theme.textColor.copy(alpha = 0.7f),
            tag = "lock_subtitle_button",
            onClick = onToggleLock
        )
        HeaderButton(
            icon = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
            description = if (isPaused) "Resume" else "Pause",
            tint = theme.textColor.copy(alpha = 0.7f),
            tag = "pause_subtitle_button",
            onClick = onTogglePause
        )
        HeaderButton(
            icon = Icons.Default.Tune,
            description = "Subtitle settings",
            tint = if (isSettingsOpen) MaterialTheme.colorScheme.primary else theme.textColor.copy(alpha = 0.7f),
            tag = "adjust_subtitle_style_button",
            onClick = onToggleSettings
        )
        HeaderButton(
            icon = Icons.Default.Close,
            description = "Stop and close",
            tint = theme.textColor.copy(alpha = 0.7f),
            tag = "close_subtitle_button",
            onClick = onClose
        )
    }
}

@Composable
private fun HeaderButton(
    icon: ImageVector,
    description: String,
    tint: Color,
    tag: String,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp).testTag(tag)) {
        Icon(imageVector = icon, contentDescription = description, tint = tint, modifier = Modifier.size(17.dp))
    }
}

/**
 * The text part of the window: the original speech (optional, one line) and the translation,
 * either as movie-style cards or as live rolling text. Fades out after the linger time.
 */
@Composable
private fun SubtitleContent(
    originalText: String,
    translatedText: String,
    isTranslatedFinal: Boolean,
    utteranceId: Long,
    partialText: String,
    isListening: Boolean,
    settings: OverlaySettings,
    lineCount: Int,
    modifier: Modifier = Modifier
) {
    val theme = settings.theme
    val coroutineScope = rememberCoroutineScope()

    // Live style: committed sentences plus the sentence still being spoken
    var committedFinalLines by remember { mutableStateOf(listOf<String>()) }
    var activeDraftLine by remember { mutableStateOf<String?>(null) }
    var currentActiveEpoch by remember { mutableStateOf(0L) }
    var latestOriginal by remember { mutableStateOf("") }

    // Roll-up style: recent sentences, oldest first; the newest may still be a draft
    var rollLines by remember { mutableStateOf(listOf<RollLine>()) }

    // Movie style: utterances queue up and are shown as cards that hold for reading.
    // Accuracy mode: phrases arrive a little later, so each card stays up longer
    // (at least 2.5 s, reading at ~13 characters a second, up to 7 s).
    val subtitlePager = remember(settings.isAccuracyMode) {
        if (settings.isAccuracyMode) {
            SubtitlePager(minHoldMs = 2_500L, maxHoldMs = 7_000L, msPerChar = 75L, fastHoldMs = 1_500L, maxBacklogCards = 5)
        } else {
            SubtitlePager()
        }
    }
    var pagerVersion by remember { mutableIntStateOf(0) }

    var lastSpeechTimestamp by remember { mutableStateOf(System.currentTimeMillis()) }
    val subtitleAlpha = remember { Animatable(1f) }

    fun markSpeech() {
        lastSpeechTimestamp = System.currentTimeMillis()
        coroutineScope.launch { subtitleAlpha.snapTo(1f) }
    }

    LaunchedEffect(translatedText, isTranslatedFinal, utteranceId, subtitlePager) {
        // A late draft from an older sentence must not overwrite newer text
        if (utteranceId > 0 && utteranceId < currentActiveEpoch && !isTranslatedFinal) return@LaunchedEffect
        val text = displayableText(translatedText) ?: return@LaunchedEffect

        subtitlePager.submit(utteranceId, text, isTranslatedFinal, System.currentTimeMillis())
        pagerVersion++

        rollLines = updateRollLines(rollLines, utteranceId, text, isTranslatedFinal)

        if (utteranceId >= currentActiveEpoch) currentActiveEpoch = utteranceId
        if (isTranslatedFinal) {
            committedFinalLines = (committedFinalLines + text).takeLast(MAX_OVERLAY_LINES)
            activeDraftLine = null
        } else {
            activeDraftLine = text
        }
        markSpeech()
    }

    LaunchedEffect(originalText) {
        displayableText(originalText)?.let {
            latestOriginal = it
            markSpeech()
        }
    }

    LaunchedEffect(partialText) {
        if (partialText.isNotBlank()) markSpeech()
    }

    // Keep the text fully visible for the linger time after speech, then fade out
    LaunchedEffect(settings.lingerTimeSeconds, lastSpeechTimestamp) {
        if (settings.lingerTimeSeconds > 0.05f) {
            delay((settings.lingerTimeSeconds * 1000L).toLong())
            subtitleAlpha.animateTo(0f, animationSpec = tween(durationMillis = 400))
        } else {
            subtitleAlpha.snapTo(1f)
        }
    }

    val shadow = if (settings.isTextOutlineShadowEnabled) {
        Shadow(
            color = Color.Black.copy(alpha = if (theme == OverlayTheme.FROST_CLEAN) 0.55f else 0.85f),
            offset = Offset(1.5f, 1.5f),
            blurRadius = settings.textShadowRadius.coerceAtLeast(1f)
        )
    } else {
        Shadow.None
    }
    val lineHeight = (settings.fontSizeSp * 1.30f).sp
    val hasSubtitles = committedFinalLines.isNotEmpty() || activeDraftLine != null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = subtitleAlpha.value }
    ) {
        if (settings.showOriginal) {
            val original = if (partialText.isNotBlank()) "$partialText …" else latestOriginal
            if (original.isNotBlank()) {
                TailText(
                    text = AnnotatedString(original),
                    visibleLines = 1,
                    lineHeight = (settings.fontSizeSp * 1.15f).sp,
                    style = TextStyle(
                        fontSize = (settings.fontSizeSp - 3.5f).coerceAtLeast(11f).sp,
                        color = theme.originalTextColor,
                        fontStyle = FontStyle.Italic,
                        shadow = shadow
                    ),
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }

        val translationStyle = TextStyle(
            fontSize = settings.fontSizeSp.sp,
            fontWeight = FontWeight.Bold,
            color = theme.textColor,
            shadow = shadow
        )
        when {
            settings.isPaused -> PlaceholderText("Paused", translationStyle, lineHeight, lineCount)
            !hasSubtitles -> PlaceholderText(
                text = when {
                    !isListening -> "Press Start in LiveSub"
                    else -> "Listening…"
                },
                style = translationStyle.copy(color = theme.textColor.copy(alpha = 0.55f), fontWeight = FontWeight.Medium),
                lineHeight = lineHeight,
                lineCount = lineCount
            )
            settings.style == SubtitleStyle.ROLL_UP -> RollUpSubtitles(
                lines = rollLines,
                visibleLines = lineCount,
                lineHeight = lineHeight,
                style = translationStyle,
                textColor = theme.textColor,
                onUserScroll = { markSpeech() },
                modifier = Modifier.testTag("floating_translated_subtitle_text")
            )
            settings.style == SubtitleStyle.MOVIE -> MovieSubtitleText(
                pager = subtitlePager,
                version = pagerVersion,
                visibleLines = lineCount,
                lineHeight = lineHeight,
                style = translationStyle,
                onCardShown = { markSpeech() },
                modifier = Modifier.testTag("floating_translated_subtitle_text")
            )
            else -> {
                // Older sentences muted, the newest bold; only the last lines that fit are shown
                val lines = committedFinalLines.takeLast(lineCount) + listOfNotNull(activeDraftLine)
                val text = buildAnnotatedString {
                    lines.forEachIndexed { index, line ->
                        if (index > 0) append("\n")
                        val isNewest = index == lines.lastIndex
                        withStyle(
                            SpanStyle(
                                fontWeight = if (isNewest) FontWeight.Bold else FontWeight.Medium,
                                color = if (isNewest) theme.textColor else theme.textColor.copy(alpha = 0.7f)
                            )
                        ) { append(line) }
                    }
                }
                TailText(
                    text = text,
                    visibleLines = lineCount,
                    lineHeight = lineHeight,
                    style = translationStyle,
                    fixedHeight = true,
                    modifier = Modifier.testTag("floating_translated_subtitle_text")
                )
            }
        }
    }
}

/** Returns the text if it is real subtitle content, or null for placeholders and blanks. */
private fun displayableText(raw: String): String? {
    val lines = raw.trim().split("\n").map { it.trim() }.filter {
        it.isNotBlank() &&
            !it.startsWith("[Translation Paused]") &&
            !it.startsWith("Subtitles will stream") &&
            !it.startsWith("Translation will appear") &&
            !it.startsWith("Waiting")
    }
    return lines.joinToString(" ").ifBlank { null }
}

@Composable
private fun PlaceholderText(text: String, style: TextStyle, lineHeight: TextUnit, lineCount: Int) {
    val height = with(LocalDensity.current) { lineHeight.toDp() } * lineCount
    Box(modifier = Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.CenterStart) {
        Text(text = text, style = style, maxLines = lineCount, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun OverlaySettingsPanel(
    settings: OverlaySettings,
    onSettingsChanged: (OverlaySettings) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Text size: A- / value / A+
        PanelRow(label = "Text size") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillButton(text = "A−", selected = false, tag = "size_smaller_button") {
                    onSettingsChanged(settings.copy(fontSizeSp = (settings.fontSizeSp - 1f).coerceAtLeast(12f)))
                }
                Text(
                    text = "${settings.fontSizeSp.toInt()}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(36.dp)
                )
                PillButton(text = "A+", selected = false, tag = "size_larger_button") {
                    onSettingsChanged(settings.copy(fontSizeSp = (settings.fontSizeSp + 1f).coerceAtMost(30f)))
                }
            }
        }

        PanelRow(label = "Lines") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (count in 1..MAX_OVERLAY_LINES) {
                    PillButton(text = "$count", selected = settings.maxSubtitleLines == count, tag = "lines_${count}_button") {
                        onSettingsChanged(settings.copy(maxSubtitleLines = count, maxLines = count))
                    }
                }
            }
        }

        PanelRow(label = "Style") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(SubtitleStyle.ROLL_UP to "Roll-up", SubtitleStyle.MOVIE to "Movie", SubtitleStyle.LIVE to "Live").forEach { (option, label) ->
                    PillButton(text = label, selected = settings.style == option, tag = "style_${option.name.lowercase()}_button") {
                        onSettingsChanged(settings.copy(style = option))
                    }
                }
            }
        }

        PanelRow(label = "Keep on screen") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(2f to "2s", 4f to "4s", 7f to "7s", 0f to "Always").forEach { (seconds, label) ->
                    val selected = kotlin.math.abs(settings.lingerTimeSeconds - seconds) < 0.5f
                    PillButton(text = label, selected = selected, tag = "linger_${label}_button") {
                        onSettingsChanged(settings.copy(lingerTimeSeconds = seconds))
                    }
                }
            }
        }

        PanelRow(label = "Original speech") {
            Switch(
                checked = settings.showOriginal,
                onCheckedChange = { onSettingsChanged(settings.copy(showOriginal = it)) },
                modifier = Modifier.testTag("toggle_overlay_original")
            )
        }

        Column {
            Text("Background", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Slider(
                value = settings.opacity.coerceIn(0.2f, 1f),
                onValueChange = { onSettingsChanged(settings.copy(opacity = it)) },
                valueRange = 0.2f..1f,
                modifier = Modifier.fillMaxWidth().height(32.dp)
            )
        }

        PanelRow(label = "Theme") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(OverlayTheme.CINEMA_DARK, OverlayTheme.OLED_BLACK, OverlayTheme.FROST_CLEAN, OverlayTheme.NEON_CYBER)
                    .forEach { option ->
                        val selected = settings.theme == option
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(option.backgroundColor)
                                .border(
                                    width = if (selected) 2.5.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.5f),
                                    shape = CircleShape
                                )
                                .clickable { onSettingsChanged(settings.copy(theme = option)) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("A", color = option.textColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
            }
        }

        Text(
            text = "Tip: double-tap the subtitles to hide the controls. Drag the corner to resize.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PanelRow(label: String, content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        content()
    }
}

@Composable
private fun PillButton(text: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One sentence in Roll-up style. [id] is the utterance id, so a draft and its final share a line. */
internal data class RollLine(val id: Long, val text: String, val isFinal: Boolean)

/** Most sentences kept for scrolling back in Roll-up style. */
internal const val ROLL_UP_HISTORY = 60

/**
 * Applies one translation result to the Roll-up lines (oldest first). A draft or final of the
 * newest utterance replaces it in place; a newer utterance adds a line; a late final for an
 * older line updates that line. A draft never replaces a final.
 */
internal fun updateRollLines(lines: List<RollLine>, utteranceId: Long, text: String, isFinal: Boolean): List<RollLine> {
    val index = lines.indexOfLast { it.id == utteranceId }
    if (index >= 0) {
        val existing = lines[index]
        if (existing.isFinal && !isFinal) return lines
        return lines.toMutableList().also { it[index] = RollLine(utteranceId, text, isFinal) }
    }
    val newest = lines.lastOrNull()
    // An older utterance that is no longer on the list: too late to show
    if (newest != null && utteranceId in 1 until newest.id) return lines
    return (lines + RollLine(utteranceId, text, isFinal)).takeLast(ROLL_UP_HISTORY)
}

/**
 * Roll-up subtitles: sentences stack with the newest at the bottom; older lines roll up and fade.
 * Scroll up to reread; a "Live" button (or 8 s without touching) returns to the newest line.
 *
 * Built so scrolling never glitches:
 * - reverseLayout keeps the list anchored to the bottom, so a growing draft pushes older lines
 *   up instead of jumping;
 * - each line has a stable key (its utterance id), so new lines never move the line you are
 *   reading while scrolled back;
 * - it only follows new text while you are at the bottom and not touching it.
 */
@Composable
private fun RollUpSubtitles(
    lines: List<RollLine>,
    visibleLines: Int,
    lineHeight: TextUnit,
    style: TextStyle,
    textColor: Color,
    onUserScroll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val boxHeight = with(LocalDensity.current) { lineHeight.toDp() } * visibleLines
    val newestFirst = remember(lines) { lines.asReversed() }
    val listState = rememberLazyListState()
    val isDragging by listState.interactionSource.collectIsDraggedAsState()
    var following by remember { mutableStateOf(true) }
    val currentOnUserScroll by rememberUpdatedState(onUserScroll)

    // Touching the list stops following; reaching the bottom again resumes it
    LaunchedEffect(isDragging) {
        if (isDragging) {
            following = false
            currentOnUserScroll()
        }
    }
    LaunchedEffect(listState) {
        // Checked again when a fling stops, so flinging back to the bottom also resumes it
        snapshotFlow {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 &&
                !listState.isScrollInProgress
        }.collect { settledAtBottom -> if (settledAtBottom) following = true }
    }
    // Back to live after 8 s without touching
    LaunchedEffect(following, isDragging) {
        if (!following && !isDragging) {
            delay(ROLL_UP_RESYNC_MS)
            following = true
        }
    }
    // Follow new lines and a growing draft while live. Touching the list turns following off
    // (and a finger always takes over from this scroll), so it never fights the user.
    val newest = newestFirst.firstOrNull()
    LaunchedEffect(newest?.id, newest?.text, following) {
        if (following && newest != null) {
            listState.animateScrollToItem(0)
        }
    }

    val lineStyle = style.copy(
        lineHeight = lineHeight,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None
        )
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(boxHeight)
            .clipToBounds()
    ) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxWidth().height(boxHeight)
        ) {
            itemsIndexed(newestFirst, key = { _, line -> line.id }) { index, line ->
                // Older lines fade a little the further they have rolled up
                val alpha = when (index) {
                    0 -> 1f
                    1 -> 0.78f
                    2 -> 0.62f
                    else -> 0.5f
                }
                Text(
                    text = line.text,
                    style = lineStyle.copy(
                        color = textColor.copy(alpha = alpha),
                        fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Medium
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp)
                        .animateItem(
                            fadeInSpec = tween(durationMillis = 220),
                            placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            fadeOutSpec = tween(durationMillis = 150)
                        )
                )
            }
        }

        // Scrolled back: one tap returns to the newest line
        if (!following) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 18.dp, bottom = 2.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { following = true }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .testTag("roll_up_live_button"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Live", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Back to live subtitles",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

private const val ROLL_UP_RESYNC_MS = 8_000L

private const val DOUBLE_TAP_HINT_AFTER_HIDE_MS = 3_000L

/**
 * Shows the end of [text]: it wraps normally, but only its last [visibleLines] lines are visible,
 * so live captions always show the newest words. With [fixedHeight] the box always keeps the
 * height of [visibleLines] lines, so the window doesn't grow and shrink with the text.
 */
@Composable
private fun TailText(
    text: AnnotatedString,
    visibleLines: Int,
    lineHeight: TextUnit,
    style: TextStyle,
    modifier: Modifier = Modifier,
    fixedHeight: Boolean = false
) {
    val boxHeight = with(LocalDensity.current) { lineHeight.toDp() } * visibleLines
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (fixedHeight) Modifier.height(boxHeight) else Modifier.heightIn(max = boxHeight))
            .clipToBounds(),
        contentAlignment = Alignment.BottomStart
    ) {
        Text(
            text = text,
            style = style.copy(
                lineHeight = lineHeight,
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.None
                )
            ),
            softWrap = true,
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.Bottom, unbounded = true)
        )
    }
}

/**
 * Movie-style subtitles: a fixed-height box that shows one card from [pager] at a time.
 * A card fills the box as words arrive and stays until it has been readable long enough,
 * then the box clears for the next card. The box never grows or shrinks, so nothing jumps.
 */
@Composable
private fun MovieSubtitleText(
    pager: SubtitlePager,
    version: Int,
    visibleLines: Int,
    lineHeight: TextUnit,
    style: TextStyle,
    onCardShown: () -> Unit,
    modifier: Modifier = Modifier
) {
    val boxHeight = with(LocalDensity.current) { lineHeight.toDp() } * visibleLines
    val textStyle = style.copy(
        lineHeight = lineHeight,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None
        )
    )
    val measurer = rememberTextMeasurer()
    val currentOnCardShown by rememberUpdatedState(onCardShown)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(boxHeight)
            .clipToBounds()
    ) {
        val widthPx = constraints.maxWidth
        // A card holds as much text as fits in visibleLines at the current width and font
        val paginate: (String) -> List<String> = remember(measurer, textStyle, widthPx, visibleLines) {
            val cache = HashMap<String, List<String>>()
            val layoutConstraints = if (constraints.hasBoundedWidth) Constraints(maxWidth = widthPx) else Constraints()
            val fits: (String) -> Boolean = { candidate ->
                measurer.measure(candidate, textStyle, constraints = layoutConstraints).lineCount <= visibleLines
            }
            { text ->
                if (cache.size > 64) cache.clear()
                cache.getOrPut(text) { SubtitleLayout.paginate(text, fits) }
            }
        }

        var frame by remember { mutableStateOf(SubtitleFrame.EMPTY) }
        // Re-check on new text, then again whenever the next card is due
        LaunchedEffect(version, paginate) {
            while (true) {
                val now = System.currentTimeMillis()
                frame = pager.display(now, paginate)
                val next = frame.nextCheckAtMs ?: break
                delay((next - now).coerceAtLeast(16L))
            }
        }
        LaunchedEffect(frame.text) {
            if (frame.text.isNotBlank()) currentOnCardShown()
        }

        Text(
            text = frame.text,
            style = textStyle,
            maxLines = visibleLines,
            overflow = TextOverflow.Clip,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
