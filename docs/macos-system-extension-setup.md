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
