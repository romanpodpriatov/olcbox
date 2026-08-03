package org.olcbox.app.ui.features.home.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.ui.icons.PkIcons
import org.olcbox.app.net.TransportKind
import org.olcbox.app.net.transportKind
import org.olcbox.app.ui.components.kit.PkBrand
import org.olcbox.app.ui.components.kit.PkFilterChip
import org.olcbox.app.ui.components.kit.PkSectionLabel
import org.olcbox.app.ui.theme.LocalPkPalette
import org.olcbox.app.data.model.SubscriptionSort
import org.olcbox.app.util.formatDate
import org.olcbox.app.util.formatDateTime
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.ui.features.locations.PingsState
import org.olcbox.app.ui.features.locations.components.LocationRow
import org.olcbox.app.ui.features.locations.components.LatencyButton
import org.olcbox.app.ui.features.locations.components.SubscriptionRefreshButton

@Composable
fun LocationSelectorScreen(
    onGetSubscriptionClick: () -> Unit = {},
    showGetSubscription: Boolean = true,
    modifier: Modifier = Modifier,
    onRefreshClick: (targetLocationIds: List<String>) -> Unit,
    /** Re-fetch this subscription from its URL. Nothing to do with latency. */
    onRefreshSubscriptionClick: (subscriptionUrl: String) -> Unit = {},
    refreshingSubscriptionUrl: String? = null,
    /** Opens a provider's support or web link in the system browser. */
    onOpenUrl: (String) -> Unit = {},
    onAddSubscriptionClick: () -> Unit,
    onAddLocationClick: () -> Unit,
    locations: List<LocationItem>,
    selectedLocationId: String?,
    pingsState: PingsState,
    onLocationSelected: (String) -> Unit,
    onLocationSettingsClick: (String) -> Unit,
    sort: SubscriptionSort = SubscriptionSort.None,
    collapsible: Boolean = true,
    showSettings: Boolean = true,
    showCustomLocation: Boolean = true
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // 24 exits x 3 transports is 72 rows out of one subscription; without a
        // filter the list is unusable. Chips only appear for transports actually
        // present, so a single-transport subscription looks exactly as before.
        var transportFilter by rememberSaveable { mutableStateOf<String?>(null) }
        // olcRTC's own carriers (VP8 / SEI / DataChannel) sit one level below the
        // protocol — they describe how data rides inside the call, not how the
        // tunnel is reached. They earn their own chips only when a user actually
        // has more than one, so the usual single olcRTC entry stays one chip.
        val splitOlcrtcCarriers = locations
            .mapNotNull { it.config }
            .filter { it.transportKind() == TransportKind.Olcrtc }
            .map { it.transport }
            .distinct()
            .size > 1
        val optionPerLocation = locations.mapNotNull { it.transportFilterOption(splitOlcrtcCarriers) }
        val counts = optionPerLocation.groupingBy { it.key }.eachCount()
        val options = optionPerLocation.distinctBy { it.key }.sortedBy { it.order }
        // Resolve rather than repair: a filter left over from a subscription that no
        // longer serves that transport simply reads as "All". Writing the state back
        // here would be a write during composition.
        val activeFilter = transportFilter?.let { key -> options.firstOrNull { it.key == key } }

        if (options.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PkFilterChip(
                    label = "All",
                    selected = activeFilter == null,
                    count = locations.size,
                    onClick = { transportFilter = null }
                )
                options.forEach { option ->
                    PkFilterChip(
                        label = option.label,
                        selected = activeFilter?.key == option.key,
                        count = counts[option.key],
                        onClick = {
                            transportFilter =
                                if (activeFilter?.key == option.key) null else option.key
                        }
                    )
                }
            }
        }

        val visibleLocations = activeFilter
            ?.let { option ->
                locations.filter { it.transportFilterOption(splitOlcrtcCarriers)?.key == option.key }
            }
            ?: locations

        // Sorted within a group, never across: the grouping is what tells a user
        // which provider a row came from, and ordering the whole list by ping
        // would shuffle two subscriptions into each other.
        fun List<LocationItem>.sorted(): List<LocationItem> = when (sort) {
            SubscriptionSort.None -> this
            SubscriptionSort.Alphabetical -> sortedBy { item ->
                (item.metadata?.name?.takeIf { it.isNotBlank() } ?: item.fullName).lowercase()
            }
            // Unmeasured sinks rather than sorting as zero, which would put every
            // row nobody has probed yet at the top as if it were the fastest.
            SubscriptionSort.Ping -> sortedBy { item ->
                pingsState.pingFor(item.storageId) ?: Int.MAX_VALUE
            }
        }

        val subscriptionLocations = visibleLocations.filter { !it.subscriptionUrl.isNullOrBlank() }
        val subscriptionGroups = subscriptionLocations
            .groupBy { it.subscriptionGroupKey() }
            .values
            .map { it.sorted() }
            .toList()
        val customLocations = visibleLocations.filter { it.subscriptionUrl.isNullOrBlank() }.sorted()

        if (locations.isEmpty()) {
            RelaySetupCard(
                onGetSubscriptionClick = onGetSubscriptionClick,
                showGetSubscription = showGetSubscription,
                onAddSubscriptionClick = onAddSubscriptionClick,
                onAddLocationClick = onAddLocationClick,
                showCustomLocation = showCustomLocation
            )
            return@Column
        }

        // Which groups are folded away. Two subscriptions of a dozen exits each
        // is most of a phone screen before a user has scrolled at all, and the
        // one they are not using is pure noise.
        //
        // Saveable, not merely remembered: opening a location's settings and
        // coming back would otherwise unfold everything again.
        val collapsed = rememberSaveable(
            saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })
        ) { mutableStateListOf<String>() }

        fun toggle(key: String) {
            if (!collapsed.remove(key)) collapsed.add(key)
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            subscriptionGroups.forEachIndexed { index, group ->
                val groupKey = group.firstOrNull()?.subscriptionGroupKey() ?: "group-$index"
                val isCollapsed = collapsible && groupKey in collapsed
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
                            collapsed = isCollapsed,
                            // Folding one away must not hide the fact that the
                            // exit in use is inside it.
                            holdsSelection = group.any { it.storageId == selectedLocationId },
                            collapsible = collapsible,
                            onToggle = { toggle(groupKey) },
                            onOpenUrl = onOpenUrl,
                            modifier = Modifier.weight(1f)
                        )

                        val groupIds = group.map { it.storageId }
                        val isGroupRefreshing = pingsState is PingsState.Loading &&
                                pingsState.pendingLocationIds.any { it in groupIds }
                        val groupUrl = group.firstOrNull()?.subscriptionUrl?.trim()

                        // Two controls, side by side, neither of them labelled:
                        // a bolt that measures and circling arrows that fetch
                        // the list again. They were one button before, captioned
                        // "Ping" and wired to the refresh.
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Support sits with the other actions on the right;
                            // the provider's page is information about the
                            // subscription and rides with its name, on the left.
                            group.firstOrNull()?.metadata?.subscription
                                ?.supportUrl?.takeIf { it.isNotBlank() }?.let { url ->
                                    LinkButton(
                                        icon = PkIcons.Send,
                                        description = "Contact support",
                                        onClick = { onOpenUrl(url) }
                                    )
                                }

                            LatencyButton(
                                isRunning = isGroupRefreshing,
                                onClick = { onRefreshClick(groupIds) },
                                tint = MaterialTheme.colorScheme.primary
                            )
                            if (!groupUrl.isNullOrBlank()) {
                                SubscriptionRefreshButton(
                                    isRefreshing = groupUrl == refreshingSubscriptionUrl,
                                    onClick = { onRefreshSubscriptionClick(groupUrl) },
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    if (!isCollapsed) {
                        Spacer(modifier = Modifier.height(2.dp))
                    }

                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (isCollapsed) return@Column
                        group.forEach { location ->
                            LocationSelectorRow(
                                location = location,
                                selectedLocationId = selectedLocationId,
                                pingsState = pingsState,
                                onLocationSelected = onLocationSelected,
                                onLocationSettingsClick = onLocationSettingsClick,
                                showSettings = showSettings
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

                        // Loading state for the custom locations only.
                        val customIds = customLocations.map { it.storageId }
                        val isCustomRefreshing = pingsState is PingsState.Loading &&
                                pingsState.pendingLocationIds.any { it in customIds }

                        LatencyButton(
                            isRunning = isCustomRefreshing,
                            onClick = { onRefreshClick(customIds) },
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        customLocations.forEach { location ->
                            LocationSelectorRow(
                                location = location,
                                selectedLocationId = selectedLocationId,
                                pingsState = pingsState,
                                onLocationSelected = onLocationSelected,
                                onLocationSettingsClick = onLocationSettingsClick,
                                showSettings = showSettings
                            )
                        }
                    }
                }
            }

            if (showCustomLocation) {
                FilledTonalButton(
                    onClick = onAddLocationClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Add custom location",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
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
                    Text(
                        text = "Add subscription",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/** A provider's link, as an icon. Nothing to read, nothing to translate. */
@Composable
private fun LinkButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(38.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun RelaySetupCard(
    onAddSubscriptionClick: () -> Unit,
    onAddLocationClick: () -> Unit,
    onGetSubscriptionClick: () -> Unit = {},
    showGetSubscription: Boolean = true,
    showCustomLocation: Boolean = true
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(modifier = Modifier.padding(start = 4.dp)) {
            PkSectionLabel("Add relay setup")
        }

        // Absent, not disabled, where a build may not point at a purchase —
        // see showGetSubscription in AddConfigurationSheet for why.
        if (showGetSubscription) {
            SetupActionRow(
                title = "Get a subscription",
                subtitle = "Open ${PkBrand.name} to create one",
                icon = PkIcons.OpenInNew,
                prominent = true,
                onClick = onGetSubscriptionClick
            )
        }

        SetupActionRow(
            title = "Add subscription",
            subtitle = "Scan QR, paste URI, or import file",
            icon = PkIcons.QrCodeScanner,
            onClick = onAddSubscriptionClick
        )

        if (showCustomLocation) {
            SetupActionRow(
                title = "Create custom location",
                subtitle = "Enter room, key, provider, and transport",
                icon = Icons.Outlined.Add,
                onClick = onAddLocationClick
            )
        }
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
    Box(modifier = modifier.padding(top = 2.dp, start = 4.dp)) {
        PkSectionLabel(title)
    }
}

@Composable
private fun SubscriptionGroupHeader(
    locations: List<LocationItem>,
    collapsed: Boolean,
    collapsible: Boolean,
    holdsSelection: Boolean,
    onToggle: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val pk = LocalPkPalette.current
    val first = locations.firstOrNull()
    val title = first?.subscriptionTitle().orEmpty().ifBlank { "Subscriptions" }
    val refreshLine = first?.subscriptionRefreshLine()
    val quotaLine = first?.subscriptionQuotaLine()
    // A quarter turn rather than two icons: the same arrow points at the rows
    // when they are there and at the title when they are not.
    val turn by animateFloatAsState(if (collapsed) -90f else 0f, label = "groupChevron")

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .then(if (collapsible) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(start = 4.dp, top = 2.dp, bottom = 2.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // No chevron where folding is switched off: an arrow that does not fold
        // anything is a promise the screen does not keep.
        if (collapsible) {
            Icon(
                imageVector = PkIcons.ChevronRight,
                contentDescription = if (collapsed) "Expand" else "Collapse",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(turn + 90f)
            )

            Spacer(modifier = Modifier.width(2.dp))
        }

        // An i in a circle, first thing on the row: this is about the
        // subscription rather than an action on it.
        first?.metadata?.subscription?.webPageUrl?.takeIf { it.isNotBlank() }?.let { url ->
            LinkButton(
                icon = PkIcons.Info,
                description = "Open the provider's site",
                onClick = { onOpenUrl(url) }
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                // Folded away, the group still has to admit it holds the exit
                // currently in use — otherwise the screen shows no selection at
                // all and the user goes looking for one.
                if (collapsed && holdsSelection) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(pk.accent)
                    )
                }
            }

            // Folded, one line that says how much is hidden and what is left of
            // the plan. Unfolded, both lines the provider gave us.
            val lines = if (collapsed) {
                listOfNotNull(
                    listOfNotNull(
                        "${locations.size} " + if (locations.size == 1) "location" else "locations",
                        quotaLine
                    ).joinToString(" · ")
                )
            } else {
                listOfNotNull(refreshLine, quotaLine)
            }

            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LocationSelectorRow(
    location: LocationItem,
    selectedLocationId: String?,
    pingsState: PingsState,
    onLocationSelected: (String) -> Unit,
    onLocationSettingsClick: (String) -> Unit,
    showSettings: Boolean = true
) {
    val pingMs = pingsState.pingFor(location.storageId)
    val isLoading = pingsState.isChecking(location.storageId)
    val isOffline = pingsState.isOffline(location.storageId)

    LocationRow(
        location = location,
        isSelected = selectedLocationId == location.storageId,
        isLoading = isLoading,
        isError = isOffline,
        pingMs = pingMs,
        settingsEnabled = showSettings,
        onSettingsClick = {
            onLocationSettingsClick(location.storageId)
        },
        onClick = {
            onLocationSelected(location.storageId)
        }
    )
}

private fun PingsState.pingFor(locationId: String): Int? {
    return when (this) {
        PingsState.Idle -> null

        is PingsState.Loading -> {
            if (currentPings.containsKey(locationId)) {
                currentPings[locationId]
            } else {
                lastPings?.get(locationId)
            }
        }

        is PingsState.Success -> {
            pings[locationId]
        }

        is PingsState.Error -> {
            lastPings?.get(locationId)
        }
    }
}

private fun PingsState.isChecking(locationId: String): Boolean {
    return this is PingsState.Loading && locationId in pendingLocationIds
}

private fun PingsState.isOffline(locationId: String): Boolean {
    return when (this) {
        PingsState.Idle -> false

        is PingsState.Loading -> {
            currentPings.containsKey(locationId) && currentPings[locationId] == null
        }

        is PingsState.Success -> {
            pings.containsKey(locationId) && pings[locationId] == null
        }

        is PingsState.Error -> false
    }
}

/**
 * A chip in the transport filter. [order] keeps chips in protocol order (Reality,
 * Hysteria2, XHTTP, …) rather than whatever order locations happen to arrive in.
 */
private data class TransportFilterOption(
    val key: String,
    val label: String,
    val order: Int
)

private fun LocationItem.transportFilterOption(
    splitOlcrtcCarriers: Boolean
): TransportFilterOption? {
    val config = config ?: return null
    val kind = config.transportKind()
    if (kind == TransportKind.Olcrtc && splitOlcrtcCarriers) {
        return TransportFilterOption(
            key = "${kind.name}:${config.transport}",
            label = "${kind.label()} · ${config.transportName()}",
            order = kind.ordinal
        )
    }
    return TransportFilterOption(key = kind.name, label = kind.label(), order = kind.ordinal)
}

private fun LocationItem.subscriptionGroupKey(): String {
    return listOfNotNull(
        metadata?.subscription?.name?.takeIf { it.isNotBlank() },
        subscriptionUrl?.trim()?.takeIf { it.isNotBlank() }
    ).joinToString("|").ifBlank { storageId }
}

private fun LocationItem.subscriptionTitle(): String {
    val subscription = metadata?.subscription
    // Falling back to the literal "Subscriptions" labelled every unnamed
    // subscription identically, so two of them read as the same heading twice.
    // Identify by host instead, which is what actually distinguishes them.
    val name = subscription?.name?.takeIf { it.isNotBlank() }
        ?: subscriptionUrl?.let { subscriptionHost(it) }
        ?: "Subscription"

    return listOfNotNull(
        subscription?.icon?.takeIf { it.isNotBlank() },
        name
    ).joinToString(" ")
}

/** `https://proofkit.org/sub/<token>` → `proofkit.org`. */
private fun subscriptionHost(url: String): String? {
    val withoutScheme = url.trim().substringAfter("://", missingDelimiterValue = "")
    if (withoutScheme.isBlank()) return null
    return withoutScheme.substringBefore('/').substringBefore(':')
        .takeIf { it.isNotBlank() }
}

/**
 * The two lines Happ shows under a subscription's name: when it last refreshed
 * and how often, then how much of it is left and until when.
 *
 * All of it comes from the provider's own response headers, which were being
 * downloaded and thrown away — so the list called a subscription by its
 * *hostname*, which is the least useful name it has and the one thing worth not
 * printing on a screen someone might show to a friend.
 */
private fun LocationItem.subscriptionRefreshLine(): String? {
    val subscription = metadata?.subscription ?: return null
    return listOfNotNull(
        subscription.lastRefreshAtEpochMs?.let { formatDateTime(it) },
        subscription.updateIntervalHours?.let { "Auto-update ${it}h" }
            ?: subscription.refresh?.takeIf { it.isNotBlank() }?.let { "Refresh $it" }
    ).joinToString(" | ").takeIf { it.isNotBlank() }
}

private fun LocationItem.subscriptionQuotaLine(): String? {
    val subscription = metadata?.subscription ?: return null
    return listOfNotNull(
        quotaText(subscription.used, subscription.available),
        subscription.expiresAtEpochMs?.let { "Expires ${formatDate(it)}" }
    ).joinToString(" · ").takeIf { it.isNotBlank() }
}

private fun LocationItem.subscriptionDetails(): String? {
    val subscription = metadata?.subscription ?: return null

    return listOfNotNull(
        quotaText(subscription.used, subscription.available),
        subscription.refresh?.takeIf { it.isNotBlank() }?.let { "Refresh $it" }
    ).joinToString(" · ").takeIf { it.isNotBlank() }
}

private fun quotaText(used: String?, available: String?): String? {
    return when {
        // "9.4 MB / 300 GB", the way a provider states a plan.
        !used.isNullOrBlank() && !available.isNullOrBlank() -> "$used / $available"
        !used.isNullOrBlank() -> "$used used"
        !available.isNullOrBlank() -> "$available available"
        else -> null
    }
}

private fun plural(value: Long, unit: String): String {
    return "$value $unit${if (value == 1L) "" else "s"}"
}

private const val MINUTE_MILLIS = 60_000L
private const val HOUR_MILLIS = 60 * MINUTE_MILLIS
private const val DAY_MILLIS = 24 * HOUR_MILLIS
