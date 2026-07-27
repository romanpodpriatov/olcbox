package org.olcbox.app.vpn

import kotlinx.coroutines.flow.StateFlow
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.repository.SubscriptionFetchProxy

sealed class VpnStatus {
    object Disconnected : VpnStatus()
    object Connecting : VpnStatus()
    object Connected : VpnStatus()
    object Reconnecting : VpnStatus()
    object Stopping : VpnStatus()
    data class Error(val message: String) : VpnStatus()
}

interface VpnManager {
    val logs: StateFlow<List<String>>
    val status: StateFlow<VpnStatus>
    val isConnected: StateFlow<Boolean>

    /**
     * When the current session came up, in epoch milliseconds, or null when
     * there is no session.
     *
     * A reconnect carries the value over rather than restarting it: rebuilding
     * the tunnel after a network handover is not a new session, and a timer
     * that resets every time the phone changes network says nothing useful.
     */
    val connectedSince: StateFlow<Long?>

    fun needsPermission(): Boolean
    fun startVpn()
    fun stopVpn()
    suspend fun ping(locationConfig: LocationConfig): Long?
    suspend fun checkConnection(locationConfig: LocationConfig): Long?
    fun subscriptionFetchProxy(): SubscriptionFetchProxy? = null
}
