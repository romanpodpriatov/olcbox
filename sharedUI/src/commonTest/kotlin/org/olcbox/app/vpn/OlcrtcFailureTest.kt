package org.olcbox.app.vpn

import kotlin.test.Test
import kotlin.test.assertEquals

class OlcrtcFailureTest {

    @Test
    fun anOlderServerIsNamedAsSuch() {
        val raw = "olcRTC start failed: handshake: peer speaks an incompatible olcrtc protocol: read welcome: i/o timeout"
        assertEquals(OlcrtcFailure.PROTOCOL, OlcrtcFailure.describe(raw))
    }

    // This used to assert PROTOCOL, on the reasoning that a server too old to
    // read our records cannot answer. So are three other causes, and the engine
    // keeps ErrPeerSilent apart from its protocol errors on purpose.
    @Test
    fun aSilentPeerIsNotAnAccusationAboutVersions() {
        assertEquals(OlcrtcFailure.SILENT, OlcrtcFailure.describe("handshake: peer did not answer the handshake"))
        assertEquals(
            OlcrtcFailure.SILENT,
            OlcrtcFailure.describe(
                "olcRTC start failed: run public client: client: handshake: " +
                    "peer did not answer the handshake: handshake client: read welcome: handshake: read hdr: timeout"
            )
        )
    }

    @Test
    fun theEnginesOwnVersionErrorsMapToo() {
        assertEquals(OlcrtcFailure.PROTOCOL, OlcrtcFailure.describe("x: incompatible protocol version: server v2, client v3"))
        assertEquals(OlcrtcFailure.PROTOCOL, OlcrtcFailure.describe("handshake rejected: protocol version mismatch"))
    }

    @Test
    fun aWrongKeyAndAnEmptyRoomHaveTheirOwnLines() {
        assertEquals(OlcrtcFailure.KEY, OlcrtcFailure.describe("handshake: key does not match the peer: read welcome"))
        assertEquals(OlcrtcFailure.NO_PEER, OlcrtcFailure.describe("failed: handshake: no peer in room: read welcome"))
    }

    @Test
    fun anythingElsePassesThrough() {
        assertEquals("carrier auth failed", OlcrtcFailure.describe("carrier auth failed"))
    }
}
