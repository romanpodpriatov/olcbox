package org.olcbox.app.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class LinkParserTest {
    @Test fun parsesVlessReality() {
        val link = "vless://11111111-1111-1111-1111-111111111111@1.2.3.4:443" +
            "?security=reality&encryption=none&pbk=PUBKEY&sid=ab12&fp=chrome&sni=www.example.com&flow=xtls-rprx-vision&type=tcp#DE"
        val s = LinkParser.parse(link)
        assertIs<OutboundSpec.Vless>(s)
        assertEquals("11111111-1111-1111-1111-111111111111", s.uuid)
        assertEquals("1.2.3.4", s.host); assertEquals(443, s.port)
        assertEquals("www.example.com", s.sni)
        assertEquals("PUBKEY", s.publicKey); assertEquals("ab12", s.shortId)
        assertEquals("xtls-rprx-vision", s.flow)
        assertEquals(TransportSpec.Tcp, s.transport)
        assertEquals("DE", s.tag)
    }

    @Test fun parsesVlessXhttp() {
        val link = "vless://22222222-2222-2222-2222-222222222222@1.2.3.4:443" +
            "?type=xhttp&security=reality&encryption=none&pbk=PBK&sid=cd34&fp=chrome&sni=sni.example&path=%2Fdownload&host=sni.example&mode=packet-up#FI"
        val s = LinkParser.parse(link)
        assertIs<OutboundSpec.Vless>(s)
        val t = s.transport
        assertIs<TransportSpec.Xhttp>(t)
        assertEquals("/download", t.path)
        assertEquals("sni.example", t.host)
        assertEquals("packet-up", t.mode)
        assertNull(s.flow) // xhttp is incompatible with flow
    }

    @Test fun parsesHysteria2() {
        val link = "hysteria2://PASSWORD@1.2.3.4:443?sni=h.example&obfs=salamander&obfs-password=OBFS&insecure=1#RU"
        val s = LinkParser.parse(link)
        assertIs<OutboundSpec.Hysteria2>(s)
        assertEquals("PASSWORD", s.password)
        assertEquals("h.example", s.sni)
        assertEquals("OBFS", s.obfsPassword)
        assertEquals(true, s.insecure)
        assertEquals("RU", s.tag)
    }

    @Test fun hy2AliasAndNoObfs() {
        val s = LinkParser.parse("hy2://PW@host:8443?sni=x#T") as OutboundSpec.Hysteria2
        assertNull(s.obfsPassword)
        assertEquals(8443, s.port)
    }

    @Test fun rejectsOlcrtcAndGarbage() {
        assertNull(LinkParser.parse("olcrtc://telemost?vp8channel@room#key"))
        assertNull(LinkParser.parse("https://example.com/sub"))
        assertNull(LinkParser.parse("not a link"))
        assertNull(LinkParser.parse("vless://missing-host"))
    }
}
