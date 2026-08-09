package org.olcbox.app.ui.features.home

import org.olcbox.app.ui.features.home.components.subscriptionAge
import org.olcbox.app.ui.features.home.components.subscriptionMetaLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubscriptionMetaLineTest {
    private val now = 1_775_000_000_000L
    private val date: (Long) -> String = { "09.09.2026" }

    @Test
    fun quotaExpiryAndAgeInOneLine() {
        assertEquals(
            "6.3 GB / 300 GB · exp 09.09.2026 · upd 2h",
            subscriptionMetaLine(
                quota = "6.3 GB / 300 GB",
                expiresAtEpochMs = 1_788_000_000_000L,
                lastRefreshAtEpochMs = now - 2 * 60 * 60 * 1000L,
                nowEpochMs = now,
                formatDate = date
            )
        )
    }

    @Test
    fun aProviderThatSaidNothingGetsNoLineAtAll() {
        assertNull(
            subscriptionMetaLine(
                quota = null,
                expiresAtEpochMs = null,
                lastRefreshAtEpochMs = null,
                nowEpochMs = now,
                formatDate = date
            )
        )
    }

    @Test
    fun eachPartIsOptional() {
        assertEquals(
            "upd 3d",
            subscriptionMetaLine(
                quota = null,
                expiresAtEpochMs = null,
                lastRefreshAtEpochMs = now - 3 * 24 * 60 * 60 * 1000L,
                nowEpochMs = now,
                formatDate = date
            )
        )
    }

    @Test
    fun ageIsCompactAndNeverNegative() {
        assertEquals("now", subscriptionAge(now, now))
        // A device whose clock moved backwards reads as "now", not as a future age.
        assertEquals("now", subscriptionAge(now + 5_000L, now))
        assertEquals("12m", subscriptionAge(now - 12 * 60 * 1000L, now))
        assertEquals("2h", subscriptionAge(now - 2 * 60 * 60 * 1000L, now))
        assertEquals("3d", subscriptionAge(now - 3 * 24 * 60 * 60 * 1000L, now))
    }
}
