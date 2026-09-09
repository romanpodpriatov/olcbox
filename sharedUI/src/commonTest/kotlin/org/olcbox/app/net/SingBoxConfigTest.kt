package org.olcbox.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SingBoxConfigTest {
    private fun inbounds(json: String) = Json.parseToJsonElement(json).jsonObject["inbounds"]!!.jsonArray
    private fun outbound(json: String) = Json.parseToJsonElement(json).jsonObject["outbounds"]!!.jsonArray[0].jsonObject

    @Test fun socksInboundOnGivenPort() {
        val json = SingBoxConfig.build(vless(), socksPort = 10809)
        val inb = inbounds(json)[0].jsonObject
        assertEquals("socks", inb["type"]!!.jsonPrimitive.content)
        assertEquals(10809, inb["listen_port"]!!.jsonPrimitive.content.toInt())
        assertEquals("127.0.0.1", inb["listen"]!!.jsonPrimitive.content)
    }

    // --- iOS: the core owns the tun -------------------------------------

    @Test fun tunInboundInsteadOfSocks() {
        val inb = inbounds(SingBoxConfig.buildTun(vless()))[0].jsonObject
        assertEquals("tun", inb["type"]!!.jsonPrimitive.content)
        assertEquals(SingBoxConfig.TUN_ADDRESS, inb["address"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(SingBoxConfig.TUN_MTU, inb["mtu"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun tunUsesGvisorBecauseTheSystemStackCannotForwardInAnExtension() {
        // The system stack needs raw-socket privileges the Network Extension
        // sandbox withholds: the tun comes up and carries nothing. This was
        // learned on a device, so it is pinned here.
        val inb = inbounds(SingBoxConfig.buildTun(vless()))[0].jsonObject
        assertEquals("gvisor", inb["stack"]!!.jsonPrimitive.content)
    }

    @Test fun tunAndSocksShareTheSameOutbound() {
        // The whole point of the second builder is a different inbound, not a
        // different transport: a drift here would mean iOS quietly connecting
        // differently from every other platform.
        assertEquals(
            outbound(SingBoxConfig.build(vless())),
            outbound(SingBoxConfig.buildTun(vless())),
        )
    }

    @Test fun vlessRealityOutbound() {
        val o = outbound(SingBoxConfig.build(vless()))
        assertEquals("vless", o["type"]!!.jsonPrimitive.content)
        assertEquals("1.2.3.4", o["server"]!!.jsonPrimitive.content)
        assertEquals(443, o["server_port"]!!.jsonPrimitive.content.toInt())
        assertTrue(o.containsKey("tls"))
        val reality = o["tls"]!!.jsonObject["reality"]!!.jsonObject
        assertEquals("PBK", reality["public_key"]!!.jsonPrimitive.content)
    }

    @Test fun hy2Outbound() {
        val o = outbound(SingBoxConfig.build(hy2()))
        assertEquals("hysteria2", o["type"]!!.jsonPrimitive.content)
        assertEquals("PW", o["password"]!!.jsonPrimitive.content)
    }

    @Test fun olcrtcSocksOutbound() {
        val o = outbound(SingBoxConfig.buildOlcrtcSocks(olcrtcPort = 10808))
        assertEquals("socks", o["type"]!!.jsonPrimitive.content)
        assertEquals("out", o["tag"]!!.jsonPrimitive.content)
        assertEquals("127.0.0.1", o["server"]!!.jsonPrimitive.content)
        assertEquals(10808, o["server_port"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun xhttpIsRefusedRatherThanEmitted() {
        // sing-box has no xhttp transport. This builder used to emit one anyway,
        // and this test used to assert it did — which is how iOS shipped a tunnel
        // that connected, refused the config, and carried nothing. xhttp belongs
        // to Xray; failing loudly is what sends callers there.
        assertFailsWith<IllegalArgumentException> { SingBoxConfig.build(xhttp()) }
    }

    // --- iOS: xhttp runs on Xray, with sing-box as its tun front-end ------

    @Test fun tunSocksPointsAtTheOtherCore() {
        val json = SingBoxConfig.buildTunSocks(socksPort = 10810)
        assertEquals("tun", inbounds(json)[0].jsonObject["type"]!!.jsonPrimitive.content)
        val o = outbound(json)
        assertEquals("socks", o["type"]!!.jsonPrimitive.content)
        assertEquals("127.0.0.1", o["server"]!!.jsonPrimitive.content)
        assertEquals(10810, o["server_port"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun tunSocksKeepsTheSameTunAsANativeOutbound() {
        // The device-side settings the extension applies are fixed, so both
        // shapes have to describe the same tun or one of them stops forwarding.
        assertEquals(
            inbounds(SingBoxConfig.buildTun(vless()))[0],
            inbounds(SingBoxConfig.buildTunSocks(10810))[0],
        )
    }

    // --- an upstream whose UDP is lossy still has to answer DNS ----------

    private fun lossyUdp() = Json.parseToJsonElement(
        SingBoxConfig.buildTunSocks(10810, upstreamUdpIsLossy = true)
    ).jsonObject

    @Test fun lossyUdpUpstreamResolvesOverTcpThroughTheSameOutbound() {
        // Lose DNS and nothing resolves, so no app opens a socket and the
        // tunnel looks connected behind a blank browser. Measured, back when
        // olcRTC had no UDP relay at all: its server logged real traffic to
        // Telegram and Meta, which dial hardcoded IPs, and none from Safari.
        val server = lossyUdp()["dns"]!!.jsonObject["servers"]!!.jsonArray[0].jsonObject
        assertEquals("tcp", server["type"]!!.jsonPrimitive.content)
        // Pointless unless it travels the tunnel: a detour naming anything but
        // the one outbound would resolve outside it, or not at all.
        assertEquals(
            outbound(SingBoxConfig.buildTunSocks(10810))["tag"]!!.jsonPrimitive.content,
            server["detour"]!!.jsonPrimitive.content,
        )
    }

    @Test fun lossyUdpUpstreamClaimsDnsButLetsEverythingElseThrough() {
        // The hijack is what sends queries to the server above instead of
        // forwarding them as the datagrams they arrived as. Everything else
        // must be left alone: olcRTC's UDP relay is the whole reason calls and
        // games work, and a blanket reject here — which this config did carry
        // while the relay was missing — silently kills them.
        val rules = lossyUdp()["route"]!!.jsonObject["rules"]!!.jsonArray
        assertEquals("hijack-dns", rules[0].jsonObject["action"]!!.jsonPrimitive.content)
        assertEquals(53, rules[0].jsonObject["port"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, rules.size, "nothing may reject UDP: calls and games ride it")
    }

    @Test fun upstreamsWithSoundUdpAreLeftExactlyAsTheyWere() {
        // xhttp reaches Xray through this same builder and works today; every
        // native outbound carries UDP itself. Rewriting their DNS would be a
        // regression dressed as a fix, so the sections appear for no one else.
        for (json in listOf(SingBoxConfig.buildTunSocks(10810), SingBoxConfig.buildTun(vless()))) {
            val obj = Json.parseToJsonElement(json).jsonObject
            assertTrue(obj["dns"] == null, "an upstream with sound UDP must resolve as before")
            assertTrue(obj["route"] == null, "no rules belong on a transport that already works")
        }
    }

    @Test fun xrayHandlesTheTransportSingBoxRefuses() {
        // The pairing that makes xhttp work at all: whatever sing-box turns
        // down, Xray must accept.
        val json = Json.parseToJsonElement(XrayConfig.buildXhttp(xhttp())).jsonObject
        val out = json["outbounds"]!!.jsonArray[0].jsonObject
        val stream = out["streamSettings"]!!.jsonObject
        assertEquals("xhttp", stream["network"]!!.jsonPrimitive.content)
        assertEquals("/dl", stream["xhttpSettings"]!!.jsonObject["path"]!!.jsonPrimitive.content)
    }

    private fun xhttp() = OutboundSpec.Vless(
        "u", "1.2.3.4", 443, "sni.x", "PBK", "sid", "chrome", null,
        TransportSpec.Xhttp("/dl", "sni.x", "packet-up"), "T"
    )

    @Test fun buildOutputIsValidJson() {
        // toString() of the built object must parse back cleanly.
        val parsed = Json.parseToJsonElement(SingBoxConfig.build(vless()))
        assertIs<kotlinx.serialization.json.JsonObject>(parsed)
    }

    @Test fun everyBuilderKeepsTheLogQuiet() {
        // At "info" sing-box names every outbound connection the user makes, and that
        // output lands in the log we invite the user to export — their browsing
        // history in a file, which our no-logs commitment says it must not be. Dial
        // failures are warnings and survive. Nothing parses this stream: readiness is
        // a socket probe (waitForCoreSocks), not a log match.
        //
        // Read the field rather than grepping the string: a config with no "log" block
        // is not quiet, it is on sing-box's default, which is "info".
        for (json in listOf(
            SingBoxConfig.build(vless()),
            SingBoxConfig.buildTun(vless()),
            SingBoxConfig.buildTunSocks(socksPort = 10809),
            SingBoxConfig.buildDesktopTun(corePort = 10809, verifyPort = 10810),
            SingBoxConfig.buildOlcrtcSocks(olcrtcPort = 10808),
        )) {
            val level = Json.parseToJsonElement(json).jsonObject["log"]
                ?.jsonObject?.get("level")?.jsonPrimitive?.content
            assertEquals("warn", level, json)
        }
    }

    private fun vless() = OutboundSpec.Vless(
        "u", "1.2.3.4", 443, "sni.x", "PBK", "sid", "chrome",
        "xtls-rprx-vision", TransportSpec.Tcp, "DE"
    )
    private fun hy2() = OutboundSpec.Hysteria2("PW", "1.2.3.4", 443, "h.x", null, false, "RU")

    /**
     * The partner subscription's CDN row: xhttp over ordinary TLS to a host with
     * a real certificate — `security=tls`, no `pbk`. Building REALITY for it
     * anyway made Xray refuse the whole config with
     * `Failed to build REALITY config > empty "password"`, which reads as a
     * missing credential rather than the wrong kind of security.
     */
    @Test fun xhttpWithoutRealityKeyUsesPlainTls() {
        val spec = OutboundSpec.Vless(
            "u", "cdn.example.org", 443, "cdn.example.org", "", "", "chrome",
            null, TransportSpec.Xhttp("/pk", "cdn.example.org", "stream-one"), "CDN"
        )
        val stream = Json.parseToJsonElement(XrayConfig.buildXhttp(spec))
            .jsonObject["outbounds"]!!.jsonArray[0]
            .jsonObject["streamSettings"]!!.jsonObject

        assertEquals("tls", stream["security"]!!.jsonPrimitive.content)
        assertNull(stream["realitySettings"])
        assertEquals(
            "cdn.example.org",
            stream["tlsSettings"]!!.jsonObject["serverName"]!!.jsonPrimitive.content
        )
    }

    @Test fun xhttpWithRealityKeyStillUsesReality() {
        val spec = OutboundSpec.Vless(
            "u", "1.2.3.4", 8644, "yandex.ru", "PBK", "b2c3", "chrome",
            null, TransportSpec.Xhttp("/pk", "yandex.ru", "packet-up"), "MSK"
        )
        val stream = Json.parseToJsonElement(XrayConfig.buildXhttp(spec))
            .jsonObject["outbounds"]!!.jsonArray[0]
            .jsonObject["streamSettings"]!!.jsonObject

        assertEquals("reality", stream["security"]!!.jsonPrimitive.content)
        assertEquals(
            "PBK",
            stream["realitySettings"]!!.jsonObject["publicKey"]!!.jsonPrimitive.content
        )
    }

    /** The same rule on the sing-box side, where an empty key is equally fatal. */
    @Test fun vlessWithoutRealityKeyOmitsTheRealityBlock() {
        val spec = OutboundSpec.Vless(
            "u", "tls.example.org", 443, "tls.example.org", "", "", "chrome",
            null, TransportSpec.Tcp, "TLS"
        )
        val tls = Json.parseToJsonElement(SingBoxConfig.build(spec))
            .jsonObject["outbounds"]!!.jsonArray
            .first { it.jsonObject["tag"]?.jsonPrimitive?.content == "out" }
            .jsonObject["tls"]!!.jsonObject

        assertNull(tls["reality"])
        assertEquals("tls.example.org", tls["server_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun desktopTunExcludesTheServerSoTheCoreDoesNotRouteThroughItself() {
        val json = SingBoxConfig.buildDesktopTun(
            corePort = 10810,
            verifyPort = 10811,
            excludeAddresses = listOf("203.0.113.7/32", "2001:db8::1/128")
        )
        assertContains(json, "\"route_exclude_address\"")
        assertContains(json, "203.0.113.7/32")
        assertContains(json, "2001:db8::1/128")
    }

    @Test
    fun desktopTunSendsTheServerDomainToTheSystemResolverDirect() {
        // The core redials while the tun is up. Its DNS query for the server's
        // own hostname enters the tun like everything else, and answering it
        // through the tunnel needs the tunnel that is being redialled.
        val json = SingBoxConfig.buildDesktopTun(
            corePort = 10810,
            verifyPort = 10811,
            directDnsDomains = listOf("de1.example.org")
        )
        assertContains(json, "\"type\":\"local\"")
        assertContains(json, "\"tag\":\"dns-direct\"")
        assertContains(json, "de1.example.org")
    }

    @Test
    fun desktopTunOffersALocalSocksSoTheVerifierProvesTheWholeChain() {
        val json = SingBoxConfig.buildDesktopTun(corePort = 10810, verifyPort = 10811)
        assertContains(json, "\"tag\":\"verify-in\"")
        assertContains(json, "\"listen_port\":10811")
        assertContains(json, "\"listen\":\"127.0.0.1\"")
    }

    @Test
    fun desktopTunCarriesSocksCredentialsOnlyWhenTheCoreAskedForThem() {
        val bare = SingBoxConfig.buildDesktopTun(corePort = 10810, verifyPort = 10811)
        assertTrue("\"username\"" !in bare)

        val authed = SingBoxConfig.buildDesktopTun(
            corePort = 10810, verifyPort = 10811, username = "u", password = "p"
        )
        assertContains(authed, "\"username\":\"u\"")
        assertContains(authed, "\"password\":\"p\"")
    }

    @Test
    fun desktopTunAlwaysNamesADefaultDomainResolver() {
        // sing-box 1.12 refuses to start a config that has a `dns` section and no
        // default_domain_resolver, and says so by naming a deprecation and an
        // environment variable rather than the field. Both shapes emit `dns`.
        for (lossy in listOf(true, false)) {
            val json = SingBoxConfig.buildDesktopTun(
                corePort = 10810, verifyPort = 10811, upstreamUdpIsLossy = lossy
            )
            assertContains(json, "\"default_domain_resolver\":\"dns-direct\"")
        }
    }

    @Test
    fun desktopTunHijacksDnsOnlyWhenTheUpstreamCannotBeTrustedWithDatagrams() {
        // The native transports carry UDP themselves, so their DNS rides the
        // tunnel as it always has. Hijacking it there would move working
        // resolution onto a path that exists for olcRTC's lossy carrier.
        assertTrue(
            "hijack-dns" !in SingBoxConfig.buildDesktopTun(
                corePort = 10810, verifyPort = 10811, upstreamUdpIsLossy = false
            )
        )
        assertContains(
            SingBoxConfig.buildDesktopTun(
                corePort = 10810, verifyPort = 10811, upstreamUdpIsLossy = true
            ),
            "hijack-dns"
        )
    }

    @Test
    fun desktopTunPutsTheRemoteResolverFirstWhenQueriesAreHijacked() {
        // The first server answers anything no rule claims. Local first would send
        // every hijacked lookup to the machine's own resolver, in the clear.
        val json = SingBoxConfig.buildDesktopTun(
            corePort = 10810, verifyPort = 10811, upstreamUdpIsLossy = true
        )
        assertTrue(json.indexOf("dns-remote") < json.indexOf("dns-direct"))
    }

    @Test
    fun desktopTunClaimsIpv6SoItCannotBeReachedAroundTheTunnel() {
        // The bug this pins: with an IPv4 address alone, auto_route leaves the
        // IPv6 default route on the physical interface, and a browser — which
        // prefers IPv6 — reaches every dual-stack site at the machine's real
        // address while `curl api.ipify.org`, an A record only, keeps reporting
        // the tunnel. Found on a real Mac, not here.
        for (lossy in listOf(true, false)) {
            val json = SingBoxConfig.buildDesktopTun(
                corePort = 10810, verifyPort = 10811, upstreamUdpIsLossy = lossy
            )
            assertContains(json, SingBoxConfig.DESKTOP_TUN_ADDRESS6)
            assertContains(json, "\"action\":\"reject\",\"ip_version\":6")
        }
    }

    @Test
    fun desktopTunCatchesDnsBeforeItRefusesIpv6() {
        // Order in a rule list is precedence: reject first and a query sent to a
        // v6 resolver is refused instead of answered.
        val json = SingBoxConfig.buildDesktopTun(
            corePort = 10810, verifyPort = 10811, upstreamUdpIsLossy = true
        )
        assertTrue(json.indexOf("hijack-dns") < json.indexOf("\"reject\""))
    }

    @Test
    fun desktopTunMtuIsNotTheIosOne() {
        // iOS rejects 9000 outright; a utun on macOS is no place to find out.
        assertContains(
            SingBoxConfig.buildDesktopTun(corePort = 10810, verifyPort = 10811),
            "\"mtu\":1500"
        )
    }
    // --- Bypass Russia -----------------------------------------------------

    private fun bypass(dns: DirectDns = DirectDns.Servers(listOf("10.20.30.40"))) =
        Routing.BypassRussia(ruleSetDir = "/data/rules", directDns = dns)
    private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject
    private fun routeRules(json: String) = obj(json)["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
    private fun dnsServers(json: String) = obj(json)["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
    private fun str(o: JsonObject, key: String) = o[key]!!.jsonPrimitive.content
    private fun strings(o: JsonObject, key: String) = o[key]!!.jsonArray.map { it.jsonPrimitive.content }

    /** Every shape a platform builds, with the same routing, so a rule is checked once and holds everywhere. */
    private fun shapes(routing: Routing) = mapOf(
        "socks" to SingBoxConfig.build(vless(), routing = routing),
        "socks-chain" to SingBoxConfig.buildSocksChain(10808, username = "u", password = "p", routing = routing),
        "tun" to SingBoxConfig.buildTun(vless(), routing = routing),
        "tun-socks" to SingBoxConfig.buildTunSocks(10810, routing = routing),
        "tun-socks-lossy" to SingBoxConfig.buildTunSocks(
            10810, username = "u", password = "p", upstreamUdpIsLossy = true, routing = routing
        ),
    )

    @Test fun globalRoutingIsExactlyWhatWasBuiltBeforeRoutingExisted() {
        assertEquals(SingBoxConfig.build(vless()), SingBoxConfig.build(vless(), routing = Routing.Global))
        assertEquals(SingBoxConfig.buildTun(vless()), SingBoxConfig.buildTun(vless(), routing = Routing.Global))
        assertEquals(
            SingBoxConfig.buildTunSocks(10810, upstreamUdpIsLossy = true),
            SingBoxConfig.buildTunSocks(10810, upstreamUdpIsLossy = true, routing = Routing.Global)
        )
        assertEquals(SingBoxConfig.buildOlcrtcSocks(10808), SingBoxConfig.buildSocksChain(10808))
        // Global still means: no dns, no route, one outbound, for the shapes that had none.
        for (json in listOf(SingBoxConfig.build(vless()), SingBoxConfig.buildSocksChain(10808))) {
            assertNull(obj(json)["dns"])
            assertNull(obj(json)["route"])
            assertEquals(1, obj(json)["outbounds"]!!.jsonArray.size)
        }
    }

    @Test fun bypassDeclaresTheThreeRuleSetsAsLocalBinaryFiles() {
        for ((name, json) in shapes(bypass())) {
            val sets = obj(json)["route"]!!.jsonObject["rule_set"]!!.jsonArray.map { it.jsonObject }
            assertEquals(RuleSets.all.map { it.tag }, sets.map { str(it, "tag") }, name)
            for ((set, file) in sets.zip(RuleSets.all)) {
                assertEquals("local", str(set, "type"), name)
                assertEquals("binary", str(set, "format"), name)
                assertEquals("/data/rules/${file.name}", str(set, "path"), name)
            }
        }
    }

    @Test fun bypassRoutesRussiaAndTheLocalNetworkDirectAndTheRestThroughTheTunnel() {
        for ((name, json) in shapes(bypass())) {
            val rules = routeRules(json)
            assertEquals(4, rules.size, name)
            assertEquals("sniff", str(rules[0], "action"), name)
            assertEquals("hijack-dns", str(rules[1], "action"), name)
            assertEquals(53, rules[1]["port"]!!.jsonPrimitive.content.toInt(), name)
            assertEquals(true, rules[2]["ip_is_private"]!!.jsonPrimitive.content.toBoolean(), name)
            assertEquals("direct", str(rules[2], "outbound"), name)
            assertEquals(RuleSets.all.map { it.tag }, strings(rules[3], "rule_set"), name)
            assertEquals("direct", str(rules[3], "outbound"), name)
            assertEquals("out", str(obj(json)["route"]!!.jsonObject, "final"), name)
            assertEquals("dns-direct", str(obj(json)["route"]!!.jsonObject, "default_domain_resolver"), name)
        }
    }

    @Test fun bypassAddsADirectOutboundAfterTheTunnelOne() {
        for ((name, json) in shapes(bypass())) {
            val outbounds = obj(json)["outbounds"]!!.jsonArray.map { it.jsonObject }
            assertEquals(2, outbounds.size, name)
            assertEquals("out", str(outbounds[0], "tag"), name)
            assertEquals("direct", str(outbounds[1], "type"), name)
            assertEquals("direct", str(outbounds[1], "tag"), name)
        }
    }

    @Test fun bypassResolvesRussianNamesOnTheNetworkUnderneathAndTheRestThroughTheTunnel() {
        for ((name, json) in shapes(bypass())) {
            val dns = obj(json)["dns"]!!.jsonObject
            val servers = dnsServers(json)
            assertEquals(listOf("dns-remote", "dns-direct"), servers.map { str(it, "tag") }, name)
            assertEquals("out", str(servers[0], "detour"), name)
            assertEquals("udp", str(servers[1], "type"), name)
            assertEquals("10.20.30.40", str(servers[1], "server"), name)
            // No detour: the default dialer is already direct, and sing-box
            // refuses at start a detour to a direct outbound with no options.
            assertNull(servers[1]["detour"], name)
            val rules = dns["rules"]!!.jsonArray.map { it.jsonObject }
            assertEquals(1, rules.size, name)
            assertEquals(RuleSets.domains.map { it.tag }, strings(rules[0], "rule_set"), name)
            assertEquals("dns-direct", str(rules[0], "server"), name)
            assertEquals("dns-remote", str(dns, "final"), name)
            assertEquals(true, dns["reverse_mapping"]!!.jsonPrimitive.content.toBoolean(), name)
        }
    }

    @Test fun remoteResolverRidesTcpOnlyWhenTheUpstreamIsLossy() {
        val byShape = shapes(bypass()).mapValues { str(dnsServers(it.value)[0], "type") }
        assertEquals("tcp", byShape["tun-socks-lossy"])
        for (name in listOf("socks", "socks-chain", "tun", "tun-socks")) assertEquals("udp", byShape[name], name)
    }

    @Test fun desktopDirectResolverIsTheSystemOne() {
        val server = dnsServers(SingBoxConfig.build(vless(), routing = bypass(DirectDns.System)))[1]
        assertEquals("local", str(server, "type"))
        assertNull(server["server"])
        assertNull(server["detour"])
    }

    @Test fun iosDirectResolverIsAPlaceholderTheExtensionFillsIn() {
        val json = SingBoxConfig.buildTun(vless(), routing = bypass(DirectDns.Placeholder))
        val server = dnsServers(json)[1]
        assertEquals(SingBoxConfig.DIRECT_DNS_PLACEHOLDER, str(server, "server"))
        assertNull(server["detour"])
        // Exactly once, quoted: the extension substitutes by string and must not
        // be able to hit anything else.
        val quoted = Regex("\"" + Regex.escape(SingBoxConfig.DIRECT_DNS_PLACEHOLDER) + "\"")
        assertEquals(1, quoted.findAll(json).count())
    }

    @Test fun bypassKeepsUdpFlowing() {
        for ((name, json) in shapes(bypass())) {
            assertTrue(routeRules(json).none { it["action"]?.jsonPrimitive?.content == "reject" }, name)
        }
    }

    @Test fun socksChainCarriesCredentialsOnlyWhenTheUpstreamAskedForThem() {
        val with = outbound(SingBoxConfig.buildSocksChain(10808, username = "u", password = "p"))
        assertEquals("u", with["username"]!!.jsonPrimitive.content)
        assertEquals("p", with["password"]!!.jsonPrimitive.content)
        assertEquals(10808, with["server_port"]!!.jsonPrimitive.content.toInt())
        val without = outbound(SingBoxConfig.buildSocksChain(10808))
        assertNull(without["username"])
        assertNull(without["password"])
    }

    @Test fun bypassShapesKeepTheLogQuietToo() {
        for ((name, json) in shapes(bypass())) {
            assertEquals("warn", str(obj(json)["log"]!!.jsonObject, "level"), name)
        }
    }
}
