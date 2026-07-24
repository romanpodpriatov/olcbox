package org.olcbox.app.ui.features.home.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.olcbox.app.ui.components.kit.PkStatus
import org.olcbox.app.ui.components.kit.PkStatusPill

/**
 * [transportLabel] names what is carrying traffic right now (Reality, Hysteria2,
 * XHTTP, olcRTC). With several transports per exit, "connected" alone does not say
 * which one survived — the pill is the only place a user can see it.
 */
@Composable
fun RelayStatus(
    isActive: Boolean,
    requiresSetup: Boolean = false,
    transportLabel: String? = null,
    exitName: String? = null,
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
        requiresSetup -> "Import a subscription to start"
        else -> "Disconnected"
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        PkStatusPill(state = state, text = label)
        Spacer(Modifier.height(8.dp))
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
