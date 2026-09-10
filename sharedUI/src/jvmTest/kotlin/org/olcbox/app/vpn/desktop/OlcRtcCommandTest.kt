package org.olcbox.app.vpn.desktop

import org.olcbox.app.data.model.LocationConfig
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class OlcRtcCommandTest {

    private fun command(provider: String, transport: String) = OlcRtcCommand(
        binary = Path.of("/tmp/olcrtc"),
        location = LocationConfig(
            id = "https://meet.example/room",
            key = "ab".repeat(32),
            bypassProvider = provider,
            transport = transport
        ),
        dnsServer = "1.1.1.1:53"
    )

    /** Upstream rejects unknown keys: none of the retired blocks may appear. */
    @Test
    fun theYamlCarriesOnlyKeysTheEngineKnows() {
        val yaml = command(LocationConfig.PROVIDER_JITSI, LocationConfig.TRANSPORT_DATACHANNEL).yaml()
        for (retired in listOf("link:", "tls:", "jitsi:", "insecure", "data:")) {
            assertFalse(retired in yaml, "retired key $retired in\n$yaml")
        }
        assertContains(yaml, "mode: cnc")
        assertContains(yaml, "provider: 'jitsi'")
        assertContains(yaml, "udp:\n  enabled: true")
    }

    @Test
    fun theRoomKeyAndSocksBlockMatchInstallShShape() {
        val yaml = command(LocationConfig.PROVIDER_TELEMOST, LocationConfig.TRANSPORT_VP8CHANNEL).yaml()
        assertContains(yaml, "room:\n  id: 'https://meet.example/room'")
        assertContains(yaml, "crypto:\n  key: '${"ab".repeat(32)}'")
        assertContains(yaml, "net:\n  transport: 'vp8channel'\n  dns: '1.1.1.1:53'")
        assertContains(yaml, "socks:\n  host: '127.0.0.1'\n  port: 10808")
        assertContains(yaml, "vp8:\n  fps: ${LocationConfig.DEFAULT_VP8_FPS}\n  batch_size: ${LocationConfig.DEFAULT_VP8_BATCH}")
    }
}
