package org.olcbox.app.ui.features.home.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.ui.features.locations.PingsState
import org.olcbox.app.ui.features.locations.components.LocationRow
import org.olcbox.app.ui.features.locations.components.RefreshButton
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Composable
fun LocationSelectorScreen(
    modifier: Modifier = Modifier,
    onRefreshClick: () -> Unit,
    onAddSubscriptionClick: () -> Unit,
    onAddLocationClick: () -> Unit,
    locations: List<LocationItem>,
    selectedLocationId: String?,
    pingsState: PingsState,
    onLocationSelected: (String) -> Unit,
    onLocationSettingsClick: (String) -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        val subscriptionLocations = locations.filter { !it.subscriptionUrl.isNullOrBlank() }
        val subscriptionGroups = subscriptionLocations
            .groupBy { it.subscriptionGroupKey() }
            .values
            .toList()
        val customLocations = locations.filter { it.subscriptionUrl.isNullOrBlank() }

        if (locations.isEmpty()) {
            RelaySetupCard(
                onAddSubscriptionClick = onAddSubscriptionClick,
                onAddLocationClick = onAddLocationClick
            )
            return@Column
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            subscriptionGroups.forEachIndexed { index, group ->
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SubscriptionGroupHeader(
                            locations = group,
                            modifier = Modifier.weight(1f)
                        )
                        if (index == 0) {
                            RefreshButton(
                                state = pingsState,
                                onClick = onRefreshClick,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        group.forEach { location ->
                            LocationSelectorRow(
                                location = location,
                                selectedLocationId = selectedLocationId,
                                pingsState = pingsState,
                                onLocationSelected = onLocationSelected,
                                onLocationSettingsClick = onLocationSettingsClick
                            )
                        }
                    }
                }
            }

            if (customLocations.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LocationGroupHeader(
                            title = "Custom locations",
                            modifier = Modifier.weight(1f)
                        )
                        if (subscriptionGroups.isEmpty()) {
                            RefreshButton(
                                state = pingsState,
                                onClick = onRefreshClick,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        customLocations.forEach { location ->
                            LocationSelectorRow(
                                location = location,
                                selectedLocationId = selectedLocationId,
                                pingsState = pingsState,
                                onLocationSelected = onLocationSelected,
                                onLocationSettingsClick = onLocationSettingsClick
                            )
                        }
                    }
                }
            }

            FilledTonalButton(
                onClick = onAddLocationClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add custom location", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }

            if (subscriptionLocations.isEmpty()) {
                FilledTonalButton(
                    onClick = onAddSubscriptionClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add subscription", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun RelaySetupCard(
    onAddSubscriptionClick: () -> Unit,
    onAddLocationClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Add relay setup",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp)
        )

        SetupActionRow(
            title = "Add subscription",
            subtitle = "Scan QR, paste URI, or import file",
            icon = Icons.Outlined.QrCodeScanner,
            prominent = true,
            onClick = onAddSubscriptionClick
        )

        SetupActionRow(
            title = "Create custom location",
            subtitle = "Enter room, key, carrier, and transport",
            icon = Icons.Outlined.Add,
            onClick = onAddLocationClick
        )
    }
}

@Composable
private fun SetupActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    prominent: Boolean = false,
    onClick: () -> Unit
) {
    val containerColor = if (prominent) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val borderColor = if (prominent) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val contentColor = if (prominent) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = if (prominent) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                contentColor = if (prominent) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(imageVector = icon, contentDescription = null)
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LocationGroupHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 2.dp, start = 4.dp)
    )
}

@Composable
private fun SubscriptionGroupHeader(
    locations: List<LocationItem>,
    modifier: Modifier = Modifier
) {
    val first = locations.firstOrNull()
    val subscription = first?.metadata?.subscription
    val title = first?.subscriptionTitle().orEmpty().ifBlank { "Subscriptions" }
    val details = first?.subscriptionDetails()

    Column(modifier = modifier.padding(start = 4.dp, top = 2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
        if (!details.isNullOrBlank()) {
            Text(
                text = details,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun LocationSelectorRow(
    location: LocationItem,
    selectedLocationId: String?,
    pingsState: PingsState,
    onLocationSelected: (String) -> Unit,
    onLocationSettingsClick: (String) -> Unit
) {
    val pingMs = (pingsState as? PingsState.Success)?.pings?.get(location.storageId)
        ?: (pingsState as? PingsState.Loading)?.lastPings?.get(location.storageId)
    val isOffline = pingsState is PingsState.Success && pingMs == null

    LocationRow(
        location = location,
        isSelected = selectedLocationId == location.storageId,
        isLoading = pingsState is PingsState.Loading,
        isError = isOffline,
        pingMs = pingMs,
        onSettingsClick = { onLocationSettingsClick(location.storageId) },
        onClick = { onLocationSelected(location.storageId) }
    )
}

private fun LocationItem.subscriptionGroupKey(): String {
    return listOfNotNull(
        metadata?.subscription?.name?.takeIf { it.isNotBlank() },
        subscriptionUrl?.trim()?.takeIf { it.isNotBlank() }
    ).joinToString("|").ifBlank { storageId }
}

private fun LocationItem.subscriptionTitle(): String {
    val subscription = metadata?.subscription
    return listOfNotNull(
        subscription?.icon?.takeIf { it.isNotBlank() },
        subscription?.name?.takeIf { it.isNotBlank() } ?: "Subscriptions"
    ).joinToString(" ")
}

private fun LocationItem.subscriptionDetails(): String? {
    val subscription = metadata?.subscription ?: return null
    return listOfNotNull(
        quotaText(subscription.used, subscription.available),
        subscriptionUpdateText(subscription.update),
        subscription.refresh?.takeIf { it.isNotBlank() }?.let { "Refresh $it" }
    ).joinToString(" · ").takeIf { it.isNotBlank() }
}

private fun quotaText(used: String?, available: String?): String? {
    return when {
        !used.isNullOrBlank() && !available.isNullOrBlank() -> "$used used · $available available"
        !used.isNullOrBlank() -> "$used used"
        !available.isNullOrBlank() -> "$available available"
        else -> null
    }
}

@OptIn(ExperimentalTime::class)
private fun subscriptionUpdateText(update: String?): String? {
    val raw = update?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val epochMillis = parseEpochMillis(raw) ?: return "Updated $raw"
    val nowMillis = Clock.System.now().toEpochMilliseconds()
    val diffMillis = nowMillis - epochMillis
    val durationMillis = if (diffMillis < 0) -diffMillis else diffMillis

    return when {
        durationMillis < MINUTE_MILLIS -> "Updated just now"
        diffMillis < 0 -> "Updates in ${durationText(durationMillis)}"
        else -> "Updated ${durationText(durationMillis)} ago"
    }
}

private fun parseEpochMillis(value: String): Long? {
    val timestamp = value.toLongOrNull() ?: return null
    return when (timestamp) {
        in 1_000_000_000L..9_999_999_999L -> timestamp * 1_000L
        in 1_000_000_000_000L..9_999_999_999_999L -> timestamp
        else -> null
    }
}

private fun durationText(millis: Long): String {
    val minutes = millis / MINUTE_MILLIS
    val hours = millis / HOUR_MILLIS
    val days = millis / DAY_MILLIS
    val months = days / 30
    val years = days / 365

    return when {
        minutes < 1 -> "less than a minute"
        hours < 1 -> plural(minutes, "minute")
        days < 1 -> plural(hours, "hour")
        days < 30 -> plural(days, "day")
        months < 12 -> plural(months, "month")
        else -> plural(years, "year")
    }
}

private fun plural(value: Long, unit: String): String {
    return "$value $unit${if (value == 1L) "" else "s"}"
}

private const val MINUTE_MILLIS = 60_000L
private const val HOUR_MILLIS = 60 * MINUTE_MILLIS
private const val DAY_MILLIS = 24 * HOUR_MILLIS
