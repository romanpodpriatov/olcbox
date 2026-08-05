package org.olcbox.app.vpn.desktop

import org.olcbox.app.net.SingBoxConfig
import java.net.InetAddress

/**
 * macOS TUN: builds the daemon's config and asks the daemon to run it.
 *
 * The routes are deliberately not this class's business. sing-box's `auto_route`
 * installs them and removes them with the process, which is why there is nothing
 * here resembling the up/down scripts the Linux controller needs and the route
 * bookkeeping the Windows one carries — on macOS the core does that job, and does
 * it on the way out too.
 */
internal class MacOsTunController(
    private val addLog: (String) -> Unit,
    private val client: TunnelDaemonClient = TunnelDaemonClient(),
    private val resolve: (String) -> List<String> = ::resolveAllAddresses,
) {
    suspend fun start(
        corePort: Int,
        verifyPort: Int,
        username: String,
        password: String,
        serverHost: String?,
        upstreamUdpIsLossy: Boolean,
    ) {
        val addresses = serverHost?.let { resolve(it) }.orEmpty()
        if (serverHost != null && addresses.isEmpty()) {
            // Starting anyway would put the core's own packets into the tunnel the
            // core is building. That does not degrade — it deadlocks, and it reads
            // as a broken server rather than as a missing route.
            addLog("cannot resolve $serverHost, so its traffic cannot be kept out of the tunnel")
            error("cannot resolve $serverHost")
        }

        val config = SingBoxConfig.buildDesktopTun(
            corePort = corePort,
            verifyPort = verifyPort,
            username = username,
            password = password,
            excludeAddresses = excludeCidrs(addresses),
            directDnsDomains = listOfNotNull(serverHost?.takeIf { !it.isIpLiteral() }),
            upstreamUdpIsLossy = upstreamUdpIsLossy,
        )

        when (val reply = client.start(config)) {
            is DaemonReply.Ok -> addLog("macOS TUN running (sing-box pid ${reply.pid})")
            is DaemonReply.Failure -> {
                addLog("macOS TUN failed: ${reply.message}")
                if (reply.logTail.isNotBlank()) addLog(reply.logTail)
                error(reply.message)
            }
        }
    }

    suspend fun stop() {
        when (val reply = client.stop()) {
            is DaemonReply.Ok -> addLog("macOS TUN stopped")
            is DaemonReply.Failure -> addLog("macOS TUN stop failed: ${reply.message}")
        }
    }

    suspend fun isRunning(): Boolean =
        (client.status() as? DaemonReply.Ok)?.state == DaemonReply.STATE_RUNNING

    internal companion object {
        /**
         * Every address the server resolves to, not the first one.
         *
         * A server that answers with four addresses and is excluded on one is a
         * tunnel that works until the core redials and happens to pick another —
         * a failure that looks intermittent and is not.
         */
        fun excludeCidrs(addresses: List<String>): List<String> =
            addresses.map { if (':' in it) "$it/128" else "$it/32" }
    }
}

private fun String.isIpLiteral(): Boolean =
    ':' in this || split('.').let { parts ->
        parts.size == 4 && parts.all { it.toIntOrNull() != null }
    }

internal fun resolveAllAddresses(host: String): List<String> =
    runCatching {
        InetAddress.getAllByName(host).mapNotNull { it.hostAddress?.substringBefore('%') }
    }.getOrDefault(emptyList())
