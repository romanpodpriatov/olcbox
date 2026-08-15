package org.olcbox.app.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.use
import org.jetbrains.skia.Image
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationMetadata
import org.olcbox.app.data.model.SubscriptionMetadata
import org.olcbox.app.data.model.SubscriptionSort
import org.olcbox.app.net.LocationKind
import org.olcbox.app.net.OlcrtcSlots
import org.olcbox.app.ui.components.kit.PkAction
import org.olcbox.app.ui.components.kit.PkActionKind
import org.olcbox.app.ui.components.kit.boardAction
import org.olcbox.app.ui.components.kit.boardHeading
import org.olcbox.app.ui.components.kit.sortLabel
import org.olcbox.app.ui.features.home.HomeBoard
import org.olcbox.app.ui.features.home.HomeCallbacks
import org.olcbox.app.ui.features.home.HomeChrome
import org.olcbox.app.ui.features.home.HomeScreenContent
import org.olcbox.app.ui.features.home.components.buildBoardModel
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.ui.features.locations.PingsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.olcbox.app.data.model.SubscriptionSettings
import org.olcbox.app.ui.components.ApplicationSettingsSheet
import org.olcbox.app.ui.components.CAMERA_SUBTITLE
import org.olcbox.app.ui.components.CAMERA_TITLE
import org.olcbox.app.ui.components.CameraRationaleBody
import org.olcbox.app.ui.components.DISCLOSURE_SUBTITLE
import org.olcbox.app.ui.components.DISCLOSURE_TITLE
import org.olcbox.app.ui.components.VpnDisclosureBody
import org.olcbox.app.ui.components.kit.PkSheetSurface
import org.olcbox.app.ui.features.home.components.ADD_SUBTITLE
import org.olcbox.app.ui.features.home.components.ADD_TITLE
import org.olcbox.app.ui.features.home.components.AddConfigurationBody
import org.olcbox.app.ui.features.onboarding.OnboardingScreen
import org.olcbox.app.ui.theme.AppTheme
import org.olcbox.app.update.AppUpdateSettings
import java.io.File
import kotlin.test.Test

/**
 * SCRATCH. Renders the real home screen headless and writes PNGs so a layout can
 * be looked at without a device. Delete before the branch is pushed.
 */
class ScreenshotScratchTest {

    private val out = File("/tmp/claude-0/-opt-proofkit/shots").apply { mkdirs() }

    // ── fabricated data ────────────────────────────────────────────────────

    private fun room(
        id: String,
        name: String,
        provider: String = "telemost",
        transport: String = "vp8channel"
    ) = LocationItem(
        storageId = id,
        fullName = name,
        config = LocationConfig(
            name = name,
            id = "room-$id",
            key = "key-$id",
            kind = LocationKind.Olcrtc,
            bypassProvider = provider,
            transport = transport
        ),
        subscriptionUrl = "https://proofkit.org/sub/abc123def456/olcrtc",
        metadata = LocationMetadata(name = name, subscription = encryptedList)
    )

    private fun vless(id: String, name: String, reality: Boolean) = LocationItem(
        storageId = id,
        fullName = name,
        config = LocationConfig(
            name = name,
            kind = LocationKind.Vless,
            rawLink = if (reality) {
                "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
                    "?security=reality&pbk=abcdef&sni=www.microsoft.com&fp=chrome#$name"
            } else {
                "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
                    "?security=tls&sni=example.com#$name"
            }
        ),
        subscriptionUrl = "https://provider.example/sub/zz",
        metadata = LocationMetadata(name = name, subscription = plainList)
    )

    private val encryptedList = SubscriptionMetadata(
        name = "Encrypted list",
        used = "148.2 GB",
        available = "300 GB",
        expiresAtEpochMs = FIXED_NOW + 12L * 24 * 3600 * 1000,
        lastRefreshAtEpochMs = FIXED_NOW - 4L * 60 * 1000
    )

    private val plainList = SubscriptionMetadata(
        name = "proofkit.org",
        lastRefreshAtEpochMs = FIXED_NOW - 12L * 60 * 1000,
        supportUrl = "https://t.me/example",
        webPageUrl = "https://example.org"
    )

    private val locations = listOf(
        room("us", "🇺🇸 United States"),
        room("de", "🇩🇪 Germany", provider = "wbstream", transport = "seichannel"),
        room("fr", "🇫🇷 France"),
        room("se", "🇸🇪 Sweden", provider = "jazz", transport = "datachannel"),
        vless("nl-r", "🇳🇱 Netherlands", reality = true),
        vless("jp-t", "🇯🇵 Japan", reality = false)
    )

    private val slots = mapOf(
        "us" to OlcrtcSlots(slots_total = 8, slots_free = 7, holds_slot = true),
        "de" to OlcrtcSlots(slots_total = 12, slots_free = 7),
        "fr" to OlcrtcSlots(slots_total = 8, slots_free = 5),
        "se" to OlcrtcSlots(slots_total = 8, slots_free = 0)
    )

    private val history = mapOf(
        "us" to listOf(.2f, .3f, .25f, .4f, .35f, .5f, .45f, .6f, .5f, .4f, .3f, .125f),
        "de" to listOf(.9f, .8f, .85f, .7f, .6f, .55f, .5f, .45f, .42f, .41f),
        "fr" to listOf(.1f, .2f, .15f, .3f, .25f, .35f, .3f, .375f),
        "se" to listOf(.6f, .7f, .8f, .85f, .9f, .95f, 1f, 1f)
    )

    private fun chrome(
        state: String,
        action: PkAction,
        hasRooms: Boolean = true
    ): HomeChrome = when (state) {
        "connected" -> HomeChrome(
            tag = "OLCRTC CORE",
            statusLabel = "IN A ROOM · OLCRTC",
            statusMeta = "↓ 41.2 MB   ↑ 9.8 MB",
            statusValue = "04:17",
            isActive = true, isBusy = false, notice = null,
            heading = boardHeading(hasRooms),
            sortLabel = sortLabel(SubscriptionSort.None),
            action = action,
            showAppSettingsButton = true, showSplitTunnelingButton = false, showLock = false
        )
        "connecting" -> HomeChrome(
            tag = "OLCRTC CORE",
            statusLabel = "JOINING ROOM",
            statusMeta = "Telemost · VP8",
            statusValue = "United States",
            isActive = false, isBusy = true, notice = null,
            heading = boardHeading(hasRooms),
            sortLabel = sortLabel(SubscriptionSort.Ping),
            action = action,
            showAppSettingsButton = true, showSplitTunnelingButton = false, showLock = false
        )
        "full" -> HomeChrome(
            tag = "OLCRTC CORE",
            statusLabel = "NOT CONNECTED",
            statusMeta = "this room is full",
            statusValue = "Sweden",
            isActive = false, isBusy = false,
            notice = "Sweden has no free seats — 8 of 8 taken. Pick another room or " +
                "wait for one to free.",
            heading = boardHeading(hasRooms),
            sortLabel = sortLabel(SubscriptionSort.None),
            action = action,
            showAppSettingsButton = true, showSplitTunnelingButton = false, showLock = false
        )
        "empty" -> HomeChrome(
            tag = "OLCRTC CORE",
            statusLabel = "NO SERVER LIST",
            statusMeta = "Add a server list to start",
            statusValue = "",
            isActive = false, isBusy = false, notice = null,
            heading = boardHeading(false),
            sortLabel = sortLabel(SubscriptionSort.None),
            action = action,
            showAppSettingsButton = true, showSplitTunnelingButton = false, showLock = false
        )
        else -> HomeChrome(
            tag = "OLCRTC CORE",
            statusLabel = "NOT CONNECTED",
            statusMeta = "Telemost · VP8",
            statusValue = "United States",
            isActive = false, isBusy = false, notice = null,
            heading = boardHeading(hasRooms),
            sortLabel = sortLabel(SubscriptionSort.None),
            action = action,
            showAppSettingsButton = true, showSplitTunnelingButton = false, showLock = false
        )
    }

    private val noop = HomeCallbacks(
        onBrandTap = {}, onDiagnosticsClick = {}, onLockClick = {},
        onSplitTunnelingClick = {}, onAddClick = {}, onSettingsClick = {},
        onSortClick = {}, onFilterSelected = {}, onActionClick = {},
        onPullToRefresh = {}, onLocationSelected = {}, onLocationSettingsClick = {},
        onMeasure = {}, onRefreshSubscriptionClick = {}, onOpenUrl = {},
        onAddLocationClick = {}, onGetSubscriptionClick = {}, canPing = { true }
    )

    private fun board(
        items: List<LocationItem> = locations,
        selected: String? = "us",
        connected: Boolean = false
    ) = HomeBoard(
        model = buildBoardModel(items, null, SubscriptionSort.None) { null },
        selectedLocationId = selected,
        isConnected = connected,
        pingsState = PingsState.Success(
            mapOf("us" to 38, "de" to 29, "fr" to 44, "se" to 52, "nl-r" to 31, "jp-t" to 148)
        ),
        olcrtcSlots = slots,
        occupancyHistory = history,
        transportFilter = null,
        isRefreshingSubscriptions = false,
        refreshingSubscriptionUrl = null,
        collapsible = true,
        showSettings = false,
        showCustomLocation = false,
        showGetSubscription = false
    )

    // ── rendering ──────────────────────────────────────────────────────────

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
    private fun shoot(name: String, width: Int, height: Int, content: @Composable () -> Unit) {
        ImageComposeScene(
            width = width,
            height = height,
            density = Density(2f),
            content = { AppTheme { content() } }
        ).use { scene ->
            val image: Image = scene.render()
            File(out, "$name.png").writeBytes(image.encodeToData()!!.bytes)
        }
    }

    private fun sheet(
        name: String,
        title: String,
        subtitle: String,
        handle: Boolean = true,
        body: @Composable ColumnScope.() -> Unit
    ) = shoot(name, 402 * 2, 900 * 2) {
        Box(Modifier.fillMaxSize().background(Color(0xFF07080D))) {
            PkSheetSurface(
                title = title,
                subtitle = subtitle,
                showHandle = handle,
                modifier = Modifier.align(Alignment.BottomCenter),
                content = body
            )
        }
    }

    private fun home(name: String, w: Int, h: Int, chrome: HomeChrome, board: HomeBoard) =
        shoot(name, w, h) {
            HomeScreenContent(
                chrome = chrome,
                board = board,
                callbacks = noop,
                scrollState = rememberScrollState(),
                snackbarHostState = SnackbarHostState()
            )
        }

    @Test
    fun renderHomeStates() {
        val phoneW = 402 * 2
        val phoneH = 874 * 2

        home(
            "01-idle", phoneW, phoneH,
            chrome("idle", boardAction(false, false, false, true, false, "United States")),
            board()
        )
        home(
            "02-connected", phoneW, phoneH,
            chrome("connected", boardAction(false, true, false, true, false, "United States")),
            board(connected = true)
        )
        home(
            "03-connecting", phoneW, phoneH,
            chrome("connecting", boardAction(false, false, true, true, false, "United States")),
            board()
        )
        home(
            "04-full", phoneW, phoneH,
            chrome("full", boardAction(false, false, false, true, true, "Sweden")),
            board(selected = "se")
        )
        home(
            "05-empty", phoneW, phoneH,
            chrome("empty", PkAction("ADD SERVER LIST", PkActionKind.Go), hasRooms = false),
            board(items = emptyList(), selected = null)
        )
        home(
            "06-vless-only", phoneW, phoneH,
            chrome(
                "idle",
                boardAction(false, false, false, false, false, "Netherlands"),
                hasRooms = false
            ),
            board(items = locations.filter { it.storageId.startsWith("nl") || it.storageId.startsWith("jp") }, selected = "nl-r")
        )
        home(
            "07-android", 412 * 2, 892 * 2,
            chrome("idle", boardAction(false, false, false, true, false, "United States")),
            board()
        )
        home(
            "08-ipad", 1024 * 2, 1366 * 2,
            chrome("idle", boardAction(false, false, false, true, false, "United States")),
            board()
        )
        shoot("09-settings", phoneW, phoneH) {
            ApplicationSettingsSheet(
                updateSettings = AppUpdateSettings(),
                updateStatusText = null,
                updateDownloadProgress = null,
                updateOffer = null,
                subscriptions = emptyList(),
                logs = emptyList(),
                connectionSummary = "System VPN · olcRTC",
                connectionDetails = emptyList(),
                socksProxySettings = null,
                isConnectionActive = true,
                subscriptionSettings = SubscriptionSettings(),
                connectionModeTitle = "System VPN",
                connectionModeSummary = "All device traffic through the tunnel",
                showUpdates = false,
                onDismiss = {},
                onCopyConfigClick = {},
                onSaveLogsClick = {},
                onShareLogsClick = {},
                onUpdateIntervalSelected = {},
                onCheckUpdatesClick = {},
                onDownloadUpdateClick = {},
                onLaterUpdateClick = {},
                onSubscriptionShareClick = {},
                onSubscriptionRefreshClick = {},
                onSubscriptionDeleteClick = {}
            )
        }

        // The sheets are rendered as their surface plus their body: a real
        // ModalBottomSheet puts itself in a platform window an ImageComposeScene
        // never sees, so shooting the sheet composable produces a blank PNG.
        sheet("10-disclosure", DISCLOSURE_TITLE, DISCLOSURE_SUBTITLE, handle = false) {
            VpnDisclosureBody(onAccept = {}, onDecline = {})
        }
        sheet("11-camera", CAMERA_TITLE, CAMERA_SUBTITLE) {
            CameraRationaleBody(onAllow = {}, onDismiss = {})
        }
        sheet("12-add", ADD_TITLE, ADD_SUBTITLE) {
            AddConfigurationBody(
                canScanQr = true,
                hasSubscriptions = true,
                showCustomLocation = false,
                onScanQrClick = {},
                onPasteLinkClick = {},
                onImportFileClick = {},
                onUpdateSubscriptionsClick = {},
                onAddCustomLocationClick = {}
            )
        }

        shoot("13-onboarding", phoneW, phoneH) {
            OnboardingScreen(onFinished = {}, onAddServerList = {})
        }

        println("wrote ${out.listFiles()?.size} shots to $out")
    }

    private companion object {
        /** Fixed so a rerun produces the same picture. */
        const val FIXED_NOW = 1_770_000_000_000L
    }
}
