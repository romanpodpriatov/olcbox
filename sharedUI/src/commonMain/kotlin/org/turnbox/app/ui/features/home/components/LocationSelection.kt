package org.turnbox.app.ui.features.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.turnbox.app.ui.features.locations.LocationItem
import org.turnbox.app.ui.features.locations.PingsState
import org.turnbox.app.ui.features.locations.components.LocationRow
import org.turnbox.app.ui.features.locations.components.RefreshButton

@Composable
fun LocationSelectorScreen(
    modifier: Modifier = Modifier,
    onRefreshClick: () -> Unit,
    onAddLocationClick: () -> Unit,
    locations: List<LocationItem>,
    selectedLocationId: String?,
    pingsState: PingsState,
    onLocationSelected: (String) -> Unit,
    onLocationSettingsClick: (String) -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Public,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "LOCATION",
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            RefreshButton(
                state = pingsState,
                onClick = onRefreshClick,
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (locations.isEmpty()) {
            Surface(
                onClick = onAddLocationClick,
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                tonalElevation = 2.dp,
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(8.dp)
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    Text(
                        text = "Add Your First Location",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                locations.forEach { location ->
                    val pingMs = (pingsState as? PingsState.Success)?.pings?.get(location.storageId)
                        ?: (pingsState as? PingsState.Loading)?.lastPings?.get(location.storageId)
                    val isOffline = pingsState is PingsState.Success && pingMs == null

                    LocationRow(
                        location = location,
                        isSelected = selectedLocationId == location.storageId,
                        isLoading = pingsState is PingsState.Loading,
                        isError = isOffline,
                        pingMs = pingMs,
                        onSettingsClick = { onLocationSettingsClick(location.storageId) },
                        onClick = { onLocationSelected(location.storageId) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            FilledTonalButton(
                onClick = onAddLocationClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Add Custom Location",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}