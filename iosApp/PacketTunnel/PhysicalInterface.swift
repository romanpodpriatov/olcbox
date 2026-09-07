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

    /// Candidates are already ordered with Wi-Fi before cellular. Only a route
    /// for this socket's family qualifies; never borrow another family's route.
    static func choose(from candidates: [PhysicalInterface], family: Int32) -> PhysicalInterface? {
        switch family {
        case AF_INET: return candidates.first(where: \.routesIPv4)
        case AF_INET6: return candidates.first(where: \.routesIPv6)
        default: return nil
        }
    }
}
