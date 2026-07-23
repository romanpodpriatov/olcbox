package org.olcbox.app.net

import org.olcbox.app.data.model.LocationConfig

/** Starts/stops sing-box (SOCKS inbound). Implemented per-platform in Task 11. */
interface SingBoxController {
    suspend fun start(configJson: String)
    suspend fun stop()
}

/** Starts/stops the olcrtc engine (SOCKS on [olcrtcSocksPort]). Platform-implemented. */
interface OlcrtcController {
    val olcrtcSocksPort: Int
    suspend fun start(location: LocationConfig)
    suspend fun stop()
}

/**
 * Chooses the engine(s) for a selected location and starts them so the existing
 * tun→SOCKS bridge can point at sing-box's SOCKS inbound:
 *  - olcrtc location → start the olcrtc engine child, then sing-box with a
 *    `socks` outbound to it.
 *  - vless/hysteria2 location → start sing-box with a native outbound; no child.
 */
class EngineDispatcher(
    private val singBox: SingBoxController,
    private val olcrtc: OlcrtcController,
) {
    suspend fun start(location: LocationConfig) {
        when (location.kind) {
            LocationKind.Olcrtc -> {
                olcrtc.start(location)
                singBox.start(SingBoxConfig.buildOlcrtcSocks(olcrtc.olcrtcSocksPort))
            }
            LocationKind.Vless, LocationKind.Hysteria2 -> {
                val raw = location.rawLink
                    ?: throw IllegalArgumentException("${location.kind} location has no rawLink")
                val spec = LinkParser.parse(raw)
                    ?: throw IllegalArgumentException("unparseable rawLink for ${location.kind}: $raw")
                singBox.start(SingBoxConfig.build(spec))
            }
        }
    }

    suspend fun stop() {
        singBox.stop()
        olcrtc.stop()
    }
}
