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
    fun aStartCarriesItsRuleFilesByNameUnderFiles() {
        val start = TunnelDaemonProtocol.startRequest("{}", mapOf("geoip-ru.srs" to "AAEC"))
        assertTrue("\"files\":{\"geoip-ru.srs\":\"AAEC\"}" in start)
        // And none at all when there are none: the first daemons never look.
        assertTrue("files" !in TunnelDaemonProtocol.startRequest("{}"))
    }

    @Test
    fun aDaemonThatNamesNoProtocolIsTheFirstOne() {
        val first = TunnelDaemonProtocol.parseReply("""{"ok":true,"state":"idle","logTail":""}""")
        assertIs<DaemonReply.Ok>(first)
        assertEquals(TunnelDaemonProtocol.PROTOCOL_FIRST, first.protocol)
        val current = TunnelDaemonProtocol.parseReply("""{"ok":true,"state":"idle","logTail":"","protocol":2}""")
        assertIs<DaemonReply.Ok>(current)
        assertEquals(TunnelDaemonProtocol.PROTOCOL_FILES, current.protocol)
    }

    @Test
    fun aDaemonSteppingAsideForAnUpdateSaysSo() {
        val stepping = TunnelDaemonProtocol.parseReply(
            """{"ok":false,"error":"the tunnel helper was updated and is restarting","restarting":true,"logTail":""}"""
        )
        assertIs<DaemonReply.Failure>(stepping)
        assertTrue(stepping.restarting)
        val plain = TunnelDaemonProtocol.parseReply("""{"ok":false,"error":"sing-box exited","logTail":""}""")
        assertIs<DaemonReply.Failure>(plain)
        assertTrue(!plain.restarting)
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
