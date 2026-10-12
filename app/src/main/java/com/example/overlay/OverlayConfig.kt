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
    CINEMA_DARK(
        title = "Cinema Dark",
        backgroundColor = Color(0xDD0F172A), // Translucent obsidian / deep charcoal
        textColor = Color(0xFFF8FAFC),       // Crisp high-contrast pure white
        originalTextColor = Color(0xFF94A3B8), // Muted slate gray
        headerColor = Color(0xEB1E293B),
        borderColor = Color(0x3338BDF8)
    ),
    OLED_BLACK(
        title = "OLED Black",
        backgroundColor = Color(0xF2000000),
        textColor = Color(0xFFFFFFFF),
        originalTextColor = Color(0xFF9CA3AF),
        headerColor = Color(0xFF111111),
        borderColor = Color(0x33FFFFFF)
    ),
    FROST_CLEAN(
        title = "Frost Clean",
        backgroundColor = Color(0xDCE2E8F0), // Translucent white with dark text
        textColor = Color(0xFF0F172A),
        originalTextColor = Color(0xFF475569),
        headerColor = Color(0xE6F8FAFC),
        borderColor = Color(0x4094A3B8)
    ),
    NEON_CYBER(
        title = "Neon Cyber",
        backgroundColor = Color(0xDE090D16),
        textColor = Color(0xFF00F5FF),
        originalTextColor = Color(0xFFF43F5E),
        headerColor = Color(0xEB0F172A),
        borderColor = Color(0x6000F5FF)
    ),
    @Deprecated("Deprecated in favor of CINEMA_DARK", ReplaceWith("CINEMA_DARK"))
    AMBER_GOLD(
        title = "Anime Amber",
        backgroundColor = Color(0xDD0F172A),
        textColor = Color(0xFFF8FAFC),
        originalTextColor = Color(0xFF94A3B8),
        headerColor = Color(0xEB1E293B),
        borderColor = Color(0x3338BDF8)
    )
}

data class OverlaySettings(
    val theme: OverlayTheme = OverlayTheme.CINEMA_DARK,
    val fontSizeSp: Float = 17f,
    val opacity: Float = 0.95f,
    val showOriginal: Boolean = true,
    val isCollapsed: Boolean = false,
    val isPaused: Boolean = false,
    val isLocked: Boolean = false,
    val maxSubtitleLines: Int = 2, // 1, 2, or 3 visible lines
    val maxLines: Int = maxSubtitleLines, // Backwards compatibility alias
    val lingerTimeSeconds: Float = 3.5f, // 1.0s to 10.0s, or 0f for 'Keep on Screen'
    val isTextOutlineShadowEnabled: Boolean = true,
    val textShadowRadius: Float = 3.0f
)
