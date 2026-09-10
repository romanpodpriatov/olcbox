package org.olcbox.app.ui.features.home

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.olcbox.app.data.datasource.LocationsDataSource
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.exporter.LogExporter
import org.olcbox.app.data.importer.ConfigImporter
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.ImportLink
import org.olcbox.app.vpn.TrafficCounters
import org.olcbox.app.vpn.VpnManager
import org.olcbox.app.vpn.VpnStatus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomeScreenModelImportLinkTest {
    private val source = MemoryLocationsDataSource()
    private val repository = LocationsRepositoryImpl(source)

    @BeforeTest fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun restore() = Dispatchers.resetMain()

    private fun viewModel() = HomeScreenViewModel(
        vpnManager = IdleVpnManager(),
        locationsRepository = repository,
        configImporter = NoConfigImporter,
        logExporter = NoLogExporter
    )

    // The import hops to Dispatchers.IO, a real thread; virtual time would
    // race ahead of it, so the wait is measured in real seconds.
    private suspend fun awaitForReal(outcome: CompletableDeferred<String>): String =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { outcome.await() } }

    private val realityLink = "vless://11111111-1111-1111-1111-111111111111@1.2.3.4:443" +
        "?security=reality&encryption=none&pbk=PUBKEY&sid=ab12&fp=chrome&sni=www.example.com&flow=xtls-rprx-vision&type=tcp#DE"

    @Test fun anImportLinkGoesThroughTheSameImportAsAPaste() = runTest {
        val outcome = CompletableDeferred<String>()
        viewModel().onImportLink(
            uri = ImportLink.schemeLink(realityLink),
            onComplete = { outcome.complete("ok") },
            onError = { outcome.complete("error: $it") }
        )
        assertEquals("ok", awaitForReal(outcome))
        val names = repository.getAllLocations().map { it.name }
        assertEquals(listOf("DE"), names)
    }

    @Test fun somethingThatIsNotAnImportLinkIsRefusedBeforeAnyImport() = runTest {
        val outcome = CompletableDeferred<String>()
        viewModel().onImportLink(
            uri = "https://example.org/add#$realityLink",
            onComplete = { outcome.complete("ok") },
            onError = { outcome.complete("error: $it") }
        )
        assertEquals("error: Not a ProofKit import link", awaitForReal(outcome))
        assertTrue(repository.getAllLocations().isEmpty())
    }
}

private class MemoryLocationsDataSource(var stored: LocationBundleV4? = null) : LocationsDataSource {
    override suspend fun loadLocationBundle(): LocationBundleV4? = stored
    override suspend fun saveLocationBundle(bundle: LocationBundleV4) { stored = bundle }
    override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()
    override suspend fun loadLegacyActiveLocationId(): String? = null
}

private class IdleVpnManager : VpnManager {
    override val logs: StateFlow<List<String>> = MutableStateFlow(emptyList())
    override val status: StateFlow<VpnStatus> = MutableStateFlow(VpnStatus.Disconnected)
    override val isConnected: StateFlow<Boolean> = MutableStateFlow(false)
    override val connectedSince: StateFlow<Long?> = MutableStateFlow(null)
    override val traffic: StateFlow<TrafficCounters?> = MutableStateFlow(null)
    override fun needsPermission(): Boolean = false
    override fun startVpn() {}
    override fun stopVpn() {}
    override suspend fun ping(locationConfig: LocationConfig): Long? = null
    override suspend fun checkConnection(locationConfig: LocationConfig): Long? = null
}

private object NoConfigImporter : ConfigImporter {
    override fun getFromClipboard(): String? = null
    override fun copyToClipboard(text: String) {}
    override suspend fun readTextFromSource(source: Any): String? = null
}

private object NoLogExporter : LogExporter {
    override suspend fun writeLogs(target: Any, content: String): Result<String> = Result.success("")
}
