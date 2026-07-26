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
    @State private var stage = "stage: -"
    @State private var engine = ""
    @AppStorage("debug.link") private var link = ""

    /// Builds the config with the same code every other platform uses.
    ///
    /// The first version parsed the link in Swift, which meant iOS would drift
    /// from Android and desktop the first time a transport gained a field.
    /// LinkParser and SingBoxConfig are already validated against sing-box
    /// 1.11.15 in CI; iOS inherits that rather than re-earning it.
    ///
    /// The link is typed in on the device and never committed: this repository is
    /// public and a subscription link is a working credential.
    private static func configFor(link: String) -> String? {
        let trimmed = link.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let spec = LinkParser.shared.parse(line: trimmed) else { return nil }
        return SingBoxConfig.shared.buildTun(
            outbound: spec,
            address: SingBoxConfig.shared.TUN_ADDRESS,
            mtu: SingBoxConfig.shared.TUN_MTU
        )
    }

    /// Writes the config the extension will hand to sing-box.
    ///
    /// A direct outbound first, deliberately. If the tunnel comes up and traffic
    /// flows with this, then libbox owns the tun and moves packets — and when a
    /// real server is added afterwards, a failure can only be the server. Proving
    /// both at once means diagnosing both at once.
    ///
    /// The tun block must agree with LibboxPlatform.Tun in the extension: the app
    /// decides the addressing and the extension applies it to the system.
    /// Whatever the extension managed to write before it stopped.
    private static func readStage() -> String {
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: appGroupId
        ), let data = try? Data(contentsOf: container.appendingPathComponent("stage.txt")),
              let text = String(data: data, encoding: .utf8)
        else { return "stage: -" }
        return "stage: \(text)"
    }

    /// The last lines sing-box wrote about itself.
    private static func readEngineLog() -> String {
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: appGroupId
        ), let data = try? Data(contentsOf: container.appendingPathComponent("engine.log")),
              let text = String(data: data, encoding: .utf8)
        else { return "" }
        return text
    }

    private static func writeConfig(json: String?) -> String {
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: appGroupId
        ) else { return "no container" }

        // With no link, the direct outbound that proved the plumbing.
        let content = json ?? #"{"log":{"level":"info"},"inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30"],"mtu":9000,"auto_route":true,"stack":"gvisor"}],"outbounds":[{"type":"direct","tag":"direct"}]}"#

        do {
            try Data(content.utf8).write(to: container.appendingPathComponent("config.json"))
            return "config \(content.utf8.count)B"
        } catch {
            return "write failed"
        }
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

            if !engine.isEmpty {
                ScrollView {
                    Text(engine)
                        .font(.system(size: 8).monospaced())
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .textSelection(.enabled)
                }
                .frame(width: 300, height: 150)
                .padding(4)
                .background(.black.opacity(0.75))
                .clipShape(RoundedRectangle(cornerRadius: 4))
            }

            Text(stage)
                .font(.caption2.monospaced())
                .padding(.horizontal, 6)
                .padding(.vertical, 3)
                .background(.black.opacity(0.6))
                .foregroundStyle(stage.contains("ready") ? .green : .yellow)
                .clipShape(RoundedRectangle(cornerRadius: 4))

            Text(channel)
                .font(.caption2.monospaced())
                .padding(.horizontal, 6)
                .padding(.vertical, 3)
                .background(.black.opacity(0.6))
                .foregroundStyle(channelOK ? .green : .orange)
                .clipShape(RoundedRectangle(cornerRadius: 4))

            TextField("paste vless:// or hysteria2:// link", text: $link)
                .font(.system(size: 9).monospaced())
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(4)
                .background(.white.opacity(0.9))
                .clipShape(RoundedRectangle(cornerRadius: 4))
                .frame(width: 300)

            HStack(spacing: 6) {
                Button("tun on") {
                    Task {
                        let json = Self.configFor(link: link)
                        channel = Self.writeConfig(json: json)
                            + (json == nil ? " (direct)" : " (proxy)")
                        channelOK = channel.hasPrefix("config")
                        stage = "stage: -"
                        await tunnel.start()
                        // Poll rather than wait once: the interesting case is the
                        // extension dying part-way, and then the last stage it
                        // wrote is the answer.
                        for _ in 0..<10 {
                            try? await Task.sleep(nanoseconds: 700_000_000)
                            stage = Self.readStage()
                            engine = Self.readEngineLog()
                        }
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
