package org.turnbox.app.ui.activities

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.turnbox.app.data.model.HysteriaConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreenContent(
    viewModel: MainActivityViewModel,
    onVpnToggleRequested: () -> Unit,
    onImportFileRequested: () -> Unit,
    onCopyToastRequested: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Turnbox", style = MaterialTheme.typography.headlineSmall) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // --- СЕКЦИЯ СТАТУСА ---
            StatusCard(
                isConnected = state.isVpnConnected,
                onStart = onVpnToggleRequested,
                onStop = onVpnToggleRequested
            )

            // --- КНОПКИ ИМПОРТА/КОПИРОВАНИЯ ---
            ImportExportRow(
                onImportClipboard = { viewModel.onPasteFromClipboard() },
                onImportFile = onImportFileRequested,
                onCopyConfig = { 
                    viewModel.onCopyFullConfigClicked()
                    onCopyToastRequested()
                }
            )

            // --- СЕКЦИЯ НАСТРОЕК ---
            ConfigEditSection(
                config = state.configData,
                viewModel = viewModel
            )

            // --- ЛОГИ ---
            LogSection(
                logs = state.logs,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 150.dp, max = 300.dp)
            )
        }
    }

    // Alert Dialog для невалидного конфига
    if (state.shouldShowConfigInvalidReminder) {
        AlertDialog(
            onDismissRequest = { viewModel.onConfigInvalidReminderDismissed() },
            confirmButton = {
                TextButton(onClick = { viewModel.onConfigInvalidReminderDismissed() }) {
                    Text("OK")
                }
            },
            title = { Text("Invalid Config") },
            text = { Text("Configuration data is incomplete.") }
        )
    }
}

@Composable
fun StatusCard(isConnected: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isConnected)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = if (isConnected) Icons.Default.Shield else Icons.Default.ShieldMoon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isConnected) "VPN CONNECTED" else "VPN DISCONNECTED",
                style = MaterialTheme.typography.titleMedium,
                color = if (isConnected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = if (isConnected) onStop else onStart,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(if (isConnected) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isConnected) "STOP SERVICE" else "START SERVICE")
            }
        }
    }
}

@Composable
fun ConfigEditSection(config: HysteriaConfig, viewModel: MainActivityViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Configuration", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

        OutlinedField(
            value = config.server,
            onValueChange = viewModel::onServerChanged,
            label = "Hysteria Server",
            placeholder = "IP:Port",
            icon = Icons.Default.Dns,
            enabled = !config.turnEnabled
        )

        OutlinedField(
            value = config.password,
            onValueChange = viewModel::onPasswordChanged,
            label = "Password",
            placeholder = "Auth key",
            icon = Icons.Default.Lock
        )

        OutlinedField(
            value = config.sni,
            onValueChange = viewModel::onSniChanged,
            label = "SNI",
            placeholder = "example.com",
            icon = Icons.Default.Language
        )

        // --- TURN RELAY CARD ---
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AltRoute, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Use VK TURN Relay", style = MaterialTheme.typography.bodyLarge)
                    }
                    Switch(checked = config.turnEnabled, onCheckedChange = viewModel::onTurnEnabledChanged)
                }

                AnimatedVisibility(
                    visible = config.turnEnabled,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier.padding(top = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedField(
                            value = config.turnPeer,
                            onValueChange = viewModel::onTurnPeerChanged,
                            label = "TURN Destination",
                            placeholder = "Real Server IP:Port"
                        )
                        OutlinedField(
                            value = config.turnLink,
                            onValueChange = viewModel::onTurnLinkChanged,
                            label = "Call Link",
                            placeholder = "VK/Yandex link"
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = config.turnUdp, onCheckedChange = viewModel::onTurnUdpChanged)
                            Text("Use UDP for TURN", style = MaterialTheme.typography.bodyMedium)
                        }

                        OutlinedField(
                            value = config.turnThreads.toString(),
                            onValueChange = viewModel::onTurnThreadsChanged,
                            label = "Threads",
                            icon = Icons.Default.SettingsSuggest
                        )
                    }
                }
            }
        }

        Button(
            onClick = viewModel::onConfigConfirmed,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Save, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("SAVE CONFIGURATION")
        }
    }
}

@Composable
fun OutlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        leadingIcon = icon?.let { { Icon(it, contentDescription = null) } },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        singleLine = true,
        enabled = enabled,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
        )
    )
}

@Composable
fun LogSection(logs: List<String>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("System Logs", style = MaterialTheme.typography.labelLarge)
            Text("${logs.size} lines", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }

        Surface(
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxSize(),
            color = Color(0xFF1C1C1C), // Темный фон терминала
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(12.dp)
            ) {
                items(logs) { log ->
                    Text(
                        text = log,
                        color = if (log.contains("✅") || log.contains("OK")) Color(0xFF81C784)
                        else if (log.contains("❌") || log.contains("error") || log.contains("failed")) Color(0xFFE57373)
                        else Color.LightGray,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ImportExportRow(
    onImportClipboard: () -> Unit,
    onImportFile: () -> Unit,
    onCopyConfig: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onImportClipboard,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Import", fontSize = 12.sp)
            }

            OutlinedButton(
                onClick = onImportFile,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("File", fontSize = 12.sp)
            }
        }

        Button(
            onClick = onCopyConfig,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Copy Current Config to Clipboard")
        }
    }
}
