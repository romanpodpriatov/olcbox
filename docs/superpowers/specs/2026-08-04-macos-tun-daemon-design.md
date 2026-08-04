# macOS TUN via a root daemon (SMAppService) — design

2026-08-04. Replaces the NetworkExtension system-extension attempt as the way
macOS gets a TUN. The NE work stays parked on `feat/macos-system-extension`;
`docs/macos-system-extension-setup.md` records where it stopped and why.

## Why not NetworkExtension

Three independent reasons, any one of which would have been enough:

1. **macOS 26 (Tahoe) cannot activate new system extensions.** sysextd rejects
   fresh activations with `no policy, cannot allow apps outside /Applications`
   → `OSSystemExtensionError code 4` for fully native, correctly signed,
   notarised apps — LuLu among them; extensions activated before 26 keep
   working. Apple DTS (Quinn) calls the log line a red herring and offers no
   fix. Our symptom on 2026-08-04 was exactly this, byte for byte. See
   https://developer.apple.com/forums/thread/817101 and
   https://developer.apple.com/forums/thread/820254.
2. **Apple requires the activation request to come from the app's main
   executable** — not a helper, daemon or CLI. Our main executable is the
   jpackage launcher; making a native binary the main executable is surgery on
   the working bundle, spent on a path that (1) blocks anyway.
3. **Nobody cross-platform ships NE on macOS.** Mullvad (`mullvad-daemon`
   LaunchDaemon), Tailscale standalone (`tailscaled` LaunchDaemon), Clash Verge
   Rev (root "service mode") all run a root daemon that owns a utun. NE is the
   App-Store-native path; the daemon is the industry path for apps like ours.

Revisit NE if macOS fixes the regression *and* App Store distribution is
wanted; nothing below blocks that.

## Shape

```
Compose app (JVM, unchanged cores)          ProofKitTunnelDaemon (Swift, root)
  cores: sing-box / xray / olcrtc             registered via SMAppService.daemon
  SOCKS on 127.0.0.1:<port>  ◄───────────┐    KeepAlive; ~200 lines
  TunnelDaemonClient (UDS, JSON lines) ──┼──► /var/run/org.olcbox.app.tunneld.sock
                                         │      peer audit token → codesign check
                                         │    exec: root-owned copy of bundled
                                         │      sing-box, verified by Team ID
                                         └──  sing-box: tun inbound (auto_route,
                                              DNS hijack) + socks outbound to the
                                              app's core, + 127.0.0.1 socks
                                              inbound for TunnelVerifier
```

**Uniform for all transports**: the daemon's sing-box always runs
`tun → socks(127.0.0.1:<core port>)` — the `SingBoxConfig.buildTunSocks` shape
already proven on iOS, including DNS answered by sing-box over TCP through the
upstream and the hijack-dns rule. The cores keep their exact current lifecycle,
ports, logs, credentials (olcrtc's generated SOCKS user/pass travel with the
config, the iOS lesson) and the `TunnelVerifier` exit check. Moving vless/hy2
outbounds into the daemon's sing-box (dropping the localhost hop) is a later
optimisation, not this design.

## The loop, and why exclusion is mandatory

The cores are user processes; once `auto_route` points default at the tun,
their own packets to the server would enter the tun and loop. The daemon config
therefore always sets `route_exclude_address` to every resolved IP of the
active location's server (resolve at start; all A/AAAA records). This also
covers mid-session redials. Two documented consequences:

- If the server hostname re-resolves to a new IP mid-session, the redial loops
  and the connection drops until reconnect. Accepted; same class of limitation
  as the Linux/Windows controllers.
- DNS for the *server hostname itself* must not depend on the tunnel: the
  builder emits a dns rule sending that one domain to the system resolver over
  direct, so a core can redial while the tunnel is down.

## Daemon contract

- **Identity**: label `org.olcbox.app.desktopApp.tunneld`, binary
  `Contents/MacOS/ProofKitTunnelDaemon`, plist
  `Contents/Library/LaunchDaemons/org.olcbox.app.desktopApp.tunneld.plist`
  (`BundleProgram`, `KeepAlive` true). macOS 13+ (`SMAppService`); on older
  macOS the app silently keeps today's SystemProxy behaviour.
- **Socket**: UDS `/var/run/org.olcbox.app.tunneld.sock`, mode 0666. Every
  connection is authenticated by `getsockopt(LOCAL_PEERTOKEN)` →
  `SecCodeCopyGuestWithAttributes(audit token)` → requirement
  `anchor apple generic` + Team `3QJG3J7L66` + identifier prefix
  `org.olcbox.app`. PID-based checks are TOCTOU-racy; the audit token is not.
  Unauthenticated peers get one line: `{"error":"unauthorized"}` and a close.
  This check is the security boundary — Clash Verge shipped a local-root CVE by
  omitting it.
- **Verbs** (JSON per line, one reply per request):
  `start {config}` → daemon writes config to a root-owned dir, execs sing-box,
  waits for the tun to exist, replies `{ok}` or `{error, tail}`;
  `stop {}` → SIGTERM child, wait, reply; `status {}` →
  `{state: idle|running, pid, singboxVersion, logTail}`; `version {}`.
- **Binary trust**: the daemon never execs from a user-writable path and never
  accepts a path from the client. It resolves the bundled `sing-box` relative
  to its own executable (inside the .app), copies it to
  `/Library/Application Support/org.olcbox.app/bin/`, root-owned, verifies the
  copy with `SecStaticCodeCheckValidity` against the same Team requirement (CI
  already Developer-ID-signs bundled cores), then execs the copy. Config JSON
  is data from an authenticated peer; it is not further sanitised.
- **Lifecycle**: sing-box exits → daemon reaps, state `idle`, routes die with
  the process (auto_route + utun close are self-cleaning — no route scripts,
  the thing the Linux/Windows controllers spend most of their code on).
  Daemon start/stop of the *tunnel* is always explicit from the app; the
  daemon itself stays resident (KeepAlive) at a few MB idle.

## App integration

- **`DesktopMode` gains `MacTun`**: chosen on macOS ≥ 13 when the daemon is
  registered + approved + socket answers `status`; otherwise `SystemProxy`
  exactly as today. No behaviour change for a user who never enables it.
- **Registration UX**: a Settings row "System-wide tunnel (TUN)" backed by a
  small JNA dylib (same extraction pattern as `libolcboxne.dylib` on the NE
  branch) exposing `register / unregister / status / openSettings` over
  `SMAppService`. `requiresApproval` → row says so and the button opens
  System Settings › Login Items. One password prompt, once.
- **Connect path** (`startDesktopMode`): cores start exactly as now; then
  instead of PAC/system-proxy, `TunnelDaemonClient.start(config)` with the
  `buildTunSocks`-shaped config. Two ports, both allocated by the app under the
  existing `CorePortCollisionTest` rules: the core's SOCKS port (daemon
  outbound targets it) and a second port for the daemon's own localhost socks
  inbound — `TunnelVerifier` verifies through the second, so a green status
  proves the daemon chain, not just the core.
- **Adopt, never remember** (the iOS lesson): on launch and on focus the app
  asks the daemon `status` and adopts a running tunnel into the UI instead of
  assuming idle. App quit leaves the tunnel up only for as long as the cores
  live; since cores die with the JVM, the shutdown hook sends `stop` best
  effort, and a daemon whose upstream socks stops answering keeps the tun up —
  fail closed, no leak — until the app returns or `stop` arrives.

## Build & CI

- `embedMacosTunnelDaemon` Gradle task (macOS runners only): `swiftc` the
  daemon (Foundation + Security + ServiceManagement, hardened runtime, **no
  restricted entitlements, no provisioning profiles**), place binary + plist,
  build the JNA dylib, sign both with the Developer ID identity, re-sign the
  app. Runs whenever `MACOS_SIGN_IDENTITY` is present — no new secrets; the
  two provisioning-profile secrets stay unused by this path.
- Notarisation unchanged (already on for every macOS build).
- The NE `embedMacosSystemExtension` machinery is not carried to this branch
  (branched from `main`).

## Tests

- **commonTest**: the desktop-tun builder — tun+socks shape, credentials
  passthrough, `route_exclude_address` emission from resolved IPs, the
  server-domain direct-DNS rule, localhost verify inbound. Shapes gated by
  `sing-box check` in `singbox-verify.yml` like the existing ones (a shape the
  binary rejects is a failed test, not a shipped surprise).
- **jvmTest**: `TunnelDaemonClient` codec (request/reply, error surfaces,
  unauthorized), `DesktopMode` selection matrix (os/version/daemon states),
  adopt-on-launch state mapping.
- **Swift daemon**: no test rig exists for it; it stays small enough to review,
  and its observable behaviour (verbs, trust checks) is exercised end-to-end by
  the on-Mac checklist in `docs/macos-tunnel-daemon.md` (to be written with the
  implementation).

## Manual verification order (user's Mac)

1. Before any daemon code: generate the tun config, run the bundled sing-box
   under `sudo` against a real node, browse — proves data path, routes, DNS,
   exclusion on this exact macOS. (Also record `sw_vers`; optionally confirm
   the Tahoe NE regression via LuLu for the parked branch's post-mortem.)
2. First CI build: register daemon, approve, connect via UI, verify exit IP,
   `netstat -rn` shows tun default + server exclusion, stop cleans up.
3. Kill -9 the app mid-tunnel: tun stays (fail closed), relaunch adopts state,
   stop works.
