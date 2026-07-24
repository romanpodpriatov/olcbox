package org.olcbox.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** ProofKit tokens with no Material 3 slot (site: --accent/--accent-2/--success/…). */
data class PkPalette(
    val accent: Color = Color(0xFFB5F23D),
    val accentSoft: Color = Color(0x1AB5F23D),
    val accent2: Color = Color(0xFFFFB547),
    val accent2Soft: Color = Color(0x1AFFB547),
    val success: Color = Color(0xFF4ADE80),
    val successSoft: Color = Color(0x1F4ADE80),
    val warning: Color = Color(0xFFF59E0B),
    val danger: Color = Color(0xFFF43F5E),
    val textDim: Color = Color(0xFF8B93A8),
    val textMuted: Color = Color(0xFF555A72),
    val gridLine: Color = Color(0x06FFFFFF),
    val tonBlue: Color = Color(0xFF0098EA)
)

val LocalPkPalette = staticCompositionLocalOf { PkPalette() }
