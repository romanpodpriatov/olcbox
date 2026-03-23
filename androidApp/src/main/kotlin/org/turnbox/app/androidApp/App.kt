package org.turnbox.app.androidApp

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.runBlocking
import org.turnbox.app.data.TUN2SOCKS_CONFIG_FILE_NAME
import org.turnbox.app.data.TUN2SOCKS_CONFIG_TEXT_DATA
import org.turnbox.app.data.datasource.HysteriaConfigDataSourceImpl
import org.turnbox.app.data.datasource.HysteriaConfigRepositoryImpl
import org.turnbox.app.vpn.data.KEY_IS_VPN_CONFIG_READY
import org.turnbox.app.vpn.data.vpnPrefDataStore
import us.leaf3stones.hy2droid.proxy.Hysteria2VpnService
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

class App : Application() {
    companion object {
        lateinit var appContext: Context
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext

        val dataSource = HysteriaConfigDataSourceImpl(this)
        val repository = HysteriaConfigRepositoryImpl(dataSource)

        Hysteria2VpnService.configRepository = repository

        val tun2socksConfigFile = File(filesDir, TUN2SOCKS_CONFIG_FILE_NAME)

        try {
            FileOutputStream(tun2socksConfigFile).use {
                it.write(TUN2SOCKS_CONFIG_TEXT_DATA.toByteArray(StandardCharsets.UTF_8))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        runBlocking {
            vpnPrefDataStore.edit { pref ->
                pref[KEY_IS_VPN_CONFIG_READY] = true
            }
        }
    }
}
