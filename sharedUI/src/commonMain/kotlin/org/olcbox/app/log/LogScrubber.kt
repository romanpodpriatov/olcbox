package org.olcbox.app.log

import kotlin.random.Random

/**
 * Rewrites a log line so it still says what happened without saying where.
 *
 * The exported log is what makes a support request answerable, so this removes
 * values rather than lines: an address becomes a tag, and the error, the port and the
 * timing around it survive. Two different hosts get two different tags, because "the
 * failover tried three and all refused" is a different fault from "one host refused
 * three times".
 *
 * The salt is per process on purpose. A short hash of an IPv4 address is otherwise a
 * confirmation oracle — an adversary who suspects an address computes its tag and
 * checks. Salted, a tag means something inside one log and nowhere else, which is all
 * diagnosis asks of it. We cannot map a tag back to a node either; the location label
 * in the same line is what support actually uses.
 *
 * Call it from `addLog` and nowhere earlier: transport state is decided by matching
 * raw engine text, and a scrubbed line must never reach those parsers.
 */
class LogScrubber(private val salt: Long) {

    fun scrub(line: String): String {
        var out = line
        // Links first: a crypt blob is base64 and could otherwise be picked apart by
        // the narrower rules below.
        out = CRYPT_LINK.replace(out, "<link>")
        out = UUID.replace(out, "<id>")
        out = OUR_HOST.replace(out, "<host>")
        out = IPV6.replace(out) { tagIfPublicV6(it.value) }
        out = IPV4.replace(out) { tagIfPublicV4(it.value) }
        return out
    }

    /**
     * FNV-1a over the salted value, shown as four hex digits. Not a cryptographic
     * hash and does not need to be — the salt does the hiding, this only spreads
     * values across buckets so two hosts rarely read alike. Hand-rolled because
     * commonMain takes no dependencies.
     */
    private fun tag(value: String): String {
        var h = FNV_OFFSET xor salt
        for (c in value) {
            h = h xor c.code.toLong()
            h *= FNV_PRIME
        }
        val short = ((h ushr 16) and 0xFFFFL).toString(16).padStart(4, '0')
        return "node#$short"
    }

    private fun tagIfPublicV4(addr: String): String {
        val o = addr.split('.').mapNotNull { it.toIntOrNull() }
        // Not an address at all — a version, a build number. Leave the text alone;
        // mangling ordinary words is a worse failure than leaving one address in.
        if (o.size != 4 || o.any { it > 255 }) return addr
        val local = when {
            o[0] == 127 || o[0] == 10 || o[0] == 0 -> true
            o[0] == 172 && o[1] in 16..31 -> true
            o[0] == 192 && o[1] == 168 -> true
            o[0] == 169 && o[1] == 254 -> true
            o[0] == 100 && o[1] in 64..127 -> true          // CGNAT
            o.all { it == 255 } -> true
            else -> false
        }
        return if (local) addr else tag(addr)
    }

    private fun tagIfPublicV6(addr: String): String {
        val a = addr.lowercase()
        // ULA (fc00::/7) covers the desktop TUN address; fe80::/10 is link-local.
        val local = a == "::1" || a == "::" ||
            a.startsWith("fc") || a.startsWith("fd") ||
            a.startsWith("fe8") || a.startsWith("fe9") ||
            a.startsWith("fea") || a.startsWith("feb")
        return if (local) addr else tag(addr)
    }

    companion object {
        /**
         * One salt for the life of the process, which is the unit a log file covers.
         */
        val default: LogScrubber by lazy { LogScrubber(Random.nextLong()) }

        private const val FNV_OFFSET = -3750763034362895579L   // 0xcbf29ce484222325
        private const val FNV_PRIME = 1099511628211L           // 0x100000001b3

        private val CRYPT_LINK = Regex("""\b(?:olcrtc|happ)://crypt\d/\S+""")

        private val UUID = Regex(
            """\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-""" +
                """[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b"""
        )

        private val OUR_HOST = Regex("""\b(?:[a-zA-Z0-9-]+\.)*proofkit\.org\b""")

        private val IPV4 = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""")

        // Two shapes cover everything that turns up in a log: any compressed address
        // (which always contains "::") and a full one (four colon-separated groups or
        // more). Deliberately not the RFC 4291 grammar — the property that matters is
        // that a timestamp like 15:28:17 must never match, and the tests pin it.
        private val IPV6 = Regex(
            """[0-9a-fA-F]{0,4}::[0-9a-fA-F:]{0,39}""" +
                """|(?:[0-9a-fA-F]{1,4}:){4,7}[0-9a-fA-F]{1,4}"""
        )
    }
}
