// The sing-box the daemon runs, and where it is allowed to run it from.
//
// Never from a path the client sends, and never from a directory a non-root user
// can write. /Applications is writable by any admin, so a binary verified there
// and executed there can be swapped between the two; the bundled core is
// therefore verified, copied into a root-owned directory, and executed from the
// copy. Without that, this daemon is a way for any admin to run their own code
// as root.
import Foundation

enum DaemonError: Error {
    case message(String)

    var text: String {
        if case let .message(value) = self { return value }
        return "unknown error"
    }
}

final class TunnelChild {
    static let stateDir = URL(
        fileURLWithPath: "/Library/Application Support/org.olcbox.app",
        isDirectory: true
    )

    private var process: Process?
    private var tail: [String] = []
    private let lock = NSLock()

    var isRunning: Bool { locked { process?.isRunning ?? false } }

    var pid: Int32? { locked { process?.isRunning == true ? process?.processIdentifier : nil } }

    var logTail: String { locked { tail.suffix(40).joined(separator: "\n") } }

    /// The bundled core, found relative to this binary: the daemon lives at
    /// `ProofKit.app/Contents/MacOS/ProofKitTunnelDaemon` and the core at
    /// `ProofKit.app/Contents/Resources/sing-box`. Not `Bundle.main` — a launchd
    /// daemon's main bundle is not reliably the app that carries it.
    private var bundledCore: URL {
        URL(fileURLWithPath: CommandLine.arguments[0])
            .resolvingSymlinksInPath()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("Resources/sing-box")
    }

    func start(config: String) throws {
        stop()

        let source = bundledCore
        guard FileManager.default.isReadableFile(atPath: source.path) else {
            throw DaemonError.message("no bundled sing-box at \(source.path)")
        }
        guard PeerAuthority.isTrustedBinary(at: source) else {
            throw DaemonError.message("the bundled sing-box is not signed by this team")
        }

        try makeRootOnlyDirectory(Self.stateDir)
        let binDir = Self.stateDir.appendingPathComponent("bin", isDirectory: true)
        try makeRootOnlyDirectory(binDir)

        let core = binDir.appendingPathComponent("sing-box")
        try? FileManager.default.removeItem(at: core)
        try FileManager.default.copyItem(at: source, to: core)
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o700, .ownerAccountID: 0],
            ofItemAtPath: core.path
        )

        let configURL = Self.stateDir.appendingPathComponent("tun.json")
        try config.write(to: configURL, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o600, .ownerAccountID: 0],
            ofItemAtPath: configURL.path
        )

        let task = Process()
        task.executableURL = core
        task.arguments = ["run", "-c", configURL.path]
        let pipe = Pipe()
        task.standardOutput = pipe
        task.standardError = pipe
        pipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
            guard let self else { return }
            let data = handle.availableData
            guard !data.isEmpty, let text = String(data: data, encoding: .utf8) else { return }
            self.locked {
                self.tail.append(contentsOf: text.split(separator: "\n").map(String.init))
                if self.tail.count > 200 { self.tail.removeFirst(self.tail.count - 200) }
            }
        }
        try task.run()
        locked {
            self.process = task
            self.tail = []
        }
    }

    func stop() {
        let running: Process? = locked {
            defer { process = nil }
            return process?.isRunning == true ? process : nil
        }
        guard let task = running else { return }
        // SIGTERM and then wait, never SIGKILL first: sing-box tears down
        // auto_route on the way out, and killing it outright leaves the machine's
        // default route pointing at a utun that no longer exists — a Mac with no
        // network until it is rebooted.
        task.terminate()
        let deadline = Date().addingTimeInterval(5)
        while task.isRunning && Date() < deadline { usleep(100_000) }
        if task.isRunning { kill(task.processIdentifier, SIGKILL) }
    }

    private func makeRootOnlyDirectory(_ url: URL) throws {
        try FileManager.default.createDirectory(
            at: url,
            withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700, .ownerAccountID: 0]
        )
        // createDirectory leaves an existing directory's attributes alone, and an
        // existing one is the common case after the first run.
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o700, .ownerAccountID: 0],
            ofItemAtPath: url.path
        )
    }

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }
}
