import Combine
import NetworkExtension
import SwiftUI
import SharedUI
import os

@main
struct OlcboxIosApp: App {
    private let platformBridge: SwiftPlatformBridge
    private let olcRtcBridge: SwiftOlcRtcManager
    private let packetTunnelBridge: SwiftPacketTunnelBridge
    private let appSession: IosAppSession

    init() {
        let platformBridge = SwiftPlatformBridge()
        let olcRtcBridge = SwiftOlcRtcManager()
        let packetTunnelBridge = SwiftPacketTunnelBridge()
        self.platformBridge = platformBridge
        self.olcRtcBridge = olcRtcBridge
        self.packetTunnelBridge = packetTunnelBridge
        self.appSession = IosAppFactory().createSession(
            platformBridge: platformBridge,
            olcRtcBridge: olcRtcBridge,
            packetTunnelBridge: packetTunnelBridge
        )
    }

    var body: some Scene {
        WindowGroup {
            ComposeHostView(
                platformBridge: platformBridge,
                appSession: appSession
            )
            .ignoresSafeArea()
        }
    }
}

private struct ComposeHostView: UIViewControllerRepresentable {
    let platformBridge: SwiftPlatformBridge
    let appSession: IosAppSession

    func makeUIViewController(context: Context) -> UIViewController {
        let controller = appSession.createViewController()
        platformBridge.presenter = controller
        return controller
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        platformBridge.presenter = uiViewController
    }
}

@MainActor
final class PacketTunnelController: ObservableObject {

    /// Must match the extension target's bundle identifier exactly; iOS silently
    /// finds nothing to launch if it does not.
    private static let providerBundleId = "org.proofkit.app.PacketTunnel"

    @Published private(set) var status: String = "not loaded"

    private var manager: NETunnelProviderManager?
    // Touched from deinit, which is not actor-isolated, so it cannot be either.
    private nonisolated(unsafe) var observer: NSObjectProtocol?

    private let log = Logger(subsystem: "org.proofkit.app", category: "tunnel-controller")

    init() {
        observer = NotificationCenter.default.addObserver(
            forName: .NEVPNStatusDidChange,
            object: nil,
            queue: .main
        ) { [weak self] note in
            guard let connection = note.object as? NEVPNConnection else { return }
            // NEVPNConnection is not Sendable; its status is a plain enum. Read
            // it here rather than carrying the connection into the task.
            let text = Self.describe(connection.status)
            Task { @MainActor in self?.status = text }
        }
    }

    deinit {
        if let observer { NotificationCenter.default.removeObserver(observer) }
    }

    /// Creates the VPN configuration if it is missing. The first save is what
    /// raises the system's "allow VPN configuration" prompt.
    func prepare() async {
        do {
            let existing = try await NETunnelProviderManager.loadAllFromPreferences()
            let manager = existing.first ?? NETunnelProviderManager()

            let proto = NETunnelProviderProtocol()
            proto.providerBundleIdentifier = Self.providerBundleId
            // Shown in Settings → VPN. Not a real address; the tunnel decides
            // where it goes once a transport exists.
            proto.serverAddress = "ProofKit"

            manager.protocolConfiguration = proto
            manager.localizedDescription = "ProofKit"
            manager.isEnabled = true

            try await manager.saveToPreferences()
            // Reloading after a save is not optional: starting from the in-memory
            // object that was just saved fails with a stale-configuration error.
            try await manager.loadFromPreferences()

            self.manager = manager
            status = Self.describe(manager.connection.status)
            log.info("configuration ready")
        } catch {
            status = "prepare failed: \(error.localizedDescription)"
            log.error("prepare failed: \(error.localizedDescription, privacy: .public)")
        }
    }

    func start() async {
        if manager == nil { await prepare() }
        guard let manager else { return }
        do {
            try manager.connection.startVPNTunnel()
            log.info("startVPNTunnel requested")
        } catch {
            status = "start failed: \(error.localizedDescription)"
            log.error("start failed: \(error.localizedDescription, privacy: .public)")
        }
    }

    func stop() {
        manager?.connection.stopVPNTunnel()
        log.info("stopVPNTunnel requested")
    }

    /// Waits for the system to say the tunnel is actually up.
    ///
    /// `startVPNTunnel()` only queues the request: it returns without error for
    /// a tunnel that is about to die, and an extension that throws out of
    /// `startTunnel` reports nothing back to the app at all. Without this the
    /// app said "connected" over a dead tunnel — which is exactly how a
    /// transport sing-box could not even parse went unnoticed.
    ///
    /// Returns nil on success, or a description of what went wrong.
    func waitUntilUp(timeout: TimeInterval = 20) async -> String? {
        guard let manager else { return "no VPN configuration" }
        var sawAttempt = false
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            switch manager.connection.status {
            case .connected:
                return nil
            case .connecting, .reasserting:
                sawAttempt = true
            case .disconnected, .invalid:
                // Only after an attempt began: the status is still
                // `disconnected` for a moment after the request is queued.
                if sawAttempt { return Self.lastStage() ?? "the tunnel stopped right after starting" }
            case .disconnecting:
                break
            @unknown default:
                break
            }
            try? await Task.sleep(nanoseconds: 250_000_000)
        }
        return Self.lastStage() ?? "timed out waiting for the tunnel"
    }

    /// The extension's own breadcrumb, which is the only thing that knows *why*
    /// a start failed — the system tells the app nothing but "disconnected".
    private static func lastStage() -> String? {
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: "group.org.proofkit.app"
        ) else { return nil }
        let stage = try? String(
            contentsOf: container.appendingPathComponent("stage.txt"), encoding: .utf8
        )
        guard let stage, stage.hasPrefix("failed") else { return nil }
        return stage
    }

    private nonisolated static func describe(_ status: NEVPNStatus) -> String {
        switch status {
        case .invalid: return "invalid"
        case .disconnected: return "disconnected"
        case .connecting: return "connecting"
        case .connected: return "connected"
        case .reasserting: return "reasserting"
        case .disconnecting: return "disconnecting"
        @unknown default: return "unknown"
        }
    }
}

/// The app's half of starting the packet tunnel.
///
/// Kotlin decides *what* to run and hands over a finished config; only the app
/// can ask the system to launch the extension, so that part lives here. The
/// config goes through the App Group because a tunnel provider is a separate
/// process — there is no argument to pass it.
final class SwiftPacketTunnelBridge: NSObject, IosPacketTunnelBridge {

    /// Main-actor bound, so it cannot be a stored property of this class — the
    /// bridge is called from Kotlin's background dispatcher and is deliberately
    /// not isolated itself.
    @MainActor private static let controller = PacketTunnelController()

    private static let appGroupId = "group.org.proofkit.app"

    /// Plain flag rather than a peek at the controller: reading main-actor state
    /// from here would mean assuming an isolation this class does not have.
    private nonisolated(unsafe) var running = false

    func start(config: String, xrayConfig: String?) -> IosBridgeResult {
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: Self.appGroupId
        ) else {
            return IosBridgeResult(success: false, message: "app group unavailable")
        }
        let xrayURL = container.appendingPathComponent("xray.json")
        do {
            try Data(config.utf8).write(to: container.appendingPathComponent("config.json"))
            // Removed rather than left behind: a stale file from a previous
            // xhttp connection would start a second core behind a tunnel that
            // does not use one.
            if let xrayConfig {
                try Data(xrayConfig.utf8).write(to: xrayURL)
            } else if FileManager.default.fileExists(atPath: xrayURL.path) {
                try FileManager.default.removeItem(at: xrayURL)
            }
        } catch {
            return IosBridgeResult(
                success: false,
                message: "could not hand over the config: \(error.localizedDescription)"
            )
        }

        // Kotlin waits for an answer on a background dispatcher, and the
        // controller lives on the main actor, so the hop is explicit.
        // Read before the hop: capturing self in a main-actor task would mean
        // sending a non-Sendable object across isolation for one boolean.
        let wasRunning = running
        let failure = UnsafeMutablePointer<String?>.allocate(capacity: 1)
        failure.initialize(to: nil)
        defer { failure.deinitialize(count: 1); failure.deallocate() }

        let done = DispatchSemaphore(value: 0)
        Task { @MainActor in
            // Starting an already-running tunnel does nothing at all, and the
            // extension keeps the config it was launched with — which looked
            // exactly like a working VPN that does not change your IP.
            if wasRunning {
                Self.controller.stop()
                try? await Task.sleep(nanoseconds: 700_000_000)
            }
            await Self.controller.start()
            failure.pointee = await Self.controller.waitUntilUp()
            done.signal()
        }
        done.wait()

        if let reason = failure.pointee {
            running = false
            return IosBridgeResult(success: false, message: reason)
        }
        running = true
        return IosBridgeResult(success: true, message: nil)
    }

    func stop() {
        running = false
        Task { @MainActor in Self.controller.stop() }
    }

    func isRunning() -> Bool { running }
}
