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
 *
 * Re-verified for 1.13.14: every shape this builds — socks+vless/reality,
 * tun+hysteria2, tun+socks, socks+socks, and tun+socks over a TCP-only upstream
 * — passes `sing-box check` on the 1.13.14 binary. Most of it survives version
 * bumps by staying minimal, emitting none of the inbound fields 1.13 removed.
 *
 * The one shape that does reach for newer schema is the TCP-only upstream: it
 * emits a `dns` server in the typed 1.12+ format and a `route` rule using the
 * `action` form. Those two are the parts to re-check first on the next bump.
 */
object SingBoxConfig {
    /** Pinned sing-box release whose config schema this builder targets. */
    const val SINGBOX_VERSION = "1.13.14"
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
    ): String = renderTun(address, mtu, tcpOnlyUpstream = false) { addOutbound(outbound) }

    /**
     * Config for a core that owns the tun and hands the traffic to another core
     * listening on a local SOCKS port.
     *
     * This is how xhttp works on iOS: sing-box cannot speak that transport, so
     * Xray runs beside it in the same extension and sing-box becomes the tun
     * front-end for it. Android reaches the same arrangement from the other
     * direction — there a separate tun2socks feeds whichever core is running.
     */
    fun buildTunSocks(
        socksPort: Int,
        username: String = "",
        password: String = "",
        upstreamCarriesUdp: Boolean = true,
        address: String = TUN_ADDRESS,
        mtu: Int = TUN_MTU,
    ): String = renderTun(address, mtu, tcpOnlyUpstream = !upstreamCarriesUdp) {
        addJsonObject {
            put("type", "socks"); put("tag", "out")
            put("server", "127.0.0.1"); put("server_port", socksPort)
            put("version", "5")
            // Sent only when the core on the other end asked for them, which is
            // olcRTC and only olcRTC: it refuses the connection outright when
            // started with a credential pair and offered none, and on iOS it
            // always is — the app generates one on first run. That produced a
            // tunnel that came up, carried its own media perfectly, and passed
            // not one user connection. Xray's inbound has no auth, so for xhttp
            // these stay absent exactly as before.
            if (username.isNotBlank()) put("username", username)
            if (password.isNotBlank()) put("password", password)
        }
    }

    /** Resolver reached over the tunnel when the upstream cannot carry UDP. */
    private const val TCP_DNS_SERVER = "1.1.1.1"

    private fun renderTun(
        address: String,
        mtu: Int,
        tcpOnlyUpstream: Boolean,
        outbounds: JsonArrayBuilder.() -> Unit,
    ): String {
        val obj = buildJsonObject {
            putJsonObject("log") { put("level", "info") }
            // A tun in front of a TCP-only upstream has one fatal gap: the
            // device sends DNS as UDP to the resolver in the tunnel settings,
            // and those datagrams have nowhere to go. Nothing resolves, so no
            // app ever opens a socket — the tunnel looks perfectly connected
            // and the browser stays blank. Proven on olcRTC: its server logged
            // real traffic to Telegram and Meta, which dial hardcoded IPs,
            // while Safari made no connection at all.
            //
            // So sing-box answers DNS itself, over TCP, through the same
            // upstream. Emitted only where it is needed — every other
            // transport here carries UDP natively and resolves as before.
            if (tcpOnlyUpstream) {
                putJsonObject("dns") {
                    putJsonArray("servers") {
                        addJsonObject {
                            put("type", "tcp"); put("tag", "dns-remote")
                            put("server", TCP_DNS_SERVER); put("detour", "out")
                        }
                    }
                }
            }
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
            if (tcpOnlyUpstream) {
                putJsonObject("route") {
                    putJsonArray("rules") {
                        // Order matters: DNS is claimed before the blanket UDP
                        // rule below can swallow it.
                        addJsonObject { put("action", "hijack-dns"); put("port", 53) }
                        // Refused rather than dropped, so a QUIC attempt fails
                        // at once and the client falls back to TCP instead of
                        // waiting out a timeout on every request.
                        addJsonObject { put("action", "reject"); put("network", "udp") }
                    }
                }
            }
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
                // Deliberately loud rather than best-effort. sing-box has no
                // xhttp transport (see XrayConfig), and emitting one anyway
                // produced a config the core silently refused — on iOS that
                // looked like a tunnel that connected and carried nothing.
                // xhttp belongs to Xray; callers route it there.
                require(spec.transport !is TransportSpec.Xhttp) {
                    "sing-box cannot speak xhttp — build this location with XrayConfig.buildXhttp"
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
