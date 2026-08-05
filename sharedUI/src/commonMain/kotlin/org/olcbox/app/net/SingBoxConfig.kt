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
 * tun+hysteria2, tun+socks, socks+socks, and tun+socks resolving over TCP —
 * passes `sing-box check` on the 1.13.14 binary. Most of it survives version
 * bumps by staying minimal, emitting none of the inbound fields 1.13 removed.
 *
 * The one shape that does reach for newer schema is the resolve-over-TCP case:
 * it emits a `dns` server in the typed 1.12+ format and a `route` rule using
 * the `action` form. Those two are the parts to re-check first on the next bump.
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
     * Desktop addressing, and it is not the iOS one.
     *
     * iOS rejects an MTU of 9000 outright (`nesessionmanager: failed to set the
     * MTU to 9000`) while the config that asked for it carries on claiming it. A
     * utun on macOS is no place to find out whether the same is true, so the
     * desktop shape uses the 1500 the Linux and Windows controllers have always
     * used.
     */
    const val DESKTOP_TUN_ADDRESS = "172.19.0.1/30"

    /**
     * The tun needs an IPv6 address even though no IPv6 is carried, because
     * `auto_route` only claims the families the interface has one for.
     *
     * Without it the machine keeps its IPv6 default route on the physical
     * interface and every dual-stack site is reached over IPv6, outside the
     * tunnel, at the machine's real address. That is not a corner case: browsers
     * prefer IPv6, so on a dual-stack network *the browser* leaks while
     * `curl api.ipify.org` — an A record only — keeps reporting the tunnel and
     * looking fine.
     */
    const val DESKTOP_TUN_ADDRESS6 = "fdfe:dcba:9876::1/126"
    const val DESKTOP_TUN_MTU = 1500

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
    ): String = renderTun(address, mtu, resolveOverTcp = false) { addOutbound(outbound) }

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
        upstreamUdpIsLossy: Boolean = false,
        address: String = TUN_ADDRESS,
        mtu: Int = TUN_MTU,
    ): String = renderTun(address, mtu, resolveOverTcp = upstreamUdpIsLossy) {
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

    /**
     * Config for the sing-box the macOS root daemon runs: a tun in front of the
     * core the app already started on localhost.
     *
     * Two things here exist only because the core is a *separate process*. On iOS
     * the outbound lives inside the same binary and neither is needed:
     *
     * [excludeAddresses] keeps the core's own packets to the VPN server out of the
     * tun. `auto_route` points the default route at the tunnel, and without an
     * exclusion the core's upstream connection is routed into the tunnel it is
     * trying to build. That does not degrade — it deadlocks, and it reads as a
     * broken server rather than a missing route.
     *
     * [directDnsDomains] does the same for name resolution. The core redials by
     * hostname, that query enters the tun like any other, and answering it needs
     * the tunnel being redialled; those names go to the system resolver instead.
     *
     * [verifyPort] is a second socks inbound, for `TunnelVerifier`. Verifying
     * through the core's own port would prove the core works and say nothing
     * about the tun in front of it — which is the half that is new here, so it is
     * the half a green light has to be about.
     *
     * This is not [renderTun]: that one emits neither an exclusion nor a second
     * inbound, and bending it to would leave the iOS shape carrying desktop
     * concerns it has no use for.
     */
    fun buildDesktopTun(
        corePort: Int,
        verifyPort: Int,
        username: String = "",
        password: String = "",
        excludeAddresses: List<String> = emptyList(),
        directDnsDomains: List<String> = emptyList(),
        upstreamUdpIsLossy: Boolean = false,
        address: String = DESKTOP_TUN_ADDRESS,
        address6: String = DESKTOP_TUN_ADDRESS6,
        mtu: Int = DESKTOP_TUN_MTU,
    ): String {
        val obj = buildJsonObject {
            putJsonObject("log") { put("level", "info") }
            putJsonObject("dns") {
                putJsonArray("servers") {
                    // Order is the default. The first server answers anything no
                    // rule claims, so `dns-remote` has to come first whenever
                    // queries are hijacked here at all — put the local one first
                    // and every hijacked lookup leaves the machine unprotected.
                    if (upstreamUdpIsLossy) {
                        addJsonObject {
                            put("type", "tcp"); put("tag", "dns-remote")
                            put("server", TCP_DNS_SERVER); put("detour", "out")
                        }
                    }
                    addJsonObject { put("type", "local"); put("tag", "dns-direct") }
                }
                if (directDnsDomains.isNotEmpty()) {
                    putJsonArray("rules") {
                        addJsonObject {
                            putJsonArray("domain") { directDnsDomains.forEach { add(it) } }
                            put("server", "dns-direct")
                        }
                    }
                }
            }
            putJsonArray("inbounds") {
                addJsonObject {
                    put("type", "tun"); put("tag", "tun-in")
                    putJsonArray("address") { add(address); add(address6) }
                    put("mtu", mtu)
                    put("auto_route", true)
                    put("stack", "gvisor")
                    if (excludeAddresses.isNotEmpty()) {
                        putJsonArray("route_exclude_address") {
                            excludeAddresses.forEach { add(it) }
                        }
                    }
                }
                addJsonObject {
                    put("type", "socks"); put("tag", "verify-in")
                    put("listen", "127.0.0.1"); put("listen_port", verifyPort)
                }
            }
            putJsonArray("outbounds") {
                addJsonObject {
                    put("type", "socks"); put("tag", "out")
                    put("server", "127.0.0.1"); put("server_port", corePort)
                    put("version", "5")
                    // Only when the core on the other end asked for them, which is
                    // olcRTC and only olcRTC: it refuses a connection offered none
                    // when it was started with a pair, and the app generates one on
                    // first run. Xray's inbound has no auth.
                    if (username.isNotBlank()) put("username", username)
                    if (password.isNotBlank()) put("password", password)
                }
                addJsonObject { put("type", "direct"); put("tag", "direct") }
            }
            putJsonObject("route") {
                // Required since 1.12 as soon as a `dns` section exists: without
                // it sing-box refuses to start, naming a deprecation and an
                // environment variable rather than the config. It resolves domain
                // names in *dial* fields only — nothing this instance dials is a
                // name, since its outbound is 127.0.0.1 — so the local resolver is
                // both correct and inert here.
                put("default_domain_resolver", "dns-direct")
                putJsonArray("rules") {
                    if (upstreamUdpIsLossy) {
                        // Without this the `dns` block above is dead weight for the
                        // system's queries: they would be forwarded as the
                        // datagrams they arrived as, which is the path being
                        // avoided. Absent it, DNS keeps riding the tunnel as UDP,
                        // which is what the native transports want. Before the
                        // IPv6 reject, so a query to a v6 resolver is still caught
                        // and answered rather than refused.
                        addJsonObject { put("action", "hijack-dns"); put("port", 53) }
                    }
                    // IPv6 is claimed and refused, not carried.
                    //
                    // Claimed because auto_route only takes the families the
                    // interface has an address for, and an unclaimed IPv6 default
                    // route means every dual-stack site is reached outside the
                    // tunnel at the machine's real address.
                    //
                    // Refused rather than forwarded because whether the far end
                    // has working IPv6 is a property of each operator's node, not
                    // of this config. A reject is answered immediately, so Happy
                    // Eyeballs falls back to IPv4 in milliseconds; forwarding into
                    // a node without IPv6 would hang instead, which is the same
                    // outcome bought with a timeout.
                    addJsonObject { put("action", "reject"); put("ip_version", 6) }
                }
            }
        }
        return obj.toString()
    }

    /** Resolver reached over the tunnel when the upstream's UDP is unreliable. */
    private const val TCP_DNS_SERVER = "1.1.1.1"

    private fun renderTun(
        address: String,
        mtu: Int,
        resolveOverTcp: Boolean,
        outbounds: JsonArrayBuilder.() -> Unit,
    ): String {
        val obj = buildJsonObject {
            putJsonObject("log") { put("level", "info") }
            // Name resolution is the one thing that must not ride an unreliable
            // datagram path: lose it and nothing resolves, so no app opens a
            // socket at all and the tunnel looks perfectly connected behind a
            // blank browser. That is not hypothetical — it is what olcRTC did
            // with no UDP relay at all, its server logging real traffic to
            // Telegram and Meta, which dial hardcoded IPs, and nothing from
            // Safari.
            //
            // olcRTC relays UDP now, but over a lossy video carrier, so DNS
            // keeps its own reliable path: sing-box answers it here and asks
            // upstream over TCP. Everything else, calls and games included,
            // goes as plain UDP. Transports whose UDP is as good as their TCP
            // need none of this and get none of it.
            if (resolveOverTcp) {
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
            if (resolveOverTcp) {
                putJsonObject("route") {
                    putJsonArray("rules") {
                        // Without this the `dns` block above is dead weight:
                        // queries would be forwarded as the datagrams they
                        // arrived as, which is the path being avoided.
                        addJsonObject { put("action", "hijack-dns"); put("port", 53) }
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
                    // Same rule as XrayConfig: a link without a public key is
                    // VLESS over ordinary TLS, and `TransportKind.Tls` exists to
                    // name exactly that case. A reality block with an empty key
                    // is not a weaker handshake, it is a rejected config.
                    if (spec.publicKey.isNotBlank()) {
                        putJsonObject("reality") {
                            put("enabled", true)
                            put("public_key", spec.publicKey)
                            put("short_id", spec.shortId)
                        }
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
