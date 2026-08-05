package org.olcbox.app.net

import kotlinx.serialization.json.Json
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
    fun desktopTunMtuIsNotTheIosOne() {
        // iOS rejects 9000 outright; a utun on macOS is no place to find out.
        assertContains(
            SingBoxConfig.buildDesktopTun(corePort = 10810, verifyPort = 10811),
            "\"mtu\":1500"
        )
    }
}
