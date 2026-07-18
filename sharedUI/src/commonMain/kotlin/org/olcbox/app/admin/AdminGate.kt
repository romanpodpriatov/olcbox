package org.olcbox.app.admin

import org.olcbox.app.crypt.PlatformCrypto
import org.olcbox.app.crypt.constantTimeEquals
import org.olcbox.app.crypt.hex

/** Verifies an admin password against a baked SHA256 hash (hex, lowercase). */
class AdminGate(private val expectedHashHex: String) {
    val enabled: Boolean get() = expectedHashHex.isNotBlank()

    fun verify(password: String): Boolean {
        if (!enabled) return false
        val actual = hex(PlatformCrypto.sha256(password.encodeToByteArray()))
        return constantTimeEquals(actual, expectedHashHex.trim().lowercase())
    }
}
