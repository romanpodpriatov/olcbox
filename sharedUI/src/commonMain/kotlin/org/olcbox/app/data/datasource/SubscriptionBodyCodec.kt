package org.olcbox.app.data.datasource

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Standard subscription bodies (Happ / v2rayNG style) are base64 of the
 * scheme-line list, sometimes wrapped/newlined and often without padding.
 * [decodeBase64] unwraps that outer layer; it returns null unless the result
 * actually looks like a link list, so plaintext/JSON imports are never mangled.
 */
@OptIn(ExperimentalEncodingApi::class)
internal object SubscriptionBodyCodec {
    private val codecs = listOf(
        Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL),
        Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
    )

    fun decodeBase64(text: String): String? {
        val compact = text.filterNot { it.isWhitespace() }
        if (compact.length < 16) return null
        // Cheap alphabet gate so arbitrary prose/JSON is never even attempted.
        if (!compact.all { it.isLetterOrDigit() || it in "+/-_=" }) return null
        for (codec in codecs) {
            val decoded = runCatching {
                codec.decode(compact).decodeToString()
            }.getOrNull() ?: continue
            // Accept only when the payload plainly carries scheme links.
            if (decoded.contains("://")) return decoded
        }
        return null
    }
}
