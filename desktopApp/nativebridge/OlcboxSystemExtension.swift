// The JVM's half of the system-extension handshake, exposed as plain C.
//
// Installing a system extension is a request the *host app* makes — the process
// asking must be the signed app in /Applications, so it cannot be delegated to a
// helper binary. The Compose UI is a JVM process, and OSSystemExtensionManager is
// Objective-C API, so something native has to sit between them. This is it, kept
// to the smallest surface that answers "is the extension installed and running":
// ask, then poll.
//
// Polling rather than callbacks into the JVM on purpose. The delegate fires on
// the main queue, JNA callbacks land on whichever thread the JVM lends, and
// bridging an async Apple delegate into JVM threads is a source of crashes that
// cost far more than a status integer read once a second.
import Foundation
import SystemExtensions

/// Mirrored in the Kotlin side; see MacOsSystemExtension.Status.
@objc private enum BridgeStatus: Int32 {
    case idle = 0
    case requested = 1
    case needsUserApproval = 2
    case activated = 3
    case failed = 4
    /// The app is not where macOS will accept the request from.
    case notInApplications = 5
}

private final class ActivationDelegate: NSObject, OSSystemExtensionRequestDelegate {
    static let shared = ActivationDelegate()

    private let lock = NSLock()
    private var status: BridgeStatus = .idle
    private var message: String = ""

    func set(_ status: BridgeStatus, _ message: String) {
        lock.lock()
        defer { lock.unlock() }
        self.status = status
        self.message = message
    }

    func read() -> (BridgeStatus, String) {
        lock.lock()
        defer { lock.unlock() }
        return (status, message)
    }

    // An already-installed extension of any version is replaced. Returning
    // .cancel here is what makes an app go on running a build it replaced weeks
    // ago while every version string in the tree says otherwise.
    func request(
        _ request: OSSystemExtensionRequest,
        actionForReplacingExtension existing: OSSystemExtensionProperties,
        withExtension ext: OSSystemExtensionProperties
    ) -> OSSystemExtensionRequest.ReplacementAction {
        set(.requested, "replacing \(existing.bundleVersion) with \(ext.bundleVersion)")
        return .replace
    }

    func requestNeedsUserApproval(_ request: OSSystemExtensionRequest) {
        set(.needsUserApproval, "waiting for approval in System Settings > General > Login Items & Extensions")
    }

    func request(
        _ request: OSSystemExtensionRequest,
        didFinishWithResult result: OSSystemExtensionRequest.Result
    ) {
        switch result {
        case .completed:
            set(.activated, "activated")
        case .willCompleteAfterReboot:
            set(.needsUserApproval, "activation completes after a reboot")
        @unknown default:
            set(.activated, "activated (unrecognised result \(result.rawValue))")
        }
    }

    func request(_ request: OSSystemExtensionRequest, didFailWithError error: Error) {
        // The code first, then where this process thinks it lives.
        //
        // OSSystemExtensionError codes are the only thing that distinguishes "you
        // are not in /Applications" (3) from "no such extension" (4) from "bad
        // signature" (8) from "system policy" (10), and sysextd's own log says
        // none of them — it prints one generic line about /Applications whatever
        // the reason. The bundle path is here beside it because that generic line
        // was emitted for an app that was demonstrably in /Applications, and the
        // two claims cannot both be checked from outside the process.
        let ns = error as NSError
        set(
            .failed,
            "\(ns.domain) code \(ns.code): \(ns.localizedDescription) " +
                "[bundle: \(Bundle.main.bundlePath)]"
        )
    }
}

/// True when the running app is inside /Applications, which macOS requires
/// before it will even consider a system-extension request.
private func runningFromApplications() -> Bool {
    Bundle.main.bundlePath.hasPrefix("/Applications/")
}

@_cdecl("olcbox_ne_activate")
public func olcbox_ne_activate(_ identifier: UnsafePointer<CChar>) -> Int32 {
    guard runningFromApplications() else {
        ActivationDelegate.shared.set(
            .notInApplications,
            "running from \(Bundle.main.bundlePath); macOS accepts the request only from /Applications"
        )
        return BridgeStatus.notInApplications.rawValue
    }

    let bundleId = String(cString: identifier)
    ActivationDelegate.shared.set(.requested, "submitted")
    let request = OSSystemExtensionRequest.activationRequest(
        forExtensionWithIdentifier: bundleId,
        queue: .main
    )
    request.delegate = ActivationDelegate.shared
    OSSystemExtensionManager.shared.submitRequest(request)
    return BridgeStatus.requested.rawValue
}

@_cdecl("olcbox_ne_deactivate")
public func olcbox_ne_deactivate(_ identifier: UnsafePointer<CChar>) -> Int32 {
    let bundleId = String(cString: identifier)
    ActivationDelegate.shared.set(.requested, "deactivation submitted")
    let request = OSSystemExtensionRequest.deactivationRequest(
        forExtensionWithIdentifier: bundleId,
        queue: .main
    )
    request.delegate = ActivationDelegate.shared
    OSSystemExtensionManager.shared.submitRequest(request)
    return BridgeStatus.requested.rawValue
}

@_cdecl("olcbox_ne_status")
public func olcbox_ne_status() -> Int32 {
    ActivationDelegate.shared.read().0.rawValue
}

/// Copies the last status message into `buffer`, NUL-terminated, and returns the
/// number of bytes written. A caller-owned buffer rather than a returned pointer:
/// nothing then has to agree about who frees what across the JNA boundary, which
/// is the usual way a bridge like this leaks or double-frees.
@_cdecl("olcbox_ne_message")
public func olcbox_ne_message(_ buffer: UnsafeMutablePointer<CChar>, _ capacity: Int32) -> Int32 {
    guard capacity > 0 else { return 0 }
    let message = ActivationDelegate.shared.read().1
    let bytes = Array(message.utf8.prefix(Int(capacity) - 1))
    bytes.withUnsafeBufferPointer { src in
        buffer.withMemoryRebound(to: UInt8.self, capacity: Int(capacity)) { dst in
            dst.update(from: src.baseAddress!, count: bytes.count)
        }
    }
    buffer[bytes.count] = 0
    return Int32(bytes.count)
}
