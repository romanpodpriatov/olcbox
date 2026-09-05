package org.olcbox.app.ui.features.home

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.vpn.VpnStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class HomeScreenStateTest {

    private val idle = HomeScreenState(
        isVpnConnected = false,
        isVpnLoading = false,
        selectedLocation = null,
        configData = LocationConfig(),
        shouldShowConfigInvalidReminder = false,
        canStartVpn = true,
        startBlockedReason = null
    )

    /** The screen after one connect has failed and the platform said why. */
    private val failed = idle.applying(VpnStatus.Error("carrier auth failed"))

    // ── what an error does ────────────────────────────────────────────────

    @Test
    fun anErrorIsTheNoticeOnScreen() {
        assertEquals("carrier auth failed", failed.notice())
        assertFalse(failed.isVpnLoading)
        assertFalse(failed.isVpnConnected)
    }

    // ── what clears it ────────────────────────────────────────────────────
    //
    // The banner used to outlive everything but a relaunch: a stop went
    // Stopping → Disconnected and neither touched it, so a user who read the
    // message, pressed stop, and moved on kept a red box about a connection
    // that no longer existed.

    @Test
    fun stoppingClearsTheLastFailure() {
        assertNull(failed.applying(VpnStatus.Stopping).failure)
    }

    @Test
    fun disconnectingClearsTheLastFailure() {
        assertNull(failed.applying(VpnStatus.Disconnected).failure)
    }

    @Test
    fun connectingAgainClearsTheLastFailure() {
        assertNull(failed.applying(VpnStatus.Connecting).failure)
    }

    @Test
    fun connectingSuccessfullyClearsTheLastFailure() {
        assertNull(failed.applying(VpnStatus.Connected).failure)
    }

    // ── what keeps it ─────────────────────────────────────────────────────

    @Test
    fun aReconnectAttemptKeepsTheFailureItIsRetryingFrom() {
        assertEquals("carrier auth failed", failed.applying(VpnStatus.Reconnecting).failure)
    }
}
