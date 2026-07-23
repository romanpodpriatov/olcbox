package org.olcbox.app.net

object LinkParser {
    fun parse(line: String): OutboundSpec? {
        val t = line.trim()
        return when {
            t.startsWith("vless://") -> parseVless(t)
            t.startsWith("hysteria2://") -> parseHy2(t, "hysteria2://")
            t.startsWith("hy2://") -> parseHy2(t, "hy2://")
            else -> null
        }
    }

    private data class Parts(
        val userinfo: String,
        val host: String,
        val port: Int,
        val query: Map<String, String>,
        val tag: String,
    )

    private fun splitLink(s: String, scheme: String): Parts? {
        val body = s.removePrefix(scheme)
        val hashIdx = body.indexOf('#')
        val tag = if (hashIdx >= 0) urlDecode(body.substring(hashIdx + 1)) else ""
        val beforeHash = if (hashIdx >= 0) body.substring(0, hashIdx) else body
        val qIdx = beforeHash.indexOf('?')
        val authority = if (qIdx >= 0) beforeHash.substring(0, qIdx) else beforeHash
        val query = if (qIdx >= 0) parseQuery(beforeHash.substring(qIdx + 1)) else emptyMap()
        val atIdx = authority.lastIndexOf('@')
        if (atIdx < 0) return null
        val userinfo = authority.substring(0, atIdx)
        val hostPort = authority.substring(atIdx + 1)
        val colon = hostPort.lastIndexOf(':')
        if (colon <= 0) return null
        val host = hostPort.substring(0, colon)
        val port = hostPort.substring(colon + 1).toIntOrNull() ?: return null
        if (host.isBlank()) return null
        return Parts(userinfo, host, port, query, tag)
    }

    private fun parseVless(s: String): OutboundSpec.Vless? {
        val p = splitLink(s, "vless://") ?: return null
        val type = p.query["type"] ?: "tcp"
        val transport = if (type == "xhttp") {
            TransportSpec.Xhttp(
                path = urlDecode(p.query["path"] ?: "/"),
                host = p.query["host"] ?: p.query["sni"].orEmpty(),
                mode = p.query["mode"] ?: "auto",
            )
        } else {
            TransportSpec.Tcp
        }
        return OutboundSpec.Vless(
            uuid = p.userinfo,
            host = p.host,
            port = p.port,
            sni = p.query["sni"].orEmpty(),
            publicKey = p.query["pbk"].orEmpty(),
            shortId = p.query["sid"].orEmpty(),
            fingerprint = p.query["fp"] ?: "chrome",
            flow = if (transport is TransportSpec.Xhttp) null else p.query["flow"]?.takeIf { it.isNotBlank() },
            transport = transport,
            tag = p.tag.ifBlank { p.host },
        )
    }

    private fun parseHy2(s: String, scheme: String): OutboundSpec.Hysteria2? {
        val p = splitLink(s, scheme) ?: return null
        return OutboundSpec.Hysteria2(
            password = p.userinfo,
            host = p.host,
            port = p.port,
            sni = p.query["sni"].orEmpty(),
            obfsPassword = p.query["obfs-password"]?.takeIf { it.isNotBlank() },
            insecure = p.query["insecure"] == "1" || p.query["insecure"] == "true",
            tag = p.tag.ifBlank { p.host },
        )
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split('&').mapNotNull {
            val eq = it.indexOf('=')
            if (eq < 0) {
                null
            } else {
                urlDecode(it.substring(0, eq)) to urlDecode(it.substring(eq + 1))
            }
        }.toMap()

    private fun urlDecode(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '%' && i + 2 < s.length -> {
                    val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        sb.append(hex.toChar()); i += 3
                    } else {
                        sb.append(c); i++
                    }
                }
                c == '+' -> { sb.append(' '); i++ }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }
}
