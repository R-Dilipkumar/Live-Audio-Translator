package com.example.overlay

import androidx.compose.ui.graphics.Color

enum class OverlayTheme(
    val title: String,
    val backgroundColor: Color,
    val textColor: Color,
    val originalTextColor: Color,
    val headerColor: Color,
    val borderColor: Color
) {
    AMBER_GOLD(
        title = "Anime Amber",
        backgroundColor = Color(0xFFF59E0B),
        textColor = Color(0xFF1E1E1E),
        originalTextColor = Color(0xFF451A03),
        headerColor = Color(0xFFD97706),
        borderColor = Color(0xFFB45309)
    ),
    CLASSIC_CINEMA(
        title = "Cinema Dark",
        backgroundColor = Color(0xE6111827),
        textColor = Color(0xFFF9FAFB),
        originalTextColor = Color(0xFF9CA3AF),
        headerColor = Color(0xFF1F2937),
        borderColor = Color(0xFF374151)
    ),
    MANGA_BUBBLE(
        title = "Manga Clean",
        backgroundColor = Color(0xF7FFFFFF),
        textColor = Color(0xFF0F172A),
        originalTextColor = Color(0xFF475569),
        headerColor = Color(0xFFE2E8F0),
        borderColor = Color(0xFFCBD5E1)
    ),
    HIGH_CONTRAST(
        title = "High Contrast Yellow",
        backgroundColor = Color(0xFA000000),
        textColor = Color(0xFFFACC15),
        originalTextColor = Color(0xFF94A3B8),
        headerColor = Color(0xFF18181B),
        borderColor = Color(0xFFEAB308)
    )
}

data class OverlaySettings(
    val theme: OverlayTheme = OverlayTheme.AMBER_GOLD,
    val fontSizeSp: Float = 17f,
    val opacity: Float = 0.95f,
    val showOriginal: Boolean = true,
    val isCollapsed: Boolean = false,
    val isPaused: Boolean = false,
    val maxLines: Int = 2,
    val lingerTimeSeconds: Float = 3.5f, // 1.0s to 10.0s, or 0f for 'Keep until next speech'
    val isTextOutlineShadowEnabled: Boolean = true,
    val textShadowRadius: Float = 4.0f
)
