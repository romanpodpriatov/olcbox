package org.olcbox.app.net

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * How long the path to a server takes, for a location nothing is connected to.
 *
 * The cores speak the protocol; this does not. It measures the route, which is
 * what "ping" has meant since before any of these protocols existed, and it is
 * the only figure available without standing a whole core up per location —
 * which on desktop means a process each and on Android a second one beside the
 * running tunnel.
 *
 * Two attempts, because neither alone covers the transports we ship. ICMP goes
 * first: it is the honest measurement, it needs nothing open on the server, and
 * it is the only thing Hysteria2 answers — it serves no TCP at all and its UDP
 * is obfuscated past recognising. Where ICMP is filtered, a TCP connect to the
 * port the link names stands in; that reaches Reality, plain TLS and XHTTP,
 * which all listen on TCP.
 *
 * Resolution happens before the clock starts. A cold DNS lookup is easily
 * 50 ms and belongs to the resolver, not to the server being measured.
 */
internal object PathLatency {
    private const val ICMP_TIMEOUT_MS = 3_000
    private const val TCP_TIMEOUT_MS = 3_000

    fun measure(host: String, port: Int): Long? {
        val address = try {
            InetAddress.getByName(host)
        } catch (_: Exception) {
            return null
        }
        return icmp(address) ?: tcp(address, port)
    }

    private fun icmp(address: InetAddress): Long? = try {
        val started = System.nanoTime()
        if (address.isReachable(ICMP_TIMEOUT_MS)) elapsedMs(started) else null
    } catch (_: Exception) {
        null
    }

    private fun tcp(address: InetAddress, port: Int): Long? = try {
        val started = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(address, port), TCP_TIMEOUT_MS) }
        elapsedMs(started)
    } catch (_: Exception) {
        null
    }

    /**
     * Never zero. A sub-millisecond figure is real on a LAN, and rendering it as
     * 0 ms reads as "not measured" in a list where that is what a blank means.
     */
    private fun elapsedMs(startedNanos: Long): Long =
        ((System.nanoTime() - startedNanos) / 1_000_000).coerceAtLeast(1)
}
