package org.olcbox.app.net

import android.content.Context

/**
 * Android [SingBoxController]: execs the bundled `sing-box` binary (packaged as
 * libsingboxcore.so in jniLibs) in SOCKS-inbound mode. The hev-socks5-tunnel
 * bridge feeds its SOCKS port.
 */
class AndroidSingBoxController(context: Context) : SingBoxController {
    private val proc = AndroidCoreProcess(
        context = context,
        soName = "libsingboxcore.so",
        label = "singbox",
        argv = { bin, config -> listOf(bin, "run", "-c", config) },
    )

    override suspend fun start(configJson: String) = proc.start(configJson)
    override suspend fun stop() = proc.stop()
    /** Non-suspend stop for the VpnService stop paths (which are not coroutines). */
    fun stopNow() = proc.stop()
    fun isRunning(): Boolean = proc.isRunning()
}
