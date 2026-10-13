package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.overlay.OverlaySettings
import com.example.overlay.OverlayTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun DraggableSubtitleBox(
    originalText: String,
    translatedText: String,
    isTranslatedFinal: Boolean = true,
    utteranceId: Long = 0L,
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
    var isPaused by remember { mutableStateOf(settings.isPaused) }
    var showAdjustPanel by remember { mutableStateOf(false) }

    // Dynamic sizing based on configured line count:
    // 1 line = compact bar, 2 lines = standard cinema, 3 lines = expanded
    val lineCount = settings.maxSubtitleLines.coerceIn(1, 3)
    val overlayMinHeight = when (lineCount) {
        1 -> 60.dp
        2 -> 80.dp
        else -> 106.dp
    }
    val contentVerticalPadding = when (lineCount) {
        1 -> 6.dp
        2 -> 10.dp
        else -> 12.dp
    }

    // Committed finalized lines (history) and active in-progress draft (re-evaluated in-place)
    var committedFinalLines by remember { mutableStateOf(listOf<String>()) }
    var activeDraftLine by remember { mutableStateOf<String?>(null) }
    var rollingOriginalLines by remember { mutableStateOf(listOf<String>()) }

    // Sequence Epoch Tracking to eliminate disappearing subtitles:
    // Discard any incoming translation callback whose utteranceId is older than current active epoch.
    // A partial draft must never overwrite or clear a committed Final sentence.
    var currentActiveEpoch by remember { mutableStateOf(0L) }

    var lastSpeechTimestamp by remember { mutableStateOf(System.currentTimeMillis()) }

    // Graceful Linger & Alpha Fade:
    // When speech stops, subtitles remain fully visible for lingerTimeSeconds, then fade out over 400ms.
    val subtitleAlpha = remember { Animatable(1.0f) }

    // Helper to split streaming/accumulated text into non-empty display lines
    fun extractLines(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isBlank() ||
            trimmed.startsWith("[Translation Paused]") ||
            trimmed.startsWith("Subtitles will stream") ||
            trimmed.startsWith("Translation will appear") ||
            trimmed.startsWith("Waiting for live speech")
        ) {
            return emptyList()
        }
        return trimmed.split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("Waiting") }
    }

    // Update translated display with active draft re-evaluation, epoch gating, and committed final sentences
    LaunchedEffect(translatedText, isTranslatedFinal, utteranceId, lineCount) {
        // Discard any draft whose utteranceId is older than current active epoch
        if (utteranceId > 0 && utteranceId < currentActiveEpoch && !isTranslatedFinal) {
            // Stale draft from older epoch: ignore completely to prevent wiping/overwriting newer text
            return@LaunchedEffect
        }

        val lines = extractLines(translatedText)
        if (lines.isNotEmpty()) {
            val textToUse = lines.last()
            if (isTranslatedFinal) {
                // Committed sentence: advance currentActiveEpoch, roll into history lines, clear active draft
                if (utteranceId >= currentActiveEpoch) {
                    currentActiveEpoch = utteranceId
                }
                val currentHistory = committedFinalLines.toMutableList()
                currentHistory.add(textToUse)
                committedFinalLines = currentHistory.takeLast(lineCount)
                activeDraftLine = null
            } else {
                // Active Draft: rewrites bottom line in-place without clearing committed lines
                if (utteranceId >= currentActiveEpoch) {
                    currentActiveEpoch = utteranceId
                    activeDraftLine = textToUse
                }
            }
            lastSpeechTimestamp = System.currentTimeMillis()
            coroutineScope.launch {
                subtitleAlpha.snapTo(1.0f)
            }
        }
    }

    // Update original text rolling buffer immediately
    LaunchedEffect(originalText, lineCount) {
        val lines = extractLines(originalText)
        if (lines.isNotEmpty()) {
            rollingOriginalLines = lines.takeLast(if (lineCount > 1) 1 else 1)
            lastSpeechTimestamp = System.currentTimeMillis()
            coroutineScope.launch {
                subtitleAlpha.snapTo(1.0f)
            }
        }
    }

    // Reset alpha when live speech partial arrives
    LaunchedEffect(partialText) {
        if (partialText.isNotBlank()) {
            lastSpeechTimestamp = System.currentTimeMillis()
            coroutineScope.launch {
                subtitleAlpha.snapTo(1.0f)
            }
        }
    }

    // Linger and smooth 400ms fade animation
    LaunchedEffect(settings.lingerTimeSeconds, lastSpeechTimestamp) {
        if (settings.lingerTimeSeconds > 0.05f) {
            val lingerMs = (settings.lingerTimeSeconds * 1000L).toLong()
            delay(lingerMs)
            // After linger window expires, apply smooth animated opacity fade (1.0f to 0.0f over 400ms)
            subtitleAlpha.animateTo(
                targetValue = 0.0f,
                animationSpec = tween(durationMillis = 400)
            )
        } else {
            // Keep on Screen (lingerTimeSeconds == 0f)
            subtitleAlpha.snapTo(1.0f)
        }
    }

    val theme = settings.theme
    val configuration = LocalConfiguration.current

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }

        val topSafetyPx = with(density) { 12.dp.toPx() }
        val bottomSafetyPx = with(density) { 16.dp.toPx() }

        val currentBoxW = boxSize.width.toFloat().coerceAtLeast(1f)
        val currentBoxH = boxSize.height.toFloat().coerceAtLeast(1f)

        val minBoundX = 0f
        val maxBoundX = (containerWidthPx - currentBoxW).coerceAtLeast(0f)
        val minBoundY = topSafetyPx
        val maxBoundY = (containerHeightPx - currentBoxH - bottomSafetyPx).coerceAtLeast(minBoundY)

        LaunchedEffect(configuration.orientation, containerWidthPx, containerHeightPx) {
            if (onDragDelta == null) {
                val clampedX = offsetX.value.coerceIn(minBoundX, maxBoundX)
                val clampedY = offsetY.value.coerceIn(minBoundY, maxBoundY)
                if (clampedX != offsetX.value) offsetX.snapTo(clampedX)
                if (clampedY != offsetY.value) offsetY.snapTo(clampedY)
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

        // Modern Cinema Glass Container
        val isLocked = settings.isLocked
        Box(
            modifier = Modifier
                .then(boxOffsetModifier)
                .fillMaxWidth()
                .defaultMinSize(minHeight = overlayMinHeight)
                .padding(horizontal = 8.dp)
                .onSizeChanged { size -> boxSize = size }
                .shadow(
                    elevation = 16.dp,
                    shape = RoundedCornerShape(16.dp),
                    spotColor = Color.Black.copy(alpha = 0.65f)
                )
                .clip(RoundedCornerShape(16.dp))
                .background(
                    color = theme.backgroundColor.copy(alpha = settings.opacity.coerceIn(0.40f, 1f))
                )
                .border(
                    width = 1.2.dp,
                    color = theme.borderColor.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(16.dp)
                )
                .testTag("draggable_floating_subtitle_window")
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Compact, Unobtrusive Header:
                // Drag pill in center, lock toggle on left, and quick-settings gear + pause/play + exit 'x' on right
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(theme.headerColor.copy(alpha = 0.70f))
                        .pointerInput(isLocked, onDragDelta != null) {
                            if (!isLocked) {
                                detectDragGestures(
                                    onDragEnd = {
                                        if (currentOnDragDelta != null) {
                                            currentOnDragEnd?.invoke()
                                        } else {
                                            val currentCenter = offsetX.value + (currentBoxW / 2f)
                                            val screenCenter = containerWidthPx / 2f
                                            val targetSnapX = if (currentCenter < screenCenter) minBoundX else maxBoundX
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
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Lock icon on far left
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
                            tint = if (isLocked) MaterialTheme.colorScheme.primary else theme.textColor.copy(alpha = 0.65f),
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    // Subtle Drag Pill in center
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(theme.textColor.copy(alpha = 0.45f))
                        )
                    }

                    // Right-side action controls: Settings gear, Pause/Play, Exit 'x'
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        // Quick-settings gear icon
                        IconButton(
                            onClick = { showAdjustPanel = !showAdjustPanel },
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("adjust_subtitle_style_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Quick Settings",
                                tint = if (showAdjustPanel) MaterialTheme.colorScheme.primary else theme.textColor.copy(alpha = 0.75f),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // Pause / Play toggle
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
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // Exit 'x' icon
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
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }


                // Subtitle Display Area with Smooth Alpha Fade (graphicsLayer)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = contentVerticalPadding)
                        .graphicsLayer {
                            alpha = subtitleAlpha.value
                        }
                ) {
                    val dropShadow = if (settings.isTextOutlineShadowEnabled) {
                        Shadow(
                            color = if (theme == OverlayTheme.FROST_CLEAN) Color.Black.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.85f),
                            offset = Offset(1.5f, 1.5f),
                            blurRadius = 3f
                        )
                    } else {
                        Shadow.None
                    }

                    val isProcessingAudio = partialText.isNotBlank()

                    // Source Foreign Text (if enabled in Display Mode): Muted slate gray in italic font
                    if (settings.showOriginal) {
                        val displayOrig = when {
                            isProcessingAudio -> {
                                val latest = rollingOriginalLines.lastOrNull().orEmpty()
                                if (latest.isNotBlank()) "$latest $partialText …" else "$partialText …"
                            }
                            rollingOriginalLines.isNotEmpty() -> rollingOriginalLines.last()
                            !originalText.startsWith("Waiting") && originalText.isNotBlank() -> originalText
                            else -> ""
                        }

                        if (displayOrig.isNotBlank()) {
                            TailText(
                                text = AnnotatedString(displayOrig),
                                visibleLines = 1,
                                lineHeight = (settings.fontSizeSp * 1.15f).sp,
                                style = TextStyle(
                                    fontSize = (settings.fontSizeSp - 3.5f).coerceAtLeast(11f).sp,
                                    color = theme.originalTextColor,
                                    fontStyle = FontStyle.Italic,
                                    shadow = dropShadow
                                ),
                                modifier = Modifier.padding(bottom = 3.dp)
                            )
                        }
                    }

                    // Rolling FIFO Subtitle Display Engine:
                    // Breaks incoming streaming text into a rolling FIFO buffer of lines rather
                    // than waiting for full sentence endpoints.
                    // The newest spoken words always appear on the bottom line, pushing top lines up.
                    if (isPaused) {
                        Text(
                            text = "[Translation Paused]",
                            fontSize = settings.fontSizeSp.sp,
                            fontWeight = FontWeight.Bold,
                            color = theme.textColor.copy(alpha = 0.70f),
                            style = TextStyle(shadow = dropShadow),
                            lineHeight = (settings.fontSizeSp * 1.35f).sp,
                            maxLines = lineCount,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.testTag("floating_translated_subtitle_text")
                        )
                    } else if (committedFinalLines.isEmpty() && activeDraftLine == null) {
                        Text(
                            text = "Subtitles will stream here...",
                            fontSize = settings.fontSizeSp.sp,
                            fontWeight = FontWeight.Bold,
                            color = theme.textColor.copy(alpha = 0.85f),
                            style = TextStyle(shadow = dropShadow),
                            lineHeight = (settings.fontSizeSp * 1.35f).sp,
                            maxLines = lineCount,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.testTag("floating_translated_subtitle_text")
                        )
                    } else {
                        // Committed lines (muted) with the newest line (bold) at the bottom. Lines wrap,
                        // and only the last lineCount lines are visible, so a long live sentence keeps
                        // showing its newest words instead of being cut off after the first line.
                        val lines = if (activeDraftLine != null) {
                            committedFinalLines.takeLast(lineCount) + activeDraftLine!!
                        } else {
                            committedFinalLines.takeLast(lineCount)
                        }
                        val subtitleText = buildAnnotatedString {
                            lines.forEachIndexed { index, lineText ->
                                if (index > 0) append("\n")
                                val isNewest = index == lines.lastIndex
                                withStyle(
                                    SpanStyle(
                                        fontSize = if (isNewest) settings.fontSizeSp.sp else (settings.fontSizeSp * 0.94f).sp,
                                        fontWeight = if (isNewest) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isNewest) theme.textColor else theme.textColor.copy(alpha = 0.75f)
                                    )
                                ) {
                                    append(lineText)
                                }
                            }
                        }
                        TailText(
                            text = subtitleText,
                            visibleLines = lineCount,
                            lineHeight = (settings.fontSizeSp * 1.30f).sp,
                            style = TextStyle(shadow = dropShadow),
                            modifier = Modifier.testTag("floating_translated_subtitle_text")
                        )
                    }
                }

                // Intuitive Quick-Settings Drawer/Sheet
                AnimatedVisibility(visible = showAdjustPanel) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.98f))
                            .padding(14.dp)
                    ) {
                        Text(
                            text = "Overlay Quick Settings",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // 1. Text Size Quick Toggle: 3 quick pill presets: Small (14sp), Normal (17sp), Large (20sp)
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Text Size",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val presets = listOf(
                                    Triple("Small (14sp)", 14f, "size_small_button"),
                                    Triple("Normal (17sp)", 17f, "size_normal_button"),
                                    Triple("Large (20sp)", 20f, "size_large_button")
                                )
                                presets.forEach { (label, size, tag) ->
                                    val isSelected = when (size) {
                                        14f -> settings.fontSizeSp <= 15.5f
                                        17f -> settings.fontSizeSp > 15.5f && settings.fontSizeSp <= 18.5f
                                        else -> settings.fontSizeSp > 18.5f
                                    }
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
                                                else Color.Gray.copy(alpha = 0.25f),
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .clickable {
                                                onSettingsChanged(settings.copy(fontSizeSp = size))
                                            }
                                            .padding(vertical = 7.dp)
                                            .testTag(tag),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Visible Lines Selector: 3 quick pills [ 1 Line ], [ 2 Lines ], [ 3 Lines ]
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Visible Lines",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val lineOptions = listOf(
                                    Triple("1 Line", 1, "lines_1_button"),
                                    Triple("2 Lines", 2, "lines_2_button"),
                                    Triple("3 Lines", 3, "lines_3_button")
                                )
                                lineOptions.forEach { (label, count, tag) ->
                                    val isSelected = settings.maxSubtitleLines == count
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
                                                else Color.Gray.copy(alpha = 0.25f),
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .clickable {
                                                onSettingsChanged(
                                                    settings.copy(
                                                        maxSubtitleLines = count,
                                                        maxLines = count
                                                    )
                                                )
                                            }
                                            .padding(vertical = 7.dp)
                                            .testTag(tag),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // 2. Display Mode: Toggle between "Dual Line (Original + Translation)" and "Translation Only"
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Display Mode",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val modes = listOf(
                                    Pair("Dual Line", true),
                                    Pair("Translation Only", false)
                                )
                                modes.forEach { (label, showOrig) ->
                                    val isSelected = settings.showOriginal == showOrig
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
                                                else Color.Gray.copy(alpha = 0.25f),
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .clickable {
                                                onSettingsChanged(settings.copy(showOriginal = showOrig))
                                            }
                                            .padding(vertical = 7.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Modernized Theme Selector: "Cinema Dark" (default), "OLED Black", "Frost Clean", "Neon Cyber"
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Theme",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val displayThemes = listOf(
                                    OverlayTheme.CINEMA_DARK,
                                    OverlayTheme.OLED_BLACK,
                                    OverlayTheme.FROST_CLEAN,
                                    OverlayTheme.NEON_CYBER
                                )
                                displayThemes.forEach { th ->
                                    val isSelected = settings.theme == th || (th == OverlayTheme.CINEMA_DARK && settings.theme == OverlayTheme.AMBER_GOLD)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(th.backgroundColor)
                                            .border(
                                                width = if (isSelected) 2.5.dp else 1.dp,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f),
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .clickable {
                                                onSettingsChanged(settings.copy(theme = th))
                                            }
                                            .padding(vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = th.title.split(" ")[0],
                                            style = TextStyle(
                                                color = th.textColor,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // 3. Subtitle Linger Slider: Intuitive feedback (2.0s, 3.5s, 5.0s, or "Keep on Screen")
                        val lingerLabel = if (settings.lingerTimeSeconds <= 0.05f) {
                            "Keep on Screen"
                        } else {
                            "${"%.1f".format(settings.lingerTimeSeconds)}s"
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.width(110.dp)) {
                                Text(
                                    text = "Subtitle Linger",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = lingerLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Slider(
                                value = settings.lingerTimeSeconds,
                                onValueChange = { onSettingsChanged(settings.copy(lingerTimeSeconds = it)) },
                                valueRange = 0.0f..6.0f,
                                steps = 11,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // 4. Background Opacity Slider: 40% to 100% transparency
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.width(110.dp)) {
                                Text(
                                    text = "Opacity",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${(settings.opacity * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Slider(
                                value = settings.opacity,
                                onValueChange = { onSettingsChanged(settings.copy(opacity = it)) },
                                valueRange = 0.40f..1.0f,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Text Outline & Shadow Toggle
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Contrast Drop Shadow",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Switch(
                                checked = settings.isTextOutlineShadowEnabled,
                                onCheckedChange = { onSettingsChanged(settings.copy(isTextOutlineShadowEnabled = it)) },
                                modifier = Modifier.testTag("toggle_overlay_text_shadow")
                            )
                        }

                        // Lock Overlay Positioning Toggle
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Lock Position",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
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

/**
 * Shows the end of [text]. The text wraps normally, but only its last [visibleLines] lines are
 * visible; earlier lines are clipped off the top. Live captions therefore always show the newest
 * words, however long the sentence gets.
 */
@Composable
private fun TailText(
    text: AnnotatedString,
    visibleLines: Int,
    lineHeight: TextUnit,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    val maxHeight = with(LocalDensity.current) { lineHeight.toDp() } * visibleLines
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .clipToBounds()
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
