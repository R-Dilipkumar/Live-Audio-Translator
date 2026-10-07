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

    // Animated offset coordinates for in-app fluid dragging and snapping
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }

    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var isExpanded by remember { mutableStateOf(!settings.isCollapsed) }
    var isPaused by remember { mutableStateOf(settings.isPaused) }
    var showAdjustPanel by remember { mutableStateOf(false) }

    // Sentence-level rolling buffers: retain recent clean sentences and roll off older ones
    var rollingSentences by remember { mutableStateOf<List<String>>(emptyList()) }
    var rollingOriginals by remember { mutableStateOf<List<String>>(emptyList()) }
    var lastSpeechTimestamp by remember { mutableStateOf(System.currentTimeMillis()) }

    androidx.compose.runtime.LaunchedEffect(translatedText) {
        val trimmed = translatedText.trim()
        if (trimmed.isNotBlank() &&
            !trimmed.startsWith("[") &&
            !trimmed.startsWith("Subtitles will stream") &&
            rollingSentences.lastOrNull() != trimmed
        ) {
            rollingSentences = (rollingSentences + trimmed).takeLast(10)
            lastSpeechTimestamp = System.currentTimeMillis()
        }
    }

    androidx.compose.runtime.LaunchedEffect(originalText) {
        val trimmed = originalText.trim()
        if (trimmed.isNotBlank() &&
            !trimmed.startsWith("Waiting") &&
            rollingOriginals.lastOrNull() != trimmed
        ) {
            rollingOriginals = (rollingOriginals + trimmed).takeLast(10)
            lastSpeechTimestamp = System.currentTimeMillis()
        }
    }

    // Subtitle Linger Time effect: clears/fades rolling subtitles after lingerTimeSeconds
    androidx.compose.runtime.LaunchedEffect(settings.lingerTimeSeconds, lastSpeechTimestamp) {
        if (settings.lingerTimeSeconds > 0f) {
            val lingerMs = (settings.lingerTimeSeconds * 1000L).toLong()
            kotlinx.coroutines.delay(lingerMs)
            rollingSentences = emptyList()
            rollingOriginals = emptyList()
        }
    }

    val maxLinesCount = settings.maxLines.coerceIn(1, 3)

    val theme = settings.theme
    val configuration = LocalConfiguration.current

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }

        // Status bar & nav bar safety margins
        val topSafetyPx = with(density) { 12.dp.toPx() }
        val bottomSafetyPx = with(density) { 16.dp.toPx() }
        val edgeMarginPx = with(density) { 8.dp.toPx() }

        // Dynamic boundary calculations
        val currentBoxW = boxSize.width.toFloat().coerceAtLeast(1f)
        val currentBoxH = boxSize.height.toFloat().coerceAtLeast(1f)

        val minBoundX = 0f
        val maxBoundX = (containerWidthPx - currentBoxW).coerceAtLeast(0f)
        val minBoundY = topSafetyPx
        val maxBoundY = (containerHeightPx - currentBoxH - bottomSafetyPx).coerceAtLeast(minBoundY)

        // Requirement 1: Recalculate and clamp coordinates on screen rotation (Portrait <-> Landscape)
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

        Box(
            modifier = Modifier
                .then(boxOffsetModifier)
                .fillMaxWidth()
                .padding(horizontal = 6.dp)
                .onSizeChanged { size -> boxSize = size }
                .shadow(12.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(theme.backgroundColor.copy(alpha = settings.opacity))
                .border(2.dp, theme.borderColor, RoundedCornerShape(16.dp))
                .animateContentSize()
                .testTag("draggable_floating_subtitle_window")
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Drag Handle & Control Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(theme.headerColor)
                        .pointerInput(containerWidthPx, currentBoxW, maxBoundX, maxBoundY, onDragDelta, onDragEnd) {
                            if (onDragDelta != null) {
                                detectDragGestures(
                                    onDragEnd = { onDragEnd?.invoke() },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        onDragDelta(dragAmount.x, dragAmount.y)
                                    }
                                )
                            } else {
                                detectDragGestures(
                                    onDragEnd = {
                                        // Requirement 1: Screen-Edge Snapping Mechanism
                                        // Snap to either left or right edge when released
                                        val currentCenter = offsetX.value + (currentBoxW / 2f)
                                        val screenCenter = containerWidthPx / 2f
                                        val targetSnapX = if (currentCenter < screenCenter) {
                                            minBoundX // Dock to left edge
                                        } else {
                                            maxBoundX // Dock to right edge
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
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        coroutineScope.launch {
                                            // Requirement 1: Clamp within safety boundaries
                                            val newX = (offsetX.value + dragAmount.x).coerceIn(minBoundX, maxBoundX)
                                            val newY = (offsetY.value + dragAmount.y).coerceIn(minBoundY, maxBoundY)
                                            offsetX.snapTo(newX)
                                            offsetY.snapTo(newY)
                                        }
                                    }
                                )
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DragHandle,
                            contentDescription = "Drag to move subtitle box",
                            tint = theme.textColor.copy(alpha = 0.85f),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Live Subtitle Overlay",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = theme.textColor
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Quick Customize Button
                        IconButton(
                            onClick = { showAdjustPanel = !showAdjustPanel },
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("adjust_subtitle_style_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Adjust Style",
                                tint = theme.textColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // Pause / Resume Button
                        IconButton(
                            onClick = {
                                isPaused = !isPaused
                                onSettingsChanged(settings.copy(isPaused = isPaused))
                            },
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("pause_subtitle_button")
                        ) {
                            Icon(
                                imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (isPaused) "Resume" else "Pause",
                                tint = theme.textColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Expand / Collapse Button
                        IconButton(
                            onClick = {
                                isExpanded = !isExpanded
                                onSettingsChanged(settings.copy(isCollapsed = !isExpanded))
                            },
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("expand_subtitle_button")
                        ) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = "Toggle Expand",
                                tint = theme.textColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Close Button (Requirement 3: Clean lifecycle termination)
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("close_subtitle_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = theme.textColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Requirement 4: High-visibility DRM & Silence Fallback Banner
                AnimatedVisibility(visible = isDrmSilenceDetected) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .testTag("drm_silence_fallback_banner"),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
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
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Audio playback protected (DRM). Switch to Mic mode?",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Button(
                                onClick = onSwitchToMic,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .height(34.dp)
                                    .testTag("switch_to_mic_banner_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Switch to Mic mode", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                // Subtitle Content
                AnimatedVisibility(visible = isExpanded) {
                    val stableMinHeight = when (maxLinesCount) {
                        1 -> if (settings.showOriginal) 68.dp else 46.dp
                        2 -> if (settings.showOriginal) 100.dp else 68.dp
                        else -> if (settings.showOriginal) 136.dp else 92.dp
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = stableMinHeight)
                            .padding(14.dp)
                    ) {
                        val textShadow = if (settings.isTextOutlineShadowEnabled) {
                            Shadow(
                                color = Color.Black.copy(alpha = 0.92f),
                                offset = Offset(2f, 2f),
                                blurRadius = settings.textShadowRadius
                            )
                        } else {
                            Shadow.None
                        }

                        // Original foreign language text
                        if (settings.showOriginal) {
                            val visibleOriginals = rollingOriginals.takeLast(maxLinesCount)
                            val origBase = if (visibleOriginals.isNotEmpty()) {
                                visibleOriginals.joinToString("\n")
                            } else {
                                originalText
                            }
                            val displayOrig = if (partialText.isNotBlank()) "$origBase $partialText …" else origBase
                            Text(
                                text = displayOrig,
                                fontSize = (settings.fontSizeSp - 3f).coerceAtLeast(12f).sp,
                                color = theme.originalTextColor,
                                fontStyle = FontStyle.Italic,
                                style = TextStyle(shadow = textShadow),
                                lineHeight = (settings.fontSizeSp + 2f).sp,
                                maxLines = maxLinesCount,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }

                        // Translated Subtitle Output (Primary) - Strict UI Slot Separation:
                        // NEVER render raw partialTextFlow or source speech inside this container.
                        val isProcessingAudio = partialText.isNotBlank()
                        val visibleSentences = rollingSentences.takeLast(maxLinesCount)
                        val displayTranslated = if (isPaused) {
                            "[Translation Paused]"
                        } else if (visibleSentences.isNotEmpty()) {
                            val joined = visibleSentences.joinToString("\n")
                            if (isProcessingAudio) "$joined …" else joined
                        } else {
                            val fallback = if (translatedText.isNotBlank()) translatedText else "…"
                            if (isProcessingAudio) "$fallback …" else fallback
                        }

                        val textColor = if (isProcessingAudio && !isPaused) {
                            theme.textColor.copy(alpha = 0.78f) // Subtle dimming while speech is being processed
                        } else {
                            theme.textColor
                        }

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

                        // Requirement 3: Configurable Display Lines Control (1, 2, or 3 lines max)
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Display Lines: ${settings.maxLines} Line${if (settings.maxLines > 1) "s" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(1, 2, 3).forEach { lines ->
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
                    }
                }
            }
        }
    }
}
