import Foundation
import os

/// A bounded, synchronous trace that also survives a failed carrier bootstrap.
/// Callers pass interface names and error codes, never keys, room URLs or IPs.
enum NetworkDiagnostics {
    private static let lock = NSLock()
    private static let logger = Logger(subsystem: "org.proofkit.app", category: "network-diagnostics")
    // Guarded by `lock`; the annotation is for the day this target moves to
    // Swift 6, whose checker cannot see the lock.
    nonisolated(unsafe) private static var entries = 0
    private static let limit = 400
    private static var file: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.org.proofkit.app")?
            .appendingPathComponent("network-diagnostics.log")
    }

    static func reset() {
        lock.lock()
        defer { lock.unlock() }
        entries = 0
        if let file { try? Data().write(to: file, options: .atomic) }
    }

    static func record(_ event: String) {
        lock.lock()
        defer { lock.unlock() }
        guard entries < limit else { return }
        entries += 1
        let line = "\(Date().timeIntervalSince1970) \(event)\n"
        logger.info("\(event, privacy: .public)")
        guard let file, let handle = try? FileHandle(forWritingTo: file) else { return }
        defer { try? handle.close() }
        do {
            try handle.seekToEnd()
            try handle.write(contentsOf: Data(line.utf8))
        } catch {
            logger.error("network trace write failed")
        }
    }
}
