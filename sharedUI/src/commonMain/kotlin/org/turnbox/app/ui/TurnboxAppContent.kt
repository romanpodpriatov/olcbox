package org.turnbox.app.ui

import androidx.compose.runtime.Composable
import org.turnbox.app.ui.features.home.HomeScreen
import org.turnbox.app.ui.features.home.HomeScreenViewModel
import org.turnbox.app.ui.features.locations.LocationViewModel

@Composable
fun TurnboxAppContent(
    homeViewModel: HomeScreenViewModel,
    locationViewModel: LocationViewModel,
    onToggleClick: () -> Unit = { homeViewModel.ToggleVpn() },
    onImportFileRequested: () -> Unit = {},
    onImportFromClipboardRequested: () -> Unit = {
        homeViewModel.onPasteFromClipboard {
            locationViewModel.loadLocations()
            homeViewModel.loadCurrentConfig()
        }
    },
    onCopyConfigRequested: () -> Unit = { homeViewModel.onCopyFullConfigClicked() },
    onSaveLogsRequested: (onSaved: (String) -> Unit, onError: (String) -> Unit) -> Unit = { _, _ -> },
    logsOpenRequest: Int = 0,
    showAppSettingsButton: Boolean = false,
    onAppSettingsClick: () -> Unit = {}
) {
    HomeScreen(
        viewModel = homeViewModel,
        locationViewModel = locationViewModel,
        onToggleClick = onToggleClick,
        onImportFileRequested = onImportFileRequested,
        onImportFromClipboardRequested = onImportFromClipboardRequested,
        onCopyConfigRequested = onCopyConfigRequested,
        onSaveLogsRequested = onSaveLogsRequested,
        logsOpenRequest = logsOpenRequest,
        showAppSettingsButton = showAppSettingsButton,
        onAppSettingsClick = onAppSettingsClick
    )
}
