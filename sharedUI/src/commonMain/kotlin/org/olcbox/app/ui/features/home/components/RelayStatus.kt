package org.olcbox.app.ui.features.home.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.olcbox.app.ui.components.kit.PkStatus
import org.olcbox.app.ui.components.kit.PkStatusPill
import org.olcbox.app.ui.icons.PkIcons
import org.olcbox.app.ui.theme.LocalPkPalette
import org.olcbox.app.util.formatByteSize
import org.olcbox.app.util.nowMillis
import org.olcbox.app.vpn.TrafficCounters

/**
 * [transportLabel] names what is carrying traffic right now (Reality, Hysteria2,
 * XHTTP, olcRTC). With several transports per exit, "connected" alone does not say
 * which one survived — the pill is the only place a user can see it.
 *
 * [connectedSince] is when the session came up, in epoch milliseconds. Null when
 * there is no session, and null on a build whose platform cannot say — the timer
 * simply does not appear rather than showing a zero that never moves.
 */
@Composable
fun RelayStatus(
    isActive: Boolean,
    requiresSetup: Boolean = false,
    transportLabel: String? = null,
    exitName: String? = null,
    connectedSince: Long? = null,
    traffic: TrafficCounters? = null,
    modifier: Modifier = Modifier
) {
    val state = if (isActive) PkStatus.Active else PkStatus.Idle
    val label = when {
        isActive -> listOfNotNull("relay active", transportLabel).joinToString(" · ")
        requiresSetup -> "no location"
        else -> "relay idle"
    }
    val caption = when {
        isActive -> exitName?.let { "Traffic exits via $it" } ?: "Connected — traffic is routed"
        requiresSetup -> "Import a server list to start"
        else -> "Disconnected"
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        PkStatusPill(state = state, text = label)

        if (isActive && connectedSince != null) {
            Spacer(Modifier.height(10.dp))
            SessionTimer(since = connectedSince)
        }

        if (isActive && traffic != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "↓ ${formatByteSize(traffic.bytesIn)}   ↑ ${formatByteSize(traffic.bytesOut)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Why the last attempt failed, or what is blocking a start.
 *
 * Kept as its own element rather than folded into the caption above: it has to
 * survive being three lines long — an extension's own account of its death is
 * not a phrase — and the caption is a single line by design.
 */
@Composable
fun RelayNotice(text: String, modifier: Modifier = Modifier) {
    val pk = LocalPkPalette.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        border = BorderStroke(1.dp, pk.danger)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = PkIcons.PriorityHigh,
                contentDescription = null,
                tint = pk.danger,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/**
 * How long this session has been up, ticking once a second.
 *
 * Mono, because a proportional face makes the digits jump sideways every time a
 * 1 becomes a 2 — on a number that changes every second, that reads as the whole
 * screen twitching.
 */
@Composable
private fun SessionTimer(since: Long) {
    val pk = LocalPkPalette.current
    // Elapsed time is not derived from anything Compose can observe, so it is
    // resampled on a timer rather than recomputed on recomposition.
    var now by remember(since) { mutableStateOf(nowMillis()) }
    LaunchedEffect(since) {
        while (true) {
            delay(1_000)
            now = nowMillis()
        }
    }

    Text(
        text = formatSessionDuration(now - since),
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 26.sp, letterSpacing = 2.sp),
        color = pk.accent
    )
}

/**
 * `MM:SS` under an hour, `H:MM:SS` above it — the leading unit is never padded,
 * so a session does not read as `01:02:03` on its second hour.
 *
 * A negative span means the clock moved backwards under us (a manual time
 * change, an NTP correction); it shows as zero rather than as a minus sign.
 */
internal fun formatSessionDuration(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0L)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) {
        "$hours:${minutes.padded()}:${secs.padded()}"
    } else {
        "${minutes.padded()}:${secs.padded()}"
    }
}

private fun Long.padded(): String = if (this < 10) "0$this" else toString()

