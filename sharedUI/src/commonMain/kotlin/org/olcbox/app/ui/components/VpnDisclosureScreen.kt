package org.olcbox.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * What the system VPN does here, said once and accepted deliberately.
 *
 * Google Play requires this of anything whose core function is a VPN, and the
 * requirement is narrower than a privacy notice: it has to be in the app rather
 * than the listing or the policy page, it has to appear in the ordinary course
 * of using the app rather than somewhere in settings, it has to name what the
 * VpnService API handles, and it has to be accepted by a deliberate action.
 * It also may not be combined with any other consent — this screen is about the
 * tunnel and nothing else, which is why it is not part of onboarding.
 *
 * Shown on every platform, not only the one that demands it. The behaviour is
 * the same everywhere and a screen that exists on one platform only is a screen
 * that stops matching the others the first time either is touched.
 *
 * Declining is a real outcome, not a dismissal: there is no close affordance and
 * no dismiss on back or outside tap, because "carry on without answering" is
 * exactly what the policy forbids and what a reviewer looks for.
 */
@Composable
fun VpnDisclosureScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDecline,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        )
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "How the VPN connection works",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(16.dp))
                Column(
                    // Bounded and scrollable so the whole text can be read on a
                    // small screen — and so it can be scrolled through slowly on
                    // camera, which is what the Play declaration video has to show.
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = DISCLOSURE_BODY,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDecline) { Text("Not now") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onAccept) { Text("I understand") }
                }
            }
        }
    }
}

/**
 * Deliberately specific about what the tunnel does rather than about privacy in
 * general. A reviewer is checking that the user was told what happens to their
 * traffic, and a paragraph of reassurance that never mentions it does not answer
 * that.
 *
 * **This is commonMain and iOS renders it too.** It was written for Play's
 * prominent disclosure and said "Android will ask you", which is what an iPhone
 * showed until 2026-08-14 — keep the wording platform-neutral, or split it
 * properly per platform, but do not name one of them here.
 *
 * **Do not write "subscription" in this text.** Every other user-visible string
 * says "server list"; the word means a configuration feed to us and a monthly
 * charge to App Review, and it cost two rejections under Guideline 3.1.1. Wire
 * and storage names keep the old spelling on purpose — this is about what a user
 * reads.
 */
private const val DISCLOSURE_BODY = """ProofKit connects using your device's system VPN.

WHAT IT DOES

While connected, network traffic from this device is routed through the server you selected from your own server list. This is what a VPN is, and it is the only reason this app asks for VPN permission.

Your device will ask you to allow this separately, in its own dialog, the first time you connect. You can end the connection at any time from this app or from system settings.

WHAT THIS APP DOES WITH IT

Nothing is collected. The app does not read, record or transmit the contents of your traffic, the addresses you visit, or anything identifying you. There is no account here, no analytics, and no advertising identifier.

Your server lists and the app's own log stay on this device.

WHERE YOUR TRAFFIC GOES

To the server you selected, and nowhere else. That server is operated by whoever gave you the server list, and what it does with your traffic is governed by them — ProofKit does not run it and does not sell one.

Traffic is encrypted between this device and that server.

Traffic is never redirected for advertising, and never routed anywhere for any purpose other than the connection you asked for."""
