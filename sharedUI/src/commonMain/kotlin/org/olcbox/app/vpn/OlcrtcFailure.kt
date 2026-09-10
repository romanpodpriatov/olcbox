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
    const val NO_PEER = "Nobody is serving this room right now."

    private val protocolMarkers = listOf(
        "peer speaks an incompatible olcrtc protocol",
        "peer did not answer the handshake",
        "incompatible protocol version",
        "protocol version mismatch"
    )

    fun describe(raw: String): String = when {
        protocolMarkers.any { raw.contains(it, ignoreCase = true) } -> PROTOCOL
        raw.contains("key does not match the peer", ignoreCase = true) -> KEY
        raw.contains("no peer in room", ignoreCase = true) -> NO_PEER
        else -> raw
    }
}
