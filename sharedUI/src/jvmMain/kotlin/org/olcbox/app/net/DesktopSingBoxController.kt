package org.olcbox.app.net

import org.olcbox.app.vpn.desktop.DesktopNativeAssets
import java.nio.file.Path

/**
 * Desktop [SingBoxController]: runs the bundled `sing-box` binary in SOCKS-inbound
 * mode with the generated config. `binaryPath` is injectable so tests can point at
 * a downloaded binary; production resolves the bundled one.
 */
class DesktopSingBoxController(
    binaryPath: () -> Path = { DesktopNativeAssets.resolveSingBoxBinary() },
) : SingBoxController {
    private val proc = DesktopCoreProcess(
        binaryPath = binaryPath,
        label = "singbox",
        argv = { bin, config -> listOf(bin, "run", "-c", config.toString()) },
    )

    override suspend fun start(configJson: String) = proc.start(configJson)
    override suspend fun stop() = proc.stop()
    fun isRunning(): Boolean = proc.isRunning()
}
