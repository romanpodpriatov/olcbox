package org.olcbox.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.ui.icons.PkIcons
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.admin.AdminState
import org.olcbox.app.data.model.SubscriptionSettings
import org.olcbox.app.data.share.SubscriptionShareItem
import org.olcbox.app.ui.components.kit.pkMaskSubscriptionUrl
import org.olcbox.app.ui.components.kit.PkBrand
import org.olcbox.app.ui.components.kit.PkSectionLabel
import org.olcbox.app.ui.components.kit.pkVersionLine
import org.olcbox.app.ui.features.home.components.LogLines
import org.olcbox.app.ui.theme.LocalPkPalette
import org.olcbox.app.update.AppUpdateInfo
import org.olcbox.app.update.AppUpdateSettings
import kotlin.time.Instant

data class ApplicationSocksProxySettings(
    val host: String = "127.0.0.1",
    val port: Int = DEFAULT_PORT,
    val username: String = "",
    val password: String = ""
) {
    companion object {
        const val DEFAULT_PORT = 10808
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
        const val MAX_CREDENTIAL_LENGTH = 64

        fun isValidPort(port: Int): Boolean = port in MIN_PORT..MAX_PORT
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApplicationSettingsSheet(
    updateSettings: AppUpdateSettings,
    updateStatusText: String?,
    updateDownloadProgress: Float?,
    updateOffer: AppUpdateInfo?,
    subscriptions: List<SubscriptionShareItem>,
    logs: List<String>,
    connectionSummary: String,
    connectionDetails: List<Pair<String, String>>,
    socksProxySettings: ApplicationSocksProxySettings? = null,
    isConnectionActive: Boolean = false,
    /**
     * How this platform actually carries traffic. The defaults describe the
     * in-app SOCKS endpoint the desktop build still uses; a platform that
     * carries the whole device through a tun says so instead, rather than
     * inheriting copy that stopped being true for it.
     */
    connectionModeTitle: String = "Proxy",
    connectionModeSummary: String = "Local SOCKS5 proxy",
    /**
     * How the platform's own tunnel component is doing, when it has one — today
     * the macOS system extension, which the user installs and approves rather
     * than receiving with the app. Null everywhere else, and the row is then
     * absent rather than disabled: a platform with no such component has nothing
     * to say about it.
     */
    tunnelExtensionSummary: String? = null,
    onTunnelExtensionClick: () -> Unit = {},
    /** How subscriptions behave. See [SubscriptionSettings]. */
    subscriptionSettings: SubscriptionSettings = SubscriptionSettings(),
    onSubscriptionSettingsChanged: (SubscriptionSettings) -> Unit = {},
    /**
     * False where the store owns updates.
     *
     * An App Store build must not check a release feed, must not offer an
     * "Update available" sheet, and must not point anyone at a download page:
     * the version numbers do not even correspond, and telling users to install
     * the app from somewhere else is grounds for rejection.
     */
    showUpdates: Boolean = true,
    onDismiss: () -> Unit,
    onCopyConfigClick: () -> Unit,
    onSaveLogsClick: () -> Unit,
    onShareLogsClick: () -> Unit,
    onUpdateIntervalSelected: (Int) -> Unit,
    onCheckUpdatesClick: () -> Unit,
    onDownloadUpdateClick: (AppUpdateInfo) -> Unit,
    onLaterUpdateClick: (AppUpdateInfo) -> Unit,
    onSubscriptionShareClick: (String) -> Unit,
    onSubscriptionRefreshClick: (String) -> Unit,
    onSubscriptionDeleteClick: (String) -> Unit = {},
    onSocksProxySettingsSaved: (String, String, Int) -> Unit = { _, _, _ -> },
    onSocksProxyPasswordRegenerated: () -> Unit = {}
) {
    // rememberModalBottomSheetState is deprecated in favour of
    // rememberBottomSheetState, which is itself alpha in the material3 this
    // project pins. Suppressed rather than migrated: swapping a sheet API
    // blind, mid-release, on a build nobody here can compile is a worse
    // trade than a warning. Migrate all four together, deliberately.
    @Suppress("DEPRECATION")
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var route by remember { mutableStateOf(SharedSettingsRoute.Hub) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        AnimatedContent(
            targetState = route,
            transitionSpec = {
                fadeIn(
                    animationSpec = tween(
                        durationMillis = 180,
                        delayMillis = 60,
                        easing = LinearOutSlowInEasing
                    )
                ).togetherWith(
                    fadeOut(
                        animationSpec = tween(
                            durationMillis = 90,
                            easing = FastOutLinearInEasing
                        )
                    )
                ).using(
                    SizeTransform(
                        clip = false,
                        sizeAnimationSpec = { _, _ ->
                            tween(
                                durationMillis = 320,
                                easing = FastOutSlowInEasing
                            )
                        }
                    )
                )
            },
            label = "sharedApplicationSettingsRoute"
        ) { currentRoute ->
            when (currentRoute) {
                SharedSettingsRoute.Hub -> SharedSettingsHubContent(
                    updateSettings = updateSettings,
                    subscriptionSettings = subscriptionSettings,
                    onSubscriptionOptionsClick = { route = SharedSettingsRoute.SubscriptionOptions },
                    showUpdates = showUpdates,
                    connectionSummary = connectionModeSummary,
                    subscriptionsCount = subscriptions.size,
                    onConnectionClick = { route = SharedSettingsRoute.Connection },
                    onSubscriptionsClick = { route = SharedSettingsRoute.Subscriptions },
                    onUpdatesClick = { route = SharedSettingsRoute.Updates },
                    onLogsClick = { route = SharedSettingsRoute.Logs }
                )

                SharedSettingsRoute.Connection -> SharedConnectionSettingsContent(
                    summary = connectionSummary,
                    details = connectionDetails,
                    modeSummary = connectionModeSummary,
                    socksProxySettings = socksProxySettings,
                    tunnelExtensionSummary = tunnelExtensionSummary,
                    onConnectionModeClick = { route = SharedSettingsRoute.ConnectionMode },
                    onSocksProxyClick = { route = SharedSettingsRoute.SocksProxy },
                    onTunnelExtensionClick = onTunnelExtensionClick,
                    onBack = { route = SharedSettingsRoute.Hub }
                )

                SharedSettingsRoute.ConnectionMode -> SharedConnectionModeSettingsContent(
                    title = connectionModeTitle,
                    summary = connectionModeSummary,
                    onBack = { route = SharedSettingsRoute.Connection }
                )

                SharedSettingsRoute.SocksProxy -> if (socksProxySettings != null) {
                    SharedSocksProxySettingsContent(
                        settings = socksProxySettings,
                        isConnectionActive = isConnectionActive,
                        onBack = { route = SharedSettingsRoute.Connection },
                        onProxySettingsSaved = onSocksProxySettingsSaved,
                        onProxyPasswordRegenerated = onSocksProxyPasswordRegenerated
                    )
                }

                SharedSettingsRoute.Subscriptions -> SharedSubscriptionsSettingsContent(
                    subscriptions = subscriptions,
                    onBack = { route = SharedSettingsRoute.Hub },
                    onCopyConfigClick = onCopyConfigClick,
                    onShareClick = onSubscriptionShareClick,
                    onRefreshClick = onSubscriptionRefreshClick,
                    onDeleteClick = onSubscriptionDeleteClick
                )

                SharedSettingsRoute.SubscriptionOptions -> SubscriptionSettingsScreen(
                    settings = subscriptionSettings,
                    onChanged = onSubscriptionSettingsChanged,
                    onBack = { route = SharedSettingsRoute.Hub }
                )

                SharedSettingsRoute.Updates -> SharedUpdatesSettingsContent(
                    settings = updateSettings,
                    statusText = updateStatusText,
                    downloadProgress = updateDownloadProgress,
                    onBack = { route = SharedSettingsRoute.Hub },
                    onIntervalSelected = onUpdateIntervalSelected,
                    onCheckUpdatesClick = onCheckUpdatesClick
                )

                SharedSettingsRoute.Logs -> SharedLogsSettingsContent(
                    logs = logs,
                    onBack = { route = SharedSettingsRoute.Hub },
                    onSaveClick = onSaveLogsClick,
                    onShareClick = onShareLogsClick
                )
            }
        }
    }
}

@Composable
private fun SharedSettingsHubContent(
    updateSettings: AppUpdateSettings,
    subscriptionSettings: SubscriptionSettings,
    onSubscriptionOptionsClick: () -> Unit,
    showUpdates: Boolean,
    connectionSummary: String,
    subscriptionsCount: Int,
    onConnectionClick: () -> Unit,
    onSubscriptionsClick: () -> Unit,
    onUpdatesClick: () -> Unit,
    onLogsClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SharedSettingsHeader(
            icon = Icons.Outlined.Settings,
            title = "Application Settings",
            subtitle = PkBrand.name
        )

        Spacer(Modifier.height(8.dp))

        SharedNavigationRow(
            title = "Connection Settings",
            value = connectionSummary,
            icon = PkIcons.Public,
            onClick = onConnectionClick
        )

        SharedNavigationRow(
            title = "Subscription Settings",
            value = subscriptionSettings.hubSummary(),
            icon = Icons.Outlined.Refresh,
            onClick = onSubscriptionOptionsClick
        )

        SharedNavigationRow(
            title = "Subscriptions & Sharing",
            value = subscriptionsCount.subscriptionSummary(),
            icon = Icons.Outlined.Share,
            onClick = onSubscriptionsClick
        )

        if (showUpdates) {
            SharedNavigationRow(
                title = "Update Settings",
                value = "Nightly · every ${updateSettings.intervalHours}h",
                icon = Icons.Outlined.Refresh,
                onClick = onUpdatesClick
            )
        }

        SharedNavigationRow(
            title = "Application Logs",
            value = "Diagnostics and export",
            icon = PkIcons.History,
            onClick = onLogsClick
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = pkVersionLine(CurrentAppInfo.value),
                style = MaterialTheme.typography.labelSmall,
                color = LocalPkPalette.current.textMuted
            )
        }
    }
}

@Composable
private fun SharedConnectionSettingsContent(
    summary: String,
    details: List<Pair<String, String>>,
    modeSummary: String,
    socksProxySettings: ApplicationSocksProxySettings?,
    tunnelExtensionSummary: String?,
    onConnectionModeClick: () -> Unit,
    onSocksProxyClick: () -> Unit,
    onTunnelExtensionClick: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 32.dp)
    ) {
        SharedDetailHeader(
            title = "Connection Settings",
            subtitle = summary,
            onBack = onBack
        )

        Spacer(Modifier.height(20.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SharedNavigationRow(
                title = "Connection Mode",
                value = modeSummary,
                icon = PkIcons.Public,
                onClick = onConnectionModeClick
            )

            // Not behind the admin gate. Installing the system extension is
            // something the person using the app has to do — macOS asks *them* to
            // approve it in System Settings — so hiding the only way to start
            // that behind seven taps would hide the app's own instructions from
            // the only person who can follow them.
            if (tunnelExtensionSummary != null) {
                SharedNavigationRow(
                    title = "Tunnel Extension",
                    value = tunnelExtensionSummary,
                    icon = PkIcons.Public,
                    onClick = onTunnelExtensionClick
                )
            }

            // Editing the local proxy credentials/port is plumbing: admin-only.
            if (socksProxySettings != null && AdminState.configuratorVisible) {
                SharedNavigationRow(
                    title = "SOCKS5 Proxy",
                    value = "${socksProxySettings.host}:${socksProxySettings.port}",
                    icon = PkIcons.Public,
                    onClick = onSocksProxyClick
                )
            }

            details
                .filterNot { (title, _) -> title.equals("Mode", ignoreCase = true) }
                .forEach { (title, value) ->
                    SharedInfoRow(title = title, value = value)
                }
        }
    }
}

@Composable
private fun SharedConnectionModeSettingsContent(
    title: String,
    summary: String,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        SharedDetailHeader(
            title = "Connection Mode",
            subtitle = summary,
            onBack = onBack
        )

        Spacer(Modifier.height(20.dp))

        SharedSelectableSettingsCard(
            selected = true,
            icon = PkIcons.Public,
            title = title,
            subtitle = summary
        )
    }
}

@Composable
private fun SharedSocksProxySettingsContent(
    settings: ApplicationSocksProxySettings,
    isConnectionActive: Boolean,
    onBack: () -> Unit,
    onProxySettingsSaved: (String, String, Int) -> Unit,
    onProxyPasswordRegenerated: () -> Unit
) {
    var editedHost by remember(settings.host) { mutableStateOf(settings.host) }
    var editedPort by remember(settings.port) { mutableStateOf(settings.port.toString()) }
    var editedUsername by remember(settings.username) { mutableStateOf(settings.username) }
    var editedPassword by remember(settings.password) { mutableStateOf(settings.password) }
    val parsedPort = editedPort.toIntOrNull()
    val hostValid = editedHost.isNotBlank()
    val portValid = parsedPort != null && ApplicationSocksProxySettings.isValidPort(parsedPort)
    val portChanged = parsedPort != null && parsedPort != settings.port
    val usernameChanged = editedUsername != settings.username
    val passwordChanged = editedPassword != settings.password
    val settingsChanged = portChanged || usernameChanged || passwordChanged
    val canSave = hostValid &&
            portValid &&
            editedUsername.isNotBlank() &&
            editedPassword.isNotBlank() &&
            settingsChanged

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        SharedDetailHeader(
            title = "SOCKS5 Proxy",
            subtitle = settings.host,
            onBack = onBack
        )

        Spacer(Modifier.height(20.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SharedSectionLabel("Endpoint")

                SharedSocksProxyTextField(
                    value = editedHost,
                    onValueChange = { value ->
                        editedHost = value
                            .replace("\r", "")
                            .replace("\n", "")
                            .trim()
                    },
                    label = "Listen address",
                    placeholder = "127.0.0.1",
                    enabled = false,
                    isError = !hostValid,
                    leadingIcon = PkIcons.Public,
                    supportingText = if (!hostValid) "Listen address is required" else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )

                SharedSocksProxyTextField(
                    value = editedPort,
                    onValueChange = { value ->
                        editedPort = value.filter { it.isDigit() }.take(5)
                    },
                    label = "Port",
                    placeholder = ApplicationSocksProxySettings.DEFAULT_PORT.toString(),
                    enabled = true,
                    isError = editedPort.isBlank() || !portValid,
                    leadingIcon = PkIcons.Public,
                    supportingText = when {
                        editedPort.isBlank() -> "Port is required"
                        !portValid -> "Use ${ApplicationSocksProxySettings.MIN_PORT}-${ApplicationSocksProxySettings.MAX_PORT}"
                        portChanged && isConnectionActive -> "Saving restarts the active connection"
                        portChanged -> "Unsaved change"
                        else -> null
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    )
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SharedSectionLabel("Credentials")

                SharedSocksProxyTextField(
                    value = editedUsername,
                    onValueChange = { editedUsername = it.take(ApplicationSocksProxySettings.MAX_CREDENTIAL_LENGTH) },
                    label = "Username",
                    placeholder = "olcbox...",
                    enabled = true,
                    isError = editedUsername.isBlank(),
                    leadingIcon = Icons.Rounded.Person,
                    supportingText = when {
                        editedUsername.isBlank() -> "Username is required"
                        usernameChanged && isConnectionActive -> "Saving restarts the active connection"
                        usernameChanged -> "Unsaved change"
                        else -> null
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
                SharedSocksProxyTextField(
                    value = editedPassword,
                    onValueChange = { editedPassword = it.take(ApplicationSocksProxySettings.MAX_CREDENTIAL_LENGTH) },
                    label = "Password",
                    placeholder = "Generated password",
                    enabled = true,
                    isError = editedPassword.isBlank(),
                    leadingIcon = PkIcons.Key,
                    supportingText = when {
                        editedPassword.isBlank() -> "Password is required"
                        passwordChanged && isConnectionActive -> "Saving restarts the active connection"
                        passwordChanged -> "Unsaved change"
                        else -> null
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onProxyPasswordRegenerated) {
                    Text("Regenerate password")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = canSave,
                    onClick = {
                        onProxySettingsSaved(
                            editedUsername,
                            editedPassword,
                            parsedPort ?: settings.port
                        )
                    }
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Save")
                }
            }
        }
    }
}

@Composable
private fun SharedSocksProxyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    enabled: Boolean,
    isError: Boolean,
    leadingIcon: ImageVector,
    supportingText: String?,
    keyboardOptions: KeyboardOptions
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        isError = isError,
        leadingIcon = { Icon(leadingIcon, contentDescription = null) },
        supportingText = supportingText?.let { { Text(it) } },
        keyboardOptions = keyboardOptions
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharedUpdatesSettingsContent(
    settings: AppUpdateSettings,
    statusText: String?,
    downloadProgress: Float?,
    onBack: () -> Unit,
    onIntervalSelected: (Int) -> Unit,
    onCheckUpdatesClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 12.dp)
    ) {
        SharedDetailHeader(
            title = "Updates",
            subtitle = "Current version ${CurrentAppInfo.value.version}",
            onBack = onBack
        )

        Spacer(Modifier.height(18.dp))

        SharedSectionLabel("Check Interval")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppUpdateSettings.INTERVAL_PRESETS.forEach { hours ->
                FilterChip(
                    selected = settings.intervalHours == hours,
                    onClick = { onIntervalSelected(hours) },
                    label = { Text("${hours}h") }
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Last check",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = settings.lastCheckAtEpochMs?.formatEpochMs() ?: "Not checked yet",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!statusText.isNullOrBlank()) {
                    Text(
                        text = statusText,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (downloadProgress != null) {
                    LinearProgressIndicator(
                        progress = { downloadProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        Button(
            onClick = onCheckUpdatesClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text("Check now")
        }
    }
}

@Composable
private fun SharedSubscriptionsSettingsContent(
    subscriptions: List<SubscriptionShareItem>,
    onBack: () -> Unit,
    onCopyConfigClick: () -> Unit,
    onShareClick: (String) -> Unit,
    onRefreshClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 620.dp)
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 12.dp)
    ) {
        SharedDetailHeader(
            title = "Subscriptions & Sharing",
            subtitle = subscriptions.size.subscriptionSummary(),
            onBack = onBack
        )

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 500.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SharedSectionLabel("Current Config")

            SharedNavigationRow(
                title = "Copy Full Config",
                value = "Backup all locations to clipboard",
                icon = PkIcons.ContentPaste,
                showChevron = false,
                onClick = onCopyConfigClick
            )

            SharedSectionLabel("Subscriptions")

            if (subscriptions.isEmpty()) {
                SharedEmptyState(
                    title = "No subscriptions",
                    subtitle = "Imported HTTPS subscriptions will appear here."
                )
            } else {
                subscriptions.forEach { item ->
                    SharedSubscriptionRow(
                        item = item,
                        onShareClick = { onShareClick(item.url) },
                        onRefreshClick = { onRefreshClick(item.url) },
                        onDeleteClick = { onDeleteClick(item.url) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SharedLogsSettingsContent(
    logs: List<String>,
    onBack: () -> Unit,
    onSaveClick: () -> Unit,
    onShareClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.8f)
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SharedDetailHeader(
                title = "Application Logs",
                subtitle = if (logs.isEmpty()) "No entries" else "${logs.size} entries",
                onBack = onBack,
                modifier = Modifier.weight(1f)
            )

            TextButton(
                enabled = logs.isNotEmpty(),
                onClick = onSaveClick
            ) {
                Text("Save")
            }
            TextButton(
                enabled = logs.isNotEmpty(),
                onClick = onShareClick
            ) {
                Text("Share")
            }
        }

        Spacer(Modifier.height(16.dp))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            LogLines(
                logs = logs,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(14.dp)
            )
        }
    }
}

@Composable
private fun SharedUpdateOfferCard(
    offer: AppUpdateInfo,
    onDownload: () -> Unit,
    onLater: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Update available",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${offer.version} · ${offer.asset.name}",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onLater) {
                    Text("Later")
                }
                Button(onClick = onDownload) {
                    Text("Download")
                }
            }
        }
    }
}

@Composable
private fun SharedSubscriptionRow(
    item: SubscriptionShareItem,
    onShareClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove subscription?") },
            text = {
                Text(
                    "${item.name} and its ${item.locationCount} location(s) will be " +
                        "removed from this device. You can add the subscription again later."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDeleteClick()
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = item.name,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = pkMaskSubscriptionUrl(item.url),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.subscriptionSummary(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onShareClick) {
                    Text("QR/share")
                }
                TextButton(onClick = onRefreshClick) {
                    Text("Refresh")
                }
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun SharedNavigationRow(
    title: String,
    value: String,
    icon: ImageVector,
    enabled: Boolean = true,
    showChevron: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = value,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (showChevron) {
                Icon(
                    imageVector = PkIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun SharedInfoRow(
    title: String,
    value: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun SharedSelectableSettingsCard(
    selected: Boolean,
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(82.dp),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun SharedSettingsHeader(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(46.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(11.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SharedDetailHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(46.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back"
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SharedSectionLabel(text: String) {
    Box(modifier = Modifier.padding(start = 2.dp)) {
        PkSectionLabel(text)
    }
}

@Composable
private fun SharedEmptyState(
    title: String,
    subtitle: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(128.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
    }
}

private enum class SharedSettingsRoute {
    Hub,
    Connection,
    ConnectionMode,
    Subscriptions,
    SubscriptionOptions,
    Updates,
    Logs,
    SocksProxy
}

private fun Int.subscriptionSummary(): String {
    return when (this) {
        0 -> "No HTTPS subscriptions"
        1 -> "1 HTTPS subscription"
        else -> "$this HTTPS subscriptions"
    }
}

private fun SubscriptionShareItem.subscriptionSummary(): String {
    val interval = updateIntervalHours?.let { "every ${it}h" } ?: "default interval"
    val count = when (locationCount) {
        1 -> "1 location"
        else -> "$locationCount locations"
    }
    val refresh = lastRefreshAtEpochMs?.let { "last refresh ${it.formatEpochMs()}" } ?: "not refreshed yet"
    return "$interval · $count · $refresh"
}

private fun Long.formatEpochMs(): String {
    return runCatching {
        Instant.fromEpochMilliseconds(this).toString()
            .substringBefore('.')
            .replace('T', ' ')
    }.getOrElse {
        toString()
    }
}
