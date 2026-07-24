package org.olcbox.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

@Composable
actual fun AppTheme(
    useDynamicColor: Boolean,
    content: @Composable () -> Unit
) {
    // ProofKit is a dark-only brand: ignore system setting and dynamic color.
    val isDarkState = remember { mutableStateOf(true) }
    val typography = getAppTypography()

    CompositionLocalProvider(
        LocalThemeIsDark provides isDarkState,
        LocalPkPalette provides PkPalette()
    ) {
        MaterialTheme(
            colorScheme = OlcboxDarkColorScheme,
            typography = typography
        ) {
            ProvideTextStyle(MaterialTheme.typography.bodyMedium, content)
        }
    }
}
