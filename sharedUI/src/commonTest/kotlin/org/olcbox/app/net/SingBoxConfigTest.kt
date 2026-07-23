package org.olcbox.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

    @Test fun xhttpTransportBlock() {
        val spec = OutboundSpec.Vless(
            "u", "1.2.3.4", 443, "sni.x", "PBK", "sid", "chrome", null,
            TransportSpec.Xhttp("/dl", "sni.x", "packet-up"), "T"
        )
        val o = outbound(SingBoxConfig.build(spec))
        val tr = o["transport"]!!.jsonObject
        assertEquals("xhttp", tr["type"]!!.jsonPrimitive.content)
        assertEquals("/dl", tr["path"]!!.jsonPrimitive.content)
    }

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
}
