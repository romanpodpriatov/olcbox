import Foundation
import os

/// The resolvers of the network the extension stands on, read from the
/// system resolver state before the tunnel's own settings replace it.
///
/// Inside a packet tunnel the system resolver is the tunnel's own from the
/// moment the settings are applied, and that is before any core starts — so
/// the engine can never learn the carrier's servers by itself, and every
/// name it resolved went to a public operator instead. Some mobile networks
/// answer those with silence and only their own servers with anything, which
/// is how a room that works on Wi-Fi came back "i/o timeout" on cellular
/// (olcbox#16). Read here, once, and handed to the engine as the head of its
/// resolver list; the public operators stay behind them for the networks
/// where the carrier's servers are the ones that stop answering.
///
/// libresolv is linked already (the Go runtime in Cores needs it); the three
/// entry points are looked up by name so no header has to be bridged.
enum ResolverSnapshot {
    private typealias InitFn = @convention(c) (UnsafeMutableRawPointer) -> Int32
    private typealias DestroyFn = @convention(c) (UnsafeMutableRawPointer) -> Void
    private typealias GetServersFn = @convention(c) (
        UnsafeMutableRawPointer, UnsafeMutableRawPointer, Int32
    ) -> Int32

    /// `struct __res_9_state` is a few hundred bytes; this is not close.
    private static let stateSize = 8192
    /// MAXNS from resolv.h, and the size of `union res_9_sockaddr_union`.
    private static let maxServers = 3
    private static let unionSize = 128

    private static let log = Logger(subsystem: "org.proofkit.app", category: "resolvers")

    /// The servers as "host:port" / "[v6%zone]:port", in the system's order,
    /// loopback left out. Empty when the state cannot be read, which the
    /// engine treats as "none known" and not as an error.
    static func servers() -> [String] {
        guard let handle = dlopen(nil, RTLD_NOW) else { return [] }
        defer { dlclose(handle) }
        guard let initSymbol = dlsym(handle, "res_9_ninit"),
              let destroySymbol = dlsym(handle, "res_9_ndestroy"),
              let serversSymbol = dlsym(handle, "res_9_getservers")
        else {
            log.error("libresolv entry points not found")
            return []
        }
        let ninit = unsafeBitCast(initSymbol, to: InitFn.self)
        let ndestroy = unsafeBitCast(destroySymbol, to: DestroyFn.self)
        let getservers = unsafeBitCast(serversSymbol, to: GetServersFn.self)

        let state = UnsafeMutableRawPointer.allocate(byteCount: stateSize, alignment: 16)
        state.initializeMemory(as: UInt8.self, repeating: 0, count: stateSize)
        defer { state.deallocate() }
        guard ninit(state) == 0 else {
            log.error("res_ninit failed")
            return []
        }
        defer { ndestroy(state) }

        let list = UnsafeMutableRawPointer.allocate(byteCount: unionSize * maxServers, alignment: 16)
        list.initializeMemory(as: UInt8.self, repeating: 0, count: unionSize * maxServers)
        defer { list.deallocate() }
        let count = Int(getservers(state, list, Int32(maxServers)))
        guard count > 0 else { return [] }

        var servers: [String] = []
        for index in 0..<min(count, maxServers) {
            if let server = server(at: list + index * unionSize), !servers.contains(server) {
                servers.append(server)
            }
        }
        return servers
    }

    /// One entry of the union: a sockaddr_in or a sockaddr_in6, told apart by
    /// the family both keep in the same place.
    private static func server(at raw: UnsafeMutableRawPointer) -> String? {
        switch Int32(raw.load(as: sockaddr.self).sa_family) {
        case AF_INET:
            let sin = raw.load(as: sockaddr_in.self)
            var address = sin.sin_addr
            var text = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            guard inet_ntop(AF_INET, &address, &text, socklen_t(text.count)) != nil else { return nil }
            let host = String(cString: text)
            guard !host.hasPrefix("127.") else { return nil }
            return "\(host):\(port(sin.sin_port))"
        case AF_INET6:
            let sin6 = raw.load(as: sockaddr_in6.self)
            var address = sin6.sin6_addr
            var text = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
            guard inet_ntop(AF_INET6, &address, &text, socklen_t(text.count)) != nil else { return nil }
            var host = String(cString: text)
            guard host != "::1" else { return nil }
            if sin6.sin6_scope_id != 0 {
                var name = [CChar](repeating: 0, count: Int(IF_NAMESIZE))
                if if_indextoname(sin6.sin6_scope_id, &name) != nil {
                    host += "%" + String(cString: name)
                }
            }
            return "[\(host)]:\(port(sin6.sin6_port))"
        default:
            return nil
        }
    }

    /// Network order in the struct; 53 when the state carries no port.
    private static func port(_ raw: in_port_t) -> Int {
        let value = Int(UInt16(bigEndian: raw))
        return value == 0 ? 53 : value
    }
}
