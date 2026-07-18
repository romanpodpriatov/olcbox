package org.olcbox.app.ui.features.home.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.olcbox.app.admin.AdminState
import org.olcbox.app.ui.components.AdminPasswordDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreenAppBar(
    onHistoryClick: () -> Unit = {},
    showAppSettingsButton: Boolean = false,
    onAppSettingsClick: () -> Unit = {},
    showSplitTunnelingButton: Boolean = false,
    onSplitTunnelingClick: () -> Unit = {},
    onAddClick: () -> Unit = {}
) {
    var showAdminDialog by remember { mutableStateOf(false) }
    val tapInteraction = remember { MutableInteractionSource() }

    CenterAlignedTopAppBar(
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable(
                    interactionSource = tapInteraction,
                    indication = null
                ) {
                    // Hidden admin gesture: 7 taps on the title within ~3s.
                    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                    if (AdminState.registerTitleTap(now)) showAdminDialog = true
                }
            ) {
                Text(
                    text = "olcbox",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "multiplatform olcrtc configurator",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        navigationIcon = {
            if (showAppSettingsButton) {
                IconButton(onClick = onAppSettingsClick) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Application settings",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            } else {
                IconButton(onClick = onHistoryClick) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = "History",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        actions = {
            if (showSplitTunnelingButton) {
                IconButton(onClick = onSplitTunnelingClick) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = "Split tunneling",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            IconButton(onClick = onAddClick) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = "Add configuration",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    )

    if (showAdminDialog) {
        AdminPasswordDialog(
            onDismiss = { showAdminDialog = false },
            onSubmit = { AdminState.tryUnlock(it) },
        )
    }
}
