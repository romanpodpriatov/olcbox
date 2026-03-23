package org.turnbox.app.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

@Serializable
data class HysteriaConfig(
    val server: String = "",
    val password: String = "",
    val sni: String = "",
    val turnEnabled: Boolean = false,
    val turnPeer: String = "",
    val turnLink: String = "",
    val turnThreads: Int = 8,
    val turnUdp: Boolean = true,
    val turnNoDtls: Boolean = false,
    val turnListen: String = "127.0.0.1:9000",
    val insecure: Boolean = true
) {
    
    fun getFullConfig(): String {
        val effectiveServer = if (turnEnabled) turnListen else server
        val mapper = mapOf(SERVER_ADDRESS_PLACEHOLDER to effectiveServer, PASSWORD_PLACEHOLDER to password)
        var resultingConf = HYSTERIA_CONFIG_TEXT_DATA
        for (m in mapper) {
            resultingConf = resultingConf.replace(m.key, m.value)
        }
        val sniData = if (sni.isBlank()) getSniData("") else getSniData(sni)
        return resultingConf.replace(SNI_PLACEHOLDER, sniData)
    }

    /**
     * Генерирует структурированный JSON конфиг для обмена (Copy/Paste)
     */
    fun toJsonConfig(): String {
        val json = Json { prettyPrint = true }
        val root = buildJsonObject {
            put("version", 1)
            putJsonObject("hysteria") {
                put("server", server)
                put("password", password)
                put("sni", sni)
                put("insecure", insecure)
            }
            putJsonObject("turn") {
                put("enabled", turnEnabled)
                put("peer", turnPeer)
                put("link", turnLink)
                put("threads", turnThreads)
                put("udp", turnUdp)
                put("noDtls", turnNoDtls)
                put("listen", turnListen)
            }
        }
        return json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), root)
    }

    private fun getSniData(sni: String): String {
        val sniLine = if (sni.isNotBlank()) "    sni: $sni" else ""
        return """
tls:
$sniLine
    insecure: $insecure
        """.trimIndent()
    }

    companion object {
        private const val SERVER_ADDRESS_PLACEHOLDER = "__SERVER_ADDRESS_PLACEHOLDER__"
        private const val PASSWORD_PLACEHOLDER = "__PASSWORD_PLACEHOLDER__"
        private const val SNI_PLACEHOLDER = "__SNI_PLACEHOLDER__"
        private const val HYSTERIA_CONFIG_TEXT_DATA = """
server: $SERVER_ADDRESS_PLACEHOLDER

auth: $PASSWORD_PLACEHOLDER

$SNI_PLACEHOLDER

bandwidth:
  up: 100 mbps
  down: 100 mbps

socks5:
  listen: 127.0.0.1:1080

fastOpen: true

http:
  listen: 127.0.0.1:1081

acl:
  inline:
    - direct(domain:vk.com)
    - direct(domain:vk-cdn.net)
    - direct(domain:vk.me)
    - direct(domain:yandex.ru)
    - direct(domain:yandex.net)
    - direct(domain:yastatic.net)
  
quic:
  maxIdleTimeout: 60s 
  keepAlivePeriod: 30s 
  handshakeTimeout: 10s
"""
    }
}
