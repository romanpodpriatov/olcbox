package org.olcbox.app.net

import org.olcbox.app.vpn.desktop.DesktopNativeAssets
import java.nio.file.Path

/**
 * Desktop [XrayController]: runs the bundled `xray` binary in SOCKS-inbound mode
 * with the generated Xray config (used for xhttp locations). `binaryPath` is
 * injectable for tests.
 */
class DesktopXrayController(
    binaryPath: () -> Path = { DesktopNativeAssets.resolveXrayBinary() },
) : XrayController {
    private val proc = DesktopCoreProcess(
        binaryPath = binaryPath,
        label = "xray",
        argv = { bin, config -> listOf(bin, "run", "-c", config.toString()) },
    )

    override suspend fun start(configJson: String) = proc.start(configJson)
    override suspend fun stop() = proc.stop()
    fun isRunning(): Boolean = proc.isRunning()
}
