package org.olcbox.app.net

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Grouping and ordering for transport auto-fallback.
 *
 * Names and ports mirror the live `/sub/{token}/unified`, where a single RU edge
 * host fronts several countries — so the exit is identified by name, not host.
 */
class TransportFallbackTest {

    private val sub = "https://proofkit.org/sub/aaa"

    private fun vlessReality(name: String, host: String, port: Int) = entry(
        name = name,
        kind = LocationKind.Vless,
        link = "vless://d67b1637-4fee-4e0d-bc96-000000000000@$host:$port" +
            "?type=tcp&security=reality&sni=www.zoom.us&fp=chrome&pbk=abc&sid=14090023" +
            "&flow=xtls-rprx-vision#$name"
    )

    private fun vlessXhttp(name: String, host: String, port: Int) = entry(
        name = name,
        kind = LocationKind.Vless,
        link = "vless://d67b1637-4fee-4e0d-bc96-000000000000@$host:$port" +
            "?type=xhttp&security=reality&encryption=none&pbk=abc&sid=14090023&fp=chrome" +
            "&sni=www.zoom.us&path=%2Fxhttp&host=www.zoom.us&mode=packet-up#$name"
    )

    private fun hysteria2(name: String, host: String, port: Int) = entry(
        name = name,
        kind = LocationKind.Hysteria2,
        link = "hysteria2://pass@$host:$port?sni=www.zoom.us&obfs=salamander" +
            "&obfs-password=secret&insecure=0#$name"
    )

    private fun olcrtc(name: String) = LocationEntry.from(
        storageId = "olcrtc",
        location = LocationConfig(name = name, id = "room", key = "k".repeat(64)),
        subscriptionUrl = sub
    )

    private fun entry(name: String, kind: LocationKind, link: String) = LocationEntry.from(
        storageId = name.replace(" ", "_"),
        location = LocationConfig(name = name, id = "x", kind = kind, rawLink = link),
        subscriptionUrl = sub
    )

    // one exit, three transports — the shape the coordinator emits
    private val usReality = vlessReality("US via RU | 0.1320TON/GB", "1.2.3.4", 443)
    private val usHy2 = hysteria2("US via RU | 0.1320TON/GB · Hysteria2", "1.2.3.4", 30023)
    private val usXhttp = vlessXhttp("US via RU | 0.1320TON/GB · XHTTP", "1.2.3.4", 40023)

    // a different country behind the SAME edge host
    private val jpReality = vlessReality("JP via RU | 0.1200TON/GB", "1.2.3.4", 443)
    private val jpHy2 = hysteria2("JP via RU | 0.1200TON/GB · Hysteria2", "1.2.3.4", 30026)

    private val all = listOf(usReality, usHy2, usXhttp, jpReality, jpHy2, olcrtc("DE · olcRTC"))

    @Test
    fun stripsTransportSuffixToFindTheExit() {
        assertEquals("US via RU | 0.1320TON/GB", TransportGroup.baseName("US via RU | 0.1320TON/GB · Hysteria2"))
        assertEquals("US via RU | 0.1320TON/GB", TransportGroup.baseName("US via RU | 0.1320TON/GB · XHTTP"))
        assertEquals("US via RU | 0.1320TON/GB", TransportGroup.baseName("US via RU | 0.1320TON/GB"))
    }

    @Test
    fun groupsTheThreeTransportsOfOneExit() {
        assertEquals(
            listOf("US_via_RU_|_0.1320TON/GB", "US_via_RU_|_0.1320TON/GB_·_Hysteria2", "US_via_RU_|_0.1320TON/GB_·_XHTTP"),
            TransportGroup.siblings(usReality, all).map { it.storageId }
        )
    }

    @Test
    fun doesNotMergeCountriesThatShareAnEdgeHost() {
        // Same host 1.2.3.4, same port 443 — only the name separates US from JP.
        val usGroup = TransportGroup.siblings(usReality, all).map { it.storageId }
        assertTrue(usGroup.none { it.startsWith("JP") }, "JP leaked into the US group: $usGroup")
        assertEquals(2, TransportGroup.siblings(jpReality, all).size)
    }

    @Test
    fun olcrtcNeverJoinsAGroup() {
        // It is a single DE entry: falling into it would silently change country.
        assertTrue(TransportGroup.groupByExit(all).values.none { group ->
            group.any { it.location.kind == LocationKind.Olcrtc }
        })
        val self = TransportGroup.siblings(olcrtc("DE · olcRTC"), all)
        assertEquals(1, self.size)
    }

    @Test
    fun subscriptionUrlIsPartOfTheKey() {
        val other = LocationEntry.from(
            storageId = "other",
            location = usReality.location,
            subscriptionUrl = "https://proofkit.org/sub/bbb"
        )
        assertEquals(1, TransportGroup.siblings(other, listOf(usReality, other)).size)
    }

    @Test
    fun readsTheTransportFromTheLinkItself() {
        assertEquals(TransportKind.Reality, usReality.location.transportKind())
        assertEquals(TransportKind.Hysteria2, usHy2.location.transportKind())
        assertEquals(TransportKind.Xhttp, usXhttp.location.transportKind())
        assertEquals(TransportKind.Olcrtc, olcrtc("x").location.transportKind())
    }

    @Test
    fun defaultOrderIsRealityThenHysteria2ThenXhttp() {
        val ordered = TransportSelector.orderCandidates(listOf(usXhttp, usHy2, usReality))
        assertEquals(
            listOf(TransportKind.Reality, TransportKind.Hysteria2, TransportKind.Xhttp),
            ordered.map { it.location.transportKind() }
        )
    }

    @Test
    fun lastKnownGoodIsProbedFirst() {
        val ordered = TransportSelector.orderCandidates(
            listOf(usReality, usHy2, usXhttp),
            lastKnownGood = usXhttp.storageId
        )
        assertEquals(usXhttp.storageId, ordered.first().storageId)
        assertEquals(3, ordered.size)
        // the rest keep the default order
        assertEquals(
            listOf(TransportKind.Reality, TransportKind.Hysteria2),
            ordered.drop(1).map { it.location.transportKind() }
        )
    }

    @Test
    fun unknownLastKnownGoodFallsBackToDefaultOrder() {
        val ordered = TransportSelector.orderCandidates(
            listOf(usReality, usHy2),
            lastKnownGood = "no-such-id"
        )
        assertEquals(listOf(TransportKind.Reality, TransportKind.Hysteria2), ordered.map { it.location.transportKind() })
    }

    @Test
    fun olcrtcIsNeverACandidate() {
        val ordered = TransportSelector.orderCandidates(listOf(usReality, olcrtc("DE · olcRTC")))
        assertEquals(1, ordered.size)
        assertEquals(TransportKind.Reality, ordered.single().location.transportKind())
    }
}
