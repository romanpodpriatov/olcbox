package org.turnbox.app.vpn.desktop

import org.turnbox.app.data.model.LocationConfig
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class DesktopProxyModeTest {

    @Test
    fun pacRoutesLocalTrafficDirectAndEverythingElseThroughSocks() {
        val pac = PacServer.generatePac("127.0.0.1", 10808)

        assertContains(pac, "isPlainHostName(host)")
        assertContains(pac, "host == \"localhost\"")
        assertContains(pac, "SOCKS5 127.0.0.1:10808; SOCKS 127.0.0.1:10808")
    }

    @Test
    fun olcRtcCommandUsesLocationProviderRoomAndKey() {
        LocationConfig.supportedBypassProviders.forEach { provider ->
            val command = OlcRtcCommand(
                binary = Path.of("/tmp/olcrtc"),
                location = LocationConfig("Test", "room-$provider", "b".repeat(64), provider),
                socksHost = "127.0.0.1",
                socksPort = 10808
            ).args()

            assertEquals("/tmp/olcrtc", command[0])
            assertEquals(listOf("-mode", "cnc"), command.slice(1..2))
            assertContains(command, "-transport")
            assertContains(command, LocationConfig.TRANSPORT_VP8CHANNEL)
            assertContains(command, "-vp8-fps")
            assertContains(command, "60")
            assertContains(command, "-vp8-batch")
            assertContains(command, "8")
            assertEquals(OlcRtcCommand.desktopProviderArg(provider), command[command.indexOf("-provider") + 1])
            assertContains(command, "room-$provider")
            assertContains(command, "10808")
        }
    }

    @Test
    fun olcRtcCommandAllowsDatachannelForNonTelemostProviders() {
        val command = OlcRtcCommand(
            binary = Path.of("/tmp/olcrtc"),
            location = LocationConfig(
                name = "WB",
                id = "room-wb",
                key = "b".repeat(64),
                bypassProvider = LocationConfig.PROVIDER_WB_STREAM,
                transport = LocationConfig.TRANSPORT_DATACHANNEL
            )
        ).args()

        assertContains(command, LocationConfig.TRANSPORT_DATACHANNEL)
    }

    @Test
    fun olcRtcCommandUsesDesktopWbStreamProviderAlias() {
        val command = OlcRtcCommand(
            binary = Path.of("/tmp/olcrtc"),
            location = LocationConfig(
                name = "WB",
                id = "room-wb",
                key = "b".repeat(64),
                bypassProvider = LocationConfig.PROVIDER_WB_STREAM
            )
        ).args()

        assertEquals("wbstream", command[command.indexOf("-provider") + 1])
    }

    @Test
    fun macOsProxyCommandsEnableAndRestorePacPerService() {
        val enable = MacOsProxyController.enableCommands(listOf("Wi-Fi"), "http://127.0.0.1:10809/proxy.pac")
        assertEquals(
            listOf(
                listOf("networksetup", "-setautoproxyurl", "Wi-Fi", "http://127.0.0.1:10809/proxy.pac"),
                listOf("networksetup", "-setautoproxystate", "Wi-Fi", "on")
            ),
            enable
        )

        val restore = MacOsProxyController.restoreCommands(
            listOf(
                MacOsAutoProxyState("Wi-Fi", enabled = true, url = "http://old/proxy.pac"),
                MacOsAutoProxyState("USB", enabled = false, url = null)
            )
        )
        assertEquals(
            listOf(
                listOf("networksetup", "-setautoproxyurl", "Wi-Fi", "http://old/proxy.pac"),
                listOf("networksetup", "-setautoproxystate", "Wi-Fi", "on"),
                listOf("networksetup", "-setautoproxystate", "USB", "off")
            ),
            restore
        )
    }

    @Test
    fun windowsProxyCommandsBackupShapeIsRestorable() {
        val enable = WindowsProxyController.enableCommands("http://127.0.0.1:10809/proxy.pac")
        assertEquals("reg", enable.first().first())
        assertContains(enable.flatten(), "AutoConfigURL")
        assertContains(enable.flatten(), "http://127.0.0.1:10809/proxy.pac")

        val restore = WindowsProxyController.restoreCommands(
            WindowsProxyState(
                proxyEnable = "0x1",
                proxyServer = "127.0.0.1:8888",
                proxyOverride = "<local>",
                autoConfigUrl = null
            )
        )

        assertContains(restore.flatten(), "ProxyEnable")
        assertContains(restore.flatten(), "ProxyServer")
        assertContains(restore.flatten(), "ProxyOverride")
        assertContains(restore.flatten(), "AutoConfigURL")
        assertContains(restore.flatten(), "delete")
    }

    @Test
    fun windowsProxyRefreshCommandUsesFullyQualifiedWinInetSignature() {
        val refresh = WindowsProxyController.refreshCommand()
        val script = refresh.last()

        assertEquals("powershell.exe", refresh.first())
        assertContains(script, "System.Runtime.InteropServices.DllImport")
        assertContains(script, "System.IntPtr")
        assertContains(script, "InternetSetOption")
    }

    @Test
    fun linuxTunConfigUsesLocalSocksAndIpv4MapDns() {
        val config = LinuxTunController.configContent()

        assertContains(config, "name: turnbox0")
        assertContains(config, "ipv4: 10.0.88.88")
        assertContains(config, "address: 127.0.0.1")
        assertContains(config, "port: 10808")
        assertContains(config, "udp: 'tcp'")
        assertContains(config, "mapdns:")
        assertContains(config, "network: 100.64.0.0")
    }

    @Test
    fun linuxTunScriptsRouteUserTrafficThroughTunAndKeepRootDirect() {
        val up = LinuxTunController.upScriptContent()
        val down = LinuxTunController.downScriptContent()

        assertContains(up, "ip rule add uidrange 0-0 lookup main pref 10")
        assertContains(up, "ip route add default dev turnbox0 table 51820")
        assertContains(up, "ip rule add lookup 51820 pref 20")
        assertContains(up, "resolvectl dns turnbox0 1.1.1.1")
        assertContains(down, "ip rule del uidrange 0-0 lookup main pref 10")
        assertContains(down, "ip route flush table 51820")
        assertContains(down, "resolvectl revert turnbox0")
    }
}
