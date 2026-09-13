package org.olcbox.app.vpn

/**
 * The engine says what it saw; the user needs what to do. The engine's
 * sentinel texts are matched as substrings because they arrive wrapped
 * ("handshake: <sentinel>: read welcome: ...") and, on iOS, behind the
 * extension's "failed: " breadcrumb.
 */
object OlcrtcFailure {
    const val PROTOCOL =
        "This room's server runs a different olcRTC protocol version. " +
            "Update the app, or ask the operator to update the server."
    const val KEY = "The key does not match this server. Import the link again."

    /**
     * A peer was there and never answered. That is all the engine claims, and
     * it is all this should claim.
     *
     * It used to say [PROTOCOL], on the reasoning that a server too old to read
     * our records cannot answer. True, and so are the alternatives: a server
     * that no longer holds our key, one that dropped us, or a path losing the
     * reply. Naming one of four and telling the user to update something sends
     * them after a fix that does nothing — as it did on a room whose media
     * track closed after 173 frames on a link that was timing out to TURN.
     *
     * The engine deliberately keeps [ErrPeerSilent] apart from its protocol
     * errors; this had put them back together.
     */
    const val SILENT = "This room's server did not answer. It may be offline, out of date, " +
        "or no longer hold your key. Try another room, or refresh the server list."
    /**
     * A server that no longer holds our key cannot answer at all, so the engine
     * reports a silent peer. The app knows better when the status probe has
     * already marked the key as gone.
     */
    const val KEY_GONE = "Your key for this room is no longer valid. Refresh the server list, then try again."
    const val NO_PEER = "Nobody is serving this room right now."

    /**
     * Only what the engine actually classified as a version problem: an
     * explicit version error, a rejection giving that reason, or records whose
     * magic belongs to another protocol. A silent peer is not one of these.
     */
    private val protocolMarkers = listOf(
        "peer speaks an incompatible olcrtc protocol",
        "incompatible protocol version",
        "protocol version mismatch"
    )

    fun describe(raw: String): String = when {
        protocolMarkers.any { raw.contains(it, ignoreCase = true) } -> PROTOCOL
        raw.contains("peer did not answer the handshake", ignoreCase = true) -> SILENT
        raw.contains("key does not match the peer", ignoreCase = true) -> KEY
        raw.contains("no peer in room", ignoreCase = true) -> NO_PEER
        else -> raw
    }
}
