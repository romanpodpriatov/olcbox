package org.turnbox.app.androidApp

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import org.turnbox.app.data.datasource.LocationsDataSourceImpl
import org.turnbox.app.data.datasource.LocationsRepositoryImpl
import org.turnbox.app.data.exporter.AndroidLogExporter
import org.turnbox.app.data.importer.AndroidConfigImporter
import org.turnbox.app.ui.activities.AndroidMainScreen
import org.turnbox.app.ui.features.home.HomeScreenViewModel
import org.turnbox.app.ui.features.locations.LocationViewModel
import org.turnbox.app.ui.theme.AppTheme
import org.turnbox.app.vpn.AndroidVpnManager

class AppActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Permission handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val vpnManager = AndroidVpnManager(this)
        val locationsDataSource = LocationsDataSourceImpl(this)
        val locationsRepository = LocationsRepositoryImpl(locationsDataSource)
        val configImporter = AndroidConfigImporter(this)
        val logExporter = AndroidLogExporter(this)

        val viewModel = HomeScreenViewModel(
            vpnManager = vpnManager,
            locationsRepository = locationsRepository,
            configImporter = configImporter,
            logExporter = logExporter
        )
        
        val locationViewModel = LocationViewModel(
            locationsRepository = locationsRepository
        )

        enableEdgeToEdge()
        setContent {
            AppTheme({ 
                AndroidMainScreen(
                    viewModel = viewModel,
                    locationViewModel = locationViewModel,
                    vpnManager = vpnManager
                ) 
            })
        }
    }
}
