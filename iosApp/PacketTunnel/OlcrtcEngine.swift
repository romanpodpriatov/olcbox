import Cores
import Foundation
import os

/// olcRTC, running inside the tunnel extension.
///
/// It used to run inside the app instead, as a SOCKS proxy on loopback that
/// other apps had to be pointed at by hand and that survived backgrounding only
/// by playing silent audio to stop iOS suspending the process. Here it is a
/// transport like any other: sing-box owns the tun and reaches this engine over
/// a loopback SOCKS port, the same arrangement xhttp already uses for Xray.
///
/// olcRTC's whole API is a set of package functions plus two callbacks, so this
/// is a namespace rather than an object — there is nothing to hold.
enum OlcrtcEngine {

    /// What the app writes into the App Group for us. A room and a key address
    /// an olcRTC location; there is no link to parse.
    struct Parameters: Decodable {
        let carrierName: String
        let transportName: String
        let roomId: String
        let clientId: String
        let keyHex: String
        let socksPort: Int
        let socksUser: String
        let socksPass: String
        let vp8Fps: Int
        let vp8BatchSize: Int
    }

    private static let log = Logger(subsystem: "org.proofkit.app", category: "olcrtc")

    /// Held for the lifetime of the process: olcRTC keeps whatever is handed to
    /// `SetProtector`/`SetLogWriter`, and Go's reference does not keep a Swift
    /// object alive on its own.
    private static let protector = InterfaceProtector()
    private static let logWriter = EngineLog()

    /// How long to wait for the engine to answer before calling it a failure.
    /// The same eight seconds the in-app path used, which is long enough for a
    /// WebRTC negotiation on a slow network and short enough that a user waiting
    /// on the connect button does not think the app has died.
    private static let readyTimeoutMillis = 8_000

    static func start(_ parameters: Parameters) throws {
        MobileSetLogWriter(logWriter)
        // Before anything dials: inside the extension the default route is our
        // own tun, so an unprotected socket loops straight back into it.
        MobileSetProtector(protector)
        MobileSetProviders()
        MobileSetTransport(parameters.transportName)
        MobileSetDNS("1.1.1.1:53")
        MobileSetVP8Options(parameters.vp8Fps, parameters.vp8BatchSize)
        // Loopback only. The port is fixed rather than user-set now: nothing
        // outside this process is meant to reach it.
        MobileSetSocksListenHost("127.0.0.1")

        // A previous tunnel that died without tearing down would otherwise hold
        // the port and make this look like a bind failure.
        if MobileIsRunning() {
            MobileStop()
        }

        var error: NSError?
        let started = MobileStartWithTransport(
            parameters.carrierName,
            parameters.transportName,
            parameters.roomId,
            parameters.clientId,
            parameters.keyHex,
            parameters.socksPort,
            parameters.socksUser,
            parameters.socksPass,
            &error
        )
        guard started else {
            throw failure(error, "olcRTC would not start")
        }

        guard MobileWaitReady(readyTimeoutMillis, &error) else {
            // Leaving a half-started engine behind would hold the SOCKS port
            // against the next attempt.
            MobileStop()
            throw failure(error, "olcRTC did not become ready")
        }
        log.info("olcrtc ready on 127.0.0.1:\(parameters.socksPort, privacy: .public)")
    }

    static func stop() {
        // Unconditional teardown runs on every tunnel stop, including tunnels
        // that never involved olcRTC at all.
        if MobileIsRunning() {
            MobileStop()
        }
    }

    private static func failure(_ error: NSError?, _ fallback: String) -> NSError {
        NSError(domain: "org.proofkit.tunnel", code: 5,
                userInfo: [NSLocalizedDescriptionKey: error?.localizedDescription ?? fallback])
    }
}

/// Keeps olcRTC's own sockets off our tun.
///
/// The engine dials from inside the extension, where the default route points
/// at the tun sing-box owns — so a socket left to the system's judgement comes
/// straight back to us. This is the same pin sing-box needs for its outbounds,
/// and deliberately the same implementation.
private final class InterfaceProtector: NSObject, MobileSocketProtectorProtocol {
    func protect(_ fd: Int) -> Bool {
        LibboxPlatform.pinToPhysicalInterface(Int32(fd))
    }
}

/// olcRTC explains itself through a callback rather than stderr, so its lines
/// do not reach the engine.log the provider redirects sing-box into. Sending
/// them to the system log at least puts them in the same place as ours.
private final class EngineLog: NSObject, MobileLogWriterProtocol {
    private let log = Logger(subsystem: "org.proofkit.app", category: "olcrtc")

    func writeLog(_ msg: String?) {
        guard let msg else { return }
        log.info("\(msg, privacy: .public)")
    }
}
