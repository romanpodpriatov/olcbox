package org.olcbox.app.ui.features.locations

import org.olcbox.app.data.share.SubscriptionShareItem

/**
 * The Server lists rows, built from what the UI already holds.
 *
 * One function rather than the three hand-written copies it replaces — desktop
 * `main.kt`, `iosMain/MainViewController.kt` and `androidMain/AndroidMainScreen.kt`
 * each had their own. They now have to agree about which subscriptions arrived
 * encrypted, and three copies of a privacy rule do not stay in agreement.
 */
fun subscriptionShareItems(items: List<LocationItem>): List<SubscriptionShareItem> {
    return items
        .mapNotNull { item ->
            val url = item.subscriptionUrl
                ?.trim()
                ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?: return@mapNotNull null
            url to item
        }
        .groupBy({ it.first }, { it.second })
        .entries
        .sortedBy { it.key }
        .map { (url, grouped) ->
            val metadata = grouped.firstNotNullOfOrNull { it.metadata?.subscription }
            SubscriptionShareItem(
                url = url,
                name = metadata?.name?.takeIf { it.isNotBlank() } ?: grouped.first().fullName,
                updateIntervalHours = metadata?.updateIntervalHours,
                lastRefreshAtEpochMs = metadata?.lastRefreshAtEpochMs,
                locationCount = grouped.size,
                originLink = grouped.firstNotNullOfOrNull { it.subscriptionOriginLink }
            )
        }
}
