package org.olcbox.app.ui.components.kit

import org.olcbox.app.AppInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class PkVersionLineTest {
    @Test
    fun formatsBrandVersionAndCore() {
        assertEquals(
            "PROOFKIT · v1.0.209 · OLCBOX CORE",
            pkVersionLine(AppInfo(name = "olcbox", version = "1.0.209"))
        )
    }

    @Test
    fun stripsLeadingVIfAlreadyPresent() {
        assertEquals(
            "PROOFKIT · v2.0.0 · OLCBOX CORE",
            pkVersionLine(AppInfo(name = "olcbox", version = "v2.0.0"))
        )
    }

    @Test
    fun masksTheSubscriptionToken() {
        assertEquals(
            "https://proofkit.org/sub/c0e79f…17e95",
            pkMaskSubscriptionUrl(
                "https://proofkit.org/sub/c0e79fc61f942fe0e7b2032fd273967f4d3b807d1ba11dfd8f9a8d17ad817e95"
            )
        )
    }

    @Test
    fun maskingKeepsShortPathsReadable() {
        // nothing secret to hide, and truncating would only make it harder to read
        assertEquals("https://example.com/sub", pkMaskSubscriptionUrl("https://example.com/sub"))
    }

    @Test
    fun maskingHidesQueryParameters() {
        assertEquals(
            "https://proofkit.org/sub/c0e79f…17e95?…",
            pkMaskSubscriptionUrl(
                "https://proofkit.org/sub/c0e79fc61f942fe0e7b2032fd273967f4d3b807d1ba11dfd8f9a8d17ad817e95?crypt=1"
            )
        )
    }
}
