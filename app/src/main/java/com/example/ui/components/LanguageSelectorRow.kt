package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.translate.SupportedLanguage

@Composable
fun LanguageSelectorRow(
    sourceLang: SupportedLanguage,
    targetLang: SupportedLanguage,
    availableLanguages: List<SupportedLanguage>,
    targetLanguages: List<SupportedLanguage> = availableLanguages.filter { !it.isAutoDetect },
    detectedLanguageHint: String? = null,
    onSourceSelected: (SupportedLanguage) -> Unit,
    onTargetSelected: (SupportedLanguage) -> Unit,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier
) {
    var sourceDropdownExpanded by remember { mutableStateOf(false) }
    var targetDropdownExpanded by remember { mutableStateOf(false) }
    var swapRotated by remember { mutableStateOf(false) }

    val rotationAngle by animateFloatAsState(
        targetValue = if (swapRotated) 180f else 0f,
        label = "swap_rotate"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("language_selector_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Source Language Selector Box
            Box(modifier = Modifier.weight(1f)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { sourceDropdownExpanded = true }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("source_language_button")
                ) {
                    Text(
                        text = if (sourceLang.isAutoDetect && detectedLanguageHint != null) "Detected: $detectedLanguageHint" else "Source Audio",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (sourceLang.isAutoDetect && detectedLanguageHint != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (sourceLang.isAutoDetect) FontWeight.Bold else FontWeight.Normal
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = sourceLang.flagEmoji, fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = sourceLang.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Select Source",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                DropdownMenu(
                    expanded = sourceDropdownExpanded,
                    onDismissRequest = { sourceDropdownExpanded = false }
                ) {
                    availableLanguages.forEach { lang ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = lang.flagEmoji, fontSize = 18.sp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = lang.displayName,
                                        fontWeight = if (lang.isAutoDetect) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            },
                            onClick = {
                                onSourceSelected(lang)
                                sourceDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            // Swap Button (disabled when source is Auto-Detect)
            IconButton(
                onClick = {
                    if (!sourceLang.isAutoDetect) {
                        swapRotated = !swapRotated
                        onSwap()
                    }
                },
                enabled = !sourceLang.isAutoDetect,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("swap_languages_button")
            ) {
                Icon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = "Swap Languages",
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(rotationAngle),
                    tint = if (sourceLang.isAutoDetect) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.primary
                )
            }

            // Target Language Selector Box
            Box(modifier = Modifier.weight(1f)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { targetDropdownExpanded = true }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("target_language_button"),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = "Translate To",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = targetLang.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = targetLang.flagEmoji, fontSize = 20.sp)
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Select Target",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                DropdownMenu(
                    expanded = targetDropdownExpanded,
                    onDismissRequest = { targetDropdownExpanded = false }
                ) {
                    targetLanguages.forEach { lang ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = lang.flagEmoji, fontSize = 18.sp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = lang.displayName)
                                }
                            },
                            onClick = {
                                onTargetSelected(lang)
                                targetDropdownExpanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}
