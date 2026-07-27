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

    private static let appGroup = "group.org.proofkit.app"

    /// Verbose engine logging.
    ///
    /// olcRTC keeps almost everything worth reading behind `logger.Debugf`, so
    /// with this off a failed start says "olcRTC start timed out" and not one
    /// word about what it tried — no ICE state, no carrier, no signalling. That
    /// is the whole of what a user, or whoever is debugging for them, ever gets.
    /// Diagnostic switch: set false once it has stopped earning its place.
    private static let verbose = true

    /// Held for the lifetime of the process: olcRTC keeps whatever is handed to
    /// `SetProtector`/`SetLogWriter`, and Go's reference does not keep a Swift
    /// object alive on its own.
    private static let protector = InterfaceProtector()
    private static let logWriter = EngineLog(
        file: FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: appGroup)?
            .appendingPathComponent("olcrtc.log")
    )

    /// How long to wait for the engine to answer before calling it a failure.
    ///
    /// Was eight seconds, inherited from the in-app path. A trace of a failed
    /// start shows ICE reaching two valid candidate pairs — a TURN relay and a
    /// server-reflexive one — inside the first second, both `succeeded`, neither
    /// nominated, and then nothing at all until the timeout. Eight seconds may
    /// simply be short for a negotiation that crosses the Atlantic to an SFU in
    /// Russia and still has DTLS and the VP8 channel ahead of it.
    ///
    /// So: raised, as an experiment that tells the two remaining explanations
    /// apart. If it connects at twelve seconds the answer is that this was too
    /// short and the number wants choosing properly; if it still stops dead
    /// after the same pairs, more time was never the problem and the log now
    /// covers enough of the attempt to say what is.
    private static let readyTimeoutMillis = 20_000

    static func start(_ parameters: Parameters) throws {
        // Fresh per attempt, so whatever the app reads back afterwards belongs
        // to the attempt it is reporting on.
        logWriter.reset()
        MobileSetLogWriter(logWriter)
        MobileSetDebug(verbose)
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
/// Where olcRTC's own account of itself goes.
///
/// The unified log alone was not enough. Reaching it means Console.app, an
/// iPhone in the sidebar, "Include Info Messages" switched on and a filter on
/// the right process — four steps, each of which has silently produced an empty
/// window at least once, for a log that answers the only question that matters
/// when a connect fails. So it is also kept in the App Group, where the app can
/// read it back and put it on screen next to the failure.
private final class EngineLog: NSObject, @unchecked Sendable, MobileLogWriterProtocol {
    private let log = Logger(subsystem: "org.proofkit.app", category: "olcrtc")
    private let file: URL?
    /// Every mutable field below is touched only from this queue. Go calls
    /// `writeLog` from whichever goroutine happens to be logging.
    private let queue = DispatchQueue(label: "org.proofkit.olcrtc-log")

    /// Both ends of the attempt, never the sagging middle.
    ///
    /// A plain tail is the wrong shape here: with ICE tracing on, a few seconds
    /// of candidate checks bury the lines that say which room was joined and
    /// what was negotiated — and those come first. So the opening is kept
    /// whole, the most recent lines are kept whole, and what is dropped between
    /// them is counted rather than hidden.
    private var head: [String] = []
    private var tail: [String] = []
    private var dropped = 0
    private var flushScheduled = false

    private static let headWindow = 150
    private static let tailWindow = 250

    init(file: URL?) {
        self.file = file
        super.init()
    }

    func reset() {
        queue.async { [self] in
            head.removeAll(keepingCapacity: true)
            tail.removeAll(keepingCapacity: true)
            dropped = 0
            if let file { try? Data().write(to: file, options: .atomic) }
        }
    }

    func writeLog(_ msg: String?) {
        guard let msg else { return }
        log.info("\(msg, privacy: .public)")
        guard file != nil else { return }
        queue.async { [self] in
            if head.count < Self.headWindow {
                head.append(msg)
            } else {
                tail.append(msg)
                if tail.count > Self.tailWindow {
                    let over = tail.count - Self.tailWindow
                    tail.removeFirst(over)
                    dropped += over
                }
            }
            scheduleFlush()
        }
    }

    /// Coalesced, because ICE tracing logs faster than a phone should be asked
    /// to rewrite a file. An atomic write per line — a temp file and a rename,
    /// hundreds of times a second, inside an extension with a ~50 MB ceiling —
    /// would risk changing the very outcome this is here to observe.
    private func scheduleFlush() {
        guard !flushScheduled else { return }
        flushScheduled = true
        queue.asyncAfter(deadline: .now() + 0.25) { [self] in
            flushScheduled = false
            persist()
        }
    }

    private func persist() {
        guard let file else { return }
        var out = head
        if dropped > 0 { out.append("… \(dropped) lines dropped …") }
        out += tail
        // Rewritten whole rather than appended: an append interrupted by the
        // process dying can tear the last line, and the last line is the one
        // this exists to read.
        try? Data((out.joined(separator: "\n") + "\n").utf8)
            .write(to: file, options: .atomic)
    }
}
