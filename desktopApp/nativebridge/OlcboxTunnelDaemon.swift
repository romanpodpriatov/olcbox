// Registering the root tunnel daemon, from inside the JVM.
//
// SMAppService is the supported way to install a root daemon since macOS 13, and
// the reason this design ships no installer script: the plist travels inside the
// bundle, the user approves it once in Login Items, and deleting the app takes it
// away again. Nothing is written to /Library that would outlive an uninstall.
//
// Polled rather than called back into, exactly as every other bridge in this app
// is: a JNA callback arrives on whatever thread the JVM lends, and marshalling
// one into an Apple main-queue callback is a class of crash worth more than an
// integer read once a second.
import Foundation
import ServiceManagement
import AppKit

private let plistName = "org.olcbox.app.desktopApp.tunneld.plist"
private var lastMessage = ""

private func currentStatusCode() -> Int32 {
    guard #available(macOS 13.0, *) else { return -1 }
    switch SMAppService.daemon(plistName: plistName).status {
    case .notRegistered: return 0
    case .requiresApproval: return 1
    case .enabled: return 2
    case .notFound: return 3
    @unknown default: return 0
    }
}

@_cdecl("olcbox_tunneld_status")
public func olcbox_tunneld_status() -> Int32 {
    currentStatusCode()
}

@_cdecl("olcbox_tunneld_register")
public func olcbox_tunneld_register() -> Int32 {
    guard #available(macOS 13.0, *) else {
        lastMessage = "the system-wide tunnel needs macOS 13 or newer"
        return -1
    }
    do {
        try SMAppService.daemon(plistName: plistName).register()
        lastMessage = ""
    } catch {
        // Verbatim, not a paraphrase. "Operation not permitted" and "already
        // registered" both mean nothing happened, and only Apple's own text says
        // which of them it was.
        lastMessage = error.localizedDescription
    }
    return currentStatusCode()
}

@_cdecl("olcbox_tunneld_unregister")
public func olcbox_tunneld_unregister() -> Int32 {
    guard #available(macOS 13.0, *) else { return -1 }
    do {
        try SMAppService.daemon(plistName: plistName).unregister()
        lastMessage = ""
    } catch {
        lastMessage = error.localizedDescription
    }
    return currentStatusCode()
}

@_cdecl("olcbox_tunneld_open_settings")
public func olcbox_tunneld_open_settings() {
    guard #available(macOS 13.0, *) else { return }
    SMAppService.openSystemSettingsLoginItems()
}

@_cdecl("olcbox_tunneld_message")
public func olcbox_tunneld_message(_ buffer: UnsafeMutablePointer<CChar>, _ capacity: Int32) -> Int32 {
    guard capacity > 1 else { return 0 }
    let bytes = Array(lastMessage.utf8.prefix(Int(capacity) - 1))
    guard !bytes.isEmpty else { return 0 }
    buffer.withMemoryRebound(to: UInt8.self, capacity: bytes.count) { destination in
        bytes.withUnsafeBufferPointer { source in
            destination.update(from: source.baseAddress!, count: bytes.count)
        }
    }
    return Int32(bytes.count)
}
