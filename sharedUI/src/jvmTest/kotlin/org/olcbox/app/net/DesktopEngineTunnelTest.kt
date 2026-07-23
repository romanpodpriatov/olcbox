package org.olcbox.app.net

import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end proof that DesktopSingBoxController (our Kotlin) actually spawns the
 * real sing-box binary and carries traffic — not just that the binary works.
 * Env-gated on OLCBOX_TEST_SINGBOX_BIN (set in CI after downloading sing-box);
 * skips locally. Chains our olcrtc-socks config (:10809 → socks :10808) through a
 * backing sing-box socks server (stands in for the olcrtc engine) and curls out.
 */
class DesktopEngineTunnelTest {
    @Test
    fun singBoxControllerTunnelsOlcrtcSocksChain() = runTest {
        val bin = System.getenv("OLCBOX_TEST_SINGBOX_BIN")?.takeIf { it.isNotBlank() } ?: return@runTest
        val binPath: () -> Path = { Path.of(bin) }
        val backing = DesktopSingBoxController(binPath)
        val client = DesktopSingBoxController(binPath)
        try {
            backing.start(backingSocksConfig(10808))
            client.start(SingBoxConfig.buildOlcrtcSocks(olcrtcPort = 10808)) // in :10809 → socks :10808
            Thread.sleep(3000)
            assertEquals(200, curlThroughSocks(10809, "http://example.com"))
        } finally {
            client.stop()
            backing.stop()
        }
    }

    private fun backingSocksConfig(port: Int): String =
        """{"inbounds":[{"type":"socks","listen":"127.0.0.1","listen_port":$port}],"outbounds":[{"type":"direct"}]}"""

    private fun curlThroughSocks(port: Int, url: String): Int {
        val p = ProcessBuilder(
            "curl", "-s", "-o", "/dev/null", "-w", "%{http_code}",
            "--max-time", "20", "--socks5-hostname", "127.0.0.1:$port", url,
        ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        return out.toIntOrNull() ?: 0
    }
}
