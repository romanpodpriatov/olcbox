package org.turnbox.app.ui.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.turnbox.app.data.importer.ConfigImporter
import org.turnbox.app.data.model.HysteriaConfig
import org.turnbox.app.data.repository.HysteriaConfigRepository
import org.turnbox.app.vpn.VpnManager

class MainActivityViewModel(
    private val vpnManager: VpnManager,
    private val configRepo: HysteriaConfigRepository,
    private val configImporter: ConfigImporter
) : ViewModel() {
    private val _state = MutableStateFlow(UiState(false, HysteriaConfig(), false, emptyList()))
    val state get() = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val savedConfig = configRepo.loadConfig()
            _state.update { it.copy(configData = savedConfig) }
        }

        viewModelScope.launch {
            vpnManager.logs.collect { logs ->
                _state.update { it.copy(logs = logs) }
            }
        }

        viewModelScope.launch {
            vpnManager.isConnected.collect { connected ->
                _state.update { it.copy(isVpnConnected = connected) }
            }
        }
    }

    fun ToggleVpn() {
        if (_state.value.isVpnConnected) {
            vpnManager.stopVpn()
        } else {
            viewModelScope.launch {
                configRepo.saveConfig(state.value.configData)
                vpnManager.startVpn()
            }
        }
    }

    fun onServerChanged(value: String) = updateConfig { it.copy(server = value) }
    fun onPasswordChanged(value: String) = updateConfig { it.copy(password = value) }
    fun onSniChanged(value: String) = updateConfig { it.copy(sni = value) }
    fun onTurnEnabledChanged(value: Boolean) = updateConfig { it.copy(turnEnabled = value) }
    fun onTurnPeerChanged(value: String) = updateConfig { it.copy(turnPeer = value) }
    fun onTurnLinkChanged(value: String) = updateConfig { it.copy(turnLink = value) }
    fun onTurnUdpChanged(value: Boolean) = updateConfig { it.copy(turnUdp = value) }
    fun onTurnThreadsChanged(threads: String) {
        val n = threads.toIntOrNull() ?: 8
        updateConfig { it.copy(turnThreads = n) }
    }

    private fun updateConfig(block: (HysteriaConfig) -> HysteriaConfig) {
        _state.update {it.copy(configData = block(it.configData))}
    }

    fun onConfigConfirmed() {
        if (isUserConfigValid()) {
            viewModelScope.launch {
                configRepo.saveConfig(_state.value.configData)
            }
        } else {
            _state.update { it.copy(shouldShowConfigInvalidReminder = true) }
        }
    }

    fun onConfigInvalidReminderDismissed() {
        _state.update { it.copy(shouldShowConfigInvalidReminder = false) }
    }

    private fun onConfigDataChanged(newConfigData: HysteriaConfig) {
        _state.update {
            it.copy(configData = newConfigData)
        }
    }

    fun onCopyFullConfigClicked() {
        val fullData = state.value.configData.toJsonConfig()
        configImporter.copyToClipboard(fullData)
    }

    fun onPasteFromClipboard() {
        configImporter.getFromClipboard()?.let { text ->
            onImportFullConfig(text)
        }
    }

    fun onFileSelected(fileSource: Any) {
        viewModelScope.launch {
            configImporter.readTextFromSource(fileSource)?.let { text ->
                onImportFullConfig(text)
            }
        }
    }

    private fun isUserConfigValid(): Boolean {
        val config = _state.value.configData
        if (config.turnEnabled) {
            return config.turnPeer.isNotBlank() && config.turnLink.isNotBlank() && config.password.isNotBlank()
        }
        return config.server.isNotBlank() && config.password.isNotBlank()
    }

    fun onRawConfigImported(rawText: String) {
        if (rawText.isBlank()) return
        viewModelScope.launch {
            configRepo.importRawConfig(rawText)
            _state.update { it.copy(shouldShowConfigInvalidReminder = false) }
        }
    }

    fun onImportFullConfig(rawText: String) {
        if (rawText.isBlank()) return
        viewModelScope.launch {
            try {
                configRepo.importRawConfig(rawText)
                val importedConfig = configRepo.loadConfig()
                _state.update { it.copy(configData = importedConfig) }
            } catch (e: Exception) {
                // add error to state
            }
        }
    }
}

data class UiState(
    val isVpnConnected: Boolean,
    val configData: HysteriaConfig,
    val shouldShowConfigInvalidReminder: Boolean,
    val logs: List<String> = emptyList()
)
