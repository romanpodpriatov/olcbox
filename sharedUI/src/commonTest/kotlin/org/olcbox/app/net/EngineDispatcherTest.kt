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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineDispatcherTest {
    private class FakeSingBox : SingBoxController {
        var startedConfig: String? = null
        var stopped = false
        override suspend fun start(configJson: String) { startedConfig = configJson }
        override suspend fun stop() { stopped = true }
    }

    private class FakeXray : XrayController {
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

    private fun singBoxOutboundType(json: String): String =
        Json.parseToJsonElement(json).jsonObject["outbounds"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content

    private fun xrayNetwork(json: String): String =
        Json.parseToJsonElement(json).jsonObject["outbounds"]!!.jsonArray[0].jsonObject["streamSettings"]!!
            .jsonObject["network"]!!.jsonPrimitive.content

    @Test fun olcrtcLocationStartsOlcrtcChildAndSingBoxSocks() = runTest {
        val sb = FakeSingBox(); val xr = FakeXray(); val olc = FakeOlcrtc()
        val loc = LocationConfig(name = "DE", id = "room", key = "k", kind = LocationKind.Olcrtc)
        EngineDispatcher(sb, xr, olc).start(loc)
        assertEquals(loc, olc.startedLocation)
        assertEquals("socks", singBoxOutboundType(sb.startedConfig!!))
        assertNull(xr.startedConfig)
    }

    @Test fun vlessRealityStartsSingBoxNotXray() = runTest {
        val sb = FakeSingBox(); val xr = FakeXray(); val olc = FakeOlcrtc()
        val loc = LocationConfig(
            name = "DE", kind = LocationKind.Vless,
            rawLink = "vless://u@1.2.3.4:443?security=reality&pbk=P&sid=s&sni=x&flow=xtls-rprx-vision&type=tcp#DE"
        )
        EngineDispatcher(sb, xr, olc).start(loc)
        assertEquals("vless", singBoxOutboundType(sb.startedConfig!!))
        assertNull(xr.startedConfig)
        assertNull(olc.startedLocation)
    }

    @Test fun vlessXhttpStartsXrayNotSingBox() = runTest {
        val sb = FakeSingBox(); val xr = FakeXray(); val olc = FakeOlcrtc()
        val loc = LocationConfig(
            name = "FI", kind = LocationKind.Vless,
            rawLink = "vless://u@1.2.3.4:443?type=xhttp&security=reality&pbk=P&sid=s&sni=x&path=%2Fd&host=x&mode=packet-up#FI"
        )
        EngineDispatcher(sb, xr, olc).start(loc)
        assertEquals("xhttp", xrayNetwork(xr.startedConfig!!)) // Xray owns xhttp
        assertNull(sb.startedConfig)
        assertNull(olc.startedLocation)
    }

    @Test fun hysteria2StartsSingBox() = runTest {
        val sb = FakeSingBox(); val xr = FakeXray(); val olc = FakeOlcrtc()
        val loc = LocationConfig(
            name = "RU", kind = LocationKind.Hysteria2,
            rawLink = "hysteria2://PW@1.2.3.4:443?sni=h#RU"
        )
        EngineDispatcher(sb, xr, olc).start(loc)
        assertEquals("hysteria2", singBoxOutboundType(sb.startedConfig!!))
        assertNull(xr.startedConfig)
    }

    @Test fun stopStopsAllThree() = runTest {
        val sb = FakeSingBox(); val xr = FakeXray(); val olc = FakeOlcrtc()
        EngineDispatcher(sb, xr, olc).stop()
        assertTrue(sb.stopped); assertTrue(xr.stopped); assertTrue(olc.stopped)
    }

    @Test fun vlessWithoutRawLinkThrows() = runTest {
        val sb = FakeSingBox(); val xr = FakeXray(); val olc = FakeOlcrtc()
        val loc = LocationConfig(name = "X", kind = LocationKind.Vless, rawLink = null)
        var threw = false
        try {
            EngineDispatcher(sb, xr, olc).start(loc)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
        assertFalse(sb.startedConfig != null)
        assertFalse(xr.startedConfig != null)
    }
}
