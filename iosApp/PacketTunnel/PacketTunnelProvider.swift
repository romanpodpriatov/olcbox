import Cores
import Network
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
    private static let useLibbox = true

    private var boxService: LibboxBoxService?

    /// Watches for the device changing network underneath the tunnel.
    ///
    /// Without this a Wi-Fi to cellular handover is noticed only when a
    /// connection finally fails, which to a user looks like the VPN randomly
    /// breaking. sing-box knows how to rebuild its sockets; it just has to be
    /// told the ground moved.
    private let pathMonitor = NWPathMonitor()

    /// Progress written where the app can read it.
    ///
    /// The extension's log lines never reach the person debugging this, so a
    /// breadcrumb that survives the process dying is worth more than a perfect
    /// log nobody sees: whatever stage is on screen when it dies is the stage
    /// that killed it.
    private func mark(_ stage: String) {
        log.info("stage: \(stage, privacy: .public)")
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: Self.appGroup
        ) else { return }
        try? Data(stage.utf8).write(to: container.appendingPathComponent("stage.txt"))
    }

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

        // Present only for xhttp. The app deletes it for every other transport,
        // so a stale file cannot start a second core behind a tunnel that does
        // not use one.
        let xrayURL = container.appendingPathComponent("xray.json")
        let xrayConfig = try? String(contentsOf: xrayURL, encoding: .utf8)

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
            self?.startEngine(
                config: config,
                xrayConfig: xrayConfig?.isEmpty == false ? xrayConfig : nil,
                container: container,
                completionHandler: completionHandler
            )
        }
    }

    private func startEngine(
        config: String,
        xrayConfig: String?,
        container: URL,
        completionHandler: @escaping (Error?) -> Void
    ) {
        do {
            // Xray first: sing-box's outbound points at its SOCKS port, and a
            // sing-box that starts against a port nobody is listening on fails
            // every connection rather than waiting.
            if let xrayConfig {
                mark("xray")
                try XrayEngine.start(configJSON: xrayConfig)
            }
            mark("setup")
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
            mark("service")

            // The platform object is what libbox calls back into; openTun is where
            // the system settings get applied, so there is no separate call here.
            let platform = LibboxPlatform(provider: self)
            var serviceError: NSError?
            guard let service = LibboxNewService(config, platform, &serviceError) else {
                throw serviceError ?? Self.failure("sing-box would not take the config")
            }
            mark("starting")
            try service.start()
            mark("ready")
            startWatchingNetworkChanges()
            self.boxService = service

            log.info("sing-box started, config \(config.count, privacy: .public) bytes")
            completionHandler(nil)
        } catch {
            mark("failed: \(error.localizedDescription)")
            XrayEngine.stop()
            completionHandler(error)
        }
    }

    private func startWatchingNetworkChanges() {
        var lastInterface: String?
        pathMonitor.pathUpdateHandler = { [weak self] path in
            let current = path.availableInterfaces.first?.name
            guard current != lastInterface else { return }
            lastInterface = current
            guard let self, let service = self.boxService else { return }
            self.log.info("network changed to \(current ?? "none", privacy: .public), resetting")
            service.resetNetwork()
        }
        pathMonitor.start(queue: DispatchQueue(label: "org.proofkit.path"))
    }

    override func stopTunnel(
        with reason: NEProviderStopReason,
        completionHandler: @escaping () -> Void
    ) {
        log.info("stopTunnel reason=\(reason.rawValue, privacy: .public)")
        // Closing the service is what releases the tun descriptor; skipping it
        // leaves the next start fighting the previous one for it.
        pathMonitor.cancel()
        try? boxService?.close()
        boxService = nil
        XrayEngine.stop()
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
