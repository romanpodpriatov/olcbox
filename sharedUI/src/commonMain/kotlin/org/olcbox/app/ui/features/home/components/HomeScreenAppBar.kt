package org.olcbox.app.ui.features.home.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.text.style.TextOverflow
import org.olcbox.app.ui.icons.PkIcons
import org.olcbox.app.admin.AdminState
import org.olcbox.app.ui.components.AdminPasswordDialog
import org.olcbox.app.ui.components.kit.PkBrand

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreenAppBar(
    onHistoryClick: () -> Unit = {},
    showAppSettingsButton: Boolean = false,
    showHistoryButton: Boolean = true,
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
                    text = PkBrand.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = PkBrand.tagline,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Belt for the short tagline: the slot narrows with every
                    // action icon a platform adds, so a line that fits today can
                    // wrap on a build nobody thought was touching this screen.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        // Settings on the left, everything else on the right. These used to
        // share one slot through a `when`, so asking for both — which every
        // platform that shows settings does — silently dropped the history
        // button and there was no way to reach the log from the main screen.
        navigationIcon = {
            if (showAppSettingsButton) {
                IconButton(onClick = onAppSettingsClick) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Application settings",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        actions = {
            if (showHistoryButton) {
                IconButton(onClick = onHistoryClick) {
                    Icon(
                        imageVector = PkIcons.History,
                        contentDescription = "History",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            if (AdminState.showLock) {
                IconButton(onClick = { AdminState.lock() }) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = "Lock admin",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            if (showSplitTunnelingButton) {
                IconButton(onClick = onSplitTunnelingClick) {
                    Icon(
                        imageVector = PkIcons.Shield,
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
