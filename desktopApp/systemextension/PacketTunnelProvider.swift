// The macOS packet tunnel provider — deliberately empty for now.
//
// This exists to answer one question before anything is built on top of it: will
// macOS install and activate a system extension that lives inside a JVM app
// bundle signed for the hardened runtime? Every later piece — tunnel settings,
// the Go cores, the routing — depends on that answer and none of it can be
// tested until it is yes. So this provider starts, reports success, and stops.
//
// `startTunnel` returning without calling `setTunnelNetworkSettings` means no
// interface is created and no traffic moves. That is the point: a connection
// that "succeeds" here proves the extension loaded, was allowed to run as a
// provider, and could talk to the app — and proves nothing about tunnelling,
// which is the next slice's job.
import Foundation
import NetworkExtension
import os

private let log = Logger(subsystem: "org.olcbox.app.desktopApp.PacketTunnel", category: "provider")

@objc(PacketTunnelProvider)
final class PacketTunnelProvider: NEPacketTunnelProvider {
    override func startTunnel(
        options: [String: NSObject]?,
        completionHandler: @escaping (Error?) -> Void
    ) {
        log.info("startTunnel: skeleton provider, no tunnel settings applied")
        completionHandler(nil)
    }

    override func stopTunnel(
        with reason: NEProviderStopReason,
        completionHandler: @escaping () -> Void
    ) {
        log.info("stopTunnel: reason \(reason.rawValue, privacy: .public)")
        completionHandler()
    }

    // The app's way of asking the extension whether it is really the build it
    // thinks it is. Without this the only evidence of which extension is loaded
    // is its version in `systemextensionsctl list`, and macOS keeps an activated
    // extension across app updates until a new one is approved — so "the code I
    // just built" and "the code that is running" can differ silently. They have,
    // in every project that assumed otherwise.
    override func handleAppMessage(
        _ messageData: Data,
        completionHandler: ((Data?) -> Void)?
    ) {
        let version = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "unknown"
        completionHandler?(Data("olcbox-ne skeleton \(version)".utf8))
    }
}
