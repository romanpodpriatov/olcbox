package org.turnbox.app.data.repository

import org.turnbox.app.data.model.HysteriaConfig

interface HysteriaConfigRepository {
    suspend fun saveConfig(config: HysteriaConfig)
    suspend fun loadConfig(): HysteriaConfig
    suspend fun importRawConfig(text: String)
}
