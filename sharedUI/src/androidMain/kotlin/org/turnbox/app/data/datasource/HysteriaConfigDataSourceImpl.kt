package org.turnbox.app.data.datasource

import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.turnbox.app.data.MASTER_HYSTERIA_CONFIG_FILE_NAME
import org.turnbox.app.data.model.HysteriaConfig
import org.turnbox.app.vpn.data.KEY_IS_VPN_CONFIG_READY
import org.turnbox.app.vpn.data.KEY_VPN_CONFIG_PATH
import org.turnbox.app.vpn.data.vpnPrefDataStore
import java.io.File

class HysteriaConfigDataSourceImpl(
    private val context: Context
) : HysteriaConfigDataSource {

    private val json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }

    override suspend fun saveConfig(config: HysteriaConfig): Unit = withContext(Dispatchers.IO) {
        val vpnConfigFile = File(context.filesDir, MASTER_HYSTERIA_CONFIG_FILE_NAME)

        vpnConfigFile.writeText(config.getFullConfig())

        val cachedFile = File(vpnConfigFile.absolutePath + "_cached.json")
        try {
            val jsonString = json.encodeToString(HysteriaConfig.serializer(), config)
            cachedFile.writeText(jsonString)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        context.vpnPrefDataStore.edit {
            it[KEY_IS_VPN_CONFIG_READY] = true
            it[KEY_VPN_CONFIG_PATH] = vpnConfigFile.absolutePath
        }
        Unit
    }

    override suspend fun loadConfig(): HysteriaConfig = withContext(Dispatchers.IO) {
        val vpnConfigFile = File(context.filesDir, MASTER_HYSTERIA_CONFIG_FILE_NAME)
        val cachedFile = File(vpnConfigFile.absolutePath + "_cached.json")

        if (!cachedFile.exists()) return@withContext HysteriaConfig()

        try {
            val jsonString = cachedFile.readText()
            return@withContext json.decodeFromString(HysteriaConfig.serializer(), jsonString)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext HysteriaConfig()
        }
    }

    override suspend fun saveRawConfig(text: String): Unit = withContext(Dispatchers.IO) {
        val vpnConfigFile = File(context.filesDir, MASTER_HYSTERIA_CONFIG_FILE_NAME)
        vpnConfigFile.writeText(text)

        context.vpnPrefDataStore.edit {
            it[KEY_IS_VPN_CONFIG_READY] = true
            it[KEY_VPN_CONFIG_PATH] = vpnConfigFile.absolutePath
        }
        Unit
    }
}
