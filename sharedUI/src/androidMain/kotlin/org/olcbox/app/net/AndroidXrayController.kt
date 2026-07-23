package org.olcbox.app.net

import android.content.Context

/**
 * Android [XrayController]: execs the bundled `xray` binary (packaged as
 * libxraycore.so in jniLibs) in SOCKS-inbound mode — used for xhttp locations.
 */
class AndroidXrayController(context: Context) : XrayController {
    private val proc = AndroidCoreProcess(
        context = context,
        soName = "libxraycore.so",
        label = "xray",
        argv = { bin, config -> listOf(bin, "run", "-c", config) },
    )

    override suspend fun start(configJson: String) = proc.start(configJson)
    override suspend fun stop() = proc.stop()
    fun isRunning(): Boolean = proc.isRunning()
}
