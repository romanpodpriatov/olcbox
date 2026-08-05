# macOS tunnel extension: the one-time Apple setup

The Mac app carries a packet-tunnel **system extension**. Outside the App Store
that is the only shape a NetworkExtension provider may take, and it needs two
things this repository cannot produce: identifiers registered with Apple, and
Developer ID provisioning profiles that authorise the restricted entitlements.

Until both secrets below exist, every macOS build is deliberately made *without*
the extension. That is not a degraded build by accident — it is the difference
between an app that cannot install a tunnel and an app that will not launch. A
restricted entitlement with no profile to authorise it passes codesign, passes
the build, and is then killed by AMFI on the first launch, with macOS saying only
"cannot be opened". That happened once; the conditional exists so it cannot again.

Team: `3QJG3J7L66` (Globvent inc).

## 1. Two identifiers

developer.apple.com → Certificates, Identifiers & Profiles → **Identifiers** → +
→ App IDs → App.

| Description | Bundle ID | Capability to tick |
|---|---|---|
| ProofKit Desktop | `org.olcbox.app.desktopApp` | **System Extension** |
| ProofKit Packet Tunnel | `org.olcbox.app.desktopApp.PacketTunnel` | **Network Extensions** |

Both are macOS identifiers. The second must be exactly the first plus
`.PacketTunnel`: macOS refuses to install an extension whose identifier is not a
child of the app requesting it, and says so in a way that names neither the rule
nor the value.

Nothing else needs ticking. Every capability enabled here has to survive into the
profile and the signature, so an unused one is only another way to fail.

## 2. Two provisioning profiles

→ **Profiles** → + → Distribution → **Developer ID**.

- One for `org.olcbox.app.desktopApp` → download as `app.provisionprofile`
- One for `org.olcbox.app.desktopApp.PacketTunnel` → download as `sysext.provisionprofile`

**Developer ID**, not Development and not Mac App Store. A development profile
works on the machine that made it and nowhere else, which is the most misleading
possible outcome: it will run for you and be killed on every other Mac.

## 3. Two repository secrets

Settings → Secrets and variables → Actions → New repository secret.

```
base64 -i app.provisionprofile | pbcopy      # → MACOS_APP_PROVISION_PROFILE_BASE64
base64 -i sysext.provisionprofile | pbcopy   # → MACOS_SYSEXT_PROVISION_PROFILE_BASE64
```

The build decodes each into `Contents/embedded.provisionprofile` of the
respective bundle *before* signing it, because the signature seals the profile in.

## 4. What the build then does, and what it tells you

With both secrets set, `Build ProofKit Releases` embeds the extension, signs it
with `packet-tunnel-provider-systemextension`, re-signs the app with the
system-extension entitlements, and fails if the extension is not in the finished
bundle. Without them it prints which one is missing and builds the app without a
tunnel.

Every macOS build is notarised now, including `test-macos`. It used to be
published builds only — reasonable when a signature was enough for a build you
open yourself, wrong the moment a system extension existed, since macOS will not
install one from an app that is not notarised and no gesture in the Finder gets
round it.

## 5. Testing it

The app must be in `/Applications` — macOS accepts the installation request from
nowhere else. Then Settings › Connection › **Tunnel Extension** → tap.

```
systemextensionsctl list      # expect: activated enabled
log show --last 5m --predicate 'process == "sysextd"' --style compact | tail -40
```

The app shows Apple's error verbatim rather than a summary of it, because
`OSSystemExtensionError` is the only thing that distinguishes "not in
/Applications" from "wrong signature" from "the user declined", and all three read
the same once paraphrased.

---

# Where this stands, 2026-08-04

The extension is built, signed, notarised, embedded and installed on a Mac, and
macOS refuses to activate it. Everything checkable has been checked; the block is
not yet understood. This section exists so the next attempt starts here instead
of at the beginning.

## The symptom

App side, from the app's own log
(`log show --last 10m --info --predicate 'subsystem == "org.olcbox.app.desktopApp.ne"'`
— **`--info` is required**, without it the output is empty):

    requesting org.olcbox.app.desktopApp.PacketTunnel from /Applications/ProofKit.app;
      SystemExtensions contains PacketTunnel.systemextension
    status 4: OSSystemExtensionErrorDomain code 4: Extension not found in App bundle.
      Unable to find any matched extension with identifier:
      org.olcbox.app.desktopApp.PacketTunnel [bundle: /Applications/ProofKit.app]

System side (`log show --last 5m --info --predicate 'process == "sysextd"'`):

    client activation request for org.olcbox.app.desktopApp.PacketTunnel
    attempting to realize extension with identifier org.olcbox.app.desktopApp.PacketTunnel
    ...SecKeyVerifySignature / SecTrustEvaluateIfNecessary...   <- no complaint
    no policy, cannot allow apps outside /Applications

So sysextd receives the request, finds the extension by identifier, checks the
signature without objecting, and then refuses on what it calls a location policy.
The client turns that into "extension not found", which is what sent four rounds
of investigation at the bundle.

## Checked and excluded

Every one of these was verified on the machine, not assumed:

| | |
|---|---|
| app runs from `/Applications/ProofKit.app` | `ps`, and the app's own `Bundle.main.bundlePath` |
| LaunchServices agrees | `lsappinfo list` → `bundle path="/Applications/ProofKit.app"` |
| no stray copies winning the lookup | eight mounted `Olcbox*` DMG volumes were detached; no change |
| LaunchServices registration is fresh | `lsregister -f` re-run after each install |
| app carries the install entitlement | `codesign -d --entitlements -` → `system-extension.install = true` |
| app's profile authorises it | `ProvisionsAllDevices = true`, Developer ID, entitlement present |
| extension identifier matches the request | Info.plist and `codesign -d` both `…desktopApp.PacketTunnel` |
| extension is a child of the app's identifier | `org.olcbox.app.desktopApp` + `.PacketTunnel` |
| extension declares itself properly | `CFBundlePackageType = SYSX`, `NetworkExtension` dict, `CFBundleSupportedPlatforms` |
| extension binary registers a provider | build asserts the `startSystemExtensionMode` selector is in the binary |
| extension signed and authorised | Developer ID, hardened runtime, own profile with `packet-tunnel-provider-systemextension` |
| DMG notarised and stapled | `spctl -a` → accepted, Notarized Developer ID |
| not a quarantine problem | flags `01c3` (assessed), and the DMG carried no quarantine at all |
| not a stale sysextd/LS cache | survives detach + re-register + reinstall |

`systemextensionsctl developer on` could not be used: it refuses while SIP is
enabled, and SIP stays enabled.

## The one remaining structural difference

The host is a **JVM app produced by jpackage** — `lsappinfo` reports
`creator="java"` and a coalition of two processes. Every working system extension
this was modelled on lives inside a native app.

**The experiment that decides it**, and the thing to do first next time: build a
minimal *native* macOS app bundle with swiftc (no window needed — an entry point
and an Info.plist), sign it with the same Developer ID and the same app profile,
embed the *same* extension, put it in `/Applications`, and request activation
from there.

- It works → the JVM host is the problem, and the answer is a small native host
  bundle that owns the extension and talks to the Compose app.
- Same code 4 → the extension is the problem, and the next step is a reference
  extension built by Xcode, compared bundle to bundle.

Either way the answer is one build away, which is not where four rounds of
reasoning got us.

## Traps already paid for

Each of these was a real defect, fixed, and **none of them was the cause** — they
are listed so nobody pays twice:

- **A restricted entitlement with no profile** kills the app at launch. AMFI
  sends SIGKILL, macOS says only "cannot be opened", and codesign, the build and
  notarisation all pass. `Killed: 9` from a terminal is the tell.
- **Notarisation was gated on publishing.** A system extension will not install
  from an app that is not notarised, so every macOS build notarises now.
- **The bridge library was loaded by name.** `jna.library.path` points at
  `user.dir/native`, which does not exist inside a packaged `.app`; it is a
  generated native resource now, extracted at runtime like the olcRTC library.
- **The extension binary had no entry point.** A NetworkExtension system
  extension must call `NEProvider.startSystemExtensionMode()`; swiftc happily
  produced a binary whose `main` returned immediately, and macOS reported the
  extension as missing.
- **`nm -u` cannot see an Objective-C selector.** The check written to catch the
  above looked in the undefined-symbol table and would have failed every binary;
  it reads the ObjC metadata now.
- **`log show` hides `info` level** unless `--info` is passed. An empty table
  looks exactly like a bridge that never ran.
- **`strings | grep -q` under `set -o pipefail`** reports "missing" for something
  present, because the reader closing first is a SIGPIPE for the writer.

---

# Parked, 2026-08-05

**Not our bug.** macOS 26 rejects *new* system-extension activations with exactly
this message, for fully native, correctly signed, notarised apps — LuLu among
them. Extensions activated before macOS 26 keep working; new ones do not. Apple
DTS calls the "no policy" line a red herring, points at `sysextd` losing a helper
service during `realize`, and has published no fix:

- https://developer.apple.com/forums/thread/817101
- https://developer.apple.com/forums/thread/820254

So the decisive experiment written above would have failed whichever way it was
built, and the four rounds spent on the bundle were spent against a wall.

Two things learned on the way out, both of which would have sunk the plan on
their own. Apple requires `OSSystemExtensionRequest` to come from the **container
app's main executable** — not a helper, a daemon or a command-line tool — so the
"native helper inside the same bundle" idea was never allowed. And no
cross-platform VPN ships NetworkExtension on macOS: Mullvad, Tailscale and Clash
Verge Rev all run a root daemon instead.

macOS ships a TUN that way now — see `docs/macos-tunnel-daemon.md` on
`feat/macos-tun-daemon`. This branch stays exactly as it is: it is the shape to
resume from if the Mac App Store ever needs a provider, and everything above is
still correct for that day.
