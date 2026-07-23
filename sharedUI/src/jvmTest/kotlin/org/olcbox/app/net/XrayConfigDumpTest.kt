package org.olcbox.app.net

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * JVM-only: emits the Xray-core xhttp config from the real parser+builder to
 * `build/xray-configs/`. The core-verify CI workflow runs `xray test` on it
 * against the pinned Xray binary — proving the xhttp config is accepted by real
 * Xray (which, unlike sing-box, supports XHTTP).
 */
class XrayConfigDumpTest {
    private val outDir = File("build/xray-configs")

    private fun dump(name: String, json: String) {
        outDir.mkdirs()
        File(outDir, "$name.json").writeText(json)
    }

    @Test fun dumpVlessXhttp() {
        val link = "vless://22222222-2222-2222-2222-222222222222@127.0.0.1:443" +
            "?type=xhttp&security=reality&encryption=none&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0" +
            "&sid=cd34&fp=chrome&sni=www.microsoft.com&path=%2Fdownload&host=www.microsoft.com&mode=packet-up#FI-xhttp"
        val spec = LinkParser.parse(link)
        assertNotNull(spec)
        assertTrue(spec is OutboundSpec.Vless)
        dump("vless-xhttp", XrayConfig.buildXhttp(spec))
    }
}
