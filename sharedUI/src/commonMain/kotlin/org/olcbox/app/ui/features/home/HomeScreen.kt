package org.olcbox.app.ui.features.home

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.olcbox.app.admin.AdminState
import org.olcbox.app.net.TransportGroup
import org.olcbox.app.net.transportKind
import org.olcbox.app.ui.components.StartButton
import org.olcbox.app.ui.components.VpnDisclosureScreen
import org.olcbox.app.ui.components.kit.PkVersionFooter
import org.olcbox.app.ui.components.kit.pkScreenBackground
import org.olcbox.app.ui.features.home.components.AddConfigurationSheet
import org.olcbox.app.ui.features.home.components.HomeScreenAppBar
import org.olcbox.app.ui.features.home.components.LocationSelectorScreen
import org.olcbox.app.ui.features.home.components.LogsSheet
import org.olcbox.app.ui.features.home.components.RelayNotice
import org.olcbox.app.ui.features.home.components.RelayStatus
import org.olcbox.app.ui.features.locations.LocationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeScreenViewModel,
    locationViewModel: LocationViewModel,
    scrollState: ScrollState,
    onToggleClick: () -> Unit = { viewModel.ToggleVpn() },
    onImportFileRequested: () -> Unit = {},
    onImportFromClipboardRequested: (onImported: () -> Unit, onError: (String) -> Unit) -> Unit = { _, _ -> },
    onScanQrRequested: () -> Unit = {},
    onCopyConfigRequested: () -> Unit = { viewModel.onCopyFullConfigClicked() },
    onSaveLogsRequested: (onSaved: (String) -> Unit, onError: (String) -> Unit) -> Unit = { _, _ -> },
    showAppSettingsButton: Boolean = false,
    canScanQr: Boolean = false,
    onAppSettingsClick: () -> Unit = {},
    showSplitTunnelingButton: Boolean = false,
    onSplitTunnelingClick: () -> Unit = {},
    onOpenLocationSettings: (String?) -> Unit,
    onAddLocation: () -> Unit,
    onGetSubscriptionClick: () -> Unit = {},
    showGetSubscription: Boolean = true,
    /** Opens a provider's support or web link. Platform-supplied. */
    onOpenExternalUrl: (String) -> Unit = {}
) {
    var isLogsSheetOpen by remember { mutableStateOf(false) }
    var isAddSheetOpen by remember { mutableStateOf(false) }
    var isRefreshingSubscriptions by remember { mutableStateOf(false) }
    var refreshingSubscriptionUrl by remember { mutableStateOf<String?>(null) }

    val state by viewModel.state.collectAsState()
    val connectedSince by viewModel.connectedSince.collectAsState()
    val traffic by viewModel.traffic.collectAsState()
    val subscriptionSettings by viewModel.subscriptionSettings.collectAsState()
    val subscriptionSettingsLoaded by viewModel.subscriptionSettingsLoaded.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val pingsState = locationViewModel.pingsState
    val locations = locationViewModel.locations.toList()
    val hasSubscriptions = locations.any { !it.subscriptionUrl.isNullOrBlank() }

    // The per-location editor and "create custom location" are plumbing, and
    // this predicate fails closed: see AdminState.plumbingVisible. Everything a
    // user legitimately needs — settings, subscription management, split
    // tunneling, logs — is visible regardless.
    //
    // Locations come from a link, a file or a QR code. Building one by hand
    // means typing a room, a key, a provider and a transport, which is not a
    // thing to ask of anyone who did not set the network up.
    val admin = AdminState.plumbingVisible

    val requiresSetup = !state.canStartVpn && !state.isVpnConnected && !state.isVpnLoading

    val primaryActionLabel = when {
        requiresSetup -> "SETUP"
        state.isVpnLoading || state.isVpnConnected -> "STOP"
        else -> "START"
    }

    val vpnDisclosureAccepted by viewModel.vpnDisclosureAccepted.collectAsState()
    var showVpnDisclosure by remember { mutableStateOf(false) }

    if (showVpnDisclosure) {
        VpnDisclosureScreen(
            onAccept = {
                showVpnDisclosure = false
                viewModel.acceptVpnDisclosure()
                onToggleClick()
            },
            // Declining leaves the app exactly as it was, connecting nothing.
            // Pressing START again brings the screen back, which is the half of
            // the flow the Play declaration video has to show.
            onDecline = { showVpnDisclosure = false }
        )
    }

    fun refreshSubscriptions() {
        isRefreshingSubscriptions = true
        viewModel.refreshSubscriptions { report ->
            locationViewModel.loadLocations {
                isRefreshingSubscriptions = false
                viewModel.restartVpnIfRunning()

                scope.launch {
                    snackbarHostState.showSnackbar(report.bulkMessage())
                }
            }
        }
    }

    fun refreshSubscription(url: String) {
        refreshingSubscriptionUrl = url
        viewModel.refreshSubscription(url) { report ->
            locationViewModel.loadLocations {
                refreshingSubscriptionUrl = null
                viewModel.restartVpnIfRunning()
                scope.launch { snackbarHostState.showSnackbar(report.singleMessage()) }
            }
        }
    }

    fun refreshHttpPings(targetLocationIds: List<String>? = null) {
        // The button is always there; the measurement is not always possible.
        // Latency is timed through a connection, so with nothing connected only
        // an olcRTC room can be probed — say that instead of appearing to do
        // nothing, which is what the user is left with otherwise.
        val measurable = locations.any { item ->
            (targetLocationIds == null || item.storageId in targetLocationIds) &&
                item.config?.let { viewModel.canPing(it) } == true
        }
        if (!measurable) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    if (state.isVpnConnected) {
                        "Latency is measured on the connected location"
                    } else {
                        "Nothing here can be measured — these locations have no " +
                            "address to reach"
                    }
                )
            }
            return
        }

        locationViewModel.refreshPings(
            targetLocationIds = targetLocationIds,
            performPing = { config ->
                viewModel.performPingFor(config)
            },
            canPing = { config -> viewModel.canPing(config) },
        )
    }

    // What the app does on its own when it opens. Each is off unless asked for:
    // connecting without being told to is not a default anyone should inherit.
    var launchActionsDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(subscriptionSettingsLoaded, locations.isEmpty(), launchActionsDone) {
        // Only once the stored settings have actually arrived: the first value is
        // a default object, and acting on it would ignore what the user chose.
        //
        // And only once there are locations to act on. Settings load faster than
        // the location list, so "ping on launch" fired against an empty list,
        // found nothing measurable and announced that instead — which is exactly
        // what "the checkbox does nothing" looks like from outside.
        if (launchActionsDone || !subscriptionSettingsLoaded || locations.isEmpty()) {
            return@LaunchedEffect
        }
        val settings = subscriptionSettings
        launchActionsDone = true
        if (settings.refreshOnOpen && hasSubscriptions) refreshSubscriptions()
        if (settings.pingOnLaunch) refreshHttpPings()
        if (settings.connectOnLaunch && state.canStartVpn && !state.isVpnConnected) {
            onToggleClick()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.autoRefreshNotice.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState)
        },
        topBar = {
            HomeScreenAppBar(
                onHistoryClick = { isLogsSheetOpen = true },
                showAppSettingsButton = showAppSettingsButton,
                showHistoryButton = true,
                onAppSettingsClick = onAppSettingsClick,
                showSplitTunnelingButton = showSplitTunnelingButton,
                onSplitTunnelingClick = onSplitTunnelingClick,
                onAddClick = { isAddSheetOpen = true }
            )
        }
    ) { innerPadding ->
        // Status and the button are pinned; only the list below them scrolls.
        //
        // One `verticalScroll` over the whole column meant that reaching a
        // location further down the list carried the START button off the top of
        // the screen with it — so selecting an exit and then connecting to it
        // took a scroll back up, and on a long subscription the button was
        // simply not visible while choosing.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(pkScreenBackground())
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                RelayStatus(
                    isActive = state.isVpnConnected,
                    requiresSetup = requiresSetup,
                    transportLabel = state.selectedLocation?.config?.transportKind()?.label(),
                    // strip the " · XHTTP" suffix: the pill already names the transport
                    exitName = state.selectedLocation?.config?.displayName()
                        ?.let { TransportGroup.baseName(it) },
                    connectedSince = connectedSince,
                    traffic = traffic
                )

                state.notice()?.let { notice ->
                    Spacer(modifier = Modifier.height(12.dp))
                    RelayNotice(text = notice)
                }

                Spacer(modifier = Modifier.height(16.dp))

                StartButton(
                    isActive = state.isVpnConnected,
                    isLoading = state.isVpnLoading,
                    requiresSetup = requiresSetup,
                    label = primaryActionLabel,
                    enabled = true,
                    onClick = {
                        if (requiresSetup) {
                            isAddSheetOpen = true
                        } else if (!vpnDisclosureAccepted && !state.isVpnConnected) {
                            // Only on the way up, and before the system's own VPN
                            // dialog: the disclosure has to be what explains that
                            // prompt, not something the user meets after granting
                            // it. Stopping never asks.
                            showVpnDisclosure = true
                        } else {
                            onToggleClick()
                        }
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Pull down on the list to fetch the subscriptions again. It was
            // reachable only from a menu item inside the "+" sheet, which is
            // not where anyone looks for it on a list of servers.
            PullToRefreshBox(
                isRefreshing = isRefreshingSubscriptions,
                onRefresh = { refreshSubscriptions() },
                modifier = Modifier.weight(1f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LocationSelectorScreen(
                        onGetSubscriptionClick = onGetSubscriptionClick,
                        showGetSubscription = showGetSubscription,
                        onRefreshClick = { targetIds ->
                            refreshHttpPings(targetIds)
                        },
                        onRefreshSubscriptionClick = { url -> refreshSubscription(url) },
                        onOpenUrl = onOpenExternalUrl,
                        refreshingSubscriptionUrl = refreshingSubscriptionUrl,
                        onAddSubscriptionClick = {
                            isAddSheetOpen = true
                        },
                        locations = locations,
                        selectedLocationId = locationViewModel.selectedLocationId,
                        pingsState = pingsState,
                        onLocationSelected = { id ->
                            // Read before the switch: picking a row while
                            // connected tears the tunnel down and builds a new
                            // one, which took seconds and announced itself only
                            // as a spinner.
                            val wasConnected = state.isVpnConnected
                            val name = locations.firstOrNull { it.storageId == id }
                                ?.let { item ->
                                    item.metadata?.name?.takeIf { it.isNotBlank() }
                                        ?: item.fullName
                                }
                            locationViewModel.selectLocation(id) {
                                viewModel.loadCurrentConfig()
                                viewModel.restartVpnIfRunning()
                                if (wasConnected) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            name?.let { "Reconnecting through $it" }
                                                ?: "Reconnecting through the new location"
                                        )
                                    }
                                }
                            }
                        },
                        onLocationSettingsClick = { id ->
                            onOpenLocationSettings(id)
                        },
                        onAddLocationClick = {
                            onAddLocation()
                        },
                        sort = subscriptionSettings.sort,
                        collapsible = subscriptionSettings.collapsible,
                        showSettings = admin,
                        showCustomLocation = admin
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    PkVersionFooter()

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        if (isLogsSheetOpen) {
            val logs by viewModel.logs.collectAsState()

            LogsSheet(
                logs = logs,
                onSaveClick = {
                    onSaveLogsRequested(
                        { message ->
                            scope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        },
                        { message ->
                            scope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        }
                    )
                },
                onShareClick = {
                    viewModel.onShareLogs(
                        onShared = { message ->
                            scope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        },
                        onError = { message ->
                            scope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        }
                    )
                },
                onDismiss = {
                    isLogsSheetOpen = false
                }
            )
        }

        if (isAddSheetOpen) {
            AddConfigurationSheet(
                canScanQr = canScanQr,
                hasSubscriptions = hasSubscriptions,
                onDismiss = {
                    isAddSheetOpen = false
                },
                onScanQrClick = {
                    isAddSheetOpen = false
                    onScanQrRequested()
                },
                onPasteLinkClick = {
                    isAddSheetOpen = false
                    onImportFromClipboardRequested(
                        {
                            scope.launch {
                                snackbarHostState.showSnackbar("Imported from clipboard")
                            }
                        },
                        { message ->
                            scope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        }
                    )
                },
                onImportFileClick = {
                    isAddSheetOpen = false
                    onImportFileRequested()
                },
                onUpdateSubscriptionsClick = {
                    isAddSheetOpen = false
                    refreshSubscriptions()
                },
                onAddCustomLocationClick = {
                    isAddSheetOpen = false
                    onAddLocation()
                },
                onGetSubscriptionClick = {
                    isAddSheetOpen = false
                    onGetSubscriptionClick()
                },
                showGetSubscription = showGetSubscription,
                showCustomLocation = admin
            )
        }
    }
}
