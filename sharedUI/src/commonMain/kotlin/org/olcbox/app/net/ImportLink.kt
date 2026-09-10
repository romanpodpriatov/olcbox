package org.olcbox.app.net

/**
 * The one-tap import link a panel or a bot hands to a person.
 *
 * Two spellings of one thing. `proofkit://add?url=…` opens the app directly
 * wherever the scheme is registered. `https://proofkit.org/add#…` is what a
 * Telegram button can carry: a phone with the app opens it in the app, a
 * phone without lands on a page with the downloads. The payload rides the
 * fragment there on purpose — a fragment never leaves the browser, so the
 * server list, which is a credential, is in nobody's access log.
 *
 * The payload is whatever a paste accepts: a list URL, an `olcrtc://crypt1/…`
 * link of ours, a partner's `happ://crypt5/…`.
 */
object ImportLink {
    const val SCHEME = "proofkit"
    const val HOST = "add"
    const val WEB_ORIGIN = "https://proofkit.org"
    const val WEB_PATH = "/add"

    fun schemeLink(payload: String): String = "$SCHEME://$HOST?url=${encode(payload)}"

    fun webLink(payload: String): String = "$WEB_ORIGIN$WEB_PATH#${encode(payload)}"

    /** The payload of an import link, or null for anything that is not one. */
    fun payloadOf(uri: String): String? {
        val text = uri.trim()
        val lower = text.lowercase()
        val prefixes = listOf("$SCHEME://$HOST", "$WEB_ORIGIN$WEB_PATH", "https://www.proofkit.org$WEB_PATH")
        val prefix = prefixes.firstOrNull { lower.startsWith(it) } ?: return null
        var rest = text.substring(prefix.length)
        if (rest.startsWith("/")) rest = rest.substring(1)
        val encoded = when {
            rest.isEmpty() -> return null
            rest.startsWith("#") -> rest.substring(1)
            rest.startsWith("?") -> queryValue(rest.substring(1), "url") ?: return null
            else -> return null
        }
        return decode(encoded).trim().takeIf { it.isNotEmpty() }
    }

    private fun queryValue(query: String, key: String): String? =
        query.substringBefore('#').split('&').firstNotNullOfOrNull { pair ->
            if (pair.substringBefore('=') == key) pair.substringAfter('=', "") else null
        }

    private fun encode(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (c < 128 && (ch.isLetterOrDigit() || ch in "-._~")) append(ch)
            else append('%').append(HEX[c shr 4]).append(HEX[c and 0xf])
        }
    }

    /**
     * Percent-decoding that leaves a stray `%` alone: a list URL pasted raw
     * into the fragment is still a list URL, not a decoding error.
     */
    private fun decode(s: String): String {
        val out = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '%' && i + 2 < s.length) {
                val hi = s.getOrNull(i + 1)?.digitToIntOrNull(16)
                val lo = s.getOrNull(i + 2)?.digitToIntOrNull(16)
                if (hi != null && lo != null) {
                    out.add(((hi shl 4) or lo).toByte())
                    i += 3
                    continue
                }
            }
            for (b in ch.toString().encodeToByteArray()) out.add(b)
            i++
        }
        return out.toByteArray().decodeToString()
    }

    private const val HEX = "0123456789ABCDEF"
}
