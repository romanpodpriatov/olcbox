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

    @Test fun dumpVlessXhttp() {
        // XHTTP is an Xray transport; this dump exists to let `sing-box check`
        // tell us definitively whether sing-box accepts it. If it rejects, the
        // builder must drop xhttp (locations fall back to unsupported).
        val link = "vless://22222222-2222-2222-2222-222222222222@127.0.0.1:443" +
            "?type=xhttp&security=reality&encryption=none&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0" +
            "&sid=cd34&fp=chrome&sni=www.microsoft.com&path=%2Fdownload&host=www.microsoft.com&mode=packet-up#FI-xhttp"
        val spec = LinkParser.parse(link)
        assertNotNull(spec)
        dump("vless-xhttp", SingBoxConfig.build(spec))
    }

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
}
