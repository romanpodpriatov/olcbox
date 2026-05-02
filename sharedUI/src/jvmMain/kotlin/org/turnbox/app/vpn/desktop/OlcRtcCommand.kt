package org.turnbox.app.vpn.desktop

import org.turnbox.app.data.model.LocationConfig
import java.nio.file.Path

internal data class OlcRtcCommand(
    val binary: Path,
    val location: LocationConfig,
    val socksHost: String = PacServer.LOCAL_SOCKS_HOST,
    val socksPort: Int = PacServer.LOCAL_SOCKS_PORT
) {
    fun args(): List<String> {
        val config = location.normalized()
        val provider = desktopProviderArg(config.bypassProvider)
        return listOf(
            binary.toString(),
            "-mode", "cnc",
            "-link", "direct",
            "-transport", config.transport,
            "-provider", provider,
            "-id", config.id,
            "-key", config.key,
            "-socks-host", socksHost,
            "-socks-port", socksPort.toString(),
            "-dns", "1.1.1.1:53",
            "-vp8-fps", config.vp8Fps.toString(),
            "-vp8-batch", config.vp8Batch.toString()
        )
    }

    companion object {
        fun desktopProviderArg(provider: String): String {
            val normalizedProvider = LocationConfig.normalizeProvider(provider)
            return when (normalizedProvider) {
                LocationConfig.PROVIDER_WB_STREAM -> "wbstream"
                else -> normalizedProvider
            }
        }
    }
}
