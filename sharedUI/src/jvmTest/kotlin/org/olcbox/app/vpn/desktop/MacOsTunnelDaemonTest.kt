package org.olcbox.app.vpn.desktop

import org.olcbox.app.vpn.desktop.MacOsTunnelDaemon.Registration
import kotlin.test.Test
import kotlin.test.assertEquals

class MacOsTunnelDaemonTest {

    /**
     * The Swift side returns integers and this side names them. Nothing checks
     * that the two agree at compile time, so the numbers are pinned here: change
     * one in `OlcboxTunnelDaemon.swift` without changing the other and the app
     * reads "installed" off a status that means something else.
     */
    @Test
    fun registrationCodesMatchTheNativeContract() {
        assertEquals(Registration.NotRegistered, Registration.from(0))
        assertEquals(Registration.RequiresApproval, Registration.from(1))
        assertEquals(Registration.Enabled, Registration.from(2))
        assertEquals(Registration.NotFound, Registration.from(3))
        assertEquals(Registration.Unsupported, Registration.from(-1))
    }

    @Test
    fun anUnknownCodeIsNeverEnabled() {
        assertEquals(Registration.NotRegistered, Registration.from(99))
    }
}
