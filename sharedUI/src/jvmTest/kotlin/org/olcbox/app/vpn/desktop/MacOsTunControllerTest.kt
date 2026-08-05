package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MacOsTunControllerTest {

    @Test
    fun everyResolvedServerAddressBecomesAnExclusion() {
        assertEquals(
            listOf("203.0.113.7/32", "198.51.100.9/32", "2001:db8::1/128"),
            MacOsTunController.excludeCidrs(listOf("203.0.113.7", "198.51.100.9", "2001:db8::1"))
        )
    }

    @Test
    fun aHostThatDoesNotResolveIsNotSilentlyLeftUnexcluded() {
        val logs = mutableListOf<String>()
        val controller = MacOsTunController(
            addLog = { logs += it },
            client = TunnelDaemonClient(Path.of("/var/run/org.olcbox.app.tunneld.missing")),
            resolve = { emptyList() }
        )

        assertFailsWith<IllegalStateException> {
            runBlocking {
                controller.start(
                    corePort = 10810,
                    verifyPort = 10811,
                    username = "",
                    password = "",
                    serverHost = "de1.example.org",
                    upstreamUdpIsLossy = false
                )
            }
        }
        assertTrue(logs.any { "de1.example.org" in it }, "the log has to name the host: $logs")
    }

    @Test
    fun anAbsentDaemonFailsLoudlyRatherThanReportingAStartedTunnel() {
        val logs = mutableListOf<String>()
        val controller = MacOsTunController(
            addLog = { logs += it },
            client = TunnelDaemonClient(Path.of("/var/run/org.olcbox.app.tunneld.missing")),
            resolve = { listOf("203.0.113.7") }
        )

        assertFailsWith<IllegalStateException> {
            runBlocking {
                controller.start(
                    corePort = 10810,
                    verifyPort = 10811,
                    username = "",
                    password = "",
                    serverHost = "de1.example.org",
                    upstreamUdpIsLossy = false
                )
            }
        }
        assertTrue(logs.any { "macOS TUN failed" in it }, "the failure has to reach the log: $logs")
    }
}
