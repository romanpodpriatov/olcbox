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

    /// 1.13 has no `LibboxNewService`: the command server owns the engine, and
    /// starting sing-box means asking it to. See `LibboxCommandHandler`.
    private var commandServer: LibboxCommandServer?

    /// Held because libbox only keeps a reference from the Go side, and a
    /// handler collected here would leave the engine calling into nothing.
    private var commandHandler: LibboxCommandHandler?

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
        // Stamped onto the memory trace too, so the footprint curve can be read
        // against the stage it was climbing during.
        MemoryWatch.mark(stage)
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: Self.appGroup
        ) else { return }
        try? Data(stage.utf8).write(to: container.appendingPathComponent("stage.txt"))
    }

    /// The soft ceiling handed to the Go runtime. See its use in startTunnel.
    ///
    /// 28 MiB rather than 32 because a limit only works while it is below what
    /// the heap would otherwise reach, and the transports differ: olcRTC alone
    /// measured a 38 MB peak on a workstation, but a phone on Vless — Xray and
    /// sing-box together — ran at 36–43 MB of footprint and died at 47.5 MB. A
    /// ceiling above that is not a ceiling. Raise it if a transport is starved;
    /// the trace in an exported log names both the limit and the footprint, so
    /// the next report says which way to move.
    private static let goMemoryLimit: Int64 = 28 * 1024 * 1024

    private static func failure(_ reason: String) -> NSError {
        NSError(domain: "org.proofkit.tunnel", code: 10,
                userInfo: [NSLocalizedDescriptionKey: reason])
    }

    override func startTunnel(
        options: [String: NSObject]?,
        completionHandler: @escaping (Error?) -> Void
    ) {
        log.info("startTunnel")
        NetworkDiagnostics.reset()
        NetworkDiagnostics.record("start os=\(ProcessInfo.processInfo.operatingSystemVersionString)")
        // Before any engine allocates. A provider is given roughly 50 MB and is
        // killed for exceeding it, and Go reaches a ceiling in one step rather
        // than climbing to it: a phone's trace showed 35.1 MB on one sample and
        // 46.0 MB on the next, 250 ms later, then nothing. This is the whole
        // runtime's limit — olcRTC, sing-box and Xray share it inside
        // Cores.xcframework, and they share the process that gets killed, which
        // is why it belongs here and not in whichever engine happens to run.
        //
        // Measured against the upload that breaks it: peak 38.0 MB unlimited,
        // 26.0 MB at 32 MiB, with throughput unchanged. Raise it if a
        // transport is ever starved; lower it if a phone still dies with
        // headroom to spare in the trace.
        MobileSetMemoryLimit(Self.goMemoryLimit)
        NetworkDiagnostics.record("go memory limit \(Self.goMemoryLimit / 1_048_576) MB")
        LibboxPlatform.invalidatePinCache()
        LibboxPlatform.tracePhysicalInterfaces("before-tun")
        // First thing, so the sentinel the app leaves in stage.txt is replaced
        // the moment this process runs a line of its own. Anything the app reads
        // back after this belongs to this attempt; the sentinel surviving means
        // the extension never got here at all, which is a different fault with a
        // different fix.
        mark("startTunnel")

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

        // Before any core is loaded, so the first sample is the extension with
        // nothing in it and the climb afterwards is attributable. The record
        // outlives the process; that is the whole point of it. See MemoryWatch.
        MemoryWatch.start(container: container)

        let configURL = container.appendingPathComponent("config.json")
        guard let config = try? String(contentsOf: configURL, encoding: .utf8), !config.isEmpty else {
            completionHandler(Self.failure("no config in the shared container"))
            return
        }

        // Each is present only for the one transport that needs it, and the app
        // deletes the other, so a stale file cannot start a second core behind a
        // tunnel that does not use one.
        let xrayURL = container.appendingPathComponent("xray.json")
        let xrayConfig = try? String(contentsOf: xrayURL, encoding: .utf8)

        let olcrtcURL = container.appendingPathComponent("olcrtc.json")
        let olcrtc = (try? Data(contentsOf: olcrtcURL))
            .flatMap { try? JSONDecoder().decode(OlcrtcEngine.Parameters.self, from: $0) }

        // Before the tunnel's settings go on: from then on the system resolver
        // is our own tun, and the servers of the network underneath can no
        // longer be read. For every transport now, not only olcRTC — under
        // Bypass Russia the config wants one of them too. Counted rather than
        // listed in the trace — they are the network's addresses, and the
        // trace keeps to interface names.
        let resolvers = ResolverSnapshot.servers()
        NetworkDiagnostics.record("resolvers from the network: \(resolvers.count)")

        // Applied before the engine starts: libbox asks for the descriptor
        // synchronously and complains if answering takes long.
        setTunnelNetworkSettings(LibboxPlatform.tunnelSettings()) { [weak self] error in
            if let error {
                NetworkDiagnostics.record("tunnel settings failed domain=\((error as NSError).domain) code=\((error as NSError).code)")
                self?.log.error("tunnel settings rejected: \(error.localizedDescription, privacy: .public)")
                completionHandler(error)
                return
            }
            guard Self.useLibbox else {
                self?.log.info("tun established, libbox deliberately not started")
                completionHandler(nil)
                return
            }
            LibboxPlatform.tracePhysicalInterfaces("after-tun")
            self?.startEngine(
                config: config,
                xrayConfig: xrayConfig?.isEmpty == false ? xrayConfig : nil,
                olcrtc: olcrtc,
                resolvers: resolvers,
                container: container,
                completionHandler: completionHandler
            )
        }
    }

    private func startEngine(
        config: String,
        xrayConfig: String?,
        olcrtc: OlcrtcEngine.Parameters?,
        resolvers: [String],
        container: URL,
        completionHandler: @escaping (Error?) -> Void
    ) {
        do {
            mark("setup")
            // libbox keeps its state on disk; inside the group so the app can
            // read logs and caches too.
            let setup = LibboxSetupOptions()
            setup.basePath = container.appendingPathComponent("libbox").path
            setup.workingPath = container.appendingPathComponent("libbox/work").path
            setup.tempPath = NSTemporaryDirectory()
            setup.fixAndroidStack = false
            // 0 means the command server would listen on a unix socket rather
            // than a port — moot either way, because it is never started.
            setup.commandServerListenPort = 0
            setup.commandServerSecret = ""
            setup.logMaxLines = 100
            setup.debug = false
            try? FileManager.default.createDirectory(
                atPath: setup.workingPath, withIntermediateDirectories: true
            )
            // Plain C functions, not Objective-C methods, so Swift leaves their
            // NSError** as an argument rather than turning it into `throws`.
            var setupError: NSError?
            LibboxSetup(setup, &setupError)
            if let setupError { throw setupError }

            // Before the command server is built: it reads this to decide whether
            // to run its own OOM killer.
            //
            // A packet tunnel provider gets about 50 MB and is killed without
            // ceremony for exceeding it — the app is told nothing, the system
            // simply reports the VPN as inactive. This puts the Go runtime under
            // a 45 MB ceiling and makes its collector aggressive, which matters
            // more now than it did with one core: sing-box, Xray and olcRTC share
            // that one runtime, and WebRTC is not the cheap one.
            LibboxSetMemoryLimit(true)

            // Setup initializes libbox's UID/GID. Redirecting before it attempts
            // chown with zero-valued IDs and fails with EPERM on iOS. In this
            // libbox version RedirectStderr configures Go crash output; regular
            // interface diagnostics use their own shared-container file.
            var logError: NSError?
            LibboxRedirectStderr(container.appendingPathComponent("engine.log").path, &logError)
            if let logError {
                // Not fatal: losing the log is worse for the next bug than for
                // this connection.
                log.error("engine log unavailable: \(logError.localizedDescription, privacy: .public)")
                NetworkDiagnostics.record("crash log failed domain=\(logError.domain) code=\(logError.code)")
            }

            // The borrowed core first, whichever it is: sing-box's outbound
            // points at its SOCKS port, and a sing-box that starts against a
            // port nobody is listening on fails every connection rather than
            // waiting. Never both — a location is one transport.
            if let xrayConfig {
                mark("xray")
                try XrayEngine.start(configJSON: xrayConfig)
            }
            if let olcrtc {
                mark("olcrtc")
                try OlcrtcEngine.start(olcrtc, resolvers: resolvers)
            }
            mark("service")

            // The platform object is what libbox calls back into; openTun is where
            // the system settings get applied, so there is no separate call here.
            let platform = LibboxPlatform(provider: self)
            let handler = LibboxCommandHandler(provider: self)
            var serverError: NSError?
            guard let server = LibboxNewCommandServer(handler, platform, &serverError) else {
                throw serverError ?? Self.failure("libbox would not build a command server")
            }
            mark("starting")
            // Bypass Russia: the config names a placeholder where the direct
            // resolver goes, because only this process could read it. A Global
            // config carries no placeholder and passes through untouched.
            let substituted = DirectResolver.substitute(in: config, resolvers: resolvers)
            #if DEBUG
            // Debug builds keep sing-box's own log in the working directory for
            // the app's log export: it is the only account of what a rule did.
            SingBoxDebugLog.reset(workingPath: container.appendingPathComponent("libbox/work").path)
            let config = SingBoxDebugLog.enable(in: substituted)
            #else
            let config = substituted
            #endif
            // Deliberately not `server.start()`: that binds the gRPC command
            // socket for an app that talks to us through handleAppMessage
            // instead. Starting the engine is a separate call, and this is it.
            try server.startOrReloadService(config, options: LibboxOverrideOptions())
            mark("ready")
            startWatchingNetworkChanges()
            self.commandHandler = handler
            self.commandServer = server

            log.info("sing-box started, config \(config.count, privacy: .public) bytes")
            completionHandler(nil)
        } catch {
            mark("failed: \(error.localizedDescription)")
            XrayEngine.stop()
            OlcrtcEngine.stop()
            completionHandler(error)
        }
    }

    private func startWatchingNetworkChanges() {
        var lastInterface: String?
        pathMonitor.pathUpdateHandler = { [weak self] path in
            // Record before the existing early return: availableInterfaces.first
            // can stay unchanged even when the usable path or family changes.
            let interfaces = path.availableInterfaces.map { "\($0.name):\($0.type)" }.joined(separator: ",")
            NetworkDiagnostics.record("path status=\(path.status) wifi=\(path.usesInterfaceType(.wifi)) cellular=\(path.usesInterfaceType(.cellular)) ipv4=\(path.supportsIPv4) ipv6=\(path.supportsIPv6) interfaces=\(interfaces)")
            // Before anything else: whatever moved, the next socket should look
            // at the interfaces afresh rather than trust a pin from before it.
            LibboxPlatform.invalidatePinCache()
            let current = path.availableInterfaces.first?.name
            guard current != lastInterface else { return }
            lastInterface = current
            guard let self, let server = self.commandServer else { return }
            self.log.info("network changed to \(current ?? "none", privacy: .public), resetting")
            server.resetNetwork()
            NetworkDiagnostics.record("sing-box resetNetwork; olcrtc not explicitly restarted")
        }
        pathMonitor.start(queue: DispatchQueue(label: "org.proofkit.path"))
    }

    override func stopTunnel(
        with reason: NEProviderStopReason,
        completionHandler: @escaping () -> Void
    ) {
        log.info("stopTunnel reason=\(reason.rawValue, privacy: .public)")
        NetworkDiagnostics.record("stop reason=\(reason.rawValue)")
        // Closing the service is what releases the tun descriptor; skipping it
        // leaves the next start fighting the previous one for it. Two calls now:
        // one stops the engine, the other tears down the server that owns it.
        pathMonitor.cancel()
        try? commandServer?.closeService()
        commandServer?.close()
        commandServer = nil
        commandHandler = nil
        XrayEngine.stop()
        OlcrtcEngine.stop()
        // Last, so a stop that is itself slow or fatal is still on the trace.
        MemoryWatch.stop()
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

/// The callbacks libbox 1.13 requires in order to hand out an engine at all.
///
/// `LibboxNewService` is gone: a command server owns the engine now, and one
/// cannot be built without a handler. Its gRPC socket stays unopened — the app
/// reaches the tunnel through `handleAppMessage` — so in practice only the
/// engine itself calls in here, and only to report that it is stopping.
final class LibboxCommandHandler: NSObject, LibboxCommandServerHandlerProtocol {

    private weak var provider: NEPacketTunnelProvider?
    private let log = Logger(subsystem: "org.proofkit.app", category: "command")

    init(provider: NEPacketTunnelProvider) {
        self.provider = provider
        super.init()
    }

    /// The engine deciding to stop — an OOM kill against the extension's memory
    /// cap arrives here and nowhere else. Tearing the tunnel down makes the
    /// system show it as disconnected instead of leaving a VPN icon over a dead
    /// engine, which is the failure that cost a night once already.
    func serviceStop() throws {
        log.error("engine asked to stop")
        // Into the trace as well, not just os_log: this is the difference
        // between "libbox gave up" and "the system took the tunnel away", and
        // the two look identical from the app, which sees only that the
        // session is gone. stopTunnel's own reason will then be providerFailed,
        // which says who asked but not which component.
        NetworkDiagnostics.record("libbox asked the provider to stop")
        provider?.cancelTunnelWithError(nil)
    }

    /// Nothing to reload into: a new location is a new config, and the app
    /// restarts the tunnel for it rather than reloading in place.
    func serviceReload() throws {}

    func getSystemProxyStatus() throws -> LibboxSystemProxyStatus {
        let status = LibboxSystemProxyStatus()
        // A packet tunnel carries every flow already; there is no separate
        // system proxy for iOS to offer.
        status.available = false
        status.enabled = false
        return status
    }

    func setSystemProxyEnabled(_ enabled: Bool) throws {
        throw NSError(domain: "org.proofkit.tunnel", code: 4,
                      userInfo: [NSLocalizedDescriptionKey: "no system proxy on iOS"])
    }

    func writeDebugMessage(_ message: String?) {
        guard let message else { return }
        log.debug("\(message, privacy: .public)")
    }
}
