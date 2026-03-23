package org.turnbox.app.androidApp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.turnbox.app.data.datasource.HysteriaConfigDataSourceImpl
import org.turnbox.app.data.datasource.HysteriaConfigRepositoryImpl
import org.turnbox.app.data.importer.AndroidConfigImporter
import org.turnbox.app.ui.activities.AndroidMainScreen
import org.turnbox.app.ui.activities.MainActivityViewModel
import org.turnbox.app.vpn.AndroidVpnManager

class AppActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val vpnManager = AndroidVpnManager(this)
        val configDataSource = HysteriaConfigDataSourceImpl(this)
        val configRepository = HysteriaConfigRepositoryImpl(configDataSource)
        val configImporter = AndroidConfigImporter(this)
        
        val viewModel = MainActivityViewModel(
            vpnManager = vpnManager,
            configRepo = configRepository,
            configImporter = configImporter
        )

        enableEdgeToEdge()
        setContent {
            AndroidMainScreen(viewModel = viewModel)
        }
    }
}
