package org.turnbox.app.data.datasource

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.turnbox.app.data.model.HysteriaConfig
import org.turnbox.app.data.repository.HysteriaConfigRepository

interface HysteriaConfigDataSource {
    suspend fun saveConfig(config: HysteriaConfig)
    suspend fun loadConfig(): HysteriaConfig
    suspend fun saveRawConfig(text: String)
}

@Serializable
private data class ImportWrapper(
    val version: Int = 1,
    val hysteria: HysteriaSection? = null,
    val turn: TurnSection? = null
)

@Serializable
private data class HysteriaSection(
    val server: String = "",
    val password: String = "",
    val sni: String = "",
    val insecure: Boolean = true
)

@Serializable
private data class TurnSection(
    val enabled: Boolean = false,
    val peer: String = "",
    val link: String = "",
    val threads: Int = 8,
    val udp: Boolean = true,
    val noDtls: Boolean = false,
    val listen: String = "127.0.0.1:9000"
)

class HysteriaConfigRepositoryImpl(
    private val dataSource: HysteriaConfigDataSource
) : HysteriaConfigRepository {
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        coerceInputValues = true
    }

    override suspend fun saveConfig(config: HysteriaConfig) {
        dataSource.saveConfig(config)
    }

    override suspend fun loadConfig(): HysteriaConfig = dataSource.loadConfig()

    override suspend fun importRawConfig(text: String) {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val wrapper = json.decodeFromString<ImportWrapper>(trimmed)

                if (wrapper.hysteria != null || wrapper.turn != null) {
                    val config = HysteriaConfig(
                        server = wrapper.hysteria?.server ?: "",
                        password = wrapper.hysteria?.password ?: "",
                        sni = wrapper.hysteria?.sni ?: "",
                        insecure = wrapper.hysteria?.insecure ?: true,
                        
                        turnEnabled = wrapper.turn?.enabled ?: false,
                        turnPeer = wrapper.turn?.peer ?: "",
                        turnLink = wrapper.turn?.link ?: "",
                        turnThreads = wrapper.turn?.threads ?: 8,
                        turnUdp = wrapper.turn?.udp ?: true,
                        turnNoDtls = wrapper.turn?.noDtls ?: false,
                        turnListen = wrapper.turn?.listen ?: "127.0.0.1:9000"
                    )
                    dataSource.saveConfig(config)
                    return
                }

                val config = json.decodeFromString<HysteriaConfig>(trimmed)
                dataSource.saveConfig(config)
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        dataSource.saveRawConfig(text)
    }
}
