package org.olcbox.app.crypt

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
object Base64Url {
    // Tolerate absent padding (server emits no-pad).
    private val codec = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

    fun decode(s: String): ByteArray? = runCatching { codec.decode(s.trim()) }.getOrNull()
    fun encode(b: ByteArray): String = codec.encode(b)
}

private val HEX = "0123456789abcdef".toCharArray()

fun hex(b: ByteArray): String {
    val sb = StringBuilder(b.size * 2)
    for (x in b) {
        sb.append(HEX[(x.toInt() ushr 4) and 0xF])
        sb.append(HEX[x.toInt() and 0xF])
    }
    return sb.toString()
}

fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}

fun constantTimeEquals(a: String, b: String): Boolean =
    constantTimeEquals(a.encodeToByteArray(), b.encodeToByteArray())
