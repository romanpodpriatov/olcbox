import Libbox
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

    /// Temporary bisection switch.
    ///
    /// The extension logs nothing at all and the system reports "VPN is inactive",
    /// so it is dying before our first line runs. With this false the framework is
    /// still linked but never called: if the tunnel then comes up, the fault is in
    /// starting libbox; if it still does not, the fault is in loading the framework
    /// at all, and no amount of reordering our own calls will help.
    private static let useLibbox = false

    private var boxService: LibboxBoxService?

    private static func failure(_ reason: String) -> NSError {
        NSError(domain: "org.proofkit.tunnel", code: 10,
                userInfo: [NSLocalizedDescriptionKey: reason])
    }

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

        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: Self.appGroup
        ) else {
            completionHandler(Self.failure("app group unavailable"))
            return
        }

        let configURL = container.appendingPathComponent("config.json")
        guard let config = try? String(contentsOf: configURL, encoding: .utf8), !config.isEmpty else {
            completionHandler(Self.failure("no config in the shared container"))
            return
        }

        // Applied before the engine starts: libbox asks for the descriptor
        // synchronously and complains if answering takes long.
        setTunnelNetworkSettings(LibboxPlatform.tunnelSettings()) { [weak self] error in
            if let error {
                self?.log.error("tunnel settings rejected: \(error.localizedDescription, privacy: .public)")
                completionHandler(error)
                return
            }
            guard Self.useLibbox else {
                self?.log.info("tun established, libbox deliberately not started")
                completionHandler(nil)
                return
            }
            self?.startEngine(config: config, container: container, completionHandler: completionHandler)
        }
    }

    private func startEngine(
        config: String,
        container: URL,
        completionHandler: @escaping (Error?) -> Void
    ) {
        do {
            // libbox keeps its state on disk; inside the group so the app can
            // read logs and caches too.
            let setup = LibboxSetupOptions()
            setup.basePath = container.appendingPathComponent("libbox").path
            setup.workingPath = container.appendingPathComponent("libbox/work").path
            setup.tempPath = NSTemporaryDirectory()
            setup.username = ""
            setup.isTVOS = false
            setup.fixAndroidStack = false
            try? FileManager.default.createDirectory(
                atPath: setup.workingPath, withIntermediateDirectories: true
            )
            // Plain C functions, not Objective-C methods, so Swift leaves their
            // NSError** as an argument rather than turning it into `throws`.
            var setupError: NSError?
            LibboxSetup(setup, &setupError)
            if let setupError { throw setupError }

            // The platform object is what libbox calls back into; openTun is where
            // the system settings get applied, so there is no separate call here.
            let platform = LibboxPlatform(provider: self)
            var serviceError: NSError?
            guard let service = LibboxNewService(config, platform, &serviceError) else {
                throw serviceError ?? Self.failure("sing-box would not take the config")
            }
            try service.start()
            self.boxService = service

            log.info("sing-box started, config \(config.count, privacy: .public) bytes")
            completionHandler(nil)
        } catch {
            log.error("sing-box failed to start: \(error.localizedDescription, privacy: .public)")
            completionHandler(error)
        }
    }

    override func stopTunnel(
        with reason: NEProviderStopReason,
        completionHandler: @escaping () -> Void
    ) {
        log.info("stopTunnel reason=\(reason.rawValue, privacy: .public)")
        // Closing the service is what releases the tun descriptor; skipping it
        // leaves the next start fighting the previous one for it.
        try? boxService?.close()
        boxService = nil
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
