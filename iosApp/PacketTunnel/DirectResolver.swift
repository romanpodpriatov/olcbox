import Foundation

/// Fills in the resolver "direct" traffic uses under Bypass Russia.
///
/// The Kotlin builder cannot know it: the network's resolvers can only be read
/// before `setTunnelNetworkSettings`, from inside the extension, after the
/// config was written by another process. So the config carries a placeholder
/// — TEST-NET-2, an address nothing routes to — and this replaces it with the
/// first resolver `ResolverSnapshot` found, or Yandex DNS when it found none.
/// A string substitution, not a JSON edit: the placeholder is quoted and
/// unique, `SingBoxConfigTest` pins that it appears exactly once, and a parser
/// here would be a second implementation of the config's shape.
///
/// The values must match `SingBoxConfig.DIRECT_DNS_PLACEHOLDER` and
/// `SingBoxConfig.DIRECT_DNS_FALLBACK`.
enum DirectResolver {
    static let placeholder = "198.51.100.53"
    static let fallback = "77.88.8.8"

    /// The first IPv4, else the first IPv6 that is not link-local, else the
    /// fallback. `ResolverSnapshot` renders servers as `host:port` and
    /// `[v6%zone]:port`; sing-box wants a bare address, and a zone is
    /// something it cannot carry.
    static func pick(_ servers: [String]) -> String {
        let hosts = servers.map(host(of:)).filter { !$0.isEmpty }
        if let v4 = hosts.first(where: { $0.contains(".") && !$0.contains(":") && !$0.hasPrefix("127.") }) {
            return v4
        }
        if let v6 = hosts.first(where: { $0.contains(":") && !$0.lowercased().hasPrefix("fe80:") && $0 != "::1" }) {
            return v6
        }
        return fallback
    }

    static func substitute(in config: String, resolvers: [String]) -> String {
        let quoted = "\"\(placeholder)\""
        guard config.contains(quoted) else { return config }
        return config.replacingOccurrences(of: quoted, with: "\"\(pick(resolvers))\"")
    }

    private static func host(of server: String) -> String {
        var value = server.trimmingCharacters(in: .whitespaces)
        if value.hasPrefix("[") {
            // [v6%zone]:port
            guard let close = value.firstIndex(of: "]") else { return "" }
            value = String(value[value.index(after: value.startIndex)..<close])
        } else if let colon = value.lastIndex(of: ":"), value.filter({ $0 == ":" }).count == 1 {
            // v4:port — a bare IPv6 has more than one colon and no port here.
            value = String(value[..<colon])
        }
        if let percent = value.firstIndex(of: "%") {
            value = String(value[..<percent])
        }
        return value
    }
}
