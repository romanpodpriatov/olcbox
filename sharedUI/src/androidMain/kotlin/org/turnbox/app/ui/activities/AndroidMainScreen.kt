package org.turnbox.app.ui.activities

import android.app.Activity
import android.net.Uri
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import org.turnbox.app.ui.TurnboxAppContent
import org.turnbox.app.ui.features.home.HomeScreenViewModel
import org.turnbox.app.ui.features.locations.LocationViewModel
import org.turnbox.app.vpn.AndroidConnectionMode
import org.turnbox.app.vpn.AndroidVpnManager

@Composable
fun AndroidMainScreen(
    viewModel: HomeScreenViewModel,
    locationViewModel: LocationViewModel,
    vpnManager: AndroidVpnManager
) {
    val context = LocalContext.current
    val connectionMode by vpnManager.connectionMode.collectAsState()
    val proxySettings by vpnManager.proxySettings.collectAsState()
    val homeState by viewModel.state.collectAsState()
    val pendingLogSaveCallbacks = remember {
        mutableStateOf<Pair<(String) -> Unit, (String) -> Unit>?>(null)
    }
    val pendingVpnAction = remember {
        mutableStateOf<PendingVpnPermissionAction?>(null)
    }
    var isAppSettingsOpen by remember { mutableStateOf(false) }
    var logsOpenRequest by remember { mutableStateOf(0) }

    val vpnRequestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            when (val action = pendingVpnAction.value) {
                PendingVpnPermissionAction.Toggle -> viewModel.ToggleVpn()
                is PendingVpnPermissionAction.RestartWithMode -> {
                    vpnManager.selectConnectionMode(action.mode)
                    viewModel.restartVpnIfRunning()
                }
                null -> Unit
            }
        }
        pendingVpnAction.value = null
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.onFileSelected(it) {
                locationViewModel.loadLocations()
                viewModel.loadCurrentConfig()
            }
        }
    }

    val logSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        val callbacks = pendingLogSaveCallbacks.value
        pendingLogSaveCallbacks.value = null
        if (uri == null || callbacks == null) return@rememberLauncherForActivityResult

        viewModel.onSaveLogsToFile(
            target = uri,
            onSaved = callbacks.first,
            onError = callbacks.second
        )
    }

    TurnboxAppContent(
        homeViewModel = viewModel,
        locationViewModel = locationViewModel,
        onToggleClick = {
            val prepIntent = if (connectionMode == AndroidConnectionMode.Tun) {
                VpnService.prepare(context)
            } else {
                null
            }
            if (prepIntent != null) {
                pendingVpnAction.value = PendingVpnPermissionAction.Toggle
                vpnRequestLauncher.launch(prepIntent)
            } else {
                viewModel.ToggleVpn()
            }
        },
        onImportFileRequested = {
            filePickerLauncher.launch("*/*")
        },
        onImportFromClipboardRequested = {
            viewModel.onPasteFromClipboard {
                locationViewModel.loadLocations()
                viewModel.loadCurrentConfig()
            }
        },
        onCopyConfigRequested = {
            viewModel.onCopyFullConfigClicked()
        },
        onSaveLogsRequested = { onSaved, onError ->
            pendingLogSaveCallbacks.value = onSaved to onError
            logSaveLauncher.launch(viewModel.suggestedLogsFileName())
        },
        logsOpenRequest = logsOpenRequest,
        showAppSettingsButton = true,
        onAppSettingsClick = { isAppSettingsOpen = true }
    )

    if (isAppSettingsOpen) {
        AppSettingsSheet(
            selectedMode = connectionMode,
            proxySettings = proxySettings,
            enabled = !homeState.isVpnLoading,
            isConnectionActive = homeState.isVpnConnected,
            onDismiss = { isAppSettingsOpen = false },
            onApplicationLogsClick = {
                isAppSettingsOpen = false
                logsOpenRequest += 1
            },
            onModeSelected = { mode ->
                if (mode != connectionMode && homeState.isVpnConnected) {
                    val prepIntent = if (mode == AndroidConnectionMode.Tun) {
                        VpnService.prepare(context)
                    } else {
                        null
                    }
                    if (prepIntent != null) {
                        pendingVpnAction.value = PendingVpnPermissionAction.RestartWithMode(mode)
                        vpnRequestLauncher.launch(prepIntent)
                    } else {
                        vpnManager.selectConnectionMode(mode)
                        viewModel.restartVpnIfRunning()
                    }
                } else if (mode != connectionMode) {
                    vpnManager.selectConnectionMode(mode)
                }
            },
            onProxyPasswordSaved = { password ->
                vpnManager.updateProxyPassword(password)
                if (homeState.isVpnConnected && connectionMode == AndroidConnectionMode.Proxy) {
                    viewModel.restartVpnIfRunning()
                }
            },
            onProxyPasswordRegenerated = {
                vpnManager.regenerateProxyPassword()
                if (homeState.isVpnConnected && connectionMode == AndroidConnectionMode.Proxy) {
                    viewModel.restartVpnIfRunning()
                }
            }
        )
    }
}

private sealed class PendingVpnPermissionAction {
    object Toggle : PendingVpnPermissionAction()
    data class RestartWithMode(val mode: AndroidConnectionMode) : PendingVpnPermissionAction()
}
