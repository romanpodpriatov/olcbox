package org.olcbox.app.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OlcrtcStatusTest {
    /**
     * Vectors produced by the coordinator's own database
     * (`left(encode(sha256(decode(key,'hex')),'hex'),16)`), which is also what the
     * forked srv and the agent's stats poller compute. Four implementations of one
     * derivation: if this app's drifts, every status lookup 404s and the list shows no
     * occupancy at all, with nothing anywhere to say why.
     */
    @Test fun keyIdMatchesTheCoordinatorsDerivation() {
        assertEquals("9a2db2e23f1504cd", keyIdFor("ab".repeat(32)))
        assertEquals("66687aadf862bd77", keyIdFor("00".repeat(32)))
        assertEquals(
            "c860af649ca467ce",
            keyIdFor("0123456789abcdef" + "11".repeat(24))
        )
    }

    @Test fun keyIdAcceptsUppercaseAndSurroundingSpace() {
        assertEquals("9a2db2e23f1504cd", keyIdFor("  " + "AB".repeat(32) + "  "))
    }

    @Test fun aMalformedKeyYieldsNoHandleRatherThanThrowing() {
        // A broken location should render without occupancy, not take the list down.
        assertNull(keyIdFor(""))
        assertNull(keyIdFor("ab".repeat(31)), "too short")
        assertNull(keyIdFor("ab".repeat(33)), "too long")
        assertNull(keyIdFor("zz".repeat(32)), "not hex")
    }

    @Test fun usedCountsOccupiedSlots() {
        assertEquals(3, OlcrtcSlots(slots_total = 8, slots_free = 5).used)
        assertEquals(8, OlcrtcSlots(slots_total = 8, slots_free = 0).used)
    }

    @Test fun usedNeverGoesNegativeWhenCapacityWasLowered() {
        // An operator draining a node sets capacity below its occupancy on purpose.
        assertEquals(0, OlcrtcSlots(slots_total = 4, slots_free = 9).used)
    }

    @Test fun aFullNodeIsBlockedUnlessYouAreAlreadyOnIt() {
        assertTrue(OlcrtcSlots(slots_total = 8, slots_free = 0, holds_slot = false).isBlocked)
        assertFalse(
            OlcrtcSlots(slots_total = 8, slots_free = 0, holds_slot = true).isBlocked,
            "greying out the server somebody is connected to is worse than showing it full"
        )
        assertFalse(OlcrtcSlots(slots_total = 8, slots_free = 1).isBlocked)
    }

    /**
     * The bug this exists for: the app's shared HttpClient installs `HttpTimeout` and
     * nothing else — no `ContentNegotiation` — so `body<T>()` threw
     * `NoTransformationFoundException` on every call, the catch-all swallowed it, and
     * occupancy was silently absent everywhere. The client here is deliberately bare for
     * exactly that reason: a test that installs a JSON negotiator would pass while the
     * app shipped broken.
     */
    @Test fun slotsParseThroughAClientThatCannotNegotiateJson() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/v1/olcrtc/status", request.url.encodedPath)
            assertEquals("9a2db2e23f1504cd", request.url.parameters["key_id"])
            respond(
                content = """{"slots_total":8,"slots_free":3,"holds_slot":true}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val slots = OlcrtcStatusClient(HttpClient(engine)).slotsFor("ab".repeat(32))

        assertEquals(OlcrtcSlots(slots_total = 8, slots_free = 3, holds_slot = true), slots)
    }

    @Test fun anUnknownKeyYieldsNoOccupancyRatherThanAnError() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":{"code":"NOT_FOUND","message":"Unknown or revoked key"}}""",
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        assertNull(OlcrtcStatusClient(HttpClient(engine)).slotsFor("ab".repeat(32)))
    }

    @Test fun aMalformedKeyNeverReachesTheNetwork() = runTest {
        val engine = MockEngine { error("the client must not call out for a key it cannot hash") }
        assertNull(OlcrtcStatusClient(HttpClient(engine)).slotsFor("not-a-key"))
    }

    // ── the three answers ──────────────────────────────────────────────────
    //
    // Collapsing all of these into null is what let a batch of revoked keys read
    // as a broken app: the board lost its seats, the tunnel refused to pair, and
    // nothing on screen said why.

    @Test fun aRevokedKeyIsAnAnswer() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":{"code":"NOT_FOUND","message":"Unknown or revoked key"}}""",
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        assertEquals(
            OlcrtcNodeStatus.KeyGone,
            OlcrtcStatusClient(HttpClient(engine)).statusFor("ab".repeat(32))
        )
    }

    @Test fun aCoordinatorWeCannotReachSaysNothingAboutTheKey() = runTest {
        val engine = MockEngine { respond(content = "", status = HttpStatusCode.BadGateway) }
        assertEquals(
            OlcrtcNodeStatus.Unavailable,
            OlcrtcStatusClient(HttpClient(engine)).statusFor("ab".repeat(32))
        )
    }

    @Test fun aKeyTooMalformedToAskAboutIsNotARevokedOne() = runTest {
        val engine = MockEngine { error("the client must not call out for a key it cannot hash") }
        assertEquals(
            OlcrtcNodeStatus.Unavailable,
            OlcrtcStatusClient(HttpClient(engine)).statusFor("not-a-key")
        )
    }

    @Test fun occupancyStillComesBackAsANumber() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"slots_total":8,"slots_free":3,"holds_slot":true}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        assertEquals(
            OlcrtcNodeStatus.Occupancy(OlcrtcSlots(slots_total = 8, slots_free = 3, holds_slot = true)),
            OlcrtcStatusClient(HttpClient(engine)).statusFor("ab".repeat(32))
        )
    }
}
