package org.turnbox.app.vpn

data class AndroidSocksProxySettings(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val username: String = DEFAULT_USERNAME,
    val password: String = ""
) {
    val isConfigured: Boolean
        get() = username.isNotBlank() && password.isNotBlank()

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 10808
        const val DEFAULT_USERNAME = "turnbox"
    }
}
