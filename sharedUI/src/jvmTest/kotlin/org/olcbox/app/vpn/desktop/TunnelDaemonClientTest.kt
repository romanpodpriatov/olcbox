package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.runBlocking
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class TunnelDaemonClientTest {

    /**
     * A real unix socket rather than a mock of one. The interesting failures in
     * this class are all in the socket handling — a path that does not exist, a
     * peer that closes early — and a mock of the transport is a mock of exactly
     * the part that can be wrong.
     */
    private fun withFakeDaemon(reply: String, body: (Path) -> Unit) {
        val dir = Files.createTempDirectory("tunneld-test")
        val path = dir.resolve("sock")
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(path))
        val thread = Thread {
            runCatching {
                while (true) {
                    server.accept().use { channel ->
                        channel.read(ByteBuffer.allocate(64 * 1024))
                        channel.write(ByteBuffer.wrap(reply.toByteArray()))
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            body(path)
        } finally {
            server.close()
            thread.interrupt()
            path.deleteIfExists()
        }
    }

    @Test
    fun statusRoundTripsThroughARealUnixSocket() = withFakeDaemon(
        """{"ok":true,"state":"running","pid":7,"logTail":""}""" + "\n"
    ) { path ->
        val reply = runBlocking { TunnelDaemonClient(path).status() }
        assertIs<DaemonReply.Ok>(reply)
        assertEquals("running", reply.state)
        assertEquals(7, reply.pid)
    }

    @Test
    fun anAbsentSocketIsAFailureNotAnException() {
        // Before the daemon is approved there is no socket, and that is the
        // normal state of a fresh install — not something to throw about.
        val missing = Path.of("/var/run/org.olcbox.app.tunneld.missing")
        assertIs<DaemonReply.Failure>(runBlocking { TunnelDaemonClient(missing).status() })
        assertFalse(runBlocking { TunnelDaemonClient(missing).isReachable() })
    }
}
