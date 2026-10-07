package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.sin

@Composable
fun AudioVisualizer(
    isCapturing: Boolean,
    audioDb: Float,
    modifier: Modifier = Modifier
) {
    val barCount = 18
    val normalizedDb = (audioDb / 100f).coerceIn(0f, 1f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .testTag("audio_visualizer_bar"),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until barCount) {
            // Compute dynamic height based on decibels and bar phase
            val phaseFactor = sin((i.toDouble() / barCount) * Math.PI).toFloat()
            val targetHeight = if (isCapturing) {
                val wave = 4.dp + (32.dp * (normalizedDb * (0.4f + 0.6f * phaseFactor))).coerceIn(4.dp, 32.dp)
                wave
            } else {
                4.dp
            }

            val animatedHeight by animateFloatAsState(
                targetValue = targetHeight.value,
                animationSpec = tween(durationMillis = 80),
                label = "bar_height_$i"
            )

            val barColor = if (isCapturing) {
                if (normalizedDb > 0.6f) {
                    MaterialTheme.colorScheme.error
                } else if (normalizedDb > 0.2f) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.tertiary
                }
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
            }

            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(animatedHeight.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(barColor)
            )
        }
    }
}
