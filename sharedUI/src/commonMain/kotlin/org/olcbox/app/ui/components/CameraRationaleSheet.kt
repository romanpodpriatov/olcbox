package org.olcbox.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.olcbox.app.ui.components.kit.PkBottomSheet
import org.olcbox.app.ui.features.home.components.PkSheetButton
import org.olcbox.app.ui.theme.LocalPkPalette

/**
 * Why this app wants a camera, before the system asks.
 *
 * New. The scanner used to be launched straight from the add sheet, so the first
 * thing a user saw was the operating system's own permission prompt with no
 * explanation attached — and on iOS that prompt is the only chance there is: a
 * declined camera permission cannot be asked for twice.
 *
 * It is also the honest answer to a reasonable suspicion. A VPN client asking for
 * a camera looks like a VPN client asking for a camera, and the reason is narrow
 * enough to state in three lines.
 *
 * Declining calls nothing — [onDismiss] closes the sheet and the other three ways
 * of adding a list are untouched.
 */
@Composable
fun CameraRationaleSheet(
    onAllow: () -> Unit,
    onDismiss: () -> Unit
) {
    PkBottomSheet(
        title = CAMERA_TITLE,
        subtitle = CAMERA_SUBTITLE,
        onDismiss = onDismiss
    ) {
        CameraRationaleBody(onAllow = onAllow, onDismiss = onDismiss)
    }
}

internal const val CAMERA_TITLE = "Camera access"
internal const val CAMERA_SUBTITLE = "QR codes only"

/** The sheet's contents, separately so a test can render them. */
@Composable
internal fun CameraRationaleBody(onAllow: () -> Unit, onDismiss: () -> Unit) {
    val palette = LocalPkPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(1.dp, palette.hairline, RoundedCornerShape(16.dp))
                .padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            CAMERA_REASONS.forEach { line ->
                Row {
                    Box(
                        Modifier
                            .padding(top = 7.dp)
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(palette.accent)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        PkSheetButton(label = "Allow and scan", onClick = onAllow, primary = true)
        PkSheetButton(label = "Not now", onClick = onDismiss)
    }
}

/**
 * Three sentences, and no fourth.
 *
 * Each answers a question somebody actually has: what for, what happens to the
 * picture, and what it costs to say no.
 */
private val CAMERA_REASONS = listOf(
    "The camera is used to read a server-list QR code, and for nothing else.",
    "No photo or video is recorded, stored or uploaded. The frame is decoded and " +
        "thrown away.",
    "Declining leaves every other way of adding a list open — a link, a URI or a file."
)
