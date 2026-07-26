import Darwin
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
        // The key path every libbox client uses. It stopped answering on iOS 26,
        // so it is tried first and no longer trusted.
        if let value = flow.value(forKeyPath: "socket.fileDescriptor") as? Int32 {
            return value
        }
        if let number = flow.value(forKeyPath: "socket.fileDescriptor") as? NSNumber {
            return number.int32Value
        }
        return findUtunDescriptor()
    }

    /// Finds the tunnel descriptor by asking each open socket what interface it
    /// is, rather than by reaching into a private property.
    ///
    /// The extension owns exactly one utun — the one the system just created for
    /// this tunnel — so the first match is the right one. Slower than a key path
    /// and considerably harder for a system update to take away.
    private static func findUtunDescriptor() -> Int32? {
        let controlProtocol: Int32 = 2   // SYSPROTO_CONTROL
        let interfaceNameOption: Int32 = 2   // UTUN_OPT_IFNAME

        for fd in Int32(0) ..< Int32(1024) {
            var name = [CChar](repeating: 0, count: Int(IFNAMSIZ))
            var length = socklen_t(name.count)
            let result = getsockopt(fd, controlProtocol, interfaceNameOption, &name, &length)
            guard result == 0 else { continue }
            let interface = String(cString: name)
            if interface.hasPrefix("utun") {
                return fd
            }
        }
        return nil
    }

    // MARK: - things iOS answers plainly

    func underNetworkExtension() -> Bool { true }

    /// procfs is a Linux notion; there is nothing to read here.
    func useProcFS() -> Bool { false }

    /// Every outbound socket has to be pinned to the physical interface.
    ///
    /// The default route now points into our own tun, so a socket left to the
    /// system's judgement comes straight back to us and dials forever. sing-box
    /// said so plainly: "open outbound connection: dial tcp …: i/o timeout"
    /// while its inbound side was happily receiving the same connection.
    func usePlatformAutoDetectControl() -> Bool { true }

    /// Only meaningful with a multipath configuration we do not use.
    func includeAllNetworks() -> Bool { false }

    func writeLog(_ message: String?) {
        guard let message else { return }
        log.info("\(message, privacy: .public)")
        Self.appendEngineLog(message)
    }

    /// sing-box explains itself in these lines, and the system log they go to
    /// never reaches the person debugging. Keeping the last few in the shared
    /// container costs nothing and is the difference between a diagnosis and a
    /// guess.
    private static let engineLogQueue = DispatchQueue(label: "org.proofkit.enginelog")
    private static var engineLog: [String] = []

    private static func appendEngineLog(_ message: String) {
        engineLogQueue.async {
            engineLog.append(message)
            if engineLog.count > 12 { engineLog.removeFirst(engineLog.count - 12) }
            guard let container = FileManager.default.containerURL(
                forSecurityApplicationGroupIdentifier: "group.org.proofkit.app"
            ) else { return }
            let text = engineLog.joined(separator: "\n")
            try? Data(text.utf8).write(to: container.appendingPathComponent("engine.log"))
        }
    }

    func clearDNSCache() {
        // The system resolver is bypassed entirely — the engine does its own DNS.
    }

    // MARK: - Android-only, and unsupported capabilities

    func autoDetectControl(_ fd: Int32) throws {
        guard let index = Self.physicalInterfaceIndex() else {
            // Better to let it try than to fail the connection outright: without
            // a physical interface there is nothing to bind to anyway.
            return
        }
        var scope = index
        let size = socklen_t(MemoryLayout<UInt32>.size)
        // IP_BOUND_IF / IPV6_BOUND_IF. Both are set because the socket family is
        // not known here and the wrong one simply fails harmlessly.
        setsockopt(fd, IPPROTO_IP, 25, &scope, size)
        setsockopt(fd, IPPROTO_IPV6, 125, &scope, size)
    }

    /// The interface the device actually reaches the internet through.
    ///
    /// Wi-Fi wins over cellular when both are up, matching what the system would
    /// have chosen if our tun were not in the way.
    private static func physicalInterfaceIndex() -> UInt32? {
        var addresses: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&addresses) == 0, let first = addresses else { return nil }
        defer { freeifaddrs(addresses) }

        var cellular: UInt32?
        for entry in sequence(first: first, next: { $0.pointee.ifa_next }) {
            guard entry.pointee.ifa_addr?.pointee.sa_family == UInt8(AF_INET),
                  entry.pointee.ifa_flags & UInt32(IFF_UP) != 0,
                  entry.pointee.ifa_flags & UInt32(IFF_LOOPBACK) == 0
            else { continue }

            let name = String(cString: entry.pointee.ifa_name)
            if name.hasPrefix("en") {
                return if_nametoindex(name)
            }
            if name.hasPrefix("pdp_ip"), cellular == nil {
                cellular = if_nametoindex(name)
            }
        }
        return cellular
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
