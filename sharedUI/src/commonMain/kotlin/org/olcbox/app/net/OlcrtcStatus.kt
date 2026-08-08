package org.olcbox.app.net

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import org.olcbox.app.crypt.PlatformCrypto

/**
 * How full an olcRTC node is, asked live.
 *
 * An olcRTC room holds single digits, not hundreds, so occupancy is something the user
 * has to see: refused with no explanation, a person concludes the app is broken and
 * retries all evening. And it cannot ride along in the subscription — a count written
 * into a server name is stale the moment it is written and stays stale, because nobody
 * refreshes a subscription that is working. The one time the number matters, the moment
 * the node fills up, is exactly the time a cached one would be wrong.
 *
 * The identity we present is [keyIdFor]: a one-way handle derived from the location's
 * own room key, which every olcRTC location already carries. That matters for
 * subscriptions we did not issue — someone who imported a partner's subscription has no
 * ProofKit account and no token, only the `olcrtc://` line — and it means the key itself
 * never goes near the network.
 */
@Serializable
data class OlcrtcSlots(
    val slots_total: Int,
    val slots_free: Int,
    /** Whether this key is one of the occupants. */
    val holds_slot: Boolean = false
) {
    /** Occupied slots, never negative even if a node's capacity was lowered below use. */
    val used: Int get() = (slots_total - slots_free).coerceAtLeast(0)

    /**
     * Whether this location should be offered.
     *
     * A node with no free slot is still connectable **for someone already on it** — they
     * hold their slot. Treating "full" as "unavailable" for that person would grey out
     * the server they are currently connected to.
     */
    val isBlocked: Boolean get() = slots_free <= 0 && !holds_slot
}

/**
 * `hex(sha256(raw key bytes))[:16]` — the same handle the olcRTC srv derives
 * (`internal/crypto.KeyIDFromHex`), the agent's stats poller computes
 * (`olcrtc_stats::key_id_for`), and Postgres stores as a generated column. Four
 * implementations of one hash; the test vectors below come from the database so they
 * cannot quietly disagree.
 *
 * Returns null when [keyHex] is not a valid 64-character hex key rather than throwing:
 * a malformed location should show no occupancy, not crash a list.
 */
fun keyIdFor(keyHex: String): String? {
    val hex = keyHex.trim().lowercase()
    if (hex.length != 64 || !hex.all { it.isDigit() || it in 'a'..'f' }) return null
    val raw = ByteArray(32) { i ->
        ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
    }
    return PlatformCrypto.sha256(raw)
        .joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }
        .take(16)
}

/**
 * Reads node occupancy from the coordinator.
 *
 * Always ProofKit's coordinator, whoever sold the subscription: the Telemost room the
 * link points at is ours, so it is the only party that knows how full it is.
 */
class OlcrtcStatusClient(
    private val httpClient: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL
) {
    /**
     * Occupancy for the node this key belongs to, or null when it cannot be determined.
     *
     * Null on every failure — unknown key, revoked key, network down, coordinator
     * unreachable — and deliberately not an error. Occupancy is an enrichment: not
     * knowing it must leave the list exactly as usable as it was before this existed,
     * never block a connection the user could otherwise make.
     */
    suspend fun slotsFor(keyHex: String): OlcrtcSlots? {
        val keyId = keyIdFor(keyHex) ?: return null
        return try {
            val response: HttpResponse = httpClient.get("$baseUrl$PATH") {
                parameter("key_id", keyId)
            }
            if (response.status != HttpStatusCode.OK) null else response.body<OlcrtcSlots>()
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://proofkit.org"
        const val PATH = "/api/v1/olcrtc/status"
    }
}
