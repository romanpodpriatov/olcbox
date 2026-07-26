import Foundation
import Libbox
import NetworkExtension
import os

/// The half of sing-box that has to be written per platform.
///
/// libbox runs the whole engine in-process and asks the host for the few things
/// only the host can do — chiefly a tun to own. On iOS there is no tun device to
/// open: the system hands the extension an `NEPacketTunnelFlow`, and the file
/// descriptor behind it is what libbox needs. That is the one interesting method
/// here; the rest are Android concerns or capabilities iOS does not expose.
final class LibboxPlatform: NSObject, LibboxPlatformInterfaceProtocol {

    /// Fixed on both sides rather than read from libbox's options.
    ///
    /// The app writes the config, so the tun inbound in it and the settings
    /// applied here are two halves of one decision — keeping them as constants
    /// in one place is clearer than parsing them back out of the engine, and it
    /// avoids depending on option types whose shape changes between releases.
    enum Tun {
        static let address = "172.19.0.1"
        static let mask = "255.255.255.252"
        static let mtu = 9000
        static let dns = ["1.1.1.1", "8.8.8.8"]
    }

    private weak var provider: NEPacketTunnelProvider?
    private let log = Logger(subsystem: "org.proofkit.app", category: "libbox")

    init(provider: NEPacketTunnelProvider) {
        self.provider = provider
        super.init()
    }

    // MARK: - the one that matters

    /// Applies the tunnel settings and hands libbox the descriptor behind
    /// `packetFlow`.
    ///
    /// `socket.fileDescriptor` is not public API. It is, however, how every
    /// libbox-based iOS client does this — the alternative is copying every
    /// packet between Swift and Go, which the memory budget here does not allow.
    func openTun(_ options: LibboxTunOptionsProtocol?, ret0_: UnsafeMutablePointer<Int32>?) throws {
        guard let provider else {
            throw NSError(domain: "org.proofkit.tunnel", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "provider went away"])
        }

        // Nothing slow happens here on purpose. libbox calls this synchronously
        // from its own start-up path and warns when it blocks — the first version
        // applied the tunnel settings here and waited on the callback, which is
        // what produced "open interface take too much time". The settings are
        // applied before the engine starts now, so this only hands over the
        // descriptor.
        guard let fd = Self.tunnelFileDescriptor(of: provider.packetFlow) else {
            throw NSError(domain: "org.proofkit.tunnel", code: 2,
                          userInfo: [NSLocalizedDescriptionKey: "no descriptor behind packetFlow"])
        }
        log.info("tun opened, fd=\(fd, privacy: .public)")
        ret0_?.pointee = fd
    }

    /// The settings libbox's tun inbound is configured to expect.
    static func tunnelSettings() -> NEPacketTunnelNetworkSettings {
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: Tun.address)
        let ipv4 = NEIPv4Settings(addresses: [Tun.address], subnetMasks: [Tun.mask])
        ipv4.includedRoutes = [NEIPv4Route.default()]
        settings.ipv4Settings = ipv4
        settings.mtu = NSNumber(value: Tun.mtu)

        let dns = NEDNSSettings(servers: Tun.dns)
        dns.matchDomains = [""]
        settings.dnsSettings = dns
        return settings
    }

    private static func tunnelFileDescriptor(of flow: NEPacketTunnelFlow) -> Int32? {
        if let value = flow.value(forKeyPath: "socket.fileDescriptor") as? Int32 {
            return value
        }
        // Older runtimes box it differently; try the number form before giving up.
        if let number = flow.value(forKeyPath: "socket.fileDescriptor") as? NSNumber {
            return number.int32Value
        }
        return nil
    }

    // MARK: - things iOS answers plainly

    func underNetworkExtension() -> Bool { true }

    /// procfs is a Linux notion; there is nothing to read here.
    func useProcFS() -> Bool { false }

    /// Let Go bind sockets itself rather than routing every one through Swift.
    func usePlatformAutoDetectControl() -> Bool { false }

    /// Only meaningful with a multipath configuration we do not use.
    func includeAllNetworks() -> Bool { false }

    func writeLog(_ message: String?) {
        guard let message else { return }
        log.info("\(message, privacy: .public)")
    }

    func clearDNSCache() {
        // The system resolver is bypassed entirely — the engine does its own DNS.
    }

    // MARK: - Android-only, and unsupported capabilities

    func autoDetectControl(_ fd: Int32) throws {
        // Never called: usePlatformAutoDetectControl() is false.
    }

    func findConnectionOwner(
        _ ipProtocol: Int32,
        sourceAddress: String?,
        sourcePort: Int32,
        destinationAddress: String?,
        destinationPort: Int32,
        ret0_: UnsafeMutablePointer<Int32>?
    ) throws {
        throw Self.unsupported("connection ownership is not visible on iOS")
    }

    /// Not `throws`: the Objective-C method returns a non-optional string, so
    /// Swift keeps the error parameter explicit instead of converting it.
    func packageName(byUid uid: Int32, error: NSErrorPointer) -> String {
        error?.pointee = Self.unsupported("no package names on iOS")
        return ""
    }

    func uid(byPackageName packageName: String?, ret0_: UnsafeMutablePointer<Int32>?) throws {
        throw Self.unsupported("no package names on iOS")
    }

    /// Reported as unavailable rather than faked: sing-box only asks when a rule
    /// depends on it, and a wrong answer would route traffic on a false premise.
    func getInterfaces() throws -> LibboxNetworkInterfaceIteratorProtocol {
        throw Self.unsupported("interface enumeration is not implemented")
    }

    func readWIFIState() -> LibboxWIFIState? { nil }

    func send(_ notification: LibboxNotification?) throws {
        // The extension has no UI; anything worth saying goes to the log.
    }

    func startDefaultInterfaceMonitor(_ listener: LibboxInterfaceUpdateListenerProtocol?) throws {
        // No monitor yet. The cost is that a Wi-Fi/cellular switch is not noticed
        // until the connection itself fails, which is worth fixing once the
        // transports work but would only obscure the first bring-up.
    }

    func closeDefaultInterfaceMonitor(_ listener: LibboxInterfaceUpdateListenerProtocol?) throws {
    }

    private static func unsupported(_ what: String) -> NSError {
        NSError(domain: "org.proofkit.tunnel", code: 3,
                userInfo: [NSLocalizedDescriptionKey: what])
    }
}
