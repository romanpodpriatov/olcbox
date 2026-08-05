// ProofKitTunnelDaemon — the only part of ProofKit that runs as root.
//
// It has one job: run the bundled sing-box with a tun inbound, on behalf of a
// caller whose code signature it has verified. It parses no share links, speaks
// none of our protocols and keeps no state beyond the child process. Everything
// else stays in the app, where it runs as the user — the smaller this file is,
// the smaller the thing running as root.
import Foundation

let socketPath = "/var/run/org.olcbox.app.tunneld.sock"
let child = TunnelChild()

func reply(_ object: [String: Any]) -> Data {
    let data = (try? JSONSerialization.data(withJSONObject: object))
        ?? Data("{\"ok\":false,\"error\":\"the daemon could not encode its own reply\"}".utf8)
    return data + Data("\n".utf8)
}

func handle(_ line: String) -> Data {
    guard let payload = line.data(using: .utf8),
          let object = try? JSONSerialization.jsonObject(with: payload) as? [String: Any],
          let verb = object["verb"] as? String else {
        return reply(["ok": false, "error": "unparseable request", "logTail": ""])
    }

    switch verb {
    case "start":
        guard let config = object["config"] as? String else {
            return reply(["ok": false, "error": "start without a config", "logTail": ""])
        }
        do {
            try child.start(config: config)
            // Report what the child is doing a moment later, not what it was asked
            // to do. sing-box rejects a bad config in well under a second, and an
            // "ok" issued before that is a lie the app draws as a green light.
            Thread.sleep(forTimeInterval: 1.0)
            guard child.isRunning else {
                return reply([
                    "ok": false,
                    "error": "sing-box exited at once",
                    "logTail": child.logTail
                ])
            }
            return reply([
                "ok": true,
                "state": "running",
                "pid": Int(child.pid ?? 0),
                "logTail": child.logTail
            ])
        } catch {
            let text = (error as? DaemonError)?.text ?? error.localizedDescription
            return reply(["ok": false, "error": text, "logTail": child.logTail])
        }
    case "stop":
        child.stop()
        return reply(["ok": true, "state": "idle", "logTail": child.logTail])
    case "status":
        return reply([
            "ok": true,
            "state": child.isRunning ? "running" : "idle",
            "pid": Int(child.pid ?? 0),
            "logTail": child.logTail
        ])
    default:
        return reply(["ok": false, "error": "unknown verb \(verb)", "logTail": ""])
    }
}

/// Read until the newline that ends a request.
///
/// Not one `recv`: a stream socket may hand over a request in pieces, and a
/// config with a dozen route exclusions is comfortably large enough for that to
/// happen. A single read would parse half a request as a whole one and answer
/// "unparseable" to something perfectly well formed.
func readRequest(_ fd: Int32) -> String? {
    var collected = Data()
    var chunk = [UInt8](repeating: 0, count: 16 * 1024)
    while collected.count < 1_048_576 {
        let read = recv(fd, &chunk, chunk.count, 0)
        if read <= 0 { break }
        collected.append(contentsOf: chunk[0..<read])
        if collected.last == UInt8(ascii: "\n") { break }
        if collected.contains(UInt8(ascii: "\n")) { break }
    }
    guard !collected.isEmpty else { return nil }
    return String(data: collected, encoding: .utf8)
}

func send(_ data: Data, to fd: Int32) {
    data.withUnsafeBytes { buffer in
        var offset = 0
        while offset < buffer.count {
            let written = write(fd, buffer.baseAddress!.advanced(by: offset), buffer.count - offset)
            if written <= 0 { return }
            offset += written
        }
    }
}

// launchd does not remove a socket left behind by a killed daemon, and bind()
// on an existing path fails with EADDRINUSE — which reads as "already running"
// and is not.
unlink(socketPath)

let listener = socket(AF_UNIX, SOCK_STREAM, 0)
guard listener >= 0 else { exit(1) }

var address = sockaddr_un()
address.sun_family = sa_family_t(AF_UNIX)
withUnsafeMutablePointer(to: &address.sun_path) { pointer in
    pointer.withMemoryRebound(to: CChar.self, capacity: MemoryLayout.size(ofValue: address.sun_path)) { path in
        _ = socketPath.withCString { strncpy(path, $0, MemoryLayout.size(ofValue: address.sun_path) - 1) }
    }
}
let addressSize = socklen_t(MemoryLayout<sockaddr_un>.size)
let bound = withUnsafePointer(to: &address) { pointer in
    pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(listener, $0, addressSize) }
}
guard bound == 0, listen(listener, 8) == 0 else { exit(1) }

// 0666, deliberately. The check that matters is the code-signature one below,
// and a mode excluding non-admin users would only make the app fail differently
// for them — with a permission error instead of an honest refusal.
chmod(socketPath, 0o666)

while true {
    let client = accept(listener, nil, nil)
    if client < 0 { continue }

    guard PeerAuthority.isTrusted(fd: client) else {
        send(reply(["ok": false, "error": "unauthorized", "logTail": ""]), to: client)
        close(client)
        continue
    }

    if let line = readRequest(client) {
        send(handle(line.trimmingCharacters(in: .whitespacesAndNewlines)), to: client)
    }
    close(client)
}
