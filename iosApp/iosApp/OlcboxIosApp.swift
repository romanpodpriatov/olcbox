import SwiftUI
import SharedUI

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

    var body: some View {
        VStack(alignment: .trailing, spacing: 6) {
            Text(tunnel.status)
                .font(.caption2.monospaced())
                .padding(.horizontal, 6)
                .padding(.vertical, 3)
                .background(.black.opacity(0.6))
                .foregroundStyle(.white)
                .clipShape(RoundedRectangle(cornerRadius: 4))

            HStack(spacing: 6) {
                Button("tun on") { Task { await tunnel.start() } }
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
