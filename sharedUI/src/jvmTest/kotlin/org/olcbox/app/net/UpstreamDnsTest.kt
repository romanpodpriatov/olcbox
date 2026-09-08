package org.olcbox.app.net

import kotlin.test.Test
import kotlin.test.assertEquals

// The engine takes its resolvers as one string: the servers of the network
// the tunnel stands on first, in the order the platform lists them, and the
// public operator behind them for the networks where the carrier's servers
// stop answering. Some mobile networks answer only their own servers, which
// is how a room that worked on Wi-Fi came back "i/o timeout" on cellular
// (olcbox#16).
class UpstreamDnsTest {
    @Test
    fun theNetworksServersComeFirstAndThePublicOperatorLast() {
        assertEquals(
            "10.0.0.1,10.0.0.2,1.1.1.1:53",
            UpstreamDns.list(listOf("10.0.0.1", "10.0.0.2"))
        )
    }

    @Test
    fun aLinkLocalServerKeepsItsZone() {
        assertEquals(
            "fe80::1%rmnet0,1.1.1.1:53",
            UpstreamDns.list(listOf("fe80::1%rmnet0"))
        )
    }

    @Test
    fun loopbackBlanksAndDuplicatesAreDropped() {
        assertEquals(
            "10.0.0.1,1.1.1.1:53",
            UpstreamDns.list(listOf("127.0.0.1", "", " 10.0.0.1 ", "::1", "10.0.0.1"))
        )
    }

    @Test
    fun noServersMeansThePublicOperatorAlone() {
        assertEquals("1.1.1.1:53", UpstreamDns.list(emptyList()))
    }
}
