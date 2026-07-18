package org.olcbox.app.admin

import org.olcbox.app.crypt.PlatformCrypto
import org.olcbox.app.crypt.hex
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminGateTest {
    private fun sha256Hex(s: String) = hex(PlatformCrypto.sha256(s.encodeToByteArray()))

    @Test
    fun verifiesCorrectPassword() {
        val gate = AdminGate(sha256Hex("hunter2-long-passphrase"))
        assertTrue(gate.verify("hunter2-long-passphrase"))
        assertFalse(gate.verify("wrong"))
    }

    @Test
    fun disabledWhenHashBlank() {
        val gate = AdminGate("")
        assertFalse(gate.enabled)
        assertFalse(gate.verify("anything"))
    }

    @Test
    fun sevenTapsWithinWindowTriggers() {
        AdminState.overrideGateForTest(sha256Hex("pw"))
        var triggered = false
        repeat(7) { i -> triggered = AdminState.registerTitleTap(1_000L + i) }
        assertTrue(triggered)
    }

    @Test
    fun tapsResetAfterWindow() {
        AdminState.overrideGateForTest(sha256Hex("pw"))
        repeat(6) { AdminState.registerTitleTap(0L) }
        assertFalse(AdminState.registerTitleTap(10_000L)) // window elapsed → counts as tap 1
    }

    @Test
    fun unlockAndLock() {
        AdminState.overrideGateForTest(sha256Hex("pw"))
        assertTrue(AdminState.tryUnlock("pw"))
        assertTrue(AdminState.unlocked)
        AdminState.lock()
        assertFalse(AdminState.unlocked)
    }

    @Test
    fun tapsInertWhenGateDisabled() {
        AdminState.overrideGateForTest("")
        assertFalse(AdminState.registerTitleTap(0L))
    }
}
