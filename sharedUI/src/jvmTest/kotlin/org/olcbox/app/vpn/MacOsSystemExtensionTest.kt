package org.olcbox.app.vpn

import org.olcbox.app.vpn.desktop.MacOsSystemExtension
import org.olcbox.app.vpn.desktop.MacOsSystemExtension.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MacOsSystemExtensionTest {

    /**
     * The Swift side returns integers and this side names them. Nothing checks
     * that the two agree at compile time, so the numbers are pinned here: change
     * one in `OlcboxSystemExtension.swift` without changing the other and the app
     * reads "activated" off a status that means something else.
     */
    @Test
    fun statusCodesMatchTheNativeContract() {
        assertEquals(Status.Idle, Status.from(0))
        assertEquals(Status.Requested, Status.from(1))
        assertEquals(Status.NeedsUserApproval, Status.from(2))
        assertEquals(Status.Activated, Status.from(3))
        assertEquals(Status.Failed, Status.from(4))
        assertEquals(Status.NotInApplications, Status.from(5))
    }

    /**
     * A code this build does not know about must not read as a running tunnel.
     * The only safe direction to round an unknown status is down.
     */
    @Test
    fun anUnknownStatusIsNotSuccess() {
        assertEquals(Status.Failed, Status.from(99))
        assertEquals(Status.Failed, Status.from(-7))
    }

    /**
     * macOS refuses a system extension whose identifier is not a child of the
     * requesting app's, and says so in a way that names neither. Keeping the two
     * strings side by side is cheap; discovering the rule from an opaque failure
     * is not.
     */
    @Test
    fun theExtensionIdentifierIsAChildOfTheApps() {
        assertTrue(
            MacOsSystemExtension.BUNDLE_ID.startsWith("${MacOsSystemExtension.BUNDLE_ID_PREFIX}."),
            "extension id ${MacOsSystemExtension.BUNDLE_ID} must be prefixed by the app's " +
                MacOsSystemExtension.BUNDLE_ID_PREFIX
        )
    }

    /**
     * These tests run on Linux CI, where there is no bridge to load. Absent must
     * be an ordinary answer rather than an exception, or every non-macOS desktop
     * build fails on a library it was never going to have.
     */
    @Test
    fun anAbsentBridgeReportsUnavailableInsteadOfThrowing() {
        if (System.getProperty("os.name").orEmpty().contains("Mac")) return
        assertEquals(Status.Unavailable, MacOsSystemExtension.status())
        assertEquals(Status.Unavailable, MacOsSystemExtension.activate())
        assertTrue(MacOsSystemExtension.message().isNotBlank())
    }
}
