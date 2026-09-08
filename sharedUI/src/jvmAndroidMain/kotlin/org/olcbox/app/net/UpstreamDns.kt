package org.olcbox.app.net

/**
 * The resolver list the olcRTC engine takes: the servers of the network the
 * tunnel stands on, in the platform's order, and the public operator behind
 * them. The engine adds that operator's IPv6 twin and the other operators
 * after it, and asks the host's resolver last.
 *
 * Some mobile networks answer only their own servers and meet every public
 * operator with silence, which is how a room that worked on Wi-Fi came back
 * "i/o timeout" on cellular (olcbox#16). The network's servers are what the
 * carrier's own apps resolve through, so they are what goes first.
 */
object UpstreamDns {
    const val PUBLIC_OPERATOR = "1.1.1.1:53"

    /**
     * [addresses] as the platform prints them — "10.0.0.1", "fe80::1%rmnet0" —
     * to the one string the engine parses. Blanks, loopback and repeats are
     * dropped; the order is kept.
     */
    fun list(addresses: List<String>): String {
        val servers = addresses
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isLoopback(it) }
            .distinct()
        return (servers + PUBLIC_OPERATOR).joinToString(",")
    }

    private fun isLoopback(address: String): Boolean =
        address.startsWith("127.") || address == "::1" || address.startsWith("::1%")
}
