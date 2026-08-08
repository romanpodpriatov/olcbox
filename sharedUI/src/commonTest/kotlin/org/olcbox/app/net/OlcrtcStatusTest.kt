package org.olcbox.app.net

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
}
