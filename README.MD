# Turnbox

Kotlin Multiplatform app with shared Compose UI.

Android supports TUN mode through `VpnService` with bundled `hev-socks5-tunnel` and `olcrtc`, plus proxy mode with local `olcrtc` SOCKS5.
macOS and Windows desktop builds use proxy mode: local `olcrtc` SOCKS5 plus OS PAC proxy settings.
Linux desktop builds use TUN mode: local `olcrtc` SOCKS5 plus bundled `hev-socks5-tunnel`.

## Prerequisites

- JDK 17 or newer.
- Android Studio, Android SDK and NDK for Android builds.
- Go for desktop builds, because `desktopApp` builds bundled `olcrtc` binaries from source.
- The `olcrtc` fork must be available locally.
- Linux TUN mode requires `iproute2`, `/dev/net/tun`, and root/Polkit/sudo privileges.

For local development keep the `olcrtc` fork next to this repository or pass it explicitly.

```bash
../olcrtc-original
```

Use `OLCRTC_REPO` if the fork is somewhere else:

```bash
OLCRTC_REPO=/path/to/olcrtc ./gradlew :desktopApp:run
```

On Windows PowerShell:

```powershell
$env:OLCRTC_REPO="C:\path\to\olcrtc"
.\gradlew.bat :desktopApp:run
```

## Android

Debug APK:

```bash
./gradlew :androidApp:assembleDebug
```

Output:

```text
androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Install debug APK to a connected device:

```bash
./gradlew :androidApp:installDebug
```

Release APK requires `keystore.properties` in the repository root:

```properties
storeFile=/absolute/path/to/release.keystore
storePassword=...
keyAlias=...
keyPassword=...
```

Build release APK:

```bash
./gradlew :androidApp:assembleRelease
```

Output:

```text
androidApp/build/outputs/apk/release/androidApp-release.apk
```

Android native libraries are built by Gradle/NDK from `androidApp/src/main/jni`.
Configured ABIs are `arm64-v8a` and `x86_64`.

Android mode selector is available from application settings in the top bar. TUN mode routes device traffic through the VPN interface. Proxy mode starts the local SOCKS5 endpoint without creating a VPN interface.
Android proxy mode exposes authenticated SOCKS5 on `127.0.0.1:10808` with user `turnbox`; a random password is generated on first launch and can be changed from application settings.

## Desktop Development

Run desktop app:

```bash
./gradlew :desktopApp:run
```

Run desktop app with hot reload:

```bash
./gradlew :desktopApp:hotRun --auto
```

Build bundled desktop `olcrtc` binaries only:

```bash
./gradlew :desktopApp:buildDesktopNativeAssets
```

Outputs:

```text
desktopApp/build/generated/desktopNativeResources/native/olcrtc-darwin-arm64
desktopApp/build/generated/desktopNativeResources/native/olcrtc-linux-amd64
desktopApp/build/generated/desktopNativeResources/native/olcrtc-linux-arm64
desktopApp/build/generated/desktopNativeResources/native/olcrtc-windows-amd64.exe
```

On Linux the same task also builds host-arch `hev-socks5-tunnel`:

```text
desktopApp/build/generated/desktopNativeResources/native/hev-socks5-tunnel-linux-amd64
desktopApp/build/generated/desktopNativeResources/native/hev-socks5-tunnel-linux-arm64
```

## macOS Release

Run on macOS:

```bash
./gradlew :desktopApp:packageReleaseDmg
```

Output:

```text
desktopApp/build/compose/binaries/main-release/dmg/Turnbox-1.0.0.dmg
```

The generated DMG is not notarized unless Apple signing/notarization is configured separately.

## Windows Release

Windows installers must be built on Windows. Compose/JPackage does not cross-package Windows `.exe` or `.msi` from macOS/Linux.

Build EXE on Windows:

```powershell
$env:OLCRTC_REPO="C:\path\to\olcrtc"
.\gradlew.bat :desktopApp:packageReleaseExe
```

Output:

```text
desktopApp\build\compose\binaries\main-release\exe\Turnbox-1.0.0.exe
```

Build MSI on Windows:

```powershell
$env:OLCRTC_REPO="C:\path\to\olcrtc"
.\gradlew.bat :desktopApp:packageReleaseMsi
```

Output:

```text
desktopApp\build\compose\binaries\main-release\msi\Turnbox-1.0.0.msi
```

If JPackage reports missing Windows packaging tools, install a JDK with `jpackage` support and WiX Toolset, then make sure WiX is available in `PATH`.

## Linux Release

Run on Linux:

```bash
./gradlew :desktopApp:packageReleaseDeb
```

Output:

```text
desktopApp/build/compose/binaries/main-release/deb/turnbox_1.0.0_amd64.deb
```

Linux packaging also must be run on Linux.

Linux runtime uses TUN mode, not system proxy mode. Start requires root privileges for `olcrtc`, `hev-socks5-tunnel`, and route setup. By default Turnbox tries `pkexec` first and falls back to `sudo -n`.

Force sudo:

```bash
TURNBOX_LINUX_PRIVILEGE=sudo ./gradlew :desktopApp:run
```

Force Polkit:

```bash
TURNBOX_LINUX_PRIVILEGE=pkexec ./gradlew :desktopApp:run
```

The TUN interface is `turnbox0`. Stop removes the policy routes and reverts `systemd-resolved` settings when `resolvectl` is available.

## Current OS Release Shortcut

Package for the current host OS:

```bash
./gradlew :desktopApp:packageReleaseDistributionForCurrentOS
```

This does not cross-package other operating systems.

## Checks

Run the main verification set:

```bash
./gradlew :sharedUI:jvmTest :desktopApp:compileKotlin :androidApp:assembleDebug
```

Run desktop JVM tests only:

```bash
./gradlew :sharedUI:jvmTest
```
