package org.olcbox.app.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogScrubberTest {
    private val s = LogScrubber(salt = 12345L)

    @Test fun aPublicAddressBecomesATag() {
        val out = s.scrub("engine: dial tcp 95.179.246.109:443: i/o timeout")
        assertFalse(out.contains("95.179.246.109"), out)
        assertTrue(Regex("node#[0-9a-f]{4}").containsMatchIn(out), out)
        assertTrue(out.contains(":443: i/o timeout"), "the port and the error must survive: $out")
    }

    @Test fun aPublicIpv6BecomesATag() {
        val out = s.scrub("engine: dial tcp [2a01:4f8:c17:b8f::1]:443 failed")
        assertFalse(out.contains("2a01"), out)
        assertTrue(Regex("node#[0-9a-f]{4}").containsMatchIn(out), out)
    }

    @Test fun theSameHostReadsTheSameAndDifferentHostsDiffer() {
        val out = s.scrub("tried 95.179.246.109 then 45.32.190.172 then 95.179.246.109")
        val tags = Regex("node#[0-9a-f]{4}").findAll(out).map { it.value }.toList()
        assertEquals(3, tags.size, out)
        assertEquals(tags[0], tags[2], "one host must read the same throughout: $out")
        assertTrue(tags[0] != tags[1], "two hosts must not collapse into one: $out")
    }

    @Test fun localAddressesSurviveVerbatim() {
        // Most of what makes a log readable is local, and none of it is secret.
        for (line in listOf(
            "core ready on 127.0.0.1:1080",
            "socks5 server listening on 0.0.0.0:10808",
            "tun 172.19.0.1/30 up",
            "tun6 fdfe:dcba:9876::1/126 up",
            "gateway 192.168.1.1",
            "wg peer 10.66.66.2",
            "carrier nat 100.64.0.1",
            "self-assigned 169.254.1.1",
        )) {
            assertEquals(line, s.scrub(line), "a local address must not be tagged")
        }
    }

    @Test fun credentialsAndCapabilitiesGo() {
        assertEquals("start room=<id>", s.scrub("start room=a3f9c1e2-4b5d-6789-0abc-def012345678"))
        assertEquals("import <link>", s.scrub("import olcrtc://crypt1/AAAAbbbbCCCCdddd"))
        assertEquals("import <link>", s.scrub("import happ://crypt5/fzvdQQSl2kKPyNPhAeRV4WSh12xLFV8"))
        assertEquals("GET <host>/sub/x", s.scrub("GET sub.proofkit.org/sub/x"))
        assertEquals("GET <host>/api", s.scrub("GET proofkit.org/api"))
    }

    @Test fun anOrdinaryLineComesBackUntouched() {
        // The regressions that matter are the ones that eat text nobody was hiding.
        for (line in listOf(
            "2026-08-07 15:28:17 INFO tunnel established",
            "olcbox 1.0.270 (270) starting",
            "build 1.2.3.400 is not an address",
            "Packet tunnel up",
            "ping DE: could not resolve dns.google",
            "sni www.microsoft.com fp chrome mtu 1500",
        )) {
            assertEquals(line, s.scrub(line), "nothing sensitive here — leave it alone")
        }
    }

    @Test fun aDifferentSaltGivesADifferentTag() {
        // Otherwise a tag is a confirmation oracle: guess the address, compute the tag.
        val line = "dial 95.179.246.109"
        assertTrue(
            s.scrub(line) != LogScrubber(salt = 999L).scrub(line),
            "tags must not be comparable across installs"
        )
    }

    @Test fun theTransportStateMarkersSurvive() {
        // These exact strings decide reconnect — handleRtcLine (IosVpnManager),
        // OlcboxVpnService and DesktopVpnManager all match on them. The scrubber runs
        // after those parsers today, and this test is what keeps a future refactor
        // from quietly breaking reconnect by moving it earlier.
        for (marker in listOf(
            "socks5 server listening on 127.0.0.1:1080",
            "ice connection state changed: connected",
            "peer connection state changed: connected",
            "ice connection state changed: failed",
            "peer connection state changed: closed",
            "network is unreachable",
            "use of closed network connection",
            "read/write on closed pipe",
        )) {
            assertEquals(marker, s.scrub(marker), "a state marker must pass through intact")
        }
    }
}
