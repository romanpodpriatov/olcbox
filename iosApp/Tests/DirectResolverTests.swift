import Foundation

/// Compiled with the production DirectResolver by scripts/test-ios-direct-resolver.sh.
/// No Cores framework, signing identity or device is needed; a swift.org toolchain
/// on Linux runs it as well as Xcode does.
@main
enum DirectResolverTests {
    static func check(_ condition: Bool, _ message: String, line: Int = #line) {
        if !condition {
            print("FAIL line \(line): \(message)")
            exit(1)
        }
    }

    static func main() {
        // pick: first IPv4, then IPv6 without a zone, then the fallback.
        check(DirectResolver.pick(["[fe80::1%en0]:53", "10.11.12.13:53", "10.11.12.14:53"]) == "10.11.12.13", "first IPv4")
        check(DirectResolver.pick(["[2001:db8::53]:53"]) == "2001:db8::53", "IPv6 without a zone")
        check(DirectResolver.pick(["[2001:db8::53%pdp_ip0]:53"]) == "2001:db8::53", "zone stripped")
        check(DirectResolver.pick(["[fe80::1%en0]:53"]) == DirectResolver.fallback, "link-local is not a resolver")
        check(DirectResolver.pick(["127.0.0.1:53"]) == DirectResolver.fallback, "loopback is the tunnel itself")
        check(DirectResolver.pick([]) == DirectResolver.fallback, "nothing offered")

        // substitute: only the placeholder, only quoted, every occurrence.
        let config = #"{"dns":{"servers":[{"type":"udp","tag":"dns-direct","server":"198.51.100.53","detour":"direct"}]}}"#
        let patched = DirectResolver.substitute(in: config, resolvers: ["10.0.0.1:53"])
        check(patched.contains(#""server":"10.0.0.1""#), "placeholder replaced")
        check(!patched.contains("198.51.100.53"), "placeholder gone")
        check(DirectResolver.substitute(in: config, resolvers: []).contains(#""server":"77.88.8.8""#), "fallback when nothing offered")
        let global = #"{"log":{"level":"warn"}}"#
        check(DirectResolver.substitute(in: global, resolvers: ["10.0.0.1:53"]) == global, "a config without the placeholder is untouched")

        print("ok")
    }
}
