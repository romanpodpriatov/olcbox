import Combine
import NetworkExtension
import SwiftUI
import SharedUI
import UIKit
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

    /// The last status the system itself reported, readable without hopping to
    /// the main actor.
    ///
    /// The watchdog asks "is the tunnel up?" from Kotlin's own thread, and a flag
    /// the app sets when it *requests* a tunnel cannot answer that: an extension
    /// killed for exceeding its memory cap never tells anyone, and the app went
    /// on showing "connected" over a tunnel the system had already written off.
    nonisolated(unsafe) private(set) static var systemConnected = false

    /// When the system says the running tunnel was established, in epoch
    /// milliseconds, or 0 when nothing is up.
    nonisolated(unsafe) private(set) static var systemConnectedSinceMs: Int64 = 0

    private var manager: NETunnelProviderManager?
    // Touched from deinit, which is not actor-isolated, so it cannot be either.
    private nonisolated(unsafe) var observer: NSObjectProtocol?
    private nonisolated(unsafe) var foregroundObserver: NSObjectProtocol?

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
            Self.adopt(connection.status, connectedDate: connection.connectedDate)
            Task { @MainActor in self?.status = text }
        }

        // A notification is only ever a *change*, and the changes that matter
        // most happen while this process is not running to hear them. See
        // `syncFromSystem`.
        foregroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in await self?.syncFromSystem() }
        }

        Task { @MainActor [weak self] in await self?.syncFromSystem() }
    }

    deinit {
        if let observer { NotificationCenter.default.removeObserver(observer) }
        if let foregroundObserver { NotificationCenter.default.removeObserver(foregroundObserver) }
    }

    /// Records what the system is doing, for readers that cannot hop to the
    /// main actor.
    private nonisolated static func adopt(_ status: NEVPNStatus, connectedDate: Date?) {
        // `.reasserting` is the system rebuilding the tunnel underneath us after
        // a network change — the tunnel is still ours and still up. Counting it
        // as down is how the watchdog came to tear down a perfectly healthy
        // tunnel on an ordinary Wi-Fi↔cellular handover.
        let up = status == .connected || status == .reasserting
        systemConnected = up
        systemConnectedSinceMs = up
            ? Int64(((connectedDate ?? Date()).timeIntervalSince1970 * 1000).rounded())
            : 0
    }

    /// Adopts whatever the system is doing right now, rather than waiting to be
    /// told about the next change.
    ///
    /// Two things make this necessary rather than merely tidy. A tunnel outlives
    /// the app: iOS suspends and then terminates a backgrounded app while the
    /// extension keeps carrying traffic, so a launch begins with no idea that a
    /// VPN is up. And `NEVPNStatusDidChange` is only delivered for a manager
    /// this process has loaded — before the first `loadAllFromPreferences()`
    /// the app is deaf to it entirely, so "connected" was never missed so much
    /// as never observable. Between them, the app reported itself disconnected
    /// over a working VPN, and the Kotlin watchdog read that as a dead tunnel.
    func syncFromSystem() async {
        if manager == nil {
            manager = try? await NETunnelProviderManager.loadAllFromPreferences().first
        }
        guard let manager else { return }
        let connection = manager.connection
        Self.adopt(connection.status, connectedDate: connection.connectedDate)
        status = Self.describe(connection.status)
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
            Self.adopt(manager.connection.status, connectedDate: manager.connection.connectedDate)
            status = Self.describe(manager.connection.status)
            log.info("configuration ready")
        } catch {
            status = "prepare failed: \(error.localizedDescription)"
            log.error("prepare failed: \(error.localizedDescription, privacy: .public)")
        }
    }

    func start() async {
        // Also when it is present but disabled: `syncFromSystem` loads whatever
        // is in preferences so the app can see a running tunnel, and what it
        // loads may be a configuration the user switched off in Settings.
        // Starting that throws, where `prepare` re-enables and saves it.
        if manager == nil || manager?.isEnabled != true { await prepare() }
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
        let stage = shared("stage.txt")

        // A stage that says `failed` explains itself: the extension caught an
        // error and wrote it down.
        if let stage, stage.hasPrefix("failed") { return stage }

        // The harder case, and until now the silent one: the extension reached
        // a perfectly good stage — `ready`, carrying traffic — and was then
        // killed from outside. The stage cannot explain that, so it was
        // reported as the useless "stopped right after starting". The memory
        // trace can: it says how much headroom was left at the last sample
        // before the process disappeared. Near zero means the ceiling; wide
        // open means the kill had nothing to do with memory.
        if let stage, let memory = lastMemorySample() {
            return "died at stage '\(stage)' — \(memory)"
        }
        return nil
    }

    /// Last line of the extension's memory trace. See MemoryWatch.
    private static func lastMemorySample() -> String? {
        shared("memory.txt")?
            .split(separator: "\n")
            .last
            .map { $0.trimmingCharacters(in: .whitespaces) }
    }

    private static func shared(_ name: String) -> String? {
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: "group.org.proofkit.app"
        ) else { return nil }
        return try? String(
            contentsOf: container.appendingPathComponent(name), encoding: .utf8
        )
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
final class SwiftPacketTunnelBridge: NSObject, @unchecked Sendable, IosPacketTunnelBridge {

    /// Main-actor bound, so it cannot be a stored property of this class — the
    /// bridge is called from Kotlin's background dispatcher and is deliberately
    /// not isolated itself.
    @MainActor private static let controller = PacketTunnelController()

    private static let appGroupId = "group.org.proofkit.app"

    /// Plain flag rather than a peek at the controller: reading main-actor state
    /// from here would mean assuming an isolation this class does not have.
    private nonisolated(unsafe) var running = false

    override init() {
        super.init()
        // Force the controller into existence at launch.
        //
        // A static `let` is lazy, and the two things that read it from Kotlin —
        // `isRunning` and `connectedSinceEpochMs` — reach a static *variable*
        // without ever touching the instance. Nothing else creates it until the
        // first START, so the observers it registers, and the question it asks
        // the system about what is already running, would not happen until the
        // tap this exists to make unnecessary.
        Task { @MainActor in _ = Self.controller }
    }

    func start(request: IosPacketTunnelStartRequest, callback: IosBridgeCallback) {
        let answer = SendableCallback(callback: callback)

        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: Self.appGroupId
        ) else {
            answer.callback.onResult(
                result: IosBridgeResult(success: false, message: "app group unavailable")
            )
            return
        }

        do {
            try Data(request.config.utf8)
                .write(to: container.appendingPathComponent("config.json"))
            // Written or removed, never left behind: a stale file from a previous
            // connection would start a core behind a tunnel that does not use one.
            try Self.handOver(
                request.xrayConfig,
                to: container.appendingPathComponent("xray.json")
            )
            try Self.handOver(
                Self.olcrtcParameters(request.olcrtc),
                to: container.appendingPathComponent("olcrtc.json")
            )
        } catch {
            answer.callback.onResult(result: IosBridgeResult(
                success: false,
                message: "could not hand over the config: \(error.localizedDescription)"
            ))
            return
        }

        // Read before the hop, as before — but nothing waits here now. The
        // tunnel takes seconds to settle and the answer goes back through the
        // callback once the system has actually decided, instead of holding a
        // coroutine thread on a semaphore for the duration.
        let wasRunning = running
        Task { @MainActor [weak self] in
            // Starting an already-running tunnel does nothing at all, and the
            // extension keeps the config it was launched with — which looked
            // exactly like a working VPN that does not change your IP.
            if wasRunning {
                Self.controller.stop()
                try? await Task.sleep(nanoseconds: 700_000_000)
            }
            await Self.controller.start()
            let reason = await Self.controller.waitUntilUp()
            self?.running = reason == nil
            answer.callback.onResult(
                result: IosBridgeResult(success: reason == nil, message: reason)
            )
        }
    }

    /// Writes the file, or removes it when there is nothing to write.
    private static func handOver(_ contents: String?, to url: URL) throws {
        if let contents, !contents.isEmpty {
            try Data(contents.utf8).write(to: url)
        } else if FileManager.default.fileExists(atPath: url.path) {
            try FileManager.default.removeItem(at: url)
        }
    }

    /// olcRTC is addressed by parameters rather than by a config document, so it
    /// crosses to the extension as JSON — the shape `OlcrtcEngine.Parameters`
    /// decodes on the other side.
    private static func olcrtcParameters(_ request: IosOlcRtcStartRequest?) -> String? {
        guard let request else { return nil }
        let fields: [String: Any] = [
            "carrierName": request.carrierName,
            "transportName": request.transportName,
            "roomId": request.roomId,
            "clientId": request.clientId,
            "keyHex": request.keyHex,
            "socksPort": Int(request.socksPort),
            "socksUser": request.socksUser,
            "socksPass": request.socksPass,
            "vp8Fps": Int(request.vp8Fps),
            "vp8BatchSize": Int(request.vp8BatchSize)
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: fields) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func stop() {
        running = false
        Task { @MainActor in Self.controller.stop() }
    }

    /// What the system says, not what we asked for. See `systemConnected`.
    func isRunning() -> Bool { PacketTunnelController.systemConnected }

    /// See `systemConnectedSinceMs`. Zero when nothing is up, which Kotlin
    /// reads as "no session".
    func connectedSinceEpochMs() -> Int64 { PacketTunnelController.systemConnectedSinceMs }
}

/// Kotlin's callback, in terms Swift's concurrency checking accepts.
///
/// Objects crossing from Kotlin/Native carry no Sendable annotation, but they
/// are safe to call from any thread under its memory model. Saying so once here
/// is better than an `@unchecked` at every use.
private struct SendableCallback: @unchecked Sendable {
    let callback: IosBridgeCallback
}

