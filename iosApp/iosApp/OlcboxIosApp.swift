import Combine
import NetworkExtension
import SwiftUI
import SharedUI
import os

@main
struct OlcboxIosApp: App {
    private let platformBridge: SwiftPlatformBridge
    private let olcRtcBridge: SwiftOlcRtcManager
    private let appSession: IosAppSession

    init() {
        let platformBridge = SwiftPlatformBridge()
        let olcRtcBridge = SwiftOlcRtcManager()
        self.platformBridge = platformBridge
        self.olcRtcBridge = olcRtcBridge
        self.appSession = IosAppFactory().createSession(
            platformBridge: platformBridge,
            olcRtcBridge: olcRtcBridge
        )
    }

    var body: some Scene {
        WindowGroup {
            ComposeHostView(
                platformBridge: platformBridge,
                appSession: appSession
            )
            .ignoresSafeArea()
            #if DEBUG
            // Scaffolding, not product: the real Connect lives in the shared UI
            // and drives this same controller once the tunnel is proven on a
            // device. Until then there has to be something to press.
            .overlay(alignment: .bottomTrailing) { TunnelDebugControl() }
            #endif
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

#if DEBUG
/// Temporary: starts and stops the packet tunnel so the entitlement, the
/// provisioning profile and the app↔extension wiring can be checked on hardware
/// before any transport exists to confuse the result.
private struct TunnelDebugControl: View {
    @StateObject private var tunnel = PacketTunnelController()

    private static let appGroupId = "group.org.proofkit.app"

    /// The app and the extension share this container; if the app cannot see it,
    /// neither can the extension, and the config would never arrive.
    private static let appGroupOK = FileManager.default.containerURL(
        forSecurityApplicationGroupIdentifier: appGroupId
    ) != nil
    private static let appGroupState = appGroupOK ? "group OK" : "group MISSING"

    @State private var channel = "channel: untested"
    @State private var channelOK = false

    /// Leaves a config for the extension and reports what came back.
    ///
    /// A token per attempt, so a stale echo from an earlier run cannot be
    /// mistaken for a fresh success — which is exactly the kind of false green
    /// that costs an afternoon.
    private static func writeConfig() -> String {
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: appGroupId
        ) else { return "no container" }

        let token = UUID().uuidString.prefix(8).lowercased()
        let payload: [String: Any] = ["token": String(token), "written": Date().timeIntervalSince1970]
        let echoURL = container.appendingPathComponent("echo.json")
        try? FileManager.default.removeItem(at: echoURL)

        guard let data = try? JSONSerialization.data(withJSONObject: payload) else { return "encode failed" }
        do {
            try data.write(to: container.appendingPathComponent("config.json"))
            return String(token)
        } catch {
            return "write failed"
        }
    }

    private static func readEcho() -> String? {
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: appGroupId
        ), let data = try? Data(contentsOf: container.appendingPathComponent("echo.json")),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }
        guard obj["seen"] as? Bool == true else { return "extension saw nothing" }
        return obj["token"] as? String
    }

    var body: some View {
        VStack(alignment: .trailing, spacing: 6) {
            // Hunting one line in Console means reading the whole device's log
            // firehose. The answer fits on screen.
            Text(Self.appGroupState)
                .font(.caption2.monospaced())
                .padding(.horizontal, 6)
                .padding(.vertical, 3)
                .background(.black.opacity(0.6))
                .foregroundStyle(Self.appGroupOK ? .green : .red)
                .clipShape(RoundedRectangle(cornerRadius: 4))

            Text(tunnel.status)
                .font(.caption2.monospaced())
                .padding(.horizontal, 6)
                .padding(.vertical, 3)
                .background(.black.opacity(0.6))
                .foregroundStyle(.white)
                .clipShape(RoundedRectangle(cornerRadius: 4))

            Text(channel)
                .font(.caption2.monospaced())
                .padding(.horizontal, 6)
                .padding(.vertical, 3)
                .background(.black.opacity(0.6))
                .foregroundStyle(channelOK ? .green : .orange)
                .clipShape(RoundedRectangle(cornerRadius: 4))

            HStack(spacing: 6) {
                Button("tun on") {
                    Task {
                        let sent = Self.writeConfig()
                        channel = "sent \(sent)"
                        channelOK = false
                        await tunnel.start()
                        // The extension writes its echo while starting up; give
                        // it a moment rather than racing it. nanoseconds rather
                        // than .seconds: the latter needs iOS 16 and this app
                        // still supports older.
                        try? await Task.sleep(nanoseconds: 2_000_000_000)
                        let seen = Self.readEcho()
                        channelOK = (seen == sent)
                        channel = channelOK ? "channel OK \(sent)" : "echo: \(seen ?? "none")"
                    }
                }
                Button("off") { tunnel.stop() }
            }
            .font(.caption)
            .buttonStyle(.borderedProminent)
        }
        .padding(12)
        .task { await tunnel.prepare() }
    }
}
#endif

/// Installs and starts the packet tunnel extension.
///
/// The extension cannot start itself: iOS runs it only on behalf of a saved VPN
/// configuration that the containing app creates. This is that side of the wiring,
/// deliberately separate from the Compose UI — the Connect button belongs to the
/// shared layer and comes once the tunnel is proven on a device.
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
