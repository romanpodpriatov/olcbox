package org.olcbox.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

// ProofKit Cypherpunk palette (site tokens → M3 roles). Dark-only brand — there is
// no light scheme: every AppTheme actual pins this one.
internal val OlcboxDarkColorScheme = darkColorScheme(
    primary = Color(0xFF6675FF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF1A1E3D),
    onPrimaryContainer = Color(0xFF8190FF),
    inversePrimary = Color(0xFF6675FF),
    secondary = Color(0xFF8B93A8),
    onSecondary = Color(0xFF07080D),
    secondaryContainer = Color(0xFF161822),
    onSecondaryContainer = Color(0xFFE8ECF2),
    tertiary = Color(0xFFB5F23D),
    onTertiary = Color(0xFF0C1300),
    tertiaryContainer = Color(0xFF232B10),
    onTertiaryContainer = Color(0xFFB5F23D),
    background = Color(0xFF07080D),
    onBackground = Color(0xFFE8ECF2),
    surface = Color(0xFF07080D),
    onSurface = Color(0xFFE8ECF2),
    surfaceVariant = Color(0xFF161822),
    onSurfaceVariant = Color(0xFF8B93A8),
    surfaceContainerLowest = Color(0xFF05060A),
    surfaceContainerLow = Color(0xFF0A0C14),
    surfaceContainer = Color(0xFF0F1117),
    surfaceContainerHigh = Color(0xFF161822),
    surfaceContainerHighest = Color(0xFF1D2030),
    inverseSurface = Color(0xFFE8ECF2),
    inverseOnSurface = Color(0xFF07080D),
    outline = Color(0xFF2A2D42),
    outlineVariant = Color(0xFF1E2030),
    error = Color(0xFFF43F5E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF3D141C),
    onErrorContainer = Color(0xFFF43F5E),
    scrim = Color(0xFF000000)
)
