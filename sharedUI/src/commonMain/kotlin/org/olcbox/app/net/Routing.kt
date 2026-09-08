package org.olcbox.app.net

/**
 * What sing-box does with a connection: everything through the tunnel, or the
 * split this app calls Bypass Russia.
 *
 * A builder-level model, deliberately separate from the persisted setting
 * ([org.olcbox.app.data.model.RoutingMode]): the setting is one word, this is
 * what the platform resolved that word into — where it put the rule-set files
 * and which resolver "direct" traffic may use.
 */
sealed interface Routing {
    /** Everything through the tunnel: the shape every builder emitted before routing existed. */
    data object Global : Routing

    /**
     * Russian destinations and the local network go straight out; everything
     * else rides the tunnel, name resolution included.
     *
     * [ruleSetDir] holds the files in [RuleSets]. Absolute where the app knows
     * the path (Android); relative to the core's working directory where only
     * the extension does (iOS, [RuleSets.IOS_RELATIVE_DIR]).
     */
    data class BypassRussia(val ruleSetDir: String, val directDns: DirectDns) : Routing
}

/**
 * The resolver for names that go direct. It must not be the tunnel: the whole
 * point of resolving `sberbank.ru` here is that neither the query nor the
 * connection that follows it leaves through the exit.
 */
sealed interface DirectDns {
    /**
     * The operating system's resolver. Only where the core reaches it without
     * looping through its own tun — desktop. Inside an iOS tunnel the system
     * resolver *is* the tun, and sing-box's darwin `local` transport falls back
     * to exactly that once a tun inbound exists.
     */
    data object System : DirectDns

    /**
     * Explicit resolver addresses as the platform lists them — IP literals, a
     * `%zone` suffix tolerated. One is used: sing-box has no failover between
     * servers, so [pick] chooses the first IPv4, else the first routable IPv6,
     * else the public fallback.
     */
    data class Servers(val addresses: List<String>) : DirectDns {
        fun pick(): String {
            val hosts = addresses.map { it.trim().substringBefore('%') }
                .filter { it.isNotEmpty() && !isLoopback(it) }
            return hosts.firstOrNull { isIPv4(it) }
                ?: hosts.firstOrNull { ':' in it && !it.startsWith("fe80:", ignoreCase = true) }
                ?: SingBoxConfig.DIRECT_DNS_FALLBACK
        }

        private fun isIPv4(host: String): Boolean =
            host.split('.').let { parts -> parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 } }

        private fun isLoopback(host: String): Boolean = host.startsWith("127.") || host == "::1"
    }

    /**
     * iOS. The extension learns the network's resolver only after the config
     * has been written — it reads it just before the tunnel's settings replace
     * the system resolver with our own tun — so the builder emits
     * [SingBoxConfig.DIRECT_DNS_PLACEHOLDER] and the extension replaces it.
     */
    data object Placeholder : DirectDns
}
