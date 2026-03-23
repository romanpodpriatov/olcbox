package org.turnbox.app.ui.activities

import android.app.Activity
import android.net.Uri
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun AndroidMainScreen(viewModel: MainActivityViewModel) {
    val context = LocalContext.current

    val vpnRequestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.ToggleVpn()
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.onFileSelected(it)
        }
    }

    MainScreenContent(
        viewModel = viewModel,
        onVpnToggleRequested = {
            val prepIntent = VpnService.prepare(context)
            if (prepIntent != null) {
                vpnRequestLauncher.launch(prepIntent)
            } else {
                viewModel.ToggleVpn()
            }
        },
        onImportFileRequested = {
            filePickerLauncher.launch("*/*")
        },
        onCopyToastRequested = {
            Toast.makeText(context, "Config copied!", Toast.LENGTH_SHORT).show()
        }
    )
}