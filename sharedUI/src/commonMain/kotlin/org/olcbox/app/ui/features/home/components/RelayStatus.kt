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

@Composable
fun RelayStatus(
    isActive: Boolean,
    requiresSetup: Boolean = false,
    modifier: Modifier = Modifier
) {
    val state = if (isActive) PkStatus.Active else PkStatus.Idle
    val label = when {
        isActive -> "relay active"
        requiresSetup -> "no location"
        else -> "relay idle"
    }
    val caption = when {
        isActive -> "Connected — traffic is routed"
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
