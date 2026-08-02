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
     * Whether the configurator affordances that are merely advanced — today the
     * SOCKS5 proxy row — are visible. Fail-safe: when NO admin hash is baked, the
     * gate is off and the app behaves like a normal olcbox (visible). Only a build
     * WITH a hash hides them until [unlocked], so a build shipped without the
     * secret never traps users in a hidden-settings state.
     *
     * Note what this does NOT cover: the per-location editor and "create custom
     * location" answer to [plumbingVisible], which fails the other way.
     */
    val configuratorVisible: Boolean get() = !gate.enabled || unlocked

    /**
     * Affordances that are plumbing rather than product: the per-location
     * configurator and "create custom location".
     *
     * Stricter than [configuratorVisible] on purpose. That one fails *open* so a
     * build shipped without the secret cannot trap anyone in a hidden-settings
     * state — reasonable for settings and logs, wrong for these two. They expose
     * a room key, a provider, an operator's edge address, its SNI and its
     * certificate pin, and they offer to hand-build a location out of them.
     * Nobody's customer needs that on the screen where they pick an exit, and
     * forgetting to bake the hash should not be what puts it there.
     *
     * So: only in a build that has a gate, and only once it is open.
     */
    val plumbingVisible: Boolean get() = gate.enabled && unlocked

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
