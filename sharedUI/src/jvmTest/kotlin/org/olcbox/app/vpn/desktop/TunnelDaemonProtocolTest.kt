package org.olcbox.app.vpn.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TunnelDaemonProtocolTest {

    @Test
    fun requestsAreOneLineEachBecauseTheDaemonReadsByLine() {
        val start = TunnelDaemonProtocol.startRequest("{\"log\":{\"level\":\"info\"}}")
        assertEquals(1, start.count { it == '\n' })
        assertTrue(start.endsWith("\n"))
        assertTrue(start.startsWith("{"))
        // The config is a JSON *string* inside the request, not spliced in raw:
        // spliced, its own newlines would end the request half-written.
        assertTrue("\\\"level\\\"" in start)
    }

    @Test
    fun aRunningDaemonReportsItsChildPid() {
        val reply = TunnelDaemonProtocol.parseReply(
            """{"ok":true,"state":"running","pid":4242,"logTail":"started"}"""
        )
        assertIs<DaemonReply.Ok>(reply)
        assertEquals("running", reply.state)
        assertEquals(4242, reply.pid)
    }

    @Test
    fun anErrorKeepsTheTailBecauseTheReasonIsInSingBoxOutputNotInOurMessage() {
        val reply = TunnelDaemonProtocol.parseReply(
            """{"ok":false,"error":"sing-box exited","logTail":"FATAL bind: permission denied"}"""
        )
        assertIs<DaemonReply.Failure>(reply)
        assertEquals("sing-box exited", reply.message)
        assertTrue("permission denied" in reply.logTail)
    }

    @Test
    fun anUnparseableReplyIsAFailureNotACrashAndNeverAnOk() {
        // A daemon that answers garbage is a daemon in an unknown state, and the
        // only safe direction to round an unknown state is down.
        assertIs<DaemonReply.Failure>(TunnelDaemonProtocol.parseReply("not json"))
        assertIs<DaemonReply.Failure>(TunnelDaemonProtocol.parseReply(""))
        assertIs<DaemonReply.Failure>(TunnelDaemonProtocol.parseReply("""{"state":"running"}"""))
    }
}
