package com.onlasdan.studio.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

// --- Core Palette Constants ---
val PrimaryPink = Color(0xFFE8457C)
val SoftBlush = Color(0xFFF4B8C6)

// --- Semantic Status Colors (Dimmed & Legible) ---
object SemanticColors {
    val Success = Color(0xFF6FCF97) // Hijau redup
    val Warning = Color(0xFFE5C07B) // Kuning redup
    val Error = Color(0xFFFF6B6B)   // Merah peringatan
    val OnStatus = Color(0xFF000000)
    val SuccessContainer = Color(0xFF132B1D)
    val OnSuccessContainer = Color(0xFFA8E6CF)
    val WarningContainer = Color(0xFF332A15)
    val OnWarningContainer = Color(0xFFFFE08A)
}

// --- Material 3 Noir & Blush Dark Color Scheme ---
val NoirBlushColorScheme = darkColorScheme(
    primary = PrimaryPink,
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF3A1422),
    onPrimaryContainer = SoftBlush,

    secondary = SoftBlush,
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF2E1B22),
    onSecondaryContainer = SoftBlush,

    tertiary = SoftBlush,
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF2E1B22),
    onTertiaryContainer = SoftBlush,

    background = Color(0xFF000000),
    onBackground = Color(0xFFEDE6E8),

    surface = Color(0xFF000000),
    onSurface = Color(0xFFEDE6E8),

    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFFA8A0A3),

    surfaceContainerLowest = Color(0xFF050505),
    surfaceContainerLow = Color(0xFF0A0A0B),
    surfaceContainer = Color(0xFF0F0F10),
    surfaceContainerHigh = Color(0xFF18181A),
    surfaceContainerHighest = Color(0xFF222225),

    outline = Color(0xFF3A3A3D),
    outlineVariant = Color(0xFF242426),

    error = Color(0xFFFF6B6B),
    onError = Color(0xFF000000),
    errorContainer = Color(0xFF3B1212),
    onErrorContainer = Color(0xFFFFB4B4),

    inverseSurface = Color(0xFFEDE6E8),
    inverseOnSurface = Color(0xFF18181A),
    inversePrimary = Color(0xFF9E1F4B),

    scrim = Color(0xFF000000)
)
