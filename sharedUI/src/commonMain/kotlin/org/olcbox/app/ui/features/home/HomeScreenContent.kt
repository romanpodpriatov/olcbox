package org.olcbox.app.ui.features.home

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.OlcrtcSlots
import org.olcbox.app.ui.components.kit.PkAction
import org.olcbox.app.ui.components.kit.PkActionBar
import org.olcbox.app.ui.components.kit.PkBoardHead
import org.olcbox.app.ui.components.kit.PkHairline
import org.olcbox.app.ui.components.kit.PkHeaderRow
import org.olcbox.app.ui.components.kit.PkIconButton
import org.olcbox.app.ui.components.kit.PkStatusStrip
import org.olcbox.app.ui.components.kit.PkVersionFooter
import org.olcbox.app.ui.components.kit.pkScreenBackground
import org.olcbox.app.ui.features.home.components.BoardFilterChips
import org.olcbox.app.ui.features.home.components.BoardModel
import org.olcbox.app.ui.features.home.components.RelayNotice
import org.olcbox.app.ui.features.home.components.RoomBoard
import org.olcbox.app.ui.features.locations.PingsState
import org.olcbox.app.ui.icons.PkIcons

/**
 * The home screen with no view models in it.
 *
 * Split out so the layout can be rendered from a test with fabricated data — a
 * redesign that cannot be looked at until it is on a phone is a redesign built
 * blind — and so the two-pane layout has one body to rearrange rather than two
 * to keep in step.
 */

/** The pinned bands: what the header, the status strip and the board head show. */
data class HomeChrome(
    val tag: String,
    val statusLabel: String,
    val statusMeta: String,
    val statusValue: String,
    val isActive: Boolean,
    val isBusy: Boolean,
    val notice: String?,
    val heading: String,
    val sortLabel: String,
    val action: PkAction,
    val showAppSettingsButton: Boolean,
    val showSplitTunnelingButton: Boolean,
    val showLock: Boolean
)

/** Everything the scrolling list needs to draw itself. */
data class HomeBoard(
    val model: BoardModel,
    val selectedLocationId: String?,
    val isConnected: Boolean,
    val pingsState: PingsState,
    val olcrtcSlots: Map<String, OlcrtcSlots>,
    val occupancyHistory: Map<String, List<Float>>,
    val transportFilter: String?,
    val isRefreshingSubscriptions: Boolean,
    val refreshingSubscriptionUrl: String?,
    val collapsible: Boolean,
    val showSettings: Boolean,
    val showCustomLocation: Boolean,
    val showGetSubscription: Boolean
)

/** One place for every callback, so the signature below stays readable. */
data class HomeCallbacks(
    val onBrandTap: () -> Unit,
    val onDiagnosticsClick: () -> Unit,
    val onLockClick: () -> Unit,
    val onSplitTunnelingClick: () -> Unit,
    val onAddClick: () -> Unit,
    val onSettingsClick: () -> Unit,
    val onSortClick: () -> Unit,
    val onFilterSelected: (String?) -> Unit,
    val onActionClick: () -> Unit,
    val onPullToRefresh: () -> Unit,
    val onLocationSelected: (String) -> Unit,
    val onLocationSettingsClick: (String) -> Unit,
    val onMeasure: (List<String>) -> Unit,
    val onRefreshSubscriptionClick: (String) -> Unit,
    val onOpenUrl: (String) -> Unit,
    val onAddLocationClick: () -> Unit,
    val onGetSubscriptionClick: () -> Unit,
    val canPing: (LocationConfig) -> Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreenContent(
    chrome: HomeChrome,
    board: HomeBoard,
    callbacks: HomeCallbacks,
    scrollState: ScrollState,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize().then(pkScreenBackground()),
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { HomeTopBands(chrome, board, callbacks) },
        bottomBar = { PkActionBar(action = chrome.action, onClick = callbacks.onActionClick) }
    ) { innerPadding ->
        // Pull down on the list to fetch the server lists again. It was reachable
        // only from a menu item inside the "+" sheet, which is not where anyone
        // looks for it on a list of servers.
        PullToRefreshBox(
            isRefreshing = board.isRefreshingSubscriptions,
            onRefresh = callbacks.onPullToRefresh,
            modifier = Modifier.fillMaxSize().padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(14.dp))
                HomeRoomBoard(board, callbacks)
                Spacer(Modifier.height(20.dp))
                PkVersionFooter()
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun HomeTopBands(
    chrome: HomeChrome,
    board: HomeBoard,
    callbacks: HomeCallbacks
) {
    Column(modifier = Modifier.statusBarsPadding()) {
        PkHeaderRow(tag = chrome.tag, onBrandTap = callbacks.onBrandTap) {
            PkIconButton(
                icon = PkIcons.History,
                contentDescription = "Diagnostics",
                onClick = callbacks.onDiagnosticsClick
            )
            if (chrome.showLock) {
                PkIconButton(
                    icon = Icons.Outlined.Lock,
                    contentDescription = "Lock admin",
                    onClick = callbacks.onLockClick
                )
            }
            if (chrome.showSplitTunnelingButton) {
                PkIconButton(
                    icon = PkIcons.Shield,
                    contentDescription = "Split tunneling",
                    onClick = callbacks.onSplitTunnelingClick
                )
            }
            PkIconButton(
                icon = Icons.Outlined.Add,
                contentDescription = "Add connection",
                onClick = callbacks.onAddClick,
                tint = MaterialTheme.colorScheme.onSurface
            )
            if (chrome.showAppSettingsButton) {
                PkIconButton(
                    icon = Icons.Outlined.Settings,
                    contentDescription = "Application settings",
                    onClick = callbacks.onSettingsClick
                )
            }
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            PkStatusStrip(
                label = chrome.statusLabel,
                meta = chrome.statusMeta,
                value = chrome.statusValue,
                isActive = chrome.isActive,
                isBusy = chrome.isBusy
            )
            chrome.notice?.let { notice ->
                Spacer(Modifier.height(11.dp))
                RelayNotice(text = notice)
            }
            Spacer(Modifier.height(12.dp))
        }

        PkBoardHead(
            heading = chrome.heading,
            sortLabel = chrome.sortLabel,
            onSortClick = callbacks.onSortClick,
            showSort = !board.model.isEmpty,
            chips = if (board.model.showChips) {
                {
                    BoardFilterChips(
                        model = board.model,
                        activeFilterKey = board.transportFilter,
                        onFilterSelected = callbacks.onFilterSelected
                    )
                }
            } else {
                null
            }
        )
        PkHairline()
    }
}

@Composable
private fun HomeRoomBoard(board: HomeBoard, callbacks: HomeCallbacks) {
    RoomBoard(
        model = board.model,
        selectedLocationId = board.selectedLocationId,
        isConnected = board.isConnected,
        pingsState = board.pingsState,
        olcrtcSlots = board.olcrtcSlots,
        occupancyHistory = board.occupancyHistory,
        canPing = callbacks.canPing,
        collapsible = board.collapsible,
        showSettings = board.showSettings,
        showCustomLocation = board.showCustomLocation,
        showGetSubscription = board.showGetSubscription,
        refreshingSubscriptionUrl = board.refreshingSubscriptionUrl,
        onLocationSelected = callbacks.onLocationSelected,
        onLocationSettingsClick = callbacks.onLocationSettingsClick,
        onMeasure = callbacks.onMeasure,
        onRefreshSubscriptionClick = callbacks.onRefreshSubscriptionClick,
        onOpenUrl = callbacks.onOpenUrl,
        onAddSubscriptionClick = callbacks.onAddClick,
        onAddLocationClick = callbacks.onAddLocationClick,
        onGetSubscriptionClick = callbacks.onGetSubscriptionClick
    )
}
