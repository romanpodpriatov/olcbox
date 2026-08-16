package org.olcbox.app.net

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
     * Whether this location should be offered, as the wire format describes it.
     *
     * A node with no free slot is still connectable **for someone already on it** — they
     * hold their slot. Treating "full" as "unavailable" for that person would grey out
     * the server they are currently connected to.
     *
     * **The board does not use this, and neither should any new screen.**
     * [holds_slot] is "does this key occupy a slot", the key id is derived from the
     * location's own room key, and one server list issues the same key across all of
     * its rooms — so it comes back true for rooms the user has never joined, and stays
     * true for five minutes after a session ends. Here that only errs towards letting a
     * tap through, which is harmless. Painting a seat from it put a lime "your seat" and
     * the words 1 / 8 on every empty room in the list. The UI asks
     * `PkBoardModel.roomIsBlocked` instead, which reads the app's own connection state.
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
 * What the coordinator had to say about one key.
 *
 * Three answers, not two: a number, a dead key, and "ask again later". The middle
 * one is the whole reason this is a type — see [OlcrtcStatusClient.statusFor].
 */
sealed interface OlcrtcNodeStatus {
    data class Occupancy(val slots: OlcrtcSlots) : OlcrtcNodeStatus

    /** The coordinator does not recognise this key. It will not pair again. */
    data object KeyGone : OlcrtcNodeStatus

    /** No answer. Says nothing about the key, and must change nothing on screen. */
    data object Unavailable : OlcrtcNodeStatus
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
     * Parsed here rather than through `body<T>()`.
     *
     * The client this is handed is the app's shared one, and it installs `HttpTimeout`
     * and nothing else — no `ContentNegotiation`. `body<T>()` against it throws
     * `NoTransformationFoundException` on every call, which the catch below would turn
     * into a silent "no occupancy" forever. `PartnerLinkResolver` reads its own body for
     * the same reason; doing it here means this works whatever client it is given.
     */
    private val json = Json { ignoreUnknownKeys = true }
    /**
     * Occupancy for the node this key belongs to, or null when it cannot be determined.
     *
     * Kept for callers that only want the number. [statusFor] is the one to use where
     * the difference between "cannot reach the coordinator" and "this key is dead"
     * matters, which on a screen it does.
     */
    suspend fun slotsFor(keyHex: String): OlcrtcSlots? =
        (statusFor(keyHex) as? OlcrtcNodeStatus.Occupancy)?.slots

    /**
     * What the coordinator says about this key.
     *
     * Every failure used to collapse into null, and that cost an evening: when a
     * partner sweeper revoked a batch of keys, the board lost its seats, the tunnel
     * refused to pair, and the app said nothing at all — indistinguishable from
     * being broken. A `404` is not a failure to answer, it is an answer: this key
     * is no longer one the coordinator knows.
     *
     * Everything else is still [Unavailable] and must stay harmless. Occupancy is an
     * enrichment: not knowing it leaves the list exactly as usable as it was before
     * this existed, and never blocks a connection the user could otherwise make.
     */
    suspend fun statusFor(keyHex: String): OlcrtcNodeStatus {
        // A malformed key is not a revoked one: it never had a chance to be asked
        // about, so it says nothing and changes nothing.
        val keyId = keyIdFor(keyHex) ?: return OlcrtcNodeStatus.Unavailable
        return try {
            val response: HttpResponse = httpClient.get("$baseUrl$PATH") {
                parameter("key_id", keyId)
            }
            when (response.status) {
                HttpStatusCode.OK ->
                    OlcrtcNodeStatus.Occupancy(json.decodeFromString(response.bodyAsText()))
                // The coordinator answers 404 both for a key it does not know and for
                // a key whose node no longer carries a room. Neither will ever pair
                // again, and the remedy the user has is the same for both.
                HttpStatusCode.NotFound -> OlcrtcNodeStatus.KeyGone
                else -> OlcrtcNodeStatus.Unavailable
            }
        } catch (_: Throwable) {
            OlcrtcNodeStatus.Unavailable
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://proofkit.org"
        const val PATH = "/api/v1/olcrtc/status"
    }
}
