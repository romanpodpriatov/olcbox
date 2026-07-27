package org.olcbox.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.ui.icons.PkIcons
import org.olcbox.app.ui.theme.LocalPkPalette

sealed class StartButtonState {
    object Idle : StartButtonState()
    object Loading : StartButtonState()
    object Success : StartButtonState()
}

@Composable
fun StartButton(
    modifier: Modifier = Modifier,
    isActive: Boolean,
    isLoading: Boolean,
    requiresSetup: Boolean = false,
    label: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val pk = LocalPkPalette.current
    // ring + halo: idle=outline/none, loading=indigo, connected=lime glow
    val ringColor by animateColorAsState(
        targetValue = when {
            isActive -> pk.accent
            isLoading -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outline
        },
        label = "buttonRing"
    )
    val haloColor by animateColorAsState(
        targetValue = when {
            isActive -> pk.accentSoft
            isLoading -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
        label = "buttonHalo"
    )
    val contentColor = when {
        isActive -> pk.accent
        isLoading -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // The whole dial is one control, and this is what a screen reader announces
    // for it. Without it VoiceOver read the icon's own label — "Start Icon" —
    // which says neither what the button does nor what state it is in, on an
    // app whose entire surface is this one button.
    val spokenLabel = when {
        requiresSetup -> "Set up a connection"
        isLoading -> "Cancel connecting"
        isActive -> "Disconnect"
        else -> "Connect"
    }
    val haptics = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .size(200.dp)
            .background(color = haloColor, shape = CircleShape)
            .padding(8.dp)
            .background(color = MaterialTheme.colorScheme.surface, shape = CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(color = MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(width = 2.dp, color = ringColor, shape = CircleShape)
            .clickable(
                enabled = enabled,
                onClickLabel = spokenLabel,
                role = Role.Button
            ) {
                // Connecting and disconnecting are the two moments where the
                // screen takes a second to catch up; a tap with no confirmation
                // reads as a tap that missed.
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .semantics { contentDescription = spokenLabel },
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(176.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 4.dp
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = PkIcons.PowerSettingsNew,
                // Decorative: the button above carries the label, and two
                // announcements for one control is worse than none.
                contentDescription = null,
                tint = contentColor.copy(alpha = if (isLoading || !enabled) 0.5f else 1f),
                modifier = Modifier.size(48.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = label ?: when {
                    isLoading -> "STOP"
                    isActive -> "STOP"
                    requiresSetup -> "SETUP"
                    else -> "START"
                },
                color = contentColor.copy(alpha = if (!enabled) 0.7f else 1f),
                style = MaterialTheme.typography.labelLarge,
                fontSize = 20.sp
            )
        }
    }
}
