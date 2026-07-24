package org.olcbox.app.ui.components.kit

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.olcbox.app.AppInfo
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.ui.theme.LocalPkPalette

/** Single source of in-UI brand strings (GeneratedAppInfo.NAME stays "olcbox"). */
object PkBrand {
    const val name = "ProofKit"
    const val tagline = "decentralized VPN · olcrtc / reality / hy2"

    /** Where a user with no subscription is sent to get one. */
    const val siteUrl = "https://proofkit.org"
}

/** Pure so it is unit-testable: "PROOFKIT · v1.0.209 · OLCBOX CORE". */
fun pkVersionLine(info: AppInfo): String {
    val version = info.version.removePrefix("v")
    return "${PkBrand.name.uppercase()} · v$version · OLCBOX CORE"
}

enum class PkStatus { Idle, Active, Warn, Error }

/** Site `.eyebrow`: mono uppercase label with a lime dot (optionally pulsing). */
@Composable
fun PkSectionLabel(text: String, pulse: Boolean = false) {
    val pk = LocalPkPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        PkDot(color = pk.accent, halo = pk.accentSoft, pulse = pulse)
        Spacer(Modifier.width(8.dp))
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = pk.textDim
        )
    }
}

@Composable
private fun PkDot(color: Color, halo: Color, pulse: Boolean) {
    val scale = if (pulse) {
        val t = rememberInfiniteTransition(label = "pkDot")
        val s by t.animateFloat(
            initialValue = 1f,
            targetValue = 1.8f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pkDotScale"
        )
        s
    } else 1f
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(14.dp)
                .scale(scale)
                .background(halo, CircleShape)
        )
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
    }
}

/** ProofKit card: surface #0F1117, 1dp #1E2030 border, radius 16. */
@Composable
fun PkCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content
    )
}

/** Sunken terminal well (#0A0C14) for logs/code content. */
@Composable
fun PkCardSunken(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content
    )
}

/** TWA `.status-eyebrow`: dot + mono text pill. */
@Composable
fun PkStatusPill(state: PkStatus, text: String, modifier: Modifier = Modifier) {
    val pk = LocalPkPalette.current
    val (dot, halo) = when (state) {
        PkStatus.Active -> pk.accent to pk.accentSoft
        PkStatus.Warn -> pk.accent2 to pk.accent2Soft
        PkStatus.Error -> pk.danger to Color(0x1FF43F5E)
        PkStatus.Idle -> pk.textMuted to Color.Transparent
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PkDot(color = dot, halo = halo, pulse = state == PkStatus.Active)
            Text(
                text = text.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = if (state == PkStatus.Idle) pk.textMuted else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** Home-screen footer: PROOFKIT · v<version> · OLCBOX CORE. */
@Composable
fun PkVersionFooter(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = pkVersionLine(CurrentAppInfo.value),
            style = MaterialTheme.typography.labelSmall,
            color = LocalPkPalette.current.textMuted
        )
    }
}

/** Site background: faint 48dp grid + indigo radial glow at the top. */
@Composable
fun pkScreenBackground(): Modifier {
    val grid = LocalPkPalette.current.gridLine
    val glow = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    val step = with(LocalDensity.current) { 48.dp.toPx() }
    return Modifier.drawBehind {
        if (size.width <= 0f || size.height <= 0f) return@drawBehind
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(glow, Color.Transparent),
                center = Offset(size.width / 2f, -size.height * 0.1f),
                radius = size.width * 0.9f
            )
        )
        var x = 0f
        while (x <= size.width) {
            drawLine(grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            x += step
        }
        var y = 0f
        while (y <= size.height) {
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += step
        }
    }
}
