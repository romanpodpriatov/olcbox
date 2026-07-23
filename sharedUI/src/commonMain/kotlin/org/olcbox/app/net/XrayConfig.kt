package org.olcbox.app.net

import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds an Xray-core config JSON: one SOCKS inbound + one vless outbound over
 * the XHTTP transport with Reality. sing-box cannot speak Xray's XHTTP (verified
 * via `sing-box check`), so xhttp locations are handled by Xray-core instead; the
 * existing tun→SOCKS bridge feeds this SOCKS inbound exactly like the sing-box path.
 *
 * The JSON schema is tied to the pinned Xray release [XRAY_VERSION]; validated in
 * CI with `xray test` against the real binary.
 */
object XrayConfig {
    /** Pinned Xray-core release whose config schema this builder targets. */
    const val XRAY_VERSION = "25.3.6"
    const val XRAY_SOCKS_PORT = 10809

    /**
     * Build an Xray config for a vless+xhttp+reality location. Requires the spec's
     * transport to be [TransportSpec.Xhttp] (Xray's role here is xhttp only).
     */
    fun buildXhttp(spec: OutboundSpec.Vless, socksPort: Int = XRAY_SOCKS_PORT): String {
        val xhttp = spec.transport as? TransportSpec.Xhttp
            ?: error("XrayConfig.buildXhttp requires an xhttp transport")
        val obj = buildJsonObject {
            putJsonObject("log") { put("loglevel", "warning") }
            putJsonArray("inbounds") {
                addJsonObject {
                    put("tag", "in"); put("listen", "127.0.0.1"); put("port", socksPort)
                    put("protocol", "socks")
                    putJsonObject("settings") { put("udp", true) }
                }
            }
            putJsonArray("outbounds") {
                addJsonObject {
                    put("tag", "out"); put("protocol", "vless")
                    putJsonObject("settings") {
                        putJsonArray("vnext") {
                            addJsonObject {
                                put("address", spec.host); put("port", spec.port)
                                putJsonArray("users") {
                                    addJsonObject {
                                        put("id", spec.uuid); put("encryption", "none")
                                    }
                                }
                            }
                        }
                    }
                    putJsonObject("streamSettings") {
                        put("network", "xhttp")
                        put("security", "reality")
                        putJsonObject("realitySettings") {
                            put("serverName", spec.sni)
                            put("fingerprint", spec.fingerprint)
                            put("publicKey", spec.publicKey)
                            put("shortId", spec.shortId)
                        }
                        putJsonObject("xhttpSettings") {
                            put("path", xhttp.path)
                            put("host", xhttp.host)
                            put("mode", xhttp.mode)
                        }
                    }
                }
            }
        }
        return obj.toString()
    }
}
