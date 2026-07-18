package org.olcbox.app.admin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.olcbox.app.GeneratedAppInfo

/**
 * Process-lifetime admin unlock state. Session-only (no persistence in v1).
 * 7 taps on the app title within TAP_WINDOW_MS opens the password dialog.
 */
object AdminState {
    private const val TAPS_REQUIRED = 7
    private const val TAP_WINDOW_MS = 3_000L

    private var gate: AdminGate = AdminGate(GeneratedAppInfo.ADMIN_PASS_SHA256)
    private var tapCount = 0
    private var firstTapMs = 0L

    var unlocked by mutableStateOf(false)
        private set

    val gateEnabled: Boolean get() = gate.enabled

    /**
     * Whether the full configurator UI (settings/location/logs/custom-location)
     * is visible. Fail-safe: when NO admin hash is baked, the gate is off and the
     * app behaves like a normal olcbox (everything visible). Only a build WITH a
     * hash hides the UI until [unlocked]. This guarantees a build that ships
     * without the secret never traps users in a hidden-settings state.
     */
    val configuratorVisible: Boolean get() = !gate.enabled || unlocked

    /** Show the Lock affordance only in a gated build that is currently unlocked. */
    val showLock: Boolean get() = gate.enabled && unlocked

    /** Returns true when the tap threshold is reached (caller shows the dialog). */
    fun registerTitleTap(nowMs: Long): Boolean {
        if (!gate.enabled) return false
        if (nowMs - firstTapMs > TAP_WINDOW_MS) {
            firstTapMs = nowMs
            tapCount = 0
        }
        tapCount++
        if (tapCount >= TAPS_REQUIRED) {
            tapCount = 0
            firstTapMs = 0L
            return true
        }
        return false
    }

    fun tryUnlock(password: String): Boolean {
        val ok = gate.verify(password)
        if (ok) unlocked = true
        return ok
    }

    fun lock() {
        unlocked = false
    }

    // Test seam: override the baked gate with a known hash.
    internal fun overrideGateForTest(hashHex: String) {
        gate = AdminGate(hashHex)
        tapCount = 0
        firstTapMs = 0L
        unlocked = false
    }
}
