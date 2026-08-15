package org.olcbox.app.ui.features.home.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.olcbox.app.ui.icons.PkIcons
import org.olcbox.app.ui.theme.LocalPkPalette

/**
 * Why the last attempt failed, or what is blocking a start.
 *
 * Kept as its own element rather than folded into the status strip's meta line:
 * it has to survive being three lines long — an extension's own account of its
 * death is not a phrase — and that line is a single line by design.
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
 * `MM:SS` under an hour, `H:MM:SS` above it — the leading unit is never padded,
 * so a session does not read as `01:02:03` on its second hour.
 *
 * A negative span means the clock moved backwards under us (a manual time
 * change, an NTP correction); it shows as zero rather than as a minus sign.
 *
 * Rendered in mono, because a proportional face makes the digits jump sideways
 * every time a 1 becomes a 2 — on a number that changes every second, that reads
 * as the whole screen twitching.
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
