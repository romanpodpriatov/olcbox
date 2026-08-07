package org.olcbox.app.ui.features.locations.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.olcbox.app.ui.icons.PkIcons

/**
 * Fetch this subscription again. Circling arrows, spinning while it runs.
 *
 * This button used to carry the label **"Ping"** while being the only control
 * on the header — which is impossible to argue with when a user says they
 * pressed ping and their subscription refreshed. The two actions have separate
 * buttons now and neither has any text: one asks the provider for the current
 * server list, the other times a request, and side by side the icons say which
 * is which.
 */
@Composable
fun SubscriptionRefreshButton(
    isRefreshing: Boolean,
    onClick: () -> Unit,
    tint: Color
) {
    val rotation by if (isRefreshing) {
        rememberInfiniteTransition(label = "subscriptionRefresh").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "subscriptionRefreshSpin"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    IconButton(onClick = onClick, enabled = !isRefreshing, modifier = Modifier.size(40.dp)) {
        Icon(
            imageVector = Icons.Rounded.Refresh,
            contentDescription = "Update server list",
            tint = tint,
            modifier = Modifier
                .size(20.dp)
                .rotate(rotation)
        )
    }
}

/**
 * Measure latency. A bolt, because what it reports is speed.
 *
 * Always offered, including where nothing can be measured this instant: a
 * control that appears and disappears is harder to understand than one that
 * always answers. When there is nothing to measure it says so — see
 * `HomeScreen.refreshHttpPings` — rather than quietly doing nothing.
 */
@Composable
fun LatencyButton(
    isRunning: Boolean,
    onClick: () -> Unit,
    tint: Color
) {
    val pulse by if (isRunning) {
        rememberInfiniteTransition(label = "latencyProbe").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "latencyProbePulse"
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }

    IconButton(onClick = onClick, enabled = !isRunning, modifier = Modifier.size(40.dp)) {
        Icon(
            imageVector = PkIcons.Bolt,
            contentDescription = "Measure latency",
            tint = tint.copy(alpha = pulse),
            modifier = Modifier.size(20.dp)
        )
    }
}
