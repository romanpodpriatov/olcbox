package org.turnbox.app.vpn

import kotlinx.coroutines.flow.StateFlow

interface VpnManager {
    val logs: StateFlow<List<String>>
    val isConnected: StateFlow<Boolean>
    fun startVpn()
    fun stopVpn()
}