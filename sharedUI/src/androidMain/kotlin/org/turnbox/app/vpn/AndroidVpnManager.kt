package org.turnbox.app.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.turnbox.app.data.model.LocationConfig
import org.turnbox.app.vpn.data.KEY_ANDROID_CONNECTION_MODE
import org.turnbox.app.vpn.data.KEY_ANDROID_SOCKS_PASSWORD
import org.turnbox.app.vpn.data.KEY_ANDROID_SOCKS_USERNAME
import org.turnbox.app.vpn.data.vpnPrefDataStore
import org.turnbox.app.vpn.service.TurnboxVpnActions
import org.turnbox.app.vpn.service.TurnboxVpnState
import java.security.SecureRandom

class AndroidVpnManager(private val context: Context) : VpnManager {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _connectionMode = MutableStateFlow(AndroidConnectionMode.Tun)
    private val _proxySettings = MutableStateFlow(AndroidSocksProxySettings())

    override val logs: StateFlow<List<String>> = TurnboxVpnState.logs
    override val status: StateFlow<VpnStatus> = TurnboxVpnState.status
    override val isConnected: StateFlow<Boolean> = TurnboxVpnState.isConnected
    val connectionMode: StateFlow<AndroidConnectionMode> = _connectionMode.asStateFlow()
    val proxySettings: StateFlow<AndroidSocksProxySettings> = _proxySettings.asStateFlow()

    init {
        scope.launch {
            ensureProxySettings()
            appContext.vpnPrefDataStore.data
                .map { preferences ->
                    val mode = AndroidConnectionMode.fromValue(preferences[KEY_ANDROID_CONNECTION_MODE])
                    val proxy = AndroidSocksProxySettings(
                        username = preferences[KEY_ANDROID_SOCKS_USERNAME]
                            ?: AndroidSocksProxySettings.DEFAULT_USERNAME,
                        password = preferences[KEY_ANDROID_SOCKS_PASSWORD].orEmpty()
                    )
                    mode to proxy
                }
                .collect { (mode, proxy) ->
                    _connectionMode.value = mode
                    _proxySettings.value = proxy
                }
        }
    }

    override fun needsPermission(): Boolean = needsPermission(_connectionMode.value)

    fun needsPermission(mode: AndroidConnectionMode): Boolean {
        return mode == AndroidConnectionMode.Tun && VpnService.prepare(context) != null
    }

    fun selectConnectionMode(mode: AndroidConnectionMode) {
        _connectionMode.value = mode
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                preferences[KEY_ANDROID_CONNECTION_MODE] = mode.value
            }
        }
    }

    fun updateProxyPassword(password: String) {
        val sanitized = password.trim().take(MAX_SOCKS_PASSWORD_LENGTH)
            .ifBlank { generateProxyPassword() }
        _proxySettings.value = _proxySettings.value.copy(password = sanitized)
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                preferences[KEY_ANDROID_SOCKS_USERNAME] = AndroidSocksProxySettings.DEFAULT_USERNAME
                preferences[KEY_ANDROID_SOCKS_PASSWORD] = sanitized
            }
        }
    }

    fun regenerateProxyPassword() {
        updateProxyPassword(generateProxyPassword())
    }

    override fun startVpn() {
        val intent = Intent().apply {
            setClassName(context.packageName, TurnboxVpnActions.SERVICE_CLASS_NAME)
            action = TurnboxVpnActions.ACTION_START_VPN
            putExtra(TurnboxVpnActions.EXTRA_CONNECTION_MODE, _connectionMode.value.value)
            putExtra(TurnboxVpnActions.EXTRA_SOCKS_USERNAME, _proxySettings.value.username)
            putExtra(TurnboxVpnActions.EXTRA_SOCKS_PASSWORD, _proxySettings.value.password)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
        }
    }

    override fun stopVpn() {
        val intent = Intent().apply {
            setClassName(context.packageName, TurnboxVpnActions.SERVICE_CLASS_NAME)
            action = TurnboxVpnActions.ACTION_STOP_VPN
        }
        context.startService(intent)
    }

    override suspend fun ping(locationConfig: LocationConfig): Long? {
        return checkConnection(locationConfig)
    }

    override suspend fun checkConnection(locationConfig: LocationConfig): Long? {
        return OlcRtcConnectionChecker.check(
            locationConfig = locationConfig,
            isVpnAlreadyRunning = TurnboxVpnState.isConnected.value
        )
    }

    private suspend fun ensureProxySettings() {
        appContext.vpnPrefDataStore.edit { preferences ->
            if (preferences[KEY_ANDROID_SOCKS_USERNAME].isNullOrBlank()) {
                preferences[KEY_ANDROID_SOCKS_USERNAME] = AndroidSocksProxySettings.DEFAULT_USERNAME
            }
            if (preferences[KEY_ANDROID_SOCKS_PASSWORD].isNullOrBlank()) {
                preferences[KEY_ANDROID_SOCKS_PASSWORD] = generateProxyPassword()
            }
        }
    }

    private fun generateProxyPassword(): String {
        return buildString(PROXY_PASSWORD_LENGTH) {
            repeat(PROXY_PASSWORD_LENGTH) {
                append(PROXY_PASSWORD_ALPHABET[random.nextInt(PROXY_PASSWORD_ALPHABET.length)])
            }
        }
    }

    private companion object {
        const val PROXY_PASSWORD_LENGTH = 24
        const val MAX_SOCKS_PASSWORD_LENGTH = 64
        const val PROXY_PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val random = SecureRandom()
    }
}
