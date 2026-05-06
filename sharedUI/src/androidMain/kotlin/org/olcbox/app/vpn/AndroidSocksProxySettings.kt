package org.olcbox.app.vpn

data class AndroidSocksProxySettings(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val username: String = "",
    val password: String = ""
) {
    val isConfigured: Boolean
        get() = username.isNotBlank() && password.isNotBlank()

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 10808
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535

        fun isValidPort(port: Int): Boolean = port in MIN_PORT..MAX_PORT

        fun sanitizePort(port: Int?): Int {
            return port?.takeIf { isValidPort(it) } ?: DEFAULT_PORT
        }
    }
}
