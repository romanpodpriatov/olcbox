import NetworkExtension
import os

/// Establishes the tun and nothing else, on purpose.
///
/// The first run on a real device has to answer one question — does the
/// entitlement, the provisioning profile and the app↔extension wiring work — and
/// it can only answer it if no core is in the way. Reality, Hysteria2, XHTTP and
/// olcRTC arrive after this passes; a failure now is never ambiguous.
class PacketTunnelProvider: NEPacketTunnelProvider {

    private let log = Logger(subsystem: "org.proofkit.app", category: "tunnel")

    /// Shared with the app, which writes the selected location's core config here.
    /// Nothing reads it yet; it is checked now because a missing App Group is
    /// invisible until the day the config matters, and then looks like a bug in
    /// the tunnel.
    private static let appGroup = "group.org.proofkit.app"

    override func startTunnel(
        options: [String: NSObject]?,
        completionHandler: @escaping (Error?) -> Void
    ) {
        log.info("startTunnel")

        if let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: Self.appGroup
        ) {
            log.info("app group reachable at \(container.path, privacy: .public)")
        } else {
            // Not fatal here — the passthrough needs no config — but it would be
            // fatal later, so say so while the cause is still obvious.
            log.error("app group \(Self.appGroup, privacy: .public) is NOT reachable")
        }

        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "127.0.0.1")

        // 198.18.0.0/15 is reserved for benchmarking and is what tunnel clients
        // conventionally use: it cannot collide with a real network the device is on.
        let ipv4 = NEIPv4Settings(addresses: ["198.18.0.1"], subnetMasks: ["255.255.255.0"])
        ipv4.includedRoutes = [NEIPv4Route.default()]
        settings.ipv4Settings = ipv4

        settings.dnsSettings = NEDNSSettings(servers: ["1.1.1.1", "8.8.8.8"])

        // 1400 leaves room for the outer headers every transport adds. The real
        // value belongs with the transport once one exists.
        settings.mtu = 1400

        setTunnelNetworkSettings(settings) { [weak self] error in
            if let error {
                self?.log.error("setTunnelNetworkSettings failed: \(error.localizedDescription, privacy: .public)")
                completionHandler(error)
                return
            }
            self?.log.info("tun established")
            completionHandler(nil)
        }
    }

    override func stopTunnel(
        with reason: NEProviderStopReason,
        completionHandler: @escaping () -> Void
    ) {
        log.info("stopTunnel reason=\(reason.rawValue, privacy: .public)")
        completionHandler()
    }

    /// The app can talk to a running tunnel through this; wired when there is
    /// something worth saying.
    override func handleAppMessage(
        _ messageData: Data,
        completionHandler: ((Data?) -> Void)?
    ) {
        completionHandler?(nil)
    }
}
