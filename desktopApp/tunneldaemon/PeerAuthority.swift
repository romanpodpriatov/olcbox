// Who is allowed to command the tunnel.
//
// This is the security boundary of the whole design: the socket is reachable by
// any local process, and behind it is root. Clash Verge shipped a local root
// escalation by not having this check, which is why it is the first file written
// rather than the last one remembered.
//
// The peer is identified by its *audit token*, not by its pid. A pid can be
// recycled between the moment it is read and the moment it is checked, and the
// process that answers the check is then not the process that asked. An audit
// token names one specific process for as long as that process exists.
import Foundation
import Security

enum PeerAuthority {
    /// All three clauses, because any two of them are satisfied by something we
    /// did not build: the anchor pins the chain to Apple, the OU pins it to our
    /// team, and the identifier pins it to our app.
    static let clientRequirement =
        "anchor apple generic and certificate leaf[subject.OU] = \"3QJG3J7L66\" " +
        "and identifier \"org.olcbox.app.desktopApp\""

    /// The core is signed by us but carries its own identifier from its own
    /// release page, so only the anchor and the team are pinned for it.
    static let coreRequirement =
        "anchor apple generic and certificate leaf[subject.OU] = \"3QJG3J7L66\""

    static func isTrusted(fd: Int32) -> Bool {
        var token = audit_token_t()
        var length = socklen_t(MemoryLayout<audit_token_t>.size)
        // LOCAL_PEERTOKEN is not surfaced in the Swift overlay; the value is the
        // one from <sys/un.h> and has been stable since it was introduced.
        let localPeerToken: Int32 = 0x006
        let read = withUnsafeMutablePointer(to: &token) { pointer in
            getsockopt(fd, SOL_LOCAL, localPeerToken, pointer, &length)
        }
        guard read == 0, length == socklen_t(MemoryLayout<audit_token_t>.size) else { return false }

        let tokenData = withUnsafeBytes(of: token) { Data($0) }
        let attributes = [kSecGuestAttributeAudit: tokenData] as CFDictionary
        var code: SecCode?
        guard SecCodeCopyGuestWithAttributes(nil, attributes, [], &code) == errSecSuccess,
              let peer = code else { return false }

        return checkValidity(requirement: clientRequirement) { rule in
            SecCodeCheckValidity(peer, [], rule)
        }
    }

    /// The same idea applied to a file on disk, used before the daemon execs it.
    static func isTrustedBinary(at url: URL) -> Bool {
        var staticCode: SecStaticCode?
        guard SecStaticCodeCreateWithPath(url as CFURL, [], &staticCode) == errSecSuccess,
              let code = staticCode else { return false }
        return checkValidity(requirement: coreRequirement) { rule in
            SecStaticCodeCheckValidity(code, [], rule)
        }
    }

    private static func checkValidity(
        requirement text: String,
        _ body: (SecRequirement) -> OSStatus
    ) -> Bool {
        var requirement: SecRequirement?
        guard SecRequirementCreateWithString(text as CFString, [], &requirement) == errSecSuccess,
              let rule = requirement else { return false }
        return body(rule) == errSecSuccess
    }
}
