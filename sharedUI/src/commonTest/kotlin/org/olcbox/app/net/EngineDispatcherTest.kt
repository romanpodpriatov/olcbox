package org.olcbox.app.net

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.LocationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineDispatcherTest {
    private class FakeSingBox : SingBoxController {
        var startedConfig: String? = null
        var stopped = false
        override suspend fun start(configJson: String) { startedConfig = configJson }
        override suspend fun stop() { stopped = true }
    }

    private class FakeOlcrtc(override val olcrtcSocksPort: Int = 10808) : OlcrtcController {
        var startedLocation: LocationConfig? = null
        var stopped = false
        override suspend fun start(location: LocationConfig) { startedLocation = location }
        override suspend fun stop() { stopped = true }
    }

    private fun outboundType(json: String): String =
        Json.parseToJsonElement(json).jsonObject["outbounds"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content

    @Test fun olcrtcLocationStartsBothWithSocksOutbound() = runTest {
        val sb = FakeSingBox(); val olc = FakeOlcrtc()
        val loc = LocationConfig(name = "DE", id = "room", key = "k", kind = LocationKind.Olcrtc)
        EngineDispatcher(sb, olc).start(loc)
        assertEquals(loc, olc.startedLocation) // olcrtc child started
        assertEquals("socks", outboundType(sb.startedConfig!!)) // sing-box socks→olcrtc
        val port = Json.parseToJsonElement(sb.startedConfig!!).jsonObject["outbounds"]!!
            .jsonArray[0].jsonObject["server_port"]!!.jsonPrimitive.content.toInt()
        assertEquals(10808, port)
    }

    @Test fun vlessLocationStartsOnlySingBox() = runTest {
        val sb = FakeSingBox(); val olc = FakeOlcrtc()
        val loc = LocationConfig(
            name = "DE", kind = LocationKind.Vless,
            rawLink = "vless://u@1.2.3.4:443?security=reality&pbk=P&sid=s&sni=x&flow=xtls-rprx-vision#DE"
        )
        EngineDispatcher(sb, olc).start(loc)
        assertEquals(null, olc.startedLocation) // no olcrtc child
        assertEquals("vless", outboundType(sb.startedConfig!!))
    }

    @Test fun hysteria2LocationStartsOnlySingBox() = runTest {
        val sb = FakeSingBox(); val olc = FakeOlcrtc()
        val loc = LocationConfig(
            name = "RU", kind = LocationKind.Hysteria2,
            rawLink = "hysteria2://PW@1.2.3.4:443?sni=h#RU"
        )
        EngineDispatcher(sb, olc).start(loc)
        assertEquals(null, olc.startedLocation)
        assertEquals("hysteria2", outboundType(sb.startedConfig!!))
    }

    @Test fun stopStopsBoth() = runTest {
        val sb = FakeSingBox(); val olc = FakeOlcrtc()
        EngineDispatcher(sb, olc).stop()
        assertTrue(sb.stopped); assertTrue(olc.stopped)
    }

    @Test fun vlessWithoutRawLinkThrows() = runTest {
        val sb = FakeSingBox(); val olc = FakeOlcrtc()
        val loc = LocationConfig(name = "X", kind = LocationKind.Vless, rawLink = null)
        var threw = false
        try {
            EngineDispatcher(sb, olc).start(loc)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
        assertFalse(sb.startedConfig != null) // sing-box not started on bad input
    }
}
