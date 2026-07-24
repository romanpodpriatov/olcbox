package org.olcbox.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import multiplatform_app.sharedui.generated.resources.Res
import multiplatform_app.sharedui.generated.resources.ibm_plex_mono_medium
import multiplatform_app.sharedui.generated.resources.ibm_plex_mono_regular
import multiplatform_app.sharedui.generated.resources.ibm_plex_mono_semi_bold
import multiplatform_app.sharedui.generated.resources.ibm_plex_sans_medium
import multiplatform_app.sharedui.generated.resources.ibm_plex_sans_regular
import multiplatform_app.sharedui.generated.resources.ibm_plex_sans_semi_bold
import multiplatform_app.sharedui.generated.resources.space_grotesk_bold
import multiplatform_app.sharedui.generated.resources.space_grotesk_medium
import multiplatform_app.sharedui.generated.resources.space_grotesk_semi_bold
import org.jetbrains.compose.resources.Font

/** ProofKit type stack: Space Grotesk (display) · IBM Plex Sans (body) · IBM Plex Mono (labels). */
@Composable
fun getAppTypography(): Typography {
    val grotesk = FontFamily(
        Font(Res.font.space_grotesk_medium, weight = FontWeight.Medium),
        Font(Res.font.space_grotesk_semi_bold, weight = FontWeight.SemiBold),
        Font(Res.font.space_grotesk_bold, weight = FontWeight.Bold)
    )
    val plexSans = FontFamily(
        Font(Res.font.ibm_plex_sans_regular, weight = FontWeight.Normal),
        Font(Res.font.ibm_plex_sans_medium, weight = FontWeight.Medium),
        Font(Res.font.ibm_plex_sans_semi_bold, weight = FontWeight.SemiBold)
    )
    val plexMono = FontFamily(
        Font(Res.font.ibm_plex_mono_regular, weight = FontWeight.Normal),
        Font(Res.font.ibm_plex_mono_medium, weight = FontWeight.Medium),
        Font(Res.font.ibm_plex_mono_semi_bold, weight = FontWeight.SemiBold)
    )

    val d = Typography()
    return Typography(
        displayLarge = d.displayLarge.copy(fontFamily = grotesk),
        displayMedium = d.displayMedium.copy(fontFamily = grotesk),
        displaySmall = d.displaySmall.copy(fontFamily = grotesk),
        headlineLarge = d.headlineLarge.copy(fontFamily = grotesk),
        headlineMedium = d.headlineMedium.copy(fontFamily = grotesk),
        headlineSmall = d.headlineSmall.copy(fontFamily = grotesk),
        titleLarge = d.titleLarge.copy(fontFamily = grotesk, fontWeight = FontWeight.SemiBold),
        titleMedium = d.titleMedium.copy(fontFamily = grotesk, fontWeight = FontWeight.SemiBold),
        titleSmall = d.titleSmall.copy(fontFamily = grotesk),
        bodyLarge = d.bodyLarge.copy(fontFamily = plexSans),
        bodyMedium = d.bodyMedium.copy(fontFamily = plexSans),
        bodySmall = d.bodySmall.copy(fontFamily = plexSans),
        labelLarge = d.labelLarge.copy(fontFamily = plexMono),
        labelMedium = d.labelMedium.copy(fontFamily = plexMono, letterSpacing = 1.sp),
        labelSmall = d.labelSmall.copy(fontFamily = plexMono, letterSpacing = 0.8.sp)
    )
}
