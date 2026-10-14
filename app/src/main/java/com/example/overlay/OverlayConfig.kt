package com.example.overlay

import androidx.compose.runtime.Immutable
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

/**
 * How subtitles are presented (the three standard caption styles):
 * - MOVIE (pop-on): a card fills the box, holds for reading, then is replaced.
 * - ROLL_UP: sentences stack, newest at the bottom; older lines roll up and fade. Can be
 *   scrolled back. The usual style for live TV and fast speech.
 * - LIVE (paint-on): the current sentence is rewritten in place as words arrive.
 * [code] is what is stored in settings.
 */
enum class SubtitleStyle(val code: Int) {
    MOVIE(0), ROLL_UP(1), LIVE(2);

    companion object {
        fun fromCode(code: Int): SubtitleStyle = entries.firstOrNull { it.code == code } ?: ROLL_UP
    }
}

// All fields are vals of stable types; marking it lets Compose skip SubtitleOverlay when unchanged
@Immutable
data class OverlaySettings(
    val theme: OverlayTheme = OverlayTheme.CINEMA_DARK,
    val fontSizeSp: Float = 17f,
    val opacity: Float = 0.95f,
    val showOriginal: Boolean = true,
    val isPaused: Boolean = false,
    val isLocked: Boolean = false,
    val maxSubtitleLines: Int = 2, // 1 to 4 visible lines
    val maxLines: Int = maxSubtitleLines, // Backwards compatibility alias
    val lingerTimeSeconds: Float = 3.5f, // 1.0s to 10.0s, or 0f for 'Keep on Screen'
    val isTextOutlineShadowEnabled: Boolean = true,
    val textShadowRadius: Float = 3.0f,
    val style: SubtitleStyle = SubtitleStyle.ROLL_UP,
    // Window width chosen by resizing, in dp; 0 = automatic
    val widthDp: Int = 0,
    // Accuracy recognition mode: subtitles arrive per phrase and stay up longer
    val isAccuracyMode: Boolean = false
)
