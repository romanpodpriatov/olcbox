package org.olcbox.app.vpn.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * What the app and the root daemon say to each other: one JSON object per line,
 * one reply per request, connection closed after it.
 *
 * Line-delimited rather than length-prefixed because every message here is small
 * and because a person debugging this can type one into `nc -U` and read the
 * answer. That has already been worth more than the framing it gives up.
 */
internal sealed interface DaemonReply {
    data class Ok(
        val state: String,
        val pid: Int?,
        val logTail: String,
        /** What the daemon speaks; the first daemons said nothing and are 1. */
        val protocol: Int = TunnelDaemonProtocol.PROTOCOL_FIRST
    ) : DaemonReply

    data class Failure(
        val message: String,
        val logTail: String,
        /** The daemon found a newer binary on disk and is stepping aside for it. */
        val restarting: Boolean = false
    ) : DaemonReply

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
    fun startRequest(config: String, files: Map<String, String> = emptyMap()): String =
        line(
            buildJsonObject {
                put("verb", "start")
                put("config", config)
                // Rule-set binaries the config refers to, file name → base64. The
                // daemon writes them next to the config, root-owned, and removes
                // the directory when there are none, so a stale list cannot
                // outlive the routing choice that put it there.
                if (files.isNotEmpty()) {
                    putJsonObject("files") { files.forEach { (name, base64) -> put(name, base64) } }
                }
            }
        )

    /**
     * The daemon's own directory, as `TunnelChild.stateDir` names it in Swift.
     * Duplicated here because the config the app builds has to name absolute
     * paths inside it — the rule-sets and the cache file — before the daemon
     * ever sees the request. The two literals must agree.
     */
    const val STATE_DIR = "/Library/Application Support/org.olcbox.app"
    const val RULES_DIR = "$STATE_DIR/rules"
    const val CACHE_FILE = "$STATE_DIR/cache.db"

    /** Mirror of the daemon's `protocolVersion` history: 1 said nothing, 2 takes rule files. */
    const val PROTOCOL_FIRST = 1
    const val PROTOCOL_FILES = 2

    /** Mirror of the plist's `Label`; what `launchctl` calls the daemon. */
    const val LABEL = "org.olcbox.app.desktopApp.tunneld"

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
        if (!ok) {
            return DaemonReply.Failure(
                obj.str("error") ?: "the tunnel daemon reported a failure",
                tail,
                restarting = (obj["restarting"] as? JsonPrimitive)?.booleanOrNull ?: false
            )
        }
        val state = obj.str("state")
            ?: return DaemonReply.Failure("the tunnel daemon reported success without a state", tail)
        return DaemonReply.Ok(
            state,
            (obj["pid"] as? JsonPrimitive)?.intOrNull,
            tail,
            protocol = (obj["protocol"] as? JsonPrimitive)?.intOrNull ?: PROTOCOL_FIRST
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** Enough of an unparseable reply to recognise it in a log, and no more. */
    private const val TAIL_LIMIT = 400
}
