package org.olcbox.app.net

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * JVM-only: runs the real parser+builder on realistic share links and writes the
 * generated sing-box configs to `build/singbox-configs/`. The singbox-verify CI
 * workflow then runs `sing-box check` on each against the pinned binary — proving
 * the config schema matches the real sing-box release, not just valid JSON.
 */
class SingBoxConfigDumpTest {
    private val outDir = File("build/singbox-configs")

    private fun dump(name: String, json: String) {
        outDir.mkdirs()
        File(outDir, "$name.json").writeText(json)
    }

    @Test fun dumpVlessRealityTcp() {
        val link = "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:443" +
            "?security=reality&encryption=none&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0" +
            "&sid=ab12cd34&fp=chrome&sni=www.microsoft.com&flow=xtls-rprx-vision&type=tcp#DE-reality"
        val spec = LinkParser.parse(link)
        assertNotNull(spec)
        dump("vless-reality", SingBoxConfig.build(spec))
    }

    // xhttp is NOT a sing-box transport — it's handled by Xray-core (see
    // XrayConfigDumpTest). sing-box covers reality (tcp) + hy2 + olcrtc-socks.

    @Test fun dumpHysteria2() {
        val link = "hysteria2://PASSWORD123@127.0.0.1:443?sni=www.microsoft.com&obfs=salamander&obfs-password=OBFSPW&insecure=1#RU-hy2"
        val spec = LinkParser.parse(link)
        assertNotNull(spec)
        dump("hysteria2", SingBoxConfig.build(spec))
    }

    @Test fun dumpOlcrtcSocks() {
        dump("olcrtc-socks", SingBoxConfig.buildOlcrtcSocks(olcrtcPort = 10808))
        assertTrue(File(outDir, "olcrtc-socks.json").exists())
    }

    /**
     * The shape the macOS root daemon runs. Everything unique to it — the route
     * exclusion, the direct-DNS rule, the second inbound — is schema the other
     * dumps never exercise, and all of it is 1.12+ syntax.
     */
    @Test fun dumpDesktopTun() {
        dump(
            "desktop-tun",
            SingBoxConfig.buildDesktopTun(
                corePort = 10810,
                verifyPort = 10811,
                excludeAddresses = listOf("203.0.113.7/32"),
                directDnsDomains = listOf("de1.example.org"),
                upstreamUdpIsLossy = true
            )
        )
        assertTrue(File(outDir, "desktop-tun.json").exists())
    }

    /**
     * The same builder without olcRTC's lossy-carrier handling — no hijack rule,
     * no remote resolver. It is a different config, so it gets its own check: the
     * first version of this shape was rejected by sing-box in exactly the section
     * the two variants differ in.
     */
    @Test fun dumpDesktopTunNative() {
        dump(
            "desktop-tun-native",
            SingBoxConfig.buildDesktopTun(
                corePort = 10810,
                verifyPort = 10811,
                excludeAddresses = listOf("203.0.113.7/32", "2001:db8::1/128"),
                directDnsDomains = listOf("de1.example.org")
            )
        )
        assertTrue(File(outDir, "desktop-tun-native.json").exists())
    }
}
