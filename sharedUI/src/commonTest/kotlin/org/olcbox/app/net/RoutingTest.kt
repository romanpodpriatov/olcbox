package org.olcbox.app.net

import kotlin.test.Test
import kotlin.test.assertEquals

class RoutingTest {
    @Test fun firstIPv4Wins() {
        assertEquals("192.0.2.5", DirectDns.Servers(listOf("fe80::1%wlan0", "192.0.2.5", "192.0.2.6")).pick())
    }

    @Test fun ipv6WithoutAZoneIsAcceptedWhenThereIsNoIPv4() {
        assertEquals("2001:db8::53", DirectDns.Servers(listOf("2001:db8::53%en0")).pick())
    }

    @Test fun linkLocalAndLoopbackAreNotResolvers() {
        // A link-local address needs a zone sing-box cannot carry, and loopback
        // inside the tunnel process is the tunnel itself.
        assertEquals(
            SingBoxConfig.DIRECT_DNS_FALLBACK,
            DirectDns.Servers(listOf("fe80::1%wlan0", "127.0.0.1", "::1")).pick()
        )
    }

    @Test fun nothingOfferedFallsBackToThePublicResolver() {
        assertEquals(SingBoxConfig.DIRECT_DNS_FALLBACK, DirectDns.Servers(emptyList()).pick())
        assertEquals(SingBoxConfig.DIRECT_DNS_FALLBACK, DirectDns.Servers(listOf(" ", "")).pick())
    }
}
