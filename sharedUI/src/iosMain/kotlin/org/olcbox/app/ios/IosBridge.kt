package org.olcbox.app.ios

data class IosOlcRtcStartRequest(
    val carrierName: String,
    val transportName: String,
    val roomId: String,
    val clientId: String,
    val keyHex: String,
    val socksPort: Int,
    val socksUser: String,
    val socksPass: String,
    val vp8Fps: Int,
    val vp8BatchSize: Int
)

data class IosOlcRtcCheckRequest(
    val carrierName: String,
    val transportName: String,
    val roomId: String,
    val clientId: String,
    val keyHex: String,
    val timeoutMillis: Long,
    val pingUrl: String,
    val vp8Fps: Int,
    val vp8BatchSize: Int
)

data class IosBridgeResult(
    val success: Boolean,
    val message: String?
)

data class IosLongResult(
    val success: Boolean,
    val valueMillis: Long,
    val message: String?
)

interface IosLogWriter {
    fun writeLog(message: String)
}

interface IosTextCallback {
    fun onSuccess(text: String)
    fun onError(message: String)
}

interface IosMessageCallback {
    fun onSuccess(message: String)
    fun onError(message: String)
}

interface IosOlcRtcBridge {
    fun setLogWriter(writer: IosLogWriter?)
    fun start(request: IosOlcRtcStartRequest): IosBridgeResult
    fun stop()
    fun isRunning(): Boolean
    fun ping(request: IosOlcRtcCheckRequest): IosLongResult
    fun check(request: IosOlcRtcCheckRequest): IosLongResult
}

/** Delivers the answer to an asynchronous bridge call, exactly once. */
interface IosBridgeCallback {
    fun onResult(result: IosBridgeResult)
}

/**
 * Everything the extension needs to bring one location up.
 *
 * [config] is a complete sing-box config with a tun inbound. At most one of the
 * other two is ever set, and for reality and hysteria2 neither is: sing-box
 * borrows a second core only for the transports it does not implement itself.
 *
 * [xrayConfig] is an Xray config for xhttp, and [olcrtc] the parameters for our
 * own engine. In both cases the borrowed core listens on a loopback SOCKS port
 * and sing-box, which owns the tun, reaches it there.
 */
data class IosPacketTunnelStartRequest(
    val config: String,
    val xrayConfig: String?,
    val olcrtc: IosOlcRtcStartRequest?
)

/**
 * Starting and stopping the packet tunnel extension, which only the app can ask
 * the system to launch. Every transport goes through it, olcRTC included.
 */
interface IosPacketTunnelBridge {
    /**
     * Answers through [callback] once the tunnel is up or has failed.
     *
     * Asynchronous because it cannot honestly be otherwise: the extension takes
     * seconds to settle, and the synchronous shape this replaces held a
     * coroutine thread on a semaphore for all of them — which the Swift runtime
     * reports as `unsafeForcedSync called from Swift Concurrent context`, and
     * which can deadlock the cooperative pool outright.
     */
    fun start(request: IosPacketTunnelStartRequest, callback: IosBridgeCallback)
    fun stop()
    fun isRunning(): Boolean
}

interface IosPlatformBridge {
    fun readClipboard(): String?
    fun writeClipboard(text: String)
    fun pickConfigText(callback: IosTextCallback)
    fun shareText(title: String, text: String)
    fun saveLogs(defaultName: String, content: String, callback: IosMessageCallback)
    fun shareLogs(defaultName: String, content: String, callback: IosMessageCallback)
    fun showMessage(message: String)

    /** Opens an https link in the system browser. */
    fun openUrl(url: String)
}
