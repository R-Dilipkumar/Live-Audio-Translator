package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.overlay.OverlaySettings
import com.example.overlay.OverlayTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun DraggableSubtitleBox(
    originalText: String,
    translatedText: String,
    partialText: String,
    settings: OverlaySettings,
    isDrmSilenceDetected: Boolean = false,
    onSwitchToMic: () -> Unit = {},
    onSettingsChanged: (OverlaySettings) -> Unit,
    onClose: () -> Unit,
    onDragDelta: ((Float, Float) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    val currentOnDragDelta by rememberUpdatedState(onDragDelta)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)

    // Animated offset coordinates for in-app fluid dragging and snapping
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }

    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var isExpanded by remember { mutableStateOf(!settings.isCollapsed) }
    var isPaused by remember { mutableStateOf(settings.isPaused) }
    var showAdjustPanel by remember { mutableStateOf(false) }

    // Sentence-level rolling buffers: strictly deduplicated and limited to 2 clean lines
    var rollingSentences by remember { mutableStateOf<List<String>>(emptyList()) }
    var rollingOriginals by remember { mutableStateOf<List<String>>(emptyList()) }
    var lastSpeechTimestamp by remember { mutableStateOf(System.currentTimeMillis()) }
    var isLingeredOut by remember { mutableStateOf(false) }

    // Normalize and add unique sentences without repetition
    // Accept valid text while filtering only transient system placeholders
    androidx.compose.runtime.LaunchedEffect(translatedText) {
        val trimmed = translatedText.trim()
        if (trimmed.isNotBlank() &&
            !trimmed.startsWith("[Translation Paused]") &&
            !trimmed.startsWith("Subtitles will stream") &&
            !trimmed.startsWith("Translation will appear")
        ) {
            // Split if translatedText contains multiple lines already
            val incomingClauses = trimmed.split("\n").map { it.trim() }.filter { it.isNotBlank() }
            val currentList = rollingSentences.toMutableList()
            for (clause in incomingClauses) {
                if (currentList.lastOrNull() != clause) {
                    currentList.add(clause)
                }
            }
            // Keep rolling buffer clean (retains up to 6, displayed as strictly maxLines)
            rollingSentences = currentList.takeLast(6)
            lastSpeechTimestamp = System.currentTimeMillis()
            isLingeredOut = false
        }
    }

    androidx.compose.runtime.LaunchedEffect(originalText) {
        val trimmed = originalText.trim()
        if (trimmed.isNotBlank() &&
            !trimmed.startsWith("Waiting")
        ) {
            val incomingClauses = trimmed.split("\n").map { it.trim() }.filter { it.isNotBlank() }
            val currentList = rollingOriginals.toMutableList()
            for (clause in incomingClauses) {
                if (currentList.lastOrNull() != clause) {
                    currentList.add(clause)
                }
            }
            rollingOriginals = currentList.takeLast(6)
            lastSpeechTimestamp = System.currentTimeMillis()
            isLingeredOut = false
        }
    }

    androidx.compose.runtime.LaunchedEffect(partialText) {
        if (partialText.isNotBlank()) {
            isLingeredOut = false
        }
    }

    // Subtitle Linger Time effect:
    // When speech pauses, keep the latest translated sentences visible with stable opacity rather than wiping them to blank
    androidx.compose.runtime.LaunchedEffect(settings.lingerTimeSeconds, lastSpeechTimestamp) {
        if (settings.lingerTimeSeconds > 0f) {
            val lingerMs = (settings.lingerTimeSeconds * 1000L).toLong()
            kotlinx.coroutines.delay(lingerMs)
            isLingeredOut = true
        } else {
            isLingeredOut = false
        }
    }

    val maxLinesCount = settings.maxLines.coerceIn(1, 2)
    val theme = settings.theme
    val configuration = LocalConfiguration.current

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }

        // Status bar & nav bar safety margins
        val topSafetyPx = with(density) { 12.dp.toPx() }
        val bottomSafetyPx = with(density) { 16.dp.toPx() }

        // Dynamic boundary calculations
        val currentBoxW = boxSize.width.toFloat().coerceAtLeast(1f)
        val currentBoxH = boxSize.height.toFloat().coerceAtLeast(1f)

        val minBoundX = 0f
        val maxBoundX = (containerWidthPx - currentBoxW).coerceAtLeast(0f)
        val minBoundY = topSafetyPx
        val maxBoundY = (containerHeightPx - currentBoxH - bottomSafetyPx).coerceAtLeast(minBoundY)

        // Recalculate and clamp coordinates on screen rotation
        androidx.compose.runtime.LaunchedEffect(configuration.orientation, containerWidthPx, containerHeightPx) {
            if (onDragDelta == null) {
                val clampedX = offsetX.value.coerceIn(minBoundX, maxBoundX)
                val clampedY = offsetY.value.coerceIn(minBoundY, maxBoundY)
                if (clampedX != offsetX.value) {
                    offsetX.snapTo(clampedX)
                }
                if (clampedY != offsetY.value) {
                    offsetY.snapTo(clampedY)
                }
            }
        }

        val boxOffsetModifier = if (onDragDelta != null) {
            Modifier
        } else {
            Modifier.offset {
                IntOffset(
                    offsetX.value.roundToInt(),
                    offsetY.value.roundToInt()
                )
            }
        }

        // Sleek Cinematic Glassmorphic Container with stable bounded height (No animateContentSize to prevent BLASTBufferQueue buffer rejection)
        Box(
            modifier = Modifier
                .then(boxOffsetModifier)
                .fillMaxWidth()
                .defaultMinSize(minHeight = 84.dp)
                .padding(horizontal = 8.dp)
                .onSizeChanged { size -> boxSize = size }
                .shadow(elevation = 16.dp, shape = RoundedCornerShape(20.dp), spotColor = Color.Black.copy(alpha = 0.6f))
                .clip(RoundedCornerShape(20.dp))
                .background(
                    color = theme.backgroundColor.copy(alpha = settings.opacity.coerceIn(0.2f, 1f))
                )
                .border(
                    width = 1.2.dp,
                    color = theme.borderColor.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(20.dp)
                )
                .testTag("draggable_floating_subtitle_window")
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Ultra-Compact Minimal Header Bar (Pill Handle + Lock + Settings + Close)
                val isLocked = settings.isLocked
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(theme.headerColor.copy(alpha = 0.65f))
                        .pointerInput(isLocked, onDragDelta != null) {
                            if (!isLocked) {
                                detectDragGestures(
                                    onDragEnd = {
                                        if (currentOnDragDelta != null) {
                                            currentOnDragEnd?.invoke()
                                        } else {
                                            val currentCenter = offsetX.value + (currentBoxW / 2f)
                                            val screenCenter = containerWidthPx / 2f
                                            val targetSnapX = if (currentCenter < screenCenter) {
                                                minBoundX
                                            } else {
                                                maxBoundX
                                            }

                                            coroutineScope.launch {
                                                offsetX.animateTo(
                                                    targetValue = targetSnapX,
                                                    animationSpec = spring(
                                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                                        stiffness = Spring.StiffnessLow
                                                    )
                                                )
                                            }
                                        }
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        if (currentOnDragDelta != null) {
                                            currentOnDragDelta?.invoke(dragAmount.x, dragAmount.y)
                                        } else {
                                            coroutineScope.launch {
                                                val newX = (offsetX.value + dragAmount.x).coerceIn(minBoundX, maxBoundX)
                                                val newY = (offsetY.value + dragAmount.y).coerceIn(minBoundY, maxBoundY)
                                                offsetX.snapTo(newX)
                                                offsetY.snapTo(newY)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Minimal Drag Handle Pill
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(32.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(theme.textColor.copy(alpha = 0.4f))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isLocked) "Locked" else "LiveSub",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = theme.textColor.copy(alpha = 0.75f),
                            fontSize = 11.sp
                        )
                    }

                    // Compact Control Action Row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        // Quick Lock / Unlock Icon
                        IconButton(
                            onClick = {
                                onSettingsChanged(settings.copy(isLocked = !isLocked))
                            },
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("lock_subtitle_button")
                        ) {
                            Icon(
                                imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = if (isLocked) "Unlock overlay" else "Lock overlay",
                                tint = if (isLocked) MaterialTheme.colorScheme.primary else theme.textColor.copy(alpha = 0.75f),
                                modifier = Modifier.size(15.dp)
                            )
                        }

                        // Style Adjust Panel Toggle
                        IconButton(
                            onClick = { showAdjustPanel = !showAdjustPanel },
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("adjust_subtitle_style_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Adjust Style",
                                tint = theme.textColor.copy(alpha = 0.75f),
                                modifier = Modifier.size(15.dp)
                            )
                        }

                        // Pause / Play
                        IconButton(
                            onClick = {
                                isPaused = !isPaused
                                onSettingsChanged(settings.copy(isPaused = isPaused))
                            },
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("pause_subtitle_button")
                        ) {
                            Icon(
                                imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (isPaused) "Resume" else "Pause",
                                tint = theme.textColor.copy(alpha = 0.75f),
                                modifier = Modifier.size(15.dp)
                            )
                        }

                        // Close Icon
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("close_subtitle_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = theme.textColor.copy(alpha = 0.75f),
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }

                // High-visibility DRM & Silence Fallback Banner
                AnimatedVisibility(visible = isDrmSilenceDetected) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("drm_silence_fallback_banner"),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Audio DRM protected. Switch to Mic?",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontSize = 11.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Button(
                                onClick = onSwitchToMic,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .height(30.dp)
                                    .testTag("switch_to_mic_banner_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Switch to Mic", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                // Clean 2-Line Rolling Subtitle Content Area
                AnimatedVisibility(visible = isExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        val textShadow = if (settings.isTextOutlineShadowEnabled) {
                            Shadow(
                                color = Color.Black.copy(alpha = 0.95f),
                                offset = Offset(2f, 2f),
                                blurRadius = settings.textShadowRadius
                            )
                        } else {
                            Shadow.None
                        }

                        val isProcessingAudio = partialText.isNotBlank()

                        // Optional Original foreign language text (1 compact line if enabled)
                        if (settings.showOriginal) {
                            val lastOrig = rollingOriginals.lastOrNull()
                                ?: if (!originalText.startsWith("Waiting")) originalText else ""
                            val displayOrig = if (isProcessingAudio) {
                                if (lastOrig.isNotBlank()) "$lastOrig $partialText …" else "$partialText …"
                            } else {
                                lastOrig
                            }
                            val origColor = if (isLingeredOut && !isProcessingAudio) {
                                theme.originalTextColor.copy(alpha = 0.65f)
                            } else {
                                theme.originalTextColor
                            }
                            if (displayOrig.isNotBlank()) {
                                Text(
                                    text = displayOrig,
                                    fontSize = (settings.fontSizeSp - 3.5f).coerceAtLeast(11f).sp,
                                    color = origColor,
                                    fontStyle = FontStyle.Italic,
                                    style = TextStyle(shadow = textShadow),
                                    lineHeight = (settings.fontSizeSp * 1.15f).sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(bottom = 3.dp)
                                )
                            }
                        }

                        // Primary Translated Subtitle: Strictly max 2 lines with smooth roll-off
                        // Takes the freshest 2 lines from the rolling sentence buffer without repeating
                        val visibleSentences = rollingSentences.takeLast(maxLinesCount)
                        val displayTranslated = if (isPaused) {
                            "[Translation Paused]"
                        } else if (visibleSentences.isNotEmpty()) {
                            visibleSentences.joinToString("\n")
                        } else {
                            val cleanTranslated = translatedText.trim()
                            if (cleanTranslated.isNotBlank()) {
                                cleanTranslated
                            } else {
                                "Subtitles will stream here..."
                            }
                        }

                        // Stable text opacity: gentle fade during linger instead of popping to blank
                        val textColor = when {
                            isPaused -> theme.textColor.copy(alpha = 0.65f)
                            isLingeredOut && !isProcessingAudio -> theme.textColor.copy(alpha = 0.70f)
                            isProcessingAudio -> theme.textColor.copy(alpha = 0.90f)
                            else -> theme.textColor
                        }

                        if (displayTranslated.isNotBlank()) {
                            Text(
                                text = displayTranslated,
                                fontSize = settings.fontSizeSp.sp,
                                fontWeight = FontWeight.Bold,
                                color = textColor,
                                style = TextStyle(shadow = textShadow),
                                lineHeight = (settings.fontSizeSp * 1.35f).sp,
                                maxLines = maxLinesCount,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("floating_translated_subtitle_text")
                            )
                        }
                    }
                }

                // Adjust Panel (Font Size, Opacity, Theme, Lines Limit, Dual-line toggle)
                AnimatedVisibility(visible = showAdjustPanel) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "Subtitle Box Settings",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Configurable Display Lines Control (1 or 2 lines clean limit)
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Display Lines: ${settings.maxLines.coerceIn(1, 2)} Line${if (settings.maxLines.coerceIn(1, 2) > 1) "s" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(1, 2).forEach { lines ->
                                    val isSelected = settings.maxLines == lines
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                                else MaterialTheme.colorScheme.surfaceVariant
                                            )
                                            .border(
                                                width = if (isSelected) 2.dp else 1.dp,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                                else Color.Gray.copy(alpha = 0.3f),
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .clickable {
                                                onSettingsChanged(settings.copy(maxLines = lines))
                                            }
                                            .padding(vertical = 8.dp)
                                            .testTag("line_count_${lines}_button"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "$lines Line${if (lines > 1) "s" else ""}",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Theme Selector Pills
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OverlayTheme.entries.forEach { th ->
                                val selected = settings.theme == th
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(th.backgroundColor)
                                        .border(
                                            width = if (selected) 2.5.dp else 1.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.5f),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    androidx.compose.foundation.text.BasicText(
                                        text = th.title.split(" ")[0],
                                        style = androidx.compose.ui.text.TextStyle(
                                            color = th.textColor,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        modifier = Modifier.clickable {
                                            onSettingsChanged(settings.copy(theme = th))
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Font Size Slider
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Size: ${settings.fontSizeSp.toInt()}sp",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(76.dp)
                            )
                            Slider(
                                value = settings.fontSizeSp,
                                onValueChange = { onSettingsChanged(settings.copy(fontSizeSp = it)) },
                                valueRange = 13f..26f,
                                steps = 12,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Opacity Slider (20% to 100%)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Opacity: ${(settings.opacity * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(76.dp)
                            )
                            Slider(
                                value = settings.opacity,
                                onValueChange = { onSettingsChanged(settings.copy(opacity = it)) },
                                valueRange = 0.20f..1.0f,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Subtitle Linger Time Slider
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (settings.lingerTimeSeconds <= 0f) "Linger: None" else "Linger: ${"%.1f".format(settings.lingerTimeSeconds)}s",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(76.dp)
                            )
                            Slider(
                                value = settings.lingerTimeSeconds,
                                onValueChange = { onSettingsChanged(settings.copy(lingerTimeSeconds = it)) },
                                valueRange = 1.0f..10.0f,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Text Outline & Shadow Toggle
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Text Outline & Shadow",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Adds dark drop shadow for high contrast over bright scenes",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 10.sp
                                )
                            }
                            Switch(
                                checked = settings.isTextOutlineShadowEnabled,
                                onCheckedChange = { onSettingsChanged(settings.copy(isTextOutlineShadowEnabled = it)) },
                                modifier = Modifier.testTag("toggle_overlay_text_shadow")
                            )
                        }

                        // Show Original Foreign Text Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Show original foreign speech",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Switch(
                                checked = settings.showOriginal,
                                onCheckedChange = { onSettingsChanged(settings.copy(showOriginal = it)) }
                            )
                        }

                        // Lock Overlay Positioning Toggle
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Lock Overlay Position",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Prevents accidental dragging during video playback",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 10.sp
                                )
                            }
                            Switch(
                                checked = settings.isLocked,
                                onCheckedChange = { onSettingsChanged(settings.copy(isLocked = it)) },
                                modifier = Modifier.testTag("toggle_lock_overlay_settings")
                            )
                        }
                    }
                }
            }
        }
    }
}
