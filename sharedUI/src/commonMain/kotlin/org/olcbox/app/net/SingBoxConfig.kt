package org.olcbox.app.net

import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds a minimal sing-box config JSON: one SOCKS inbound + one outbound.
 * The existing tun→SOCKS bridge (hev-socks5-tunnel on Android, PAC on Desktop)
 * feeds this SOCKS inbound; the outbound is a native vless/hy2/xhttp outbound or
 * a socks outbound to the olcrtc engine.
 *
 * The JSON schema is tied to the pinned sing-box release [SINGBOX_VERSION].
 * Bumping sing-box is a deliberate change: re-verify this builder against the
 * new schema and device-smoke before shipping.
 */
object SingBoxConfig {
    /** Pinned sing-box release whose config schema this builder targets. */
    const val SINGBOX_VERSION = "1.11.15"
    // NOT 10809: the desktop PAC server (PacServer.PAC_PORT) owns that port, and the
    // core binding it first made every desktop connect fail with "Address already in use".
    const val SINGBOX_SOCKS_PORT = 10810

    fun build(outbound: OutboundSpec, socksPort: Int = SINGBOX_SOCKS_PORT): String =
        render(socksPort) { addOutbound(outbound) }

    /// iOS addressing. Fixed rather than negotiated: the extension applies these
    /// same values to the system when it hands the core its descriptor, so the two
    /// halves have to agree and one constant is easier to keep honest than two.
    const val TUN_ADDRESS = "172.19.0.1/30"
    const val TUN_MTU = 9000

    /**
     * Config for a core that owns the tun itself, as on iOS.
     *
     * The desktop and Android builds put the core behind a SOCKS port and bridge
     * packets into it separately. In a Network Extension there is no room for that
     * hop — the core is handed the tunnel descriptor and does the whole job.
     *
     * The gvisor stack is not a preference: the system stack needs raw-socket
     * privileges the extension sandbox withholds, and a tun built on it comes up
     * and forwards nothing.
     */
    fun buildTun(
        outbound: OutboundSpec,
        address: String = TUN_ADDRESS,
        mtu: Int = TUN_MTU,
    ): String = renderTun(address, mtu) { addOutbound(outbound) }

    private fun renderTun(
        address: String,
        mtu: Int,
        outbounds: JsonArrayBuilder.() -> Unit,
    ): String {
        val obj = buildJsonObject {
            putJsonObject("log") { put("level", "info") }
            putJsonArray("inbounds") {
                addJsonObject {
                    put("type", "tun"); put("tag", "tun-in")
                    putJsonArray("address") { add(address) }
                    put("mtu", mtu)
                    put("auto_route", true)
                    put("stack", "gvisor")
                }
            }
            putJsonArray("outbounds", outbounds)
        }
        return obj.toString()
    }

    fun buildOlcrtcSocks(olcrtcPort: Int, socksPort: Int = SINGBOX_SOCKS_PORT): String =
        render(socksPort) {
            addJsonObject {
                put("type", "socks"); put("tag", "olcrtc")
                put("server", "127.0.0.1"); put("server_port", olcrtcPort)
                put("version", "5")
            }
        }

    private fun render(socksPort: Int, outbounds: JsonArrayBuilder.() -> Unit): String {
        val obj = buildJsonObject {
            putJsonArray("inbounds") {
                addJsonObject {
                    put("type", "socks"); put("tag", "in")
                    put("listen", "127.0.0.1"); put("listen_port", socksPort)
                }
            }
            putJsonArray("outbounds", outbounds)
        }
        return obj.toString()
    }

    private fun JsonArrayBuilder.addOutbound(spec: OutboundSpec) {
        when (spec) {
            is OutboundSpec.Vless -> addJsonObject {
                put("type", "vless"); put("tag", "out")
                put("server", spec.host); put("server_port", spec.port)
                put("uuid", spec.uuid); put("packet_encoding", "xudp")
                if (spec.flow != null) put("flow", spec.flow)
                putJsonObject("tls") {
                    put("enabled", true); put("server_name", spec.sni)
                    putJsonObject("utls") { put("enabled", true); put("fingerprint", spec.fingerprint) }
                    putJsonObject("reality") {
                        put("enabled", true); put("public_key", spec.publicKey); put("short_id", spec.shortId)
                    }
                }
                val t = spec.transport
                if (t is TransportSpec.Xhttp) {
                    putJsonObject("transport") {
                        put("type", "xhttp"); put("path", t.path); put("host", t.host)
                    }
                }
            }
            is OutboundSpec.Hysteria2 -> addJsonObject {
                put("type", "hysteria2"); put("tag", "out")
                put("server", spec.host); put("server_port", spec.port)
                put("password", spec.password)
                if (spec.obfsPassword != null) {
                    putJsonObject("obfs") {
                        put("type", "salamander"); put("password", spec.obfsPassword)
                    }
                }
                putJsonObject("tls") {
                    put("enabled", true); put("server_name", spec.sni)
                    // A published pin means the server presents a self-signed
                    // certificate that no CA store can validate. sing-box has no
                    // pinning option, so verifying against the system store would
                    // reject every connection — which is exactly what it did. Skip
                    // verification in that case; the Salamander obfuscation and the
                    // auth password still gate the connection, but note that the
                    // fingerprint the operator published is NOT being checked.
                    put("insecure", spec.insecure || spec.certPinSha256 != null)
                }
            }
        }
    }
}
