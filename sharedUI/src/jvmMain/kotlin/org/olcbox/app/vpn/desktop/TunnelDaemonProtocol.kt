package org.olcbox.app.vpn.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * What the app and the root daemon say to each other: one JSON object per line,
 * one reply per request, connection closed after it.
 *
 * Line-delimited rather than length-prefixed because every message here is small
 * and because a person debugging this can type one into `nc -U` and read the
 * answer. That has already been worth more than the framing it gives up.
 */
internal sealed interface DaemonReply {
    data class Ok(val state: String, val pid: Int?, val logTail: String) : DaemonReply
    data class Failure(val message: String, val logTail: String) : DaemonReply

    companion object {
        const val STATE_RUNNING = "running"
        const val STATE_IDLE = "idle"
    }
}

internal object TunnelDaemonProtocol {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The config travels as a JSON *string*, not as a nested object: the daemon
     * writes it to disk byte for byte, and a re-serialised copy is a copy that
     * the `sing-box check` in CI never saw.
     */
    fun startRequest(config: String): String =
        line(buildJsonObject { put("verb", "start"); put("config", config) })

    fun stopRequest(): String = line(buildJsonObject { put("verb", "stop") })

    fun statusRequest(): String = line(buildJsonObject { put("verb", "status") })

    private fun line(obj: JsonObject): String = obj.toString() + "\n"

    fun parseReply(raw: String): DaemonReply {
        val obj = runCatching { json.parseToJsonElement(raw.trim()) as? JsonObject }
            .getOrNull()
            ?: return DaemonReply.Failure(
                "the tunnel daemon replied with something that is not JSON",
                raw.take(TAIL_LIMIT)
            )
        val tail = obj.str("logTail").orEmpty()
        val ok = (obj["ok"] as? JsonPrimitive)?.booleanOrNull
            ?: return DaemonReply.Failure("the tunnel daemon's reply carried no verdict", tail)
        if (!ok) return DaemonReply.Failure(obj.str("error") ?: "the tunnel daemon reported a failure", tail)
        val state = obj.str("state")
            ?: return DaemonReply.Failure("the tunnel daemon reported success without a state", tail)
        return DaemonReply.Ok(state, (obj["pid"] as? JsonPrimitive)?.intOrNull, tail)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** Enough of an unparseable reply to recognise it in a log, and no more. */
    private const val TAIL_LIMIT = 400
}
