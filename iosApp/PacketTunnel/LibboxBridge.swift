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
        guard let pin = currentPhysicalInterface() else { return false }
        var scope = pin.index
        let size = socklen_t(MemoryLayout<UInt32>.size)
        // IP_BOUND_IF / IPV6_BOUND_IF. Both are set because the socket family is
        // not known here and the wrong one simply fails harmlessly.
        setsockopt(fd, IPPROTO_IP, 25, &scope, size)
        setsockopt(fd, IPPROTO_IPV6, 125, &scope, size)
        return true
    }

    /// Forgets the interface last pinned to, so the next socket looks again.
    ///
    /// The provider calls this whenever the system says the path changed. The
    /// cache is short-lived regardless; this makes a handover count on the very
    /// next dial rather than the next few.
    static func invalidatePinCache() {
        pinCache.invalidate()
    }

    // MARK: - choosing the interface

    /// One interface the extension could dial out of, and whether it can.
    ///
    /// The two `routes` flags answer "can a socket bound to this interface pick
    /// a source address for a public destination" — the probe the engine uses
    /// to order address families, and one that sends nothing. Both false is an
    /// interface with an address and no way out: the VoLTE bearer, a Wi-Fi
    /// whose uplink is gone.
    struct PhysicalInterface: Equatable {
        let name: String
        let index: UInt32
        let routesIPv4: Bool
        let routesIPv6: Bool

        var reachesInternet: Bool { routesIPv4 || routesIPv6 }

        /// `pdp_ip0[46]`, `en0[4-]`: what it is and which families leave by it.
        var summary: String {
            "\(name)[\(routesIPv4 ? "4" : "-")\(routesIPv6 ? "6" : "-")]"
        }
    }

    private static let pinLog = Logger(subsystem: "org.proofkit.app", category: "pin")
    private static let pinCache = PinCache()
    /// Every outbound socket asks. `getifaddrs` plus a route probe per family
    /// per candidate on each dial would be a cost of its own, so the answer is
    /// kept briefly — short enough that a handover is noticed within a dial or
    /// two, and the provider drops it outright when the path changes.
    private static let pinCacheLifetime: TimeInterval = 5

    /// The last answer, behind a lock: Go calls `protect` from whichever
    /// goroutine is dialing, and libbox from threads of its own.
    private final class PinCache: @unchecked Sendable {
        private let lock = NSLock()
        private var value: PhysicalInterface?
        private var stamp: TimeInterval = 0

        func invalidate() {
            lock.lock()
            value = nil
            lock.unlock()
        }

        /// The cached answer while it is fresh, else a new one from `refresh` —
        /// under the lock, so two sockets arriving together probe once.
        func current(
            lifetime: TimeInterval,
            refresh: (_ previous: PhysicalInterface?) -> PhysicalInterface?
        ) -> PhysicalInterface? {
            lock.lock()
            defer { lock.unlock() }
            let now = Date().timeIntervalSinceReferenceDate
            if let value, now - stamp < lifetime {
                return value
            }
            let fresh = refresh(value)
            value = fresh
            stamp = now
            return fresh
        }
    }

    private static func currentPhysicalInterface() -> PhysicalInterface? {
        pinCache.current(lifetime: pinCacheLifetime) { previous in
            let probed = probePhysicalInterfaces()
            let chosen = choose(from: probed)
            // Once per change, and every refresh while nothing reaches out —
            // the second case is rare and is the one worth a line each time.
            if chosen != previous || chosen?.reachesInternet != true {
                report(chosen, among: probed)
            }
            return chosen
        }
    }

    /// The first candidate that can leave the device, in the order the old
    /// heuristic used — Wi-Fi ahead of cellular, each in `getifaddrs` order.
    ///
    /// Failing that, the old answer: the first candidate regardless. Pinning to
    /// a dead interface fails fast with "no route to host"; leaving the socket
    /// unpinned would send it down the default route into our own tun, where
    /// nothing is reading yet, and it would hang instead. The log line says
    /// which of the two happened.
    private static func choose(from probed: [PhysicalInterface]) -> PhysicalInterface? {
        probed.first(where: \.reachesInternet) ?? probed.first
    }

    private static func report(_ chosen: PhysicalInterface?, among probed: [PhysicalInterface]) {
        let seen = probed.isEmpty ? "no candidates" : probed.map(\.summary).joined(separator: " ")
        let line: String
        if let chosen, chosen.reachesInternet {
            line = "pin: \(chosen.name) (\(seen))"
        } else {
            line = "pin: NO ROUTE on any interface, pinning \(chosen?.name ?? "nothing") (\(seen))"
        }
        pinLog.info("\(line, privacy: .public)")
        // Onto stderr as well, which the provider has pointed into engine.log:
        // that file is what the app reads back and puts in the shareable log,
        // and the unified log is not. This is the line that turns "no route to
        // host" from a guess about the carrier into a fact about the phone.
        try? FileHandle.standardError.write(contentsOf: Data((line + "\n").utf8))
    }

    /// Every interface that could carry traffic out, probed.
    private static func probePhysicalInterfaces() -> [PhysicalInterface] {
        candidateInterfaces().map { candidate in
            PhysicalInterface(
                name: candidate.name,
                index: candidate.index,
                routesIPv4: hasRoute(index: candidate.index, family: AF_INET),
                routesIPv6: hasRoute(index: candidate.index, family: AF_INET6)
            )
        }
    }

    private struct Candidate {
        let name: String
        let index: UInt32
    }

    /// Interfaces that are up, not loopback, and carry a real address, with
    /// Wi-Fi ahead of cellular. Ordering only: whether an interface can reach
    /// anything is the probe's question, not this one's.
    ///
    /// This used to *return* the first `en*`, else the first `pdp_ip*`, and that
    /// answer was final. A phone on cellular has several `pdp_ip` interfaces up
    /// at once — the internet bearer and the VoLTE one at least — and the VoLTE
    /// bearer carries a perfectly global address with no route to anything but
    /// the carrier's IMS core. Whichever `getifaddrs` listed first won, and on
    /// one carrier that was the wrong one: every socket bound to it, "no route
    /// to host" on both families, while the system's own resolver — never
    /// pinned — answered fine beside it.
    private static func candidateInterfaces() -> [Candidate] {
        var addresses: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&addresses) == 0, let first = addresses else { return [] }
        defer { freeifaddrs(addresses) }

        var wifi: [Candidate] = []
        var cellular: [Candidate] = []
        var seen = Set<String>()
        for entry in sequence(first: first, next: { $0.pointee.ifa_next }) {
            guard entry.pointee.ifa_flags & UInt32(IFF_UP) != 0,
                  entry.pointee.ifa_flags & UInt32(IFF_LOOPBACK) == 0,
                  let address = entry.pointee.ifa_addr,
                  carriesTraffic(address)
            else { continue }

            let name = String(cString: entry.pointee.ifa_name)
            guard seen.insert(name).inserted else { continue }
            let index = if_nametoindex(name)
            guard index != 0 else { continue }
            if name.hasPrefix("en") {
                wifi.append(Candidate(name: name, index: index))
            } else if name.hasPrefix("pdp_ip") {
                cellular.append(Candidate(name: name, index: index))
            }
        }
        return wifi + cellular
    }

    /// Whether a socket bound to `index` can pick a source address for a public
    /// destination in `family`. Connecting a UDP socket assigns a route and
    /// sends nothing — the engine's own probe, done here in Swift so the choice
    /// is made before any Go socket exists.
    private static func hasRoute(index: UInt32, family: Int32) -> Bool {
        let fd = socket(family, SOCK_DGRAM, 0)
        guard fd >= 0 else { return false }
        defer { close(fd) }

        var scope = index
        let size = socklen_t(MemoryLayout<UInt32>.size)
        let bound = family == AF_INET
            ? setsockopt(fd, IPPROTO_IP, 25, &scope, size)
            : setsockopt(fd, IPPROTO_IPV6, 125, &scope, size)
        guard bound == 0 else { return false }

        // The resolver the engine is already pointed at, on each family.
        return family == AF_INET
            ? connectProbe(fd, v4: "1.1.1.1")
            : connectProbe(fd, v6: "2606:4700:4700::1111")
    }

    private static func connectProbe(_ fd: Int32, v4 host: String) -> Bool {
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = UInt16(53).bigEndian
        guard inet_pton(AF_INET, host, &addr.sin_addr) == 1 else { return false }
        return withUnsafePointer(to: &addr) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { generic in
                connect(fd, generic, socklen_t(MemoryLayout<sockaddr_in>.size)) == 0
            }
        }
    }

    private static func connectProbe(_ fd: Int32, v6 host: String) -> Bool {
        var addr = sockaddr_in6()
        addr.sin6_len = UInt8(MemoryLayout<sockaddr_in6>.size)
        addr.sin6_family = sa_family_t(AF_INET6)
        addr.sin6_port = UInt16(53).bigEndian
        guard inet_pton(AF_INET6, host, &addr.sin6_addr) == 1 else { return false }
        return withUnsafePointer(to: &addr) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { generic in
                connect(fd, generic, socklen_t(MemoryLayout<sockaddr_in6>.size)) == 0
            }
        }
    }

    /// Whether an interface address is one traffic can leave the device by.
    ///
    /// This used to be `sa_family == AF_INET` and nothing else, which asks "does
    /// it have an address" only of a network that has IPv4. On an IPv6-only
    /// carrier `pdp_ip0` carries no AF_INET address at all, so no interface
    /// matched, the lookup returned nil, and `pinToPhysicalInterface` then
    /// answered false for *every* socket - the cores could not dial at all, and
    /// olcRTC's own account of it was an unrelated "network is unreachable" from
    /// the IPv4 half of a dual-stack dial (olcrtc#1). Apple runs App Review on
    /// an IPv6-only NAT64 network, so this is the review path, not an edge case.
    ///
    /// Link-local is excluded deliberately. `fe80::/10` sits on every interface
    /// whether or not it reaches anything, and `169.254/16` means IPv4 gave up;
    /// counting either would pin to an associated-but-dead Wi-Fi in front of a
    /// working cellular, which is the bug this function already existed to avoid.
    private static func carriesTraffic(_ address: UnsafeMutablePointer<sockaddr>) -> Bool {
        switch Int32(address.pointee.sa_family) {
        case AF_INET:
            let v4 = address.withMemoryRebound(to: sockaddr_in.self, capacity: 1) {
                $0.pointee.sin_addr
            }
            return withUnsafeBytes(of: v4) { raw in
                !(raw[0] == 169 && raw[1] == 254)
            }
        case AF_INET6:
            let v6 = address.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) {
                $0.pointee.sin6_addr
            }
            return withUnsafeBytes(of: v6) { raw in
                !(raw[0] == 0xfe && raw[1] & 0xc0 == 0x80)
            }
        default:
            return false
        }
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
