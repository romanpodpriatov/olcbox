package org.olcbox.app.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The verifier turns "we ran the steps" into "traffic came back". Its parsing is the
 * part that can be tested without a network, so it is tested here against the exact
 * shape Cloudflare returns — including the IPv6 form a WARP exit produces, which is
 * what our origins actually present.
 */
class TunnelVerifierTest {

    @Test
    fun readsIpv6ExitAndCountry() {
        // Shape observed through a real JP origin behind WARP.
        val body = """
            fl=123abc
            h=1.1.1.1
            ip=2a09:bac5:42f3:174b::252:63
            ts=1769289600.123
            visit_scheme=https
            uag=curl/8.7.1
            colo=KIX
            loc=JP
            tls=TLSv1.3
        """.trimIndent()

        val exit = TunnelVerifier.parseTrace(body)

        assertEquals("2a09:bac5:42f3:174b::252:63", exit?.ip)
        assertEquals("JP", exit?.country)
    }

    @Test
    fun readsIpv4Exit() {
        val exit = TunnelVerifier.parseTrace("ip=203.0.113.7\nloc=DE\n")
        assertEquals("203.0.113.7", exit?.ip)
        assertEquals("DE", exit?.country)
    }

    @Test
    fun missingCountryStillCounts() {
        // An exit is proven by the address alone; the country is a bonus.
        val exit = TunnelVerifier.parseTrace("ip=203.0.113.7\n")
        assertEquals("203.0.113.7", exit?.ip)
        assertNull(exit?.country)
    }

    @Test
    fun unknownCountryIsNotReported() {
        // Cloudflare uses XX when it cannot place the address; showing "XX · …" to a
        // user is worse than showing the address alone.
        assertNull(TunnelVerifier.parseTrace("ip=203.0.113.7\nloc=XX\n")?.country)
    }

    @Test
    fun bodyWithoutAnAddressIsAFailure() {
        // A captive portal or an error page must not read as a working tunnel.
        assertNull(TunnelVerifier.parseTrace("<html>Access denied</html>"))
        assertNull(TunnelVerifier.parseTrace(""))
        assertNull(TunnelVerifier.parseTrace("loc=JP\n"))
    }

    @Test
    fun realWarpExitFitsWithoutTruncation() {
        // The address our origins actually present must be readable in full.
        assertEquals(
            "JP · 2a09:bac5:42f3:174b::252:63",
            TunnelExit("2a09:bac5:42f3:174b::252:63", "JP").label()
        )
    }

    @Test
    fun veryLongExitKeepsItsTail() {
        // Two addresses from one block differ at the end, so the tail must survive.
        assertEquals(
            "JP · 2a09:bac5:42…252:0063",
            TunnelExit("2a09:bac5:42f3:174b:0000:0000:0252:0063", "JP").label()
        )
    }

    @Test
    fun shortExitsAreShownAsIs() {
        assertEquals("DE · 203.0.113.7", TunnelExit("203.0.113.7", "DE").label())
        assertEquals("203.0.113.7", TunnelExit("203.0.113.7", null).label())
    }
}
