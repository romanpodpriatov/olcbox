package org.olcbox.app.ui.features.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.olcbox.app.ui.components.kit.PkCardSunken
import org.olcbox.app.ui.theme.LocalPkPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsSheet(
    logs: List<String>,
    onSaveClick: () -> Unit,
    onShareClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    val scope = rememberCoroutineScope()

    val closeSheet = {
        scope.launch {
            sheetState.hide()
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        LogsContent(
            logs = logs,
            modifier = Modifier.fillMaxHeight(0.8f),
            onSaveClick = onSaveClick,
            onShareClick = onShareClick,
            onCloseClick = { closeSheet() }
        )
    }
}

@Composable
fun LogsContent(
    logs: List<String>,
    modifier: Modifier = Modifier,
    onSaveClick: () -> Unit,
    onShareClick: () -> Unit,
    onCloseClick: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Application Logs",
                style = MaterialTheme.typography.headlineSmall,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
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

                IconButton(onClick = onCloseClick) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close logs",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        PkCardSunken(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            LogLines(
                logs = logs,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp)
            )
        }
    }
}

@Composable
fun LogLines(
    logs: List<String>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.scrollToItem(logs.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = contentPadding
    ) {
        itemsIndexed(
            items = logs,
            key = { index, log -> "$index:$log" }
        ) { _, log ->
            Text(
                text = log,
                style = MaterialTheme.typography.labelSmall,
                color = getLogColor(log),
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun getLogColor(log: String): Color {
    val pk = LocalPkPalette.current
    return when {
        log.contains("❌") || log.contains("Error") -> pk.danger
        log.contains("✅") || log.contains("OK") -> pk.success
        log.contains("⚠️") -> pk.warning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
