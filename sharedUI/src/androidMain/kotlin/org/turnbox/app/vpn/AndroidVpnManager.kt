package org.turnbox.app.vpn

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.StateFlow
import us.leaf3stones.hy2droid.proxy.Hysteria2VpnService

class AndroidVpnManager(private val context: Context) : VpnManager {
    override val logs: StateFlow<List<String>> = Hysteria2VpnService.logs
    override val isConnected: StateFlow<Boolean> = Hysteria2VpnService.isConnected

    override fun startVpn() {
        val intent = Intent(context, Hysteria2VpnService::class.java).apply {
            action = Hysteria2VpnService.ACTION_START_VPN
        }
        context.startService(intent)
    }

    override fun stopVpn() {
        val intent = Intent(context, Hysteria2VpnService::class.java).apply {
            action = Hysteria2VpnService.ACTION_STOP_VPN
        }
        context.startService(intent)
    }
}
