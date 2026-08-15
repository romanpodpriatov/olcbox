package org.olcbox.app.data.repository

import kotlinx.coroutines.flow.StateFlow
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.SubscriptionSettings

interface LocationsRepository {
    val changes: StateFlow<Long>
    suspend fun getBundle(): LocationBundleV4
    suspend fun saveBundle(bundle: LocationBundleV4)
    suspend fun exportBundle(): String
    suspend fun importText(text: String, subscriptionProxy: SubscriptionFetchProxy? = null): Boolean
    suspend fun refreshSubscriptions(
        subscriptionProxy: SubscriptionFetchProxy? = null
    ): SubscriptionRefreshReport
    suspend fun refreshSubscription(
        subscriptionUrl: String,
        subscriptionProxy: SubscriptionFetchProxy? = null
    ): SubscriptionRefreshReport
    suspend fun refreshDueSubscriptions(
        subscriptionProxy: SubscriptionFetchProxy? = null
    ): SubscriptionRefreshReport
    suspend fun setSubscriptionUpdateInterval(subscriptionUrl: String, hours: Int)

    /**
     * Removes every location that came from [subscriptionUrl]. Returns how many
     * were removed. Manually added locations are matched by the same trimmed-URL
     * rule the refresh path uses, so they are never touched.
     */
    suspend fun deleteSubscription(subscriptionUrl: String): Int
    suspend fun saveLocation(storageId: String, location: LocationConfig)
    suspend fun loadLocation(storageId: String): LocationConfig?
    suspend fun deleteLocation(storageId: String)
    suspend fun getAllLocations(): List<LocationEntry>
    suspend fun getActiveLocationId(): String?
    suspend fun setActiveLocationId(storageId: String?)
    suspend fun getActiveLocation(): LocationEntry?
    suspend fun getDeviceIdentity(): String

    /** How subscriptions behave. Persisted with the bundle, so one copy per device. */
    suspend fun getSubscriptionSettings(): SubscriptionSettings
    suspend fun saveSubscriptionSettings(settings: SubscriptionSettings)

    /**
     * Whether the VPN disclosure has been accepted on this device.
     *
     * Google Play requires an in-app screen, shown in the ordinary course of
     * using the app, that says what the VpnService API does here and is accepted
     * by a deliberate action — not a privacy policy, not a line in the store
     * listing, and not bundled with any other consent.
     */
    suspend fun isVpnDisclosureAccepted(): Boolean
    suspend fun acceptVpnDisclosure(atMillis: Long)

    /**
     * Whether the first-run walkthrough has already been shown on this device.
     *
     * Nothing to do with the disclosure above, and deliberately separate: Play
     * requires that consent not be combined with any other screen, so these two
     * may never become one flag however alike they look.
     */
    suspend fun isOnboardingSeen(): Boolean

    /** Null replays it — see "replay first run" in settings. */
    suspend fun setOnboardingSeen(atMillis: Long?)
}

data class SubscriptionFetchProxy(
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = ""
)
