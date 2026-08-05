# The macOS tunnel daemon

macOS is the last platform to get a system-wide tunnel. Linux and Windows have
had one for as long as the desktop app has existed; macOS had a SOCKS proxy,
which only applications that read the system proxy settings ever used — so a
browser was covered and a game, a terminal or anything dialling a hardcoded IP
was not.

It gets one now the way every other cross-platform VPN does on this operating
system: a small daemon running as root, owning a `utun`, with the app talking to
it over a socket. Not NetworkExtension — see the last section for why.

Design: `docs/superpowers/specs/2026-08-04-macos-tun-daemon-design.md`.

## What is actually running

```
ProofKit.app (as you)                    ProofKitTunnelDaemon (as root)
  sing-box / Xray / olcrtc                 launchd, KeepAlive
  SOCKS on 127.0.0.1:<core port>  ◄────┐   /var/run/org.olcbox.app.tunneld.sock
                                       │     ↑ peer checked by code signature
                                       └── sing-box #2: tun → socks(core)
                                             auto_route owns the routes
                                             the server's IPs are excluded
```

The cores are unchanged — same binaries, same ports, same logs as in proxy mode.
The daemon adds one more sing-box in front of them whose only job is to own the
tun. Everything the tunnel carries still leaves the machine through the same core
that carried it before.

## Installing it

Settings → Connection → **System-wide tunnel** → tap.

macOS then asks you to approve it in **System Settings › General › Login Items**.
That is one authorisation, once — after it, connecting and disconnecting never
prompt again. Until it is approved the app keeps using the SOCKS proxy exactly as
it always has; nothing about connecting changes and nothing fails.

The row says which of the states you are in:

| Row reads | Meaning |
|---|---|
| `Not installed — tap to install` | Nothing registered yet. |
| `Approve in System Settings › General › Login Items` | Registered; macOS is waiting for you. |
| `Installed` | Approved and running. Connecting now builds a real tun. |
| `Missing from this build — reinstall ProofKit` | The app was built without the daemon (no signing identity in CI). |
| *(row absent)* | Not macOS, or macOS older than 13. |

Deleting ProofKit.app removes the daemon with it: the launchd plist points into
the bundle (`BundleProgram`), so there is nothing left in `/Library` to clean up
afterwards.

## Where things live

| | |
|---|---|
| daemon binary | `/Applications/ProofKit.app/Contents/MacOS/ProofKitTunnelDaemon` |
| launchd plist | `/Applications/ProofKit.app/Contents/Library/LaunchDaemons/org.olcbox.app.desktopApp.tunneld.plist` |
| the core it runs | `/Applications/ProofKit.app/Contents/Resources/sing-box`, copied to `/Library/Application Support/org.olcbox.app/bin/sing-box` |
| generated config | `/Library/Application Support/org.olcbox.app/tun.json` (root, 0600) |
| control socket | `/var/run/org.olcbox.app.tunneld.sock` |

## Reading it

```bash
sudo launchctl print system/org.olcbox.app.desktopApp.tunneld | head -20
log show --last 10m --info --predicate 'process == "ProofKitTunnelDaemon"'
sudo nc -U /var/run/org.olcbox.app.tunneld.sock <<< '{"verb":"status"}'

ifconfig | grep -A3 utun        # a utun holding 172.19.0.1
netstat -rn | head -20          # default via that utun, the server IP via the LAN gateway
curl -s https://api.ipify.org   # from a plain shell that knows nothing about a proxy
```

That last command is the whole point of this work. `curl` never reads the system
proxy settings the old mode set, so an exit IP there is the system-wide tunnel and
could not have been produced by the mode it replaces.

## Who is allowed to command it

The socket is reachable by any local process and root is behind it, so the daemon
checks every caller: the peer's **audit token** → its code signature → a
requirement pinning Apple's anchor, our Team ID and the app's identifier. A pid
would not do, because a pid can be recycled between being read and being checked.
Clash Verge shipped a local root escalation by leaving this check out.

The daemon also never execs a path a caller sends it. It verifies the signature of
the `sing-box` inside the bundle, copies it into a root-owned directory and runs
the copy — `/Applications` is writable by any admin, so verifying and executing
the same file there leaves a window to swap it.

## Known limits, stated plainly

- **A server hostname that re-resolves to a new address mid-session loses its
  exclusion** until the next reconnect, and the core's redial then routes into its
  own tunnel. Reconnecting fixes it. Every address known at connect time is
  excluded, so this only bites on a DNS change during a session.
- **A tunnel left behind by a killed app is stopped at the next launch, not
  adopted.** The app cannot say which location an orphaned tun belongs to, and
  showing a connection it cannot describe would be worse than a clean restart.
  Between the kill and that relaunch the tun stays up and traffic keeps flowing
  through it — fail closed, no leak.
- **macOS 13 or newer.** `SMAppService` does not exist below it; those Macs keep
  the SOCKS proxy.
- **If the daemon's sing-box dies, the app does not notice until the next
  command.** On Linux and Windows the tun process is a child of this JVM and its
  exit is watched; here it belongs to the daemon, and nothing pushes that news
  back. The status stays green over a tunnel that has gone. A notification from
  the daemon is the fix; polling from the app would only be a slower guess.
- **olcRTC locations have no server host to exclude** — they are addressed by a
  room on someone else's SFU, so there is no endpoint to pin a route to. Their DNS
  still takes the reliable path (`hijack-dns` plus a TCP resolver through the
  tunnel), which is the same treatment iOS gives them.

## Why not NetworkExtension

It was built, signed, notarised, embedded and installed, and macOS would not
activate it. `sysextd` rejects **new** system-extension activations on macOS 26
with `no policy, cannot allow apps outside /Applications` for apps that are in
`/Applications` — fully native, correctly signed ones included, LuLu among them.
Apple DTS calls that log line a red herring and has published no fix; extensions
activated before macOS 26 keep working, new ones do not.

- https://developer.apple.com/forums/thread/817101
- https://developer.apple.com/forums/thread/820254

Two further reasons the daemon would have won anyway: Apple requires the
activation request to come from the app's **main executable**, which for a
jpackage app is a JVM launcher; and no cross-platform VPN ships NE on macOS —
Mullvad, Tailscale and Clash Verge Rev all run a root daemon.

The NE work is parked on `feat/macos-system-extension` with its setup notes in
`docs/macos-system-extension-setup.md`. It is the shape to resume from if the Mac
App Store ever needs a provider.
