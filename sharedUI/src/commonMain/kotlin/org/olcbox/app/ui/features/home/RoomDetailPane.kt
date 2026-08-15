package org.olcbox.app.ui.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.ui.components.kit.PkSeats
import org.olcbox.app.ui.components.kit.PkSectionEyebrow
import org.olcbox.app.ui.components.kit.PkSparkline
import org.olcbox.app.ui.components.kit.SeatDisplay
import org.olcbox.app.ui.components.kit.pkMono
import org.olcbox.app.ui.components.kit.pkPingColor
import org.olcbox.app.ui.components.kit.seatCountText
import org.olcbox.app.ui.components.kit.seatDisplay
import org.olcbox.app.ui.components.kit.seatFreeText
import org.olcbox.app.ui.components.kit.transportTag
import org.olcbox.app.ui.components.kit.wireShape
import org.olcbox.app.ui.features.home.components.isChecking
import org.olcbox.app.ui.features.home.components.locationDisplayParts
import org.olcbox.app.ui.features.home.components.pingFor
import org.olcbox.app.ui.theme.LocalPkPalette

/**
 * The selected room, given a whole pane.
 *
 * Only ever seen beside the board, on a screen wide enough that stretching the
 * phone layout across it reads as a template rather than a design — which is
 * exactly the reading App Review gave the app, on an iPad Air.
 *
 * It says the same things the selected card says. That is the point: a wider
 * screen should show more of one thing, not the same thing further apart.
 */
@Composable
fun RoomDetailPane(
    board: HomeBoard,
    callbacks: HomeCallbacks,
    modifier: Modifier = Modifier
) {
    val palette = LocalPkPalette.current
    val selected = board.model.subscriptionGroups
        .flatMap { it.locations }
        .plus(board.model.customLocations)
        .firstOrNull { it.storageId == board.selectedLocationId }

    if (selected == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Pick a room on the left",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textMuted
            )
        }
        return
    }

    val (emoji, name) = locationDisplayParts(selected)
    val config = selected.config
    val slots = board.olcrtcSlots[selected.storageId]
    val seats = seatDisplay(slots, mine = board.isConnected)
    val ping = board.pingsState.pingFor(selected.storageId)
    val measuring = board.pingsState.isChecking(selected.storageId)

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PkSectionEyebrow(if (board.isConnected) "Your seat" else "Selected")

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (emoji.isNotBlank()) {
                Text(text = emoji, fontSize = 26.sp)
                Spacer(Modifier.padding(horizontal = 5.dp))
            }
            Text(
                text = name,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Text(
            text = wireShape(config).uppercase(),
            style = pkMono(10, 1.2),
            color = palette.textDim
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                DetailStat(label = "Transport", value = transportTag(config) ?: "—")
                slots?.let {
                    DetailStat(label = "Seats", value = seatCountText(it).orEmpty())
                    DetailStat(
                        label = "Free",
                        value = seatFreeText(it).orEmpty(),
                        color = when {
                            it.slots_free <= 0 -> palette.danger
                            it.slots_free <= 2 -> palette.accent2
                            else -> palette.accent
                        }
                    )
                }
                DetailStat(
                    label = "Latency",
                    value = when {
                        measuring -> "···"
                        ping != null -> "$ping ms"
                        else -> "—"
                    },
                    color = ping?.let { pkPingColor(it) } ?: palette.textMuted
                )
            }

            if (seats != SeatDisplay.None) {
                PkSeats(display = seats, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "LAST FEW MINUTES",
                        style = pkMono(9, 1.2),
                        color = palette.textMuted,
                        modifier = Modifier.weight(1f)
                    )
                    PkSparkline(
                        history = board.occupancyHistory[selected.storageId].orEmpty(),
                        mine = board.isConnected
                    )
                }
            }
        }

        if (config != null && callbacks.canPing(config)) {
            Text(
                text = if (measuring) "···" else "MEASURE",
                style = pkMono(11, 1.2),
                color = palette.link,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(onClickLabel = "Measure latency", role = Role.Button) {
                        callbacks.onMeasure(listOf(selected.storageId))
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }

        Spacer(Modifier.height(0.dp))
    }
}

@Composable
private fun DetailStat(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color? = null
) {
    val palette = LocalPkPalette.current
    Column {
        Text(text = label.uppercase(), style = pkMono(9, 1.2), color = palette.textMuted)
        Spacer(Modifier.height(5.dp))
        Text(
            text = value,
            style = pkMono(15, 0.4).copy(fontWeight = FontWeight.Medium),
            color = color ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}
