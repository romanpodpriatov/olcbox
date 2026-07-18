package org.olcbox.app.crypt

import org.olcbox.app.GeneratedAppInfo

/**
 * crypt1 decrypt-only codec. Wire format matches the coordinator's crypt_link
 * service exactly (see docs/superpowers/plans/2026-07-18-olcbox-hidden-settings-crypt-links.md).
 * Detection is marker-only, so plain links/bodies are never mis-decrypted.
 */
class CryptCodec(masterKey: ByteArray) {
    private val encKey: ByteArray
    private val macKey: ByteArray

    init {
        require(masterKey.size == 32) { "crypt1 master key must be 32 bytes" }
        encKey = PlatformCrypto.sha256(ENC_LABEL + masterKey)
        macKey = PlatformCrypto.sha256(MAC_LABEL + masterKey)
    }

    /** `olcrtc://crypt1/<blob>` → decrypted payload (a URL or olcrtc lines), else null. */
    fun decryptLink(text: String): String? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(LINK_PREFIX)) return null // marker-only
        return decryptBlob(trimmed.removePrefix(LINK_PREFIX))?.decodeToString()
    }

    /** A bare crypt1 blob (encrypted `/sub` body) → plaintext, else null. */
    fun decryptBody(text: String): String? = decryptBlob(text.trim())?.decodeToString()

    private fun decryptBlob(blob: String): ByteArray? {
        val raw = Base64Url.decode(blob) ?: return null
        if (raw.size < 16 + 32) return null
        val iv = raw.copyOfRange(0, 16)
        val tag = raw.copyOfRange(raw.size - 32, raw.size)
        val ct = raw.copyOfRange(16, raw.size - 32)
        val expected = PlatformCrypto.hmacSha256(macKey, iv + ct)
        if (!constantTimeEquals(expected, tag)) return null
        return PlatformCrypto.aesCbcDecrypt(encKey, iv, ct)
    }

    companion object {
        private const val LINK_PREFIX = "olcrtc://crypt1/"
        private val ENC_LABEL = "olcrtc-crypt-v1-enc".encodeToByteArray()
        private val MAC_LABEL = "olcrtc-crypt-v1-mac".encodeToByteArray()

        /** Built from the baked key; null when unset (crypt disabled in this build). */
        fun default(): CryptCodec? {
            val key = decodeMaster(GeneratedAppInfo.CRYPT_KEY_V1) ?: return null
            return CryptCodec(key)
        }

        /** Accept standard or url-safe base64, padded or not; must decode to 32 bytes. */
        fun decodeMaster(b64: String): ByteArray? {
            if (b64.isBlank()) return null
            val urlsafe = b64.trim().replace('+', '-').replace('/', '_')
            val bytes = Base64Url.decode(urlsafe) ?: return null
            return if (bytes.size == 32) bytes else null
        }
    }
}
