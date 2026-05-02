package org.turnbox.app.vpn.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import mobile.LogWriter
import mobile.Mobile
import mobile.SocketProtector
import org.turnbox.app.data.TUN2SOCKS_CONFIG_FILE_NAME
import org.turnbox.app.data.datasource.LocationsDataSourceImpl
import org.turnbox.app.data.datasource.LocationsRepositoryImpl
import org.turnbox.app.data.model.LocationConfig
import org.turnbox.app.data.repository.LocationsRepository
import org.turnbox.app.vpn.AndroidConnectionMode
import org.turnbox.app.vpn.AndroidSocksProxySettings
import org.turnbox.app.vpn.UpstreamCandidate
import org.turnbox.app.vpn.UpstreamNetworkSelector
import org.turnbox.app.vpn.UpstreamTransport
import org.turnbox.app.vpn.VpnStatus
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.coroutines.coroutineContext

class TurnboxVpnService : VpnService() {

    private external fun startTun2socksNative(configPath: String, fd: Int): Int
    private external fun stopTun2socksNative()
    private external fun getTun2socksStatsNative(): LongArray

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private val tunnelMutex = Mutex()
    private val repository: LocationsRepository by lazy {
        LocationsRepositoryImpl(LocationsDataSourceImpl(applicationContext))
    }

    private var startupJob: Job? = null
    private var watchdogJob: Job? = null
    private var cleanupJob: Job? = null
    private var generation = 0L
    private var recoveryRequestedForGeneration = 0L

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tun2socksThread: Thread? = null
    @Volatile
    private var tun2socksStarted = false
    @Volatile
    private var tun2socksStopRequested = false

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var connectivityManager: ConnectivityManager
    private var currentNetwork: Network? = null
    private var isCallbackRegistered = false
    private var localSocksPort = LOCAL_SOCKS_PORT_BASE
    private var nextSocksPort = LOCAL_SOCKS_PORT_BASE
    private var connectionMode = AndroidConnectionMode.Tun
    private var socksUsername = AndroidSocksProxySettings.DEFAULT_USERNAME
    private var socksPassword = ""
    private var socksProxy: AuthenticatedSocksProxy? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handleNetworkChange(network, "Available")
        }

        override fun onLost(network: Network) {
            addLog("Network lost")
            if (network == currentNetwork) {
                updateUnderlyingNetwork(null)
                unbindProcessFromNetwork()
            }
            scope.launch {
                delay(NETWORK_LOSS_FALLBACK_DELAY_MS)
                val upstream = findActiveUpstreamNetwork()
                if (upstream != null) {
                    handleNetworkChange(upstream, "Fallback")
                } else if (TurnboxVpnState.status.value is VpnStatus.Connected ||
                    TurnboxVpnState.status.value is VpnStatus.Reconnecting
                ) {
                    setStatus(VpnStatus.Reconnecting)
                    updateNotification("Waiting for network...")
                    addLog("Waiting for upstream network")
                }
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (network == currentNetwork || caps.isUsableUpstream()) {
                handleNetworkChange(network, "Capabilities")
            }
        }

        private fun handleNetworkChange(network: Network, reason: String) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return
            if (!caps.isUsableUpstream()) return

            val upstream = findActiveUpstreamNetwork() ?: return
            if (currentNetwork == upstream) {
                if (TurnboxVpnState.status.value is VpnStatus.Reconnecting &&
                    startupJob?.isActive != true
                ) {
                    addLog("Network $reason: ${getNetName(upstream)}")
                    startTunnel(isMigration = true)
                }
                return
            }

            updateUnderlyingNetwork(upstream)
            addLog("Network $reason: ${getNetName(upstream)}")

            when (TurnboxVpnState.status.value) {
                is VpnStatus.Connected,
                is VpnStatus.Reconnecting -> startTunnel(isMigration = true)

                else -> Unit
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Turnbox::VpnWakeLock")

        installMobileCallbacks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TurnboxVpnActions.ACTION_STOP_VPN -> {
                addLog("Stop VPN requested")
                cleanup()
                return START_NOT_STICKY
            }

            TurnboxVpnActions.ACTION_START_VPN -> Unit
            else -> {
                cleanup()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        connectionMode = AndroidConnectionMode.fromValue(
            intent.getStringExtra(TurnboxVpnActions.EXTRA_CONNECTION_MODE)
        )
        socksUsername = intent.getStringExtra(TurnboxVpnActions.EXTRA_SOCKS_USERNAME)
            ?.takeIf { it.isNotBlank() }
            ?: AndroidSocksProxySettings.DEFAULT_USERNAME
        socksPassword = intent.getStringExtra(TurnboxVpnActions.EXTRA_SOCKS_PASSWORD).orEmpty()
        startForeground(
            if (connectionMode == AndroidConnectionMode.Proxy) {
                "Starting proxy..."
            } else {
                "Protecting your connection"
            }
        )
        wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
        registerNetworkMonitor()
        updateUnderlyingNetwork(findActiveUpstreamNetwork())
        startTunnel(isMigration = false)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup(stopService = false)
    }

    override fun onRevoke() {
        addLog("VPN permission revoked")
        cleanup()
        stopSelf()
        super.onRevoke()
    }

    private fun installMobileCallbacks() {
        Mobile.setProtector(object : SocketProtector {
            override fun protect(fd: Long): Boolean {
                if (connectionMode == AndroidConnectionMode.Proxy) return true
                return this@TurnboxVpnService.protect(fd.toInt())
            }
        })
        Mobile.setProviders()
        Mobile.setLogWriter(object : LogWriter {
            override fun writeLog(msg: String) {
                val line = msg.trimEnd()
                addLog("rtc: $line")
                Log.v("olcrtc", line)
                handleRtcLine(line)
            }
        })
    }

    private fun startTunnel(isMigration: Boolean) {
        startupJob?.cancel()
        watchdogJob?.cancel()
        if (!isMigration) {
            recoveryRequestedForGeneration = 0L
        }
        val requestedGeneration = ++generation

        startupJob = scope.launch {
            cleanupJob?.takeIf { it.isActive }?.let {
                addLog("Waiting for previous VPN stop to finish")
                val completed = withTimeoutOrNull(PREVIOUS_STOP_WAIT_MS) {
                    it.join()
                    true
                } ?: false

                if (!completed) {
                    addLog("Previous VPN stop is still pending; forcing transport cleanup")
                    it.cancel()
                    stopTransportProcesses(closeTun = true, waitForSocksPort = false)
                }
            }

            tunnelMutex.withLock {
                coroutineContext.ensureActive()
                if (requestedGeneration != generation) return@withLock

                val active = repository.getActiveLocation()
                val location = active?.location?.normalized()
                if (location == null || !location.isComplete()) {
                    setStatus(VpnStatus.Error("No active location"))
                    updateNotification("Add a location first")
                    stopTransportProcesses(closeTun = true, waitForSocksPort = false)
                    return@withLock
                }

                if (isMigration && canReconnectTransportInPlace()) {
                    reconnectTransport(location, requestedGeneration)
                } else {
                    startFullTunnel(location, requestedGeneration, isMigration)
                }
            }
        }
    }

    private suspend fun reconnectTransport(location: LocationConfig, requestedGeneration: Long) {
        setStatus(VpnStatus.Reconnecting)
        updateNotification("Reconnecting...")
        val upstream = findActiveUpstreamNetwork()
        if (upstream == null) {
            updateUnderlyingNetwork(null)
            unbindProcessFromNetwork()
            updateNotification("Waiting for network...")
            addLog("No upstream network; keeping tunnel alive")
            return
        }

        updateUnderlyingNetwork(upstream)
        stopMobileAndWait()
        coroutineContext.ensureActive()
        if (requestedGeneration != generation) return

        if (startMobile(location, upstream, setErrorOnFailure = false)) {
            setStatus(VpnStatus.Connected)
            recoveryRequestedForGeneration = 0L
            updateNotification(connectedNotificationText())
            addLog("${activeModeLabel()} transport reconnected")
            startWatchdog()
        } else {
            updateUnderlyingNetwork(null)
            setStatus(VpnStatus.Reconnecting)
            updateNotification("Waiting for transport...")
        }
    }

    private suspend fun startFullTunnel(
        location: LocationConfig,
        requestedGeneration: Long,
        isMigration: Boolean
    ) {
        setStatus(if (isMigration) VpnStatus.Reconnecting else VpnStatus.Connecting)
        updateNotification("Connecting...")
        stopTransportProcesses(closeTun = true, waitForSocksPort = false)
        coroutineContext.ensureActive()
        if (requestedGeneration != generation) return

        val socksPort = chooseAvailableSocksPort()
        if (socksPort == null) {
            setStatus(VpnStatus.Error("No free local SOCKS port"))
            updateNotification("Connection failed")
            return
        }
        localSocksPort = socksPort

        val upstream = findActiveUpstreamNetwork()
        if (upstream == null) {
            updateUnderlyingNetwork(null)
            unbindProcessFromNetwork()
            addLog("No upstream network")
            setStatus(VpnStatus.Reconnecting)
            updateNotification("Waiting for network...")
            return
        }
        updateUnderlyingNetwork(upstream)

        if (!startMobile(location, upstream, setErrorOnFailure = !isMigration)) {
            if (isMigration) {
                updateUnderlyingNetwork(null)
                setStatus(VpnStatus.Reconnecting)
                updateNotification("Waiting for transport...")
            }
            return
        }

        coroutineContext.ensureActive()
        if (requestedGeneration != generation) return

        if (connectionMode == AndroidConnectionMode.Proxy) {
            if (!startAuthenticatedSocksProxy()) {
                stopTransportProcesses(closeTun = true)
                return
            }
            setStatus(VpnStatus.Connected)
            recoveryRequestedForGeneration = 0L
            updateNotification(connectedNotificationText())
            addLog("Proxy mode connected on SOCKS ${AndroidSocksProxySettings.DEFAULT_HOST}:${AndroidSocksProxySettings.DEFAULT_PORT}")
            startWatchdog()
            return
        }

        delay(TUNNEL_HANDOFF_DELAY_MS)
        coroutineContext.ensureActive()

        val pfd = establishSystemVpnTunnel()
        if (pfd == null) {
            stopMobileAndWait()
            return
        }

        vpnInterface = pfd
        if (!startTun2socks(pfd)) {
            stopTransportProcesses(closeTun = true)
            return
        }

        coroutineContext.ensureActive()
        if (requestedGeneration != generation) return

        setStatus(VpnStatus.Connected)
        recoveryRequestedForGeneration = 0L
        updateNotification(connectedNotificationText())
        addLog("VPN tunnel established")
        startWatchdog()
    }

    private suspend fun startMobile(
        location: LocationConfig,
        upstream: Network,
        setErrorOnFailure: Boolean
    ): Boolean {
        val keepProcessBound = shouldKeepProcessBound(upstream)
        val config = location.normalized()
        return try {
            installMobileCallbacks()
            val socksPort = localSocksPort
            waitForSocksPortReleased(socksPort, SOCKS_RELEASE_QUICK_TIMEOUT_MS)
            if (isLocalSocksPortOpen(socksPort)) {
                throw IllegalStateException("SOCKS port $socksPort is still in use")
            }
            bindProcessToNetwork(upstream, "Bound to ${getNetName(upstream)}")
            configureMobileTransport(config)
            addLog(
                "Starting olcRTC provider=${config.bypassProvider}, " +
                    "transport=${config.transport}, room=${config.id}"
            )
            Mobile.start(
                config.bypassProvider,
                config.id,
                config.key,
                socksPort.toLong(),
                "",
                ""
            )
            Mobile.waitReady(MOBILE_READY_TIMEOUT_MS)
            addLog("olcRTC ready on 127.0.0.1:$socksPort")
            if (keepProcessBound) {
                addLog("Keeping olcRTC bound to ${getNetName(upstream)}")
            }
            true
        } catch (e: Exception) {
            addLog("olcRTC start failed: ${e.message}")
            unbindProcessFromNetwork()
            stopMobileAndWait()
            if (setErrorOnFailure) {
                setStatus(VpnStatus.Error(e.message ?: "Transport failed"))
                updateNotification("Connection failed")
            }
            false
        } finally {
            if (!keepProcessBound || !Mobile.isRunning()) {
                unbindProcessFromNetwork()
            }
        }
    }

    private fun configureMobileTransport(location: LocationConfig) {
        val config = location.normalized()
        Mobile.setProviders()
        Mobile.setLink("direct")
        Mobile.setTransport(config.transport)
        Mobile.setDNS("1.1.1.1:53")
        Mobile.setVP8Options(config.vp8Fps.toLong(), config.vp8Batch.toLong())
    }

    private fun startTun2socks(pfd: ParcelFileDescriptor): Boolean {
        return try {
            if (!ensureNativeLibrariesLoaded()) {
                addLog("tun2socks native libraries are unavailable")
                setStatus(VpnStatus.Error("tun2socks native libraries are unavailable"))
                updateNotification("Tunnel failed")
                return false
            }

            val nativeFd = ParcelFileDescriptor.dup(pfd.fileDescriptor).detachFd()
            val configFile = writeTun2socksConfig()
            tun2socksStarted = true
            tun2socksStopRequested = false
            tun2socksThread = thread(name = "TurnboxTun2Socks", isDaemon = true) {
                try {
                    val result = startTun2socksNative(configFile.absolutePath, nativeFd)
                    if (TurnboxVpnState.status.value !is VpnStatus.Stopping && result != 0) {
                        addLog("tun2socks exited with code $result")
                    } else {
                        addLog("tun2socks stopped")
                    }
                } finally {
                    tun2socksStarted = false
                    tun2socksStopRequested = false
                }
            }
            true
        } catch (e: Exception) {
            addLog("tun2socks start failed: ${e.message}")
            setStatus(VpnStatus.Error(e.message ?: "tun2socks failed"))
            updateNotification("Tunnel failed")
            false
        }
    }

    private fun establishSystemVpnTunnel(): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
                .setSession("Turnbox VPN")
                .setMtu(TUN_MTU)
                .addAddress(TUN_IPV4_ADDRESS, IPV4_PREFIX_LENGTH)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(MAPDNS_ADDRESS)
                .setBlocking(true)

            runCatching {
                builder.addDisallowedApplication(packageName)
            }.onFailure {
                addLog("Failed to exclude Turnbox from VPN: ${it.message}")
            }

            currentNetwork?.let { builder.setUnderlyingNetworks(arrayOf(it)) }
            builder.establish()
        } catch (e: Exception) {
            addLog("VPN establish failed: ${e.message}")
            setStatus(VpnStatus.Error(e.message ?: "VPN establish failed"))
            updateNotification("VPN tunnel error")
            null
        }
    }

    private fun writeTun2socksConfig(): File {
        val file = File(filesDir, TUN2SOCKS_CONFIG_FILE_NAME)
        file.writeText(
            """
            tunnel:
              name: tun0
              mtu: $TUN_MTU
              multi-queue: false
              ipv4: $TUN_IPV4_ADDRESS

            socks5:
              address: 127.0.0.1
              port: $localSocksPort
              udp: 'tcp'
              pipeline: false

            mapdns:
              address: $MAPDNS_ADDRESS
              port: 53
              network: $MAPDNS_NETWORK
              netmask: $MAPDNS_NETMASK
              cache-size: 10000

            misc:
              task-stack-size: 24576
              tcp-buffer-size: 4096
              max-session-count: 1200
              connect-timeout: 10000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 60000
              log-file: stderr
              log-level: warn
            """.trimIndent()
        )
        return file
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        val mode = connectionMode
        watchdogJob = scope.launch {
            while (isActive && TurnboxVpnState.status.value is VpnStatus.Connected) {
                delay(WATCHDOG_INTERVAL_MS)
                when {
                    !Mobile.isRunning() -> {
                        addLog("Watchdog: olcRTC stopped")
                        requestTransportRecovery("olcRTC stopped", fullRestart = false)
                        return@launch
                    }

                    mode == AndroidConnectionMode.Tun && tun2socksThread?.isAlive != true -> {
                        addLog("Watchdog: tun2socks stopped")
                        requestTransportRecovery("tun2socks stopped", fullRestart = true)
                        return@launch
                    }

                    mode == AndroidConnectionMode.Proxy && socksProxy?.isRunning != true -> {
                        addLog("Watchdog: SOCKS proxy stopped")
                        requestTransportRecovery("SOCKS proxy stopped", fullRestart = true)
                        return@launch
                    }
                }
            }
        }
    }

    private fun cleanup(stopService: Boolean = true) {
        val status = TurnboxVpnState.status.value
        if (status is VpnStatus.Disconnected &&
            vpnInterface == null &&
            tun2socksThread == null &&
            socksProxy == null &&
            cleanupJob?.isActive != true
        ) {
            if (stopService) stopSelf()
            return
        }
        if (status is VpnStatus.Stopping && cleanupJob?.isActive == true) return

        val cleanupGeneration = ++generation
        setStatus(VpnStatus.Stopping)
        startupJob?.cancel()
        watchdogJob?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }

        if (isCallbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
            isCallbackRegistered = false
        }
        stopAuthenticatedSocksProxy()
        updateUnderlyingNetwork(null)
        unbindProcessFromNetwork()

        cleanupVpnInterface()
        tun2socksThread?.interrupt()
        tun2socksThread = null

        cleanupJob = scope.launch {
            try {
                stopTransportProcesses(closeTun = true)
                recoveryRequestedForGeneration = 0L
                if (generation == cleanupGeneration) {
                    setStatus(VpnStatus.Disconnected)
                    addLog("${activeModeLabel()} stopped")
                }
            } finally {
                if (stopService && generation == cleanupGeneration) stopSelf()
            }
        }
    }

    private suspend fun stopTransportProcesses(
        closeTun: Boolean,
        waitForSocksPort: Boolean = true
    ) {
        stopAuthenticatedSocksProxy()
        stopTun2socks()
        if (closeTun) cleanupVpnInterface()
        tun2socksThread?.interrupt()
        tun2socksThread = null
        if (waitForSocksPort) {
            stopMobileAndWait()
        } else {
            stopMobile()
        }
        if (closeTun) {
            unbindProcessFromNetwork()
        }
    }

    private fun stopTun2socks() {
        if (nativeLibrariesLoaded && tun2socksStarted && !tun2socksStopRequested) {
            tun2socksStopRequested = true
            runCatching { stopTun2socksNative() }
        }
    }

    private fun stopMobile() {
        runCatching { Mobile.stop() }
    }

    private fun startAuthenticatedSocksProxy(): Boolean {
        if (socksPassword.isBlank()) {
            addLog("SOCKS proxy password is missing")
            setStatus(VpnStatus.Error("SOCKS proxy password is missing"))
            updateNotification("Proxy failed")
            return false
        }

        return try {
            stopAuthenticatedSocksProxy()
            socksProxy = AuthenticatedSocksProxy(
                listenPort = AndroidSocksProxySettings.DEFAULT_PORT,
                backendPort = localSocksPort,
                username = socksUsername,
                password = socksPassword,
                log = ::addLog
            ).also { it.start() }
            true
        } catch (e: Exception) {
            addLog("SOCKS proxy start failed: ${e.message}")
            setStatus(VpnStatus.Error(e.message ?: "SOCKS proxy failed"))
            updateNotification("Proxy failed")
            false
        }
    }

    private fun stopAuthenticatedSocksProxy() {
        socksProxy?.stop()
        socksProxy = null
    }

    private suspend fun stopMobileAndWait() {
        val socksPort = localSocksPort
        stopMobile()
        waitForSocksPortReleased(socksPort)
    }

    private suspend fun waitForSocksPortReleased(
        port: Int = localSocksPort,
        timeoutMs: Long = SOCKS_RELEASE_TIMEOUT_MS
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isLocalSocksPortOpen(port)) return
            delay(SOCKS_RELEASE_POLL_MS)
        }
        addLog("SOCKS port $port is still busy after stop")
    }

    private fun isLocalSocksPortOpen(port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress("127.0.0.1", port),
                    SOCKET_CONNECT_TIMEOUT_MS
                )
            }
        }.isSuccess
    }

    private fun chooseAvailableSocksPort(): Int? {
        repeat(LOCAL_SOCKS_PORT_MAX - LOCAL_SOCKS_PORT_BASE + 1) {
            val candidate = nextSocksPort
            nextSocksPort = if (candidate >= LOCAL_SOCKS_PORT_MAX) {
                LOCAL_SOCKS_PORT_BASE
            } else {
                candidate + 1
            }
            if (!isLocalSocksPortOpen(candidate)) return candidate
        }
        return null
    }

    private fun handleRtcLine(line: String) {
        val lowerLine = line.lowercase()

        if (lowerLine.contains("ice connection state changed: failed") ||
            lowerLine.contains("peer connection state changed: failed")
        ) {
            requestTransportRecovery("RTC failed", fullRestart = false)
            return
        }

        if (lowerLine.contains("ice connection state changed: closed") ||
            lowerLine.contains("peer connection state changed: closed")
        ) {
            if (!Mobile.isRunning()) {
                requestTransportRecovery("RTC closed", fullRestart = false)
            }
        }
    }

    private fun requestTransportRecovery(reason: String, fullRestart: Boolean) {
        if (TurnboxVpnState.status.value !is VpnStatus.Connected) return

        val recoveryGeneration = generation
        if (recoveryRequestedForGeneration == recoveryGeneration) return

        recoveryRequestedForGeneration = recoveryGeneration
        addLog("$reason; reconnecting transport")
        if (fullRestart) {
            scope.launch {
                tunnelMutex.withLock {
                    stopTun2socks()
                    cleanupVpnInterface()
                    tun2socksThread?.interrupt()
                    tun2socksThread = null
                }
                startTunnel(isMigration = true)
            }
        } else {
            startTunnel(isMigration = true)
        }
    }

    private fun cleanupVpnInterface() {
        runCatching { vpnInterface?.close() }
        vpnInterface = null
    }

    private fun canReconnectTransportInPlace(): Boolean {
        return when (connectionMode) {
            AndroidConnectionMode.Tun -> vpnInterface != null && tun2socksThread?.isAlive == true
            AndroidConnectionMode.Proxy -> Mobile.isRunning() && socksProxy?.isRunning == true
        }
    }

    private fun registerNetworkMonitor() {
        if (isCallbackRegistered) return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            isCallbackRegistered = true
            addLog("Network monitor registered")
        } catch (e: Exception) {
            Log.e(TAG, "Network monitor failed", e)
        }
    }

    private fun findActiveUpstreamNetwork(): Network? {
        val active = connectivityManager.activeNetwork
        val candidates = connectivityManager.allNetworks.mapNotNull { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.isUsableUpstream()) return@mapNotNull null
            network to UpstreamCandidate(
                isActive = network == active,
                isValidated = caps.isValidatedUpstream(),
                transport = caps.upstreamTransport()
            )
        }
        val selectedIndex = UpstreamNetworkSelector.selectIndex(candidates.map { it.second }) ?: return null
        return candidates[selectedIndex].first
    }

    private fun NetworkCapabilities.isUsableUpstream(): Boolean {
        return !hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun NetworkCapabilities.isValidatedUpstream(): Boolean {
        return isUsableUpstream() &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun NetworkCapabilities.upstreamTransport(): UpstreamTransport {
        return when {
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> UpstreamTransport.Wifi
            hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> UpstreamTransport.Cellular
            else -> UpstreamTransport.Other
        }
    }

    private fun updateUnderlyingNetwork(network: Network?) {
        currentNetwork = network
        if (connectionMode == AndroidConnectionMode.Tun || vpnInterface != null) {
            setUnderlyingNetworks(if (network != null) arrayOf(network) else null)
        }
    }

    private fun bindProcessToNetwork(network: Network?, successLog: String? = null) {
        try {
            connectivityManager.bindProcessToNetwork(network)
            if (successLog != null) addLog(successLog)
        } catch (e: Exception) {
            Log.w(TAG, "bindProcessToNetwork failed", e)
        }
    }

    private fun unbindProcessFromNetwork() {
        bindProcessToNetwork(null)
    }

    private fun getNetName(network: Network): String {
        val caps = connectivityManager.getNetworkCapabilities(network)
        return if (caps != null) getNetName(caps) else "Other"
    }

    private fun getNetName(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Other"
    }

    private fun shouldKeepProcessBound(network: Network): Boolean {
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun startForeground(statusText: String = "Protecting your connection") {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Turnbox VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(statusText),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
    }

    private fun updateNotification(status: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: String) =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Turnbox ${activeModeLabel()}")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(getAppPendingIntent())
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, TurnboxVpnService::class.java).apply { action = ACTION_STOP_VPN },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun getAppPendingIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun setStatus(status: VpnStatus) {
        TurnboxVpnState.setStatus(status)
    }

    private fun activeModeLabel(): String {
        return when (connectionMode) {
            AndroidConnectionMode.Tun -> "VPN"
            AndroidConnectionMode.Proxy -> "Proxy"
        }
    }

    private fun connectedNotificationText(): String = "${activeModeLabel()} Connected"

    private class AuthenticatedSocksProxy(
        private val listenPort: Int,
        private val backendPort: Int,
        private val username: String,
        private val password: String,
        private val log: (String) -> Unit
    ) {
        @Volatile
        private var stopped = false
        @Volatile
        private var serverSocket: ServerSocket? = null
        private var acceptThread: Thread? = null
        private val sockets = mutableSetOf<Socket>()

        val isRunning: Boolean
            get() = !stopped && serverSocket?.isClosed == false && acceptThread?.isAlive == true

        fun start() {
            stopped = false
            val server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(AndroidSocksProxySettings.DEFAULT_HOST, listenPort))
            }
            serverSocket = server
            acceptThread = thread(name = "TurnboxSocksProxy", isDaemon = true) {
                acceptLoop(server)
            }
            log("SOCKS proxy listening on ${AndroidSocksProxySettings.DEFAULT_HOST}:$listenPort")
        }

        fun stop() {
            stopped = true
            runCatching { serverSocket?.close() }
            synchronized(sockets) {
                sockets.forEach { socket -> runCatching { socket.close() } }
                sockets.clear()
            }
            acceptThread?.interrupt()
            acceptThread = null
            serverSocket = null
        }

        private fun acceptLoop(server: ServerSocket) {
            while (!stopped) {
                val client = runCatching { server.accept() }
                    .onFailure { if (!stopped) log("SOCKS proxy accept failed: ${it.message}") }
                    .getOrNull() ?: continue

                synchronized(sockets) { sockets.add(client) }
                thread(name = "TurnboxSocksProxyClient", isDaemon = true) {
                    try {
                        handleClient(client)
                    } finally {
                        synchronized(sockets) { sockets.remove(client) }
                        runCatching { client.close() }
                    }
                }
            }
        }

        private fun handleClient(client: Socket) {
            val clientIn = DataInputStream(client.getInputStream())
            val clientOut = DataOutputStream(client.getOutputStream())
            if (!authenticate(clientIn, clientOut)) return

            Socket().use { backend ->
                backend.connect(
                    InetSocketAddress(AndroidSocksProxySettings.DEFAULT_HOST, backendPort),
                    SOCKET_CONNECT_TIMEOUT_MS
                )
                val backendIn = DataInputStream(backend.getInputStream())
                val backendOut = DataOutputStream(backend.getOutputStream())

                backendOut.write(byteArrayOf(SOCKS_VERSION, 0x01, SOCKS_METHOD_NO_AUTH))
                backendOut.flush()
                if (backendIn.readUnsignedByte() != SOCKS_VERSION.toInt()) return
                if (backendIn.readUnsignedByte() == SOCKS_METHOD_NO_ACCEPTABLE.toInt()) return

                val c2b = relay(client, backend, "client-to-backend")
                val b2c = relay(backend, client, "backend-to-client")
                c2b.join()
                runCatching { backend.close() }
                runCatching { client.close() }
                b2c.join(RELAY_JOIN_TIMEOUT_MS)
            }
        }

        private fun authenticate(input: DataInputStream, output: DataOutputStream): Boolean {
            if (input.readUnsignedByte() != SOCKS_VERSION.toInt()) return false
            val methodCount = input.readUnsignedByte()
            var supportsPassword = false
            repeat(methodCount) {
                if (input.readUnsignedByte() == SOCKS_METHOD_USERNAME_PASSWORD.toInt()) {
                    supportsPassword = true
                }
            }
            if (!supportsPassword) {
                output.write(byteArrayOf(SOCKS_VERSION, SOCKS_METHOD_NO_ACCEPTABLE))
                output.flush()
                return false
            }

            output.write(byteArrayOf(SOCKS_VERSION, SOCKS_METHOD_USERNAME_PASSWORD))
            output.flush()

            if (input.readUnsignedByte() != SOCKS_AUTH_VERSION.toInt()) return false
            val userBytes = ByteArray(input.readUnsignedByte())
            input.readFully(userBytes)
            val passwordBytes = ByteArray(input.readUnsignedByte())
            input.readFully(passwordBytes)

            val accepted = userBytes.decodeToString() == username &&
                passwordBytes.decodeToString() == password
            output.write(byteArrayOf(SOCKS_AUTH_VERSION, if (accepted) 0x00 else 0x01))
            output.flush()
            return accepted
        }

        private fun relay(from: Socket, to: Socket, name: String): Thread {
            return thread(name = "TurnboxSocksRelay-$name", isDaemon = true) {
                runCatching {
                    from.getInputStream().copyTo(to.getOutputStream(), RELAY_BUFFER_SIZE)
                }
                runCatching { to.shutdownOutput() }
                runCatching { from.shutdownInput() }
            }
        }

        private companion object {
            const val SOCKS_VERSION: Byte = 0x05
            const val SOCKS_AUTH_VERSION: Byte = 0x01
            const val SOCKS_METHOD_NO_AUTH: Byte = 0x00
            const val SOCKS_METHOD_USERNAME_PASSWORD: Byte = 0x02
            const val SOCKS_METHOD_NO_ACCEPTABLE: Byte = 0xFF.toByte()
            const val SOCKET_CONNECT_TIMEOUT_MS = 1_000
            const val RELAY_BUFFER_SIZE = 16 * 1024
            const val RELAY_JOIN_TIMEOUT_MS = 500L
        }
    }

    companion object {
        @Volatile
        private var nativeLibrariesLoaded = false
        private var nativeLibrariesLoadError: Throwable? = null
        private val nativeLibrariesLock = Any()

        private fun ensureNativeLibrariesLoaded(): Boolean {
            if (nativeLibrariesLoaded) return true
            nativeLibrariesLoadError?.let { return false }

            return synchronized(nativeLibrariesLock) {
                if (nativeLibrariesLoaded) {
                    true
                } else {
                    try {
                        System.loadLibrary("hev-socks5-tunnel")
                        System.loadLibrary("turnbox_tun2socks")
                        nativeLibrariesLoaded = true
                        true
                    } catch (e: UnsatisfiedLinkError) {
                        nativeLibrariesLoadError = e
                        Log.e(TAG, "Failed to load native tun2socks libraries", e)
                        false
                    }
                }
            }
        }

        const val ACTION_START_VPN = TurnboxVpnActions.ACTION_START_VPN
        const val ACTION_STOP_VPN = TurnboxVpnActions.ACTION_STOP_VPN

        private const val LOCAL_SOCKS_PORT_BASE = 10818
        private const val LOCAL_SOCKS_PORT_MAX = 10858
        private const val MOBILE_READY_TIMEOUT_MS = 25_000L
        private const val PREVIOUS_STOP_WAIT_MS = 2_000L
        private const val TUNNEL_HANDOFF_DELAY_MS = 300L
        private const val NETWORK_LOSS_FALLBACK_DELAY_MS = 300L
        private const val WATCHDOG_INTERVAL_MS = 5_000L
        private const val SOCKS_RELEASE_TIMEOUT_MS = 2_500L
        private const val SOCKS_RELEASE_QUICK_TIMEOUT_MS = 500L
        private const val SOCKS_RELEASE_POLL_MS = 100L
        private const val SOCKET_CONNECT_TIMEOUT_MS = 150
        private const val WAKE_LOCK_TIMEOUT_MS = 24 * 60 * 60 * 1000L
        private const val TUN_MTU = 1500
        private const val TUN_IPV4_ADDRESS = "10.0.88.88"
        private const val IPV4_PREFIX_LENGTH = 24
        private const val MAPDNS_ADDRESS = "1.1.1.1"
        private const val MAPDNS_NETWORK = "100.64.0.0"
        private const val MAPDNS_NETMASK = "255.192.0.0"
        private const val NOTIFICATION_CHANNEL_ID = "turnbox_vpn"
        private const val NOTIFICATION_ID = 100
        private const val TAG = "TurnboxVpnService"

        private fun addLog(msg: String) {
            TurnboxVpnState.addLog(msg)
        }
    }
}
