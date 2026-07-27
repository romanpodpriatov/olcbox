package org.olcbox.app.vpn.service

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.olcbox.app.util.nowMillis
import org.olcbox.app.vpn.VpnStatus

object OlcboxVpnState {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
    val status = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _connectedSince = MutableStateFlow<Long?>(null)
    val connectedSince = _connectedSince.asStateFlow()

    fun setStatus(status: VpnStatus) {
        _status.value = status
        _isConnected.value = status is VpnStatus.Connected
        _connectedSince.value = when (status) {
            // Only the first Connected of a session stamps the clock; a
            // reconnect passes through Reconnecting and back, and must not
            // restart it.
            VpnStatus.Connected -> _connectedSince.value ?: nowMillis()
            VpnStatus.Reconnecting -> _connectedSince.value
            else -> null
        }
    }

    fun addLog(msg: String) {
        Log.d(TAG, msg)
        _logs.update { (it + msg).takeLast(MAX_LOG_ENTRIES) }
    }

    private const val MAX_LOG_ENTRIES = 1_000
    private const val TAG = "OlcboxVpnService"
}
