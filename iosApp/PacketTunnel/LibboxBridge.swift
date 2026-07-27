import Cores
import Darwin
import Foundation
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

    // sing-box no longer pushes its log lines here — 1.13 keeps them in the
    // daemon and serves them over the command channel instead, and the platform
    // lost `WriteLog` with that change. The lines still reach the shared
    // container: the provider redirects the engine's stderr into `engine.log`
    // before starting it, which also catches the Go panics `WriteLog` never saw.

    func clearDNSCache() {
        // The system resolver is bypassed entirely — the engine does its own DNS.
    }

    // MARK: - Android-only, and unsupported capabilities

    func autoDetectControl(_ fd: Int32) throws {
        _ = Self.pinToPhysicalInterface(fd)
    }

    /// Binds one socket to the interface that actually reaches the internet.
    ///
    /// Shared with olcRTC, which needs exactly this and for exactly the same
    /// reason: any core running beside sing-box dials out from inside the
    /// extension, where the default route now points at our own tun. A second
    /// copy of this would be a second thing to get wrong.
    ///
    /// Returns false only when there is no physical interface to bind to — in
    /// which case there is nothing to dial out of either, so callers that must
    /// answer a boolean can report it and callers that cannot simply proceed.
    static func pinToPhysicalInterface(_ fd: Int32) -> Bool {
        guard let index = physicalInterfaceIndex() else { return false }
        var scope = index
        let size = socklen_t(MemoryLayout<UInt32>.size)
        // IP_BOUND_IF / IPV6_BOUND_IF. Both are set because the socket family is
        // not known here and the wrong one simply fails harmlessly.
        setsockopt(fd, IPPROTO_IP, 25, &scope, size)
        setsockopt(fd, IPPROTO_IPV6, 125, &scope, size)
        return true
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

    /// 1.13 returns a whole owner record rather than a uid, and folded the two
    /// Android package-name lookups into it — so the pair of methods that used to
    /// sit here is gone with them.
    func findConnectionOwner(
        _ ipProtocol: Int32,
        sourceAddress: String?,
        sourcePort: Int32,
        destinationAddress: String?,
        destinationPort: Int32
    ) throws -> LibboxConnectionOwner {
        throw Self.unsupported("connection ownership is not visible on iOS")
    }

    /// Only Android has a DNS resolver worth borrowing. Answering nil leaves the
    /// engine on its own resolver, which is what the config already asks for.
    func localDNSTransport() -> LibboxLocalDNSTransportProtocol? { nil }

    /// New in 1.13, and only meaningful where the platform holds a trust store
    /// the engine cannot read. iOS pins nothing here: outbound TLS is either
    /// verified against the system roots the engine already reaches or, for a
    /// published certificate pin, deliberately unverified.
    func systemCertificates() -> LibboxStringIteratorProtocol? { nil }

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
