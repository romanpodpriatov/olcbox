package org.turnbox.app.ui.activities

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.turnbox.app.vpn.AndroidConnectionMode
import org.turnbox.app.vpn.AndroidSocksProxySettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppSettingsSheet(
    selectedMode: AndroidConnectionMode,
    proxySettings: AndroidSocksProxySettings,
    enabled: Boolean,
    isConnectionActive: Boolean,
    onDismiss: () -> Unit,
    onApplicationLogsClick: () -> Unit,
    onModeSelected: (AndroidConnectionMode) -> Unit,
    onProxyPasswordSaved: (String) -> Unit,
    onProxyPasswordRegenerated: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var route by remember { mutableStateOf<AppSettingsRoute>(AppSettingsRoute.Hub) }

    fun closeSheet(afterClose: () -> Unit = {}) {
        scope.launch {
            sheetState.hide()
            onDismiss()
            afterClose()
        }
    }

    ModalBottomSheet(
        onDismissRequest = { closeSheet() },
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        AnimatedContent(
            targetState = route,
            transitionSpec = {
                val forward = targetState.depth > initialState.depth
                val enterOffset: (Int) -> Int = { width -> if (forward) width / 3 else -width / 3 }
                val exitOffset: (Int) -> Int = { width -> if (forward) -width / 3 else width / 3 }

                (slideInHorizontally(initialOffsetX = enterOffset) + fadeIn())
                    .togetherWith(slideOutHorizontally(targetOffsetX = exitOffset) + fadeOut())
            },
            label = "appSettingsRoute"
        ) { currentRoute ->
            when (currentRoute) {
                AppSettingsRoute.Hub -> AppSettingsHubContent(
                    selectedMode = selectedMode,
                    proxySettings = proxySettings,
                    enabled = enabled,
                    onConnectionModeClick = { route = AppSettingsRoute.ConnectionMode },
                    onProxySettingsClick = { route = AppSettingsRoute.SocksProxy },
                    onSplitTunnelingClick = { route = AppSettingsRoute.SplitTunneling },
                    onApplicationLogsClick = { closeSheet(onApplicationLogsClick) }
                )

                AppSettingsRoute.ConnectionMode -> ConnectionModeSettingsContent(
                    selectedMode = selectedMode,
                    enabled = enabled,
                    onBack = { route = AppSettingsRoute.Hub },
                    onModeSelected = onModeSelected
                )

                AppSettingsRoute.SocksProxy -> SocksProxySettingsContent(
                    proxySettings = proxySettings,
                    enabled = enabled,
                    isConnectionActive = isConnectionActive,
                    onBack = { route = AppSettingsRoute.Hub },
                    onProxyPasswordSaved = onProxyPasswordSaved,
                    onProxyPasswordRegenerated = onProxyPasswordRegenerated
                )

                AppSettingsRoute.SplitTunneling -> SplitTunnelingSettingsContent(
                    onBack = { route = AppSettingsRoute.Hub }
                )
            }
        }
    }
}

@Composable
private fun AppSettingsHubContent(
    selectedMode: AndroidConnectionMode,
    proxySettings: AndroidSocksProxySettings,
    enabled: Boolean,
    onConnectionModeClick: () -> Unit,
    onProxySettingsClick: () -> Unit,
    onSplitTunnelingClick: () -> Unit,
    onApplicationLogsClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        SettingsSheetHeader(
            icon = Icons.Outlined.Settings,
            title = "Application Settings",
            subtitle = selectedMode.shortLabel()
        )

        Spacer(Modifier.height(20.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsNavigationRow(
                title = "Connection Mode",
                value = selectedMode.settingsSummary(),
                icon = selectedMode.icon(),
                enabled = enabled,
                onClick = onConnectionModeClick
            )
            SettingsNavigationRow(
                title = "SOCKS5 Proxy",
                value = "${proxySettings.host}:${proxySettings.port}",
                icon = Icons.Rounded.Public,
                enabled = enabled,
                onClick = onProxySettingsClick
            )
            SettingsNavigationRow(
                title = "Split Tunneling",
                value = "App routing rules",
                icon = Icons.Outlined.Settings,
                enabled = enabled,
                onClick = onSplitTunnelingClick
            )
            SettingsNavigationRow(
                title = "Application Logs",
                value = "Diagnostics and export",
                icon = Icons.Outlined.History,
                enabled = true,
                onClick = onApplicationLogsClick
            )
        }
    }
}

@Composable
private fun ConnectionModeSettingsContent(
    selectedMode: AndroidConnectionMode,
    enabled: Boolean,
    onBack: () -> Unit,
    onModeSelected: (AndroidConnectionMode) -> Unit
) {
    val options = listOf(AndroidConnectionMode.Tun, AndroidConnectionMode.Proxy)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        SettingsDetailHeader(
            title = "Connection Mode",
            subtitle = selectedMode.subtitle(),
            onBack = onBack
        )

        Spacer(Modifier.height(20.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            options.forEach { mode ->
                ConnectionModeOption(
                    mode = mode,
                    selected = selectedMode == mode,
                    enabled = enabled,
                    onClick = { onModeSelected(mode) }
                )
            }
        }
    }
}

@Composable
private fun SocksProxySettingsContent(
    proxySettings: AndroidSocksProxySettings,
    enabled: Boolean,
    isConnectionActive: Boolean,
    onBack: () -> Unit,
    onProxyPasswordSaved: (String) -> Unit,
    onProxyPasswordRegenerated: () -> Unit
) {
    var editedPassword by remember(proxySettings.password) { mutableStateOf(proxySettings.password) }
    val passwordChanged = editedPassword != proxySettings.password
    val canSavePassword = editedPassword.isNotBlank() && passwordChanged && enabled

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        SettingsDetailHeader(
            title = "SOCKS5 Proxy",
            subtitle = "${proxySettings.host}:${proxySettings.port}",
            onBack = onBack
        )

        Spacer(Modifier.height(20.dp))

        SocksProxySettingsCard(
            settings = proxySettings,
            password = editedPassword,
            passwordChanged = passwordChanged,
            canSave = canSavePassword,
            enabled = enabled,
            isConnectionActive = isConnectionActive,
            onPasswordChanged = { value -> editedPassword = value.take(MAX_PROXY_PASSWORD_LENGTH) },
            onSavePassword = { onProxyPasswordSaved(editedPassword) },
            onRegeneratePassword = onProxyPasswordRegenerated
        )
    }
}

@Composable
private fun SplitTunnelingSettingsContent(
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        SettingsDetailHeader(
            title = "Split Tunneling",
            subtitle = "App routing rules",
            onBack = onBack
        )

        Spacer(Modifier.height(18.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SplitTunnelingRulePreview(
                title = "Apps Through Turnbox",
                value = "Not configured"
            )
            SplitTunnelingRulePreview(
                title = "Apps Outside Turnbox",
                value = "Not configured"
            )
        }
    }
}

@Composable
private fun SettingsNavigationRow(
    title: String,
    value: String,
    icon: ImageVector,
    enabled: Boolean,
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
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = value,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1
                )
            }

            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun SettingsSheetHeader(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HeaderIcon(icon = icon)

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
private fun SettingsDetailHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
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
private fun HeaderIcon(icon: ImageVector) {
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
}

@Composable
private fun ConnectionModeOption(
    mode: AndroidConnectionMode,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        label = "connectionModeOptionContainer"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        label = "connectionModeOptionBorder"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(82.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
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
                    imageVector = mode.icon(),
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mode.label(),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = mode.description(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1
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
private fun SocksProxySettingsCard(
    settings: AndroidSocksProxySettings,
    password: String,
    passwordChanged: Boolean,
    canSave: Boolean,
    enabled: Boolean,
    isConnectionActive: Boolean,
    onPasswordChanged: (String) -> Unit,
    onSavePassword: () -> Unit,
    onRegeneratePassword: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Public,
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${settings.host}:${settings.port}",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "User: ${settings.username}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChanged,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                label = { Text("Password") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                supportingText = {
                    Text(
                        text = when {
                            password.isBlank() -> "Password is required"
                            passwordChanged && isConnectionActive -> "Saving restarts the active connection"
                            passwordChanged -> "Unsaved change"
                            else -> "Required by default for SOCKS5 clients"
                        }
                    )
                }
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    enabled = enabled,
                    onClick = onRegeneratePassword
                ) {
                    Text("Regenerate")
                }

                Spacer(Modifier.width(8.dp))

                Button(
                    enabled = canSave,
                    onClick = onSavePassword
                ) {
                    Text("Save")
                }
            }
        }
    }
}

@Composable
private fun SplitTunnelingRulePreview(
    title: String,
    value: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
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
}

private sealed class AppSettingsRoute(val depth: Int) {
    object Hub : AppSettingsRoute(0)
    object ConnectionMode : AppSettingsRoute(1)
    object SocksProxy : AppSettingsRoute(1)
    object SplitTunneling : AppSettingsRoute(1)
}

private fun AndroidConnectionMode.label(): String {
    return when (this) {
        AndroidConnectionMode.Tun -> "TUN"
        AndroidConnectionMode.Proxy -> "Proxy"
    }
}

private fun AndroidConnectionMode.shortLabel(): String {
    return when (this) {
        AndroidConnectionMode.Tun -> "TUN"
        AndroidConnectionMode.Proxy -> "SOCKS"
    }
}

private fun AndroidConnectionMode.subtitle(): String {
    return when (this) {
        AndroidConnectionMode.Tun -> "Full tunnel"
        AndroidConnectionMode.Proxy -> "Local SOCKS5 proxy"
    }
}

private fun AndroidConnectionMode.settingsSummary(): String {
    return when (this) {
        AndroidConnectionMode.Tun -> "TUN · Full tunnel"
        AndroidConnectionMode.Proxy -> "Proxy · Local SOCKS5"
    }
}

private fun AndroidConnectionMode.description(): String {
    return when (this) {
        AndroidConnectionMode.Tun -> "System VPN interface"
        AndroidConnectionMode.Proxy -> "Local SOCKS endpoint"
    }
}

private fun AndroidConnectionMode.icon() = when (this) {
    AndroidConnectionMode.Tun -> Icons.Outlined.Shield
    AndroidConnectionMode.Proxy -> Icons.Rounded.Public
}

private const val MAX_PROXY_PASSWORD_LENGTH = 64
