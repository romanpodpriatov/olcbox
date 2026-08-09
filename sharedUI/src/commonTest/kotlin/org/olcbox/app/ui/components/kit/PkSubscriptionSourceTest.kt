package org.olcbox.app.ui.components.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PkSubscriptionSourceTest {
    private val ourUrl = "https://proofkit.org/sub/c0e79fc61f942fe0e7b2032fd273967f4d3b807d/olcrtc?crypt=1"
    private val partnerUrl = "https://sub.reviewassistant.org/sub/j7k9e/54vnskyn4srw2y1j?x=1"

    @Test
    fun anEncryptedSubscriptionNamesNeitherHostNorPath() {
        assertEquals(
            "Encrypted link",
            pkSubscriptionSourceLine(partnerUrl, originLink = "happ://crypt5/fzvdQQSl2kKPyNPhAeRV4WSh12xLFV8")
        )
    }

    @Test
    fun ourOwnCryptSubscriptionsAreRecognisedWithoutAnOriginLink() {
        // Installed before the origin link existed: ?crypt=1 is ours by definition.
        assertEquals("Encrypted link", pkSubscriptionSourceLine(ourUrl, originLink = null))
    }

    @Test
    fun aPlainSubscriptionShowsOnlyItsHost() {
        assertEquals("sub.reviewassistant.org", pkSubscriptionSourceLine(partnerUrl.substringBefore('?')))
    }

    @Test
    fun theAdminGateRevealsTheMaskedUrl() {
        assertEquals(
            "https://proofkit.org/sub/c0e79f…b807d/olcrtc?…",
            pkSubscriptionSourceLine(ourUrl, originLink = null, revealed = true)
        )
    }

    @Test
    fun somethingUnparseableFallsBackToTheMaskRatherThanAnEmptyRow() {
        assertEquals("not a url", pkSubscriptionSourceLine("not a url"))
    }

    @Test
    fun hostParsing() {
        assertEquals("proofkit.org", pkSubscriptionHost("https://proofkit.org/sub/x"))
        assertEquals("proofkit.org", pkSubscriptionHost("https://proofkit.org:8443/sub/x"))
        assertNull(pkSubscriptionHost("garbage"))
    }

    @Test
    fun secrecyIsMarkerOnly() {
        assertTrue(pkSubscriptionIsSecret("https://x.test/sub/y", "olcrtc://crypt1/blob"))
        assertTrue(pkSubscriptionIsSecret("https://x.test/sub/y?crypt=1", null))
        assertTrue(!pkSubscriptionIsSecret("https://x.test/sub/y", null))
        assertTrue(!pkSubscriptionIsSecret("https://x.test/sub/y", "   "))
    }
}
