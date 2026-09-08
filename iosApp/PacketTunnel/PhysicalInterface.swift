import Darwin

/// A local routing probe, not an end-to-end reachability guarantee.
struct PhysicalInterface: Equatable {
    let name: String
    let index: UInt32
    let routesIPv4: Bool
    let routesIPv6: Bool

    var summary: String {
        "\(name)[\(routesIPv4 ? "4" : "-")\(routesIPv6 ? "6" : "-")]"
    }

    /// Whether a socket of this family bound here can pick a source address
    /// for a public destination.
    func routes(_ family: Int32) -> Bool {
        switch family {
        case AF_INET: return routesIPv4
        case AF_INET6: return routesIPv6
        default: return false
        }
    }

    var reachesInternet: Bool { routesIPv4 || routesIPv6 }

    /// Candidates are already ordered with Wi-Fi before cellular. The first
    /// with a route for this socket's family wins, so an IPv4 socket is never
    /// pinned to a bearer that only carries IPv6 and vice versa — the split
    /// that produced "no route to host" and "network is unreachable" together.
    ///
    /// A family nobody routes still gets an interface. Sockets of that family
    /// are not all bound for the internet: a dial to 127.0.0.1 is AF_INET on
    /// an IPv6-only network too, and every core reaches the others through
    /// loopback, so refusing it would refuse the tunnel. Whatever reaches out
    /// at all comes first, else the first candidate: a dial to the internet
    /// then fails fast with "no route" instead of hanging in our own tun.
    static func choose(from candidates: [PhysicalInterface], family: Int32) -> PhysicalInterface? {
        guard family == AF_INET || family == AF_INET6 else { return nil }
        return candidates.first(where: { $0.routes(family) })
            ?? candidates.first(where: \.reachesInternet)
            ?? candidates.first
    }
}
