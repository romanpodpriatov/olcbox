# App Store submission — what is done and what is not

Written 2026-07-28, against `main`. The point of this file is that the next
person to attempt a submission does not rediscover the same six things.

## Done in the repository

- **No self-update on iOS.** The release feed is not queried, the "Update
  available" sheet cannot appear, and nothing points a user at a download page.
  An App Store build whose version numbers disagree with the store's, telling
  people to install it from elsewhere, is a rejection.
- **No unused background mode.** `UIBackgroundModes: audio` was declared for a
  silent-audio keep-alive that no longer runs at all. Declaring a mode the app
  does not use is 2.5.4.
- **`NSCameraUsageDescription`** is present and says what the camera is for: one
  QR code, nothing recorded.
- **`PrivacyInfo.xcprivacy`** declares the one required-reason API this app uses
  (`NSUserDefaults`, reason `CA92.1`) and that it collects nothing and tracks
  nobody. **It must be a member of the app target** — a file on disk that
  nothing references is not in the bundle. Check it in Xcode's File Inspector
  before the first upload.
- **Diagnostics are off.** `MemoryWatch.enabled` and olcRTC's verbose engine log
  are both false; each was a switch that earned its place during a specific
  investigation and costs real work in a process with a ~50 MB ceiling.
- **Admin plumbing fails closed.** The per-location configurator and "create
  custom location" appear only in a build with `OLCBOX_ADMIN_PASS_SHA256` baked
  in, and only once seven taps and the password have opened it. Forgetting the
  secret now hides them rather than showing an operator's edge addresses, SNIs
  and certificate pins to every customer.

## Decisions that are yours, not the code's

- **Export compliance.** No `ITSAppUsesNonExemptEncryption` key is set, so App
  Store Connect asks on every upload. Setting it is a legal declaration about
  what this app does, and a VPN carrying a custom transport is not obviously the
  same case as an app that only speaks TLS. Answer it once, deliberately, then
  bake the key to stop being asked.
- **The signing identity.** Guideline 5.4 requires VPN apps to come from an
  organisation account, which Team `3QJG3J7L66` (Globvent inc) is.
- **Privacy policy URL** and support URL, both required by the listing.
- **What the listing says.** Screenshots, description, age rating, category.

## Before the first upload

1. Build with the admin hash set, and verify on a device that seven taps on the
   title are needed before the configurator appears. An ungated build is not a
   build to ship.
2. Confirm `PrivacyInfo.xcprivacy` is in the app target's Copy Bundle Resources.
3. Bump `CFBundleVersion` for every upload; App Store Connect rejects a repeat.
4. TestFlight first. The tunnel behaves differently under a store-signed
   provisioning profile than under a development one, and the first place to
   discover that is not review.

## Known and deliberate

- **No latency for an unconnected Reality/Hysteria2/XHTTP exit beyond ICMP.**
  Their cores live in the tunnel extension, which runs one location at a time,
  and they cannot be linked into the app beside olcRTC's framework — two
  gomobile binds in one binary is fifty duplicate symbols. ICMP measures the
  path, which is what "ping" has always meant, and every node tested answers it.
- **`rememberModalBottomSheetState` is deprecated and suppressed** at four call
  sites. Its replacement is itself alpha in the pinned material3; migrate all
  four together, deliberately, not during a release.
