package org.turnbox.app.ui.features.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.turnbox.app.ui.components.StartButton
import org.turnbox.app.ui.features.home.components.HomeScreenAppBar
import org.turnbox.app.ui.features.home.components.LocationSelectorScreen
import org.turnbox.app.ui.features.home.components.LogsSheet
import org.turnbox.app.ui.features.home.components.RelayStatus
import org.turnbox.app.ui.features.locations.LocationSettingsSheet
import org.turnbox.app.ui.features.locations.LocationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeScreenViewModel,
    locationViewModel: LocationViewModel,
    onToggleClick: () -> Unit = { viewModel.ToggleVpn() },
    onImportFileRequested: () -> Unit = {},
    onImportFromClipboardRequested: () -> Unit = {
        viewModel.onPasteFromClipboard {
            locationViewModel.loadLocations()
            viewModel.loadCurrentConfig()
        }
    },
    onCopyConfigRequested: () -> Unit = { viewModel.onCopyFullConfigClicked() },
    onSaveLogsRequested: (onSaved: (String) -> Unit, onError: (String) -> Unit) -> Unit = { _, _ -> },
    logsOpenRequest: Int = 0,
    showAppSettingsButton: Boolean = false,
    onAppSettingsClick: () -> Unit = {}
) {
    val scrollState = rememberScrollState()
    var isLocationSettingsOpen by remember { mutableStateOf(false) }
    var isLogsSheetOpen by remember { mutableStateOf(false) }

    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val pingsState = locationViewModel.pingsState

    LaunchedEffect(logsOpenRequest) {
        if (logsOpenRequest > 0) {
            isLogsSheetOpen = true
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            HomeScreenAppBar(
                onHistoryClick = { isLogsSheetOpen = true },
                showAppSettingsButton = showAppSettingsButton,
                onAppSettingsClick = onAppSettingsClick,
                onImportFileClick = onImportFileRequested,
                onImportClipboardClick = {
                    onImportFromClipboardRequested()
                    scope.launch { snackbarHostState.showSnackbar("Импортировано из буфера") }
                },
                onExportClipboardClick = {
                    onCopyConfigRequested()
                    scope.launch { snackbarHostState.showSnackbar("Конфиг скопирован") }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 32.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RelayStatus(isActive = state.isVpnConnected)
            Spacer(
                modifier = Modifier.height(16.dp)
            )
            StartButton(
                isActive = state.isVpnConnected,
                isLoading = state.isVpnLoading,
                enabled = state.isVpnLoading || state.isVpnConnected || state.canStartVpn,
                onClick = { onToggleClick() }
            )
            Spacer(
                modifier = Modifier.height(32.dp)
            )
            LocationSelectorScreen(
                onRefreshClick = {
                    scope.launch {
                        locationViewModel.refreshPings { viewModel.checkConnectionFor(it) }
                    }
                },
                locations = locationViewModel.locations,
                selectedLocationId = locationViewModel.selectedLocationId,
                pingsState = pingsState,
                onLocationSelected = { id ->
                    locationViewModel.selectLocation(id) {
                        viewModel.loadCurrentConfig()
                        viewModel.restartVpnIfRunning()
                    }
                },
                onLocationSettingsClick = { id ->
                    locationViewModel.startEditing(id)
                    isLocationSettingsOpen = true
                },
                onAddLocationClick = {
                    locationViewModel.startEditing(null)
                    isLocationSettingsOpen = true
                }
            )
            Spacer(
                modifier = Modifier.height(24.dp)
            )

            if (isLocationSettingsOpen) {
                LocationSettingsSheet(
                    onDismiss = {
                        isLocationSettingsOpen = false
                        viewModel.loadCurrentConfig()
                    },
                    viewModel = locationViewModel,
                    homeViewModel = viewModel
                )
            }

            if (isLogsSheetOpen) {
                val logs by viewModel.logs.collectAsState()
                LogsSheet(
                    logs = logs,
                    onSaveClick = {
                        onSaveLogsRequested(
                            { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                            { message -> scope.launch { snackbarHostState.showSnackbar(message) } }
                        )
                    },
                    onDismiss = { isLogsSheetOpen = false }
                )
            }
        }
    }
}
