# macOS TUN via a root daemon — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give macOS a system-wide TUN by running the bundled sing-box as root under a
small Swift daemon registered with `SMAppService`, driven from the Compose app over a
unix socket.

**Architecture:** The app's cores (sing-box / Xray / olcrtc) keep their current lifecycle
and expose SOCKS on localhost. A root daemon execs a *second* sing-box whose only job is
`tun → socks(core)` — the `buildTunSocks` shape already proven on iOS. The daemon owns
the utun, `auto_route` owns the routes, and `route_exclude_address` keeps the cores' own
upstream packets out of the tunnel. No NetworkExtension, no provisioning profiles, no
restricted entitlements.

**Tech Stack:** Kotlin Multiplatform (jvmMain/commonMain), Swift 5 via `swiftc` from
Gradle, `SMAppService` (macOS 13+), unix domain sockets (JDK 16+ `UnixDomainSocketAddress`),
JNA for the registration bridge, sing-box 1.13.14.

**Spec:** `docs/superpowers/specs/2026-08-04-macos-tun-daemon-design.md`

## Global Constraints

- **Repo:** `/root/olcbox-fork`, branch `feat/macos-tun-daemon` (already created off `main`).
- **This box has no JDK, no Gradle, no Xcode.** Never run `./gradlew`, never `dnf install java`.
  Kotlin verification = commit, push the branch, read `pr-checks.yml`
  (`:sharedUI:jvmTest :desktopApp:compileKotlin :androidApp:assembleDebug`) — it runs on
  every branch push. Swift/macOS packaging verification = `release.yml` (dispatch-only,
  10x-billed macOS runners): batch those, do not dispatch per commit.
- **Team ID:** `3QJG3J7L66` (Globvent inc). **App bundle id:** `org.olcbox.app.desktopApp`.
- **Daemon label:** `org.olcbox.app.desktopApp.tunneld`. **Socket:** `/var/run/org.olcbox.app.tunneld.sock`.
- **Root state dir:** `/Library/Application Support/org.olcbox.app/` (root-owned, 0700).
- **sing-box pin:** `1.13.14` — same value in `SingBoxConfig.SINGBOX_VERSION`, `release.yml`,
  `singbox-verify.yml`. A config shape is not trusted until `sing-box check` accepts it on
  that binary in CI.
- **macOS floor:** 13.0. Below it the app keeps today's `SystemProxy` behaviour, silently.
- **No new CI secrets.** `MACOS_SIGN_IDENTITY` already exists; the two
  `MACOS_*_PROVISION_PROFILE_BASE64` secrets stay unused by this path.
- Comments explain *why*, in the voice of the surrounding code. No decorative comments.

---

### Task 1: The daemon's sing-box config shape

**Files:**
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/SingBoxConfig.kt`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/net/SingBoxConfigTest.kt`
- Test: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/net/SingBoxConfigDumpTest.kt`
- Modify: `.github/workflows/singbox-verify.yml:242` (add a check step after `olcrtc-socks`)

**Interfaces:**
- Consumes: existing `renderTun`, `TCP_DNS_SERVER`, `SINGBOX_SOCKS_PORT` in the same file.
- Produces:
  ```kotlin
  const val DESKTOP_TUN_ADDRESS = "172.19.0.1/30"
  const val DESKTOP_TUN_MTU = 1500
  fun SingBoxConfig.buildDesktopTun(
      corePort: Int,
      verifyPort: Int,
      username: String = "",
      password: String = "",
      excludeAddresses: List<String> = emptyList(),
      directDnsDomains: List<String> = emptyList(),
      upstreamUdpIsLossy: Boolean = false,
      address: String = DESKTOP_TUN_ADDRESS,
      mtu: Int = DESKTOP_TUN_MTU,
  ): String
  ```
  `excludeAddresses` are already-suffixed CIDRs (`"1.2.3.4/32"`), not bare IPs.

- [ ] **Step 1: Write the failing tests**

Append to `sharedUI/src/commonTest/kotlin/org/olcbox/app/net/SingBoxConfigTest.kt`:

```kotlin
    @Test
    fun desktopTunExcludesTheServerSoTheCoreDoesNotRouteThroughItself() {
        val json = SingBoxConfig.buildDesktopTun(
            corePort = 10810,
            verifyPort = 10811,
            excludeAddresses = listOf("203.0.113.7/32", "2001:db8::1/128")
        )
        assertContains(json, "\"route_exclude_address\"")
        assertContains(json, "203.0.113.7/32")
        assertContains(json, "2001:db8::1/128")
    }

    @Test
    fun desktopTunSendsTheServerDomainToTheSystemResolverDirect() {
        // The core redials while the tun is up. Its DNS query for the server's
        // own hostname enters the tun like everything else, and answering it
        // through the tunnel needs the tunnel that is being redialled.
        val json = SingBoxConfig.buildDesktopTun(
            corePort = 10810,
            verifyPort = 10811,
            directDnsDomains = listOf("de1.example.org")
        )
        assertContains(json, "\"type\":\"local\"")
        assertContains(json, "\"tag\":\"dns-direct\"")
        assertContains(json, "de1.example.org")
    }

    @Test
    fun desktopTunOffersALocalSocksSoTheVerifierProvesTheWholeChain() {
        val json = SingBoxConfig.buildDesktopTun(corePort = 10810, verifyPort = 10811)
        assertContains(json, "\"tag\":\"verify-in\"")
        assertContains(json, "\"listen_port\":10811")
        assertContains(json, "\"listen\":\"127.0.0.1\"")
    }

    @Test
    fun desktopTunCarriesSocksCredentialsOnlyWhenTheCoreAskedForThem() {
        val bare = SingBoxConfig.buildDesktopTun(corePort = 10810, verifyPort = 10811)
        assertTrue("\"username\"" !in bare)

        val authed = SingBoxConfig.buildDesktopTun(
            corePort = 10810, verifyPort = 10811, username = "u", password = "p"
        )
        assertContains(authed, "\"username\":\"u\"")
        assertContains(authed, "\"password\":\"p\"")
    }

    @Test
    fun desktopTunMtuIsNotTheIosOne() {
        // iOS rejects 9000 outright; a utun on macOS is no place to find out.
        assertContains(
            SingBoxConfig.buildDesktopTun(corePort = 10810, verifyPort = 10811),
            "\"mtu\":1500"
        )
    }
```

Append to `sharedUI/src/jvmTest/kotlin/org/olcbox/app/net/SingBoxConfigDumpTest.kt`:

```kotlin
    @Test fun dumpDesktopTun() {
        dump(
            "desktop-tun",
            SingBoxConfig.buildDesktopTun(
                corePort = 10810,
                verifyPort = 10811,
                excludeAddresses = listOf("203.0.113.7/32"),
                directDnsDomains = listOf("de1.example.org"),
                upstreamUdpIsLossy = true
            )
        )
        assertTrue(File(outDir, "desktop-tun.json").exists())
    }
```

- [ ] **Step 2: Push and confirm the tests fail**

```bash
cd /root/olcbox-fork
git add -A && git commit -m "test(macos): the desktop tun config shape, before it exists"
git push proofkit feat/macos-tun-daemon
```
Expected: `pr-checks.yml` fails to compile — `Unresolved reference: buildDesktopTun`.

- [ ] **Step 3: Implement**

In `SingBoxConfig.kt`, next to `TUN_ADDRESS`/`TUN_MTU`:

```kotlin
    /**
     * The desktop tun is not the iOS one. iOS rejects an MTU of 9000
     * (`nesessionmanager: failed to set the MTU to 9000`) and a utun on macOS is
     * no place to discover the same thing, so the desktop shape uses the 1500 the
     * Linux and Windows controllers have always used.
     */
    const val DESKTOP_TUN_ADDRESS = "172.19.0.1/30"
    const val DESKTOP_TUN_MTU = 1500
```

Then the builder — it cannot reuse `renderTun`, because that one emits neither the
exclusion nor a second inbound:

```kotlin
    /**
     * The config the macOS root daemon runs: a tun in front of the core that the
     * app already started on localhost.
     *
     * Two things here exist only because the core is a *separate process* — on
     * iOS the outbound lives inside the same binary and neither is needed:
     *
     * `route_exclude_address` keeps the core's own packets to the VPN server out
     * of the tun. Without it `auto_route` sends them into the tunnel they are
     * trying to build, and the tunnel eats itself.
     *
     * `directDnsDomains` does the same for name resolution: the core redials by
     * hostname, that query enters the tun like any other, and answering it needs
     * the tunnel being redialled. Those names go to the system resolver instead.
     *
     * [verifyPort] is a socks inbound for `TunnelVerifier`. Verifying through the
     * core's own port would prove the core works and say nothing about the tun in
     * front of it — which is exactly the failure this whole path can have.
     */
    fun buildDesktopTun(
        corePort: Int,
        verifyPort: Int,
        username: String = "",
        password: String = "",
        excludeAddresses: List<String> = emptyList(),
        directDnsDomains: List<String> = emptyList(),
        upstreamUdpIsLossy: Boolean = false,
        address: String = DESKTOP_TUN_ADDRESS,
        mtu: Int = DESKTOP_TUN_MTU,
    ): String {
        val obj = buildJsonObject {
            putJsonObject("log") { put("level", "info") }
            if (upstreamUdpIsLossy || directDnsDomains.isNotEmpty()) {
                putJsonObject("dns") {
                    putJsonArray("servers") {
                        if (upstreamUdpIsLossy) {
                            addJsonObject {
                                put("type", "tcp"); put("tag", "dns-remote")
                                put("server", TCP_DNS_SERVER); put("detour", "out")
                            }
                        }
                        if (directDnsDomains.isNotEmpty()) {
                            addJsonObject { put("type", "local"); put("tag", "dns-direct") }
                        }
                    }
                    if (directDnsDomains.isNotEmpty()) {
                        putJsonArray("rules") {
                            addJsonObject {
                                putJsonArray("domain") { directDnsDomains.forEach { add(it) } }
                                put("server", "dns-direct")
                            }
                        }
                    }
                }
            }
            putJsonArray("inbounds") {
                addJsonObject {
                    put("type", "tun"); put("tag", "tun-in")
                    putJsonArray("address") { add(address) }
                    put("mtu", mtu)
                    put("auto_route", true)
                    put("stack", "gvisor")
                    if (excludeAddresses.isNotEmpty()) {
                        putJsonArray("route_exclude_address") {
                            excludeAddresses.forEach { add(it) }
                        }
                    }
                }
                addJsonObject {
                    put("type", "socks"); put("tag", "verify-in")
                    put("listen", "127.0.0.1"); put("listen_port", verifyPort)
                }
            }
            putJsonArray("outbounds") {
                addJsonObject {
                    put("type", "socks"); put("tag", "out")
                    put("server", "127.0.0.1"); put("server_port", corePort)
                    put("version", "5")
                    if (username.isNotBlank()) put("username", username)
                    if (password.isNotBlank()) put("password", password)
                }
                addJsonObject { put("type", "direct"); put("tag", "direct") }
            }
            if (upstreamUdpIsLossy) {
                putJsonObject("route") {
                    putJsonArray("rules") {
                        addJsonObject { put("action", "hijack-dns"); put("port", 53) }
                    }
                }
            }
        }
        return obj.toString()
    }
```

- [ ] **Step 4: Add the CI schema check**

In `.github/workflows/singbox-verify.yml`, immediately after the `check olcrtc-socks` step:

```yaml
      - name: check desktop-tun
        working-directory: olcbox
        run: sing-box check -c sharedUI/build/singbox-configs/desktop-tun.json
```

- [ ] **Step 5: Push and confirm green, then dispatch the schema check**

```bash
git add -A && git commit -m "feat(macos): the tun config the root daemon runs"
git push proofkit feat/macos-tun-daemon
gh workflow run singbox-verify.yml --ref feat/macos-tun-daemon
```
Expected: `pr-checks.yml` green; `core verify` green on the new step. If `sing-box check`
rejects the shape, that is the task's real deliverable failing — fix the JSON, not the test.

- [ ] **Step 6: Hand the config to the user for the on-Mac proof**

Print `desktop-tun.json` from the CI log, substitute a real core port, and give the user
this to run on their Mac against a live location — before any daemon exists:

```bash
sudo /Applications/ProofKit.app/Contents/app/... /sing-box run -c ~/tun.json
# expect: a new utun in `ifconfig`, `netstat -rn | head` showing it as default,
# the server IP still routed via the physical gateway, and browsing that works
curl -s https://api.ipify.org; echo
```
This proves the data path on the exact macOS in play, with no CI and no Swift.

---

### Task 2: Daemon wire protocol

**Files:**
- Create: `sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/desktop/TunnelDaemonProtocol.kt`
- Test: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/TunnelDaemonProtocolTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  ```kotlin
  internal sealed interface DaemonReply {
      data class Ok(val state: String, val pid: Int?, val logTail: String) : DaemonReply
      data class Failure(val message: String, val logTail: String) : DaemonReply
      companion object { const val STATE_RUNNING = "running"; const val STATE_IDLE = "idle" }
  }
  internal object TunnelDaemonProtocol {
      fun startRequest(config: String): String
      fun stopRequest(): String
      fun statusRequest(): String
      fun parseReply(line: String): DaemonReply
  }
  ```

- [ ] **Step 1: Write the failing test**

```kotlin
package org.olcbox.app.vpn.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TunnelDaemonProtocolTest {

    @Test
    fun requestsAreOneLineEachBecauseTheDaemonReadsByLine() {
        val start = TunnelDaemonProtocol.startRequest("{\"log\":{\"level\":\"info\"}}")
        assertEquals(1, start.count { it == '\n' })
        assertTrue(start.endsWith("\n"))
        assertTrue(start.startsWith("{"))
        // The config is a JSON *string* inside the request, not spliced in raw:
        // spliced, its own newlines would end the request half-written.
        assertTrue("\\\"level\\\"" in start)
    }

    @Test
    fun aRunningDaemonReportsItsChildPid() {
        val reply = TunnelDaemonProtocol.parseReply(
            """{"ok":true,"state":"running","pid":4242,"logTail":"started"}"""
        )
        assertIs<DaemonReply.Ok>(reply)
        assertEquals("running", reply.state)
        assertEquals(4242, reply.pid)
    }

    @Test
    fun anErrorKeepsTheTailBecauseTheReasonIsInSingBoxOutputNotInOurMessage() {
        val reply = TunnelDaemonProtocol.parseReply(
            """{"ok":false,"error":"sing-box exited","logTail":"FATAL bind: permission denied"}"""
        )
        assertIs<DaemonReply.Failure>(reply)
        assertEquals("sing-box exited", reply.message)
        assertTrue("permission denied" in reply.logTail)
    }

    @Test
    fun anUnparseableReplyIsAFailureNotACrashAndNeverAnOk() {
        // A daemon that answers garbage is a daemon in an unknown state, and the
        // only safe direction to round an unknown state is down.
        assertIs<DaemonReply.Failure>(TunnelDaemonProtocol.parseReply("not json"))
        assertIs<DaemonReply.Failure>(TunnelDaemonProtocol.parseReply(""))
        assertIs<DaemonReply.Failure>(TunnelDaemonProtocol.parseReply("""{"state":"running"}"""))
    }
}
```

- [ ] **Step 2: Push and confirm it fails**

```bash
git add -A && git commit -m "test(macos): the daemon wire protocol"
git push proofkit feat/macos-tun-daemon
```
Expected: `Unresolved reference: TunnelDaemonProtocol`.

- [ ] **Step 3: Implement**

```kotlin
package org.olcbox.app.vpn.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * What the app and the root daemon say to each other: one JSON object per line,
 * one reply per request, connection closed after it.
 *
 * Line-delimited rather than length-prefixed because every message here is small
 * and a human debugging this can type one into `nc -U`.
 */
internal sealed interface DaemonReply {
    data class Ok(val state: String, val pid: Int?, val logTail: String) : DaemonReply
    data class Failure(val message: String, val logTail: String) : DaemonReply

    companion object {
        const val STATE_RUNNING = "running"
        const val STATE_IDLE = "idle"
    }
}

internal object TunnelDaemonProtocol {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The config travels as a JSON *string*, not as a nested object: the daemon
     * writes it to disk byte for byte, and a re-serialised copy is a copy the
     * `sing-box check` in CI never saw.
     */
    fun startRequest(config: String): String =
        line(buildJsonObject { put("verb", "start"); put("config", config) })

    fun stopRequest(): String = line(buildJsonObject { put("verb", "stop") })

    fun statusRequest(): String = line(buildJsonObject { put("verb", "status") })

    private fun line(obj: JsonObject): String = obj.toString() + "\n"

    fun parseReply(raw: String): DaemonReply {
        val obj = runCatching { json.parseToJsonElement(raw.trim()) as? JsonObject }
            .getOrNull()
            ?: return DaemonReply.Failure("daemon replied with something that is not JSON", raw.take(TAIL))
        val tail = obj.str("logTail").orEmpty()
        val ok = (obj["ok"] as? JsonPrimitive)?.booleanOrNull
            ?: return DaemonReply.Failure("daemon reply carried no verdict", tail)
        if (!ok) return DaemonReply.Failure(obj.str("error") ?: "daemon reported a failure", tail)
        val state = obj.str("state")
            ?: return DaemonReply.Failure("daemon reported success without a state", tail)
        return DaemonReply.Ok(state, (obj["pid"] as? JsonPrimitive)?.intOrNull, tail)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private const val TAIL = 400
}
```

- [ ] **Step 4: Push and confirm green**

```bash
git add -A && git commit -m "feat(macos): the daemon wire protocol"
git push proofkit feat/macos-tun-daemon
```
Expected: `pr-checks.yml` green.

---

### Task 3: Unix-socket client

**Files:**
- Create: `sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/desktop/TunnelDaemonClient.kt`
- Test: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/TunnelDaemonClientTest.kt`

**Interfaces:**
- Consumes: `TunnelDaemonProtocol`, `DaemonReply` from Task 2.
- Produces:
  ```kotlin
  internal class TunnelDaemonClient(private val socketPath: Path = DEFAULT_SOCKET_PATH) {
      suspend fun start(config: String): DaemonReply
      suspend fun stop(): DaemonReply
      suspend fun status(): DaemonReply
      suspend fun isReachable(): Boolean
      companion object { val DEFAULT_SOCKET_PATH: Path = Path("/var/run/org.olcbox.app.tunneld.sock") }
  }
  ```

- [ ] **Step 1: Write the failing test**

The test stands up a real `UnixDomainSocketAddress` server in-process, so it exercises the
actual socket path rather than a mock of it.

```kotlin
package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse

class TunnelDaemonClientTest {

    private fun withFakeDaemon(reply: String, body: (java.nio.file.Path) -> Unit) {
        val dir = Files.createTempDirectory("tunneld-test")
        val path = dir.resolve("sock")
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(path))
        val thread = Thread {
            runCatching {
                while (true) {
                    val ch = server.accept()
                    ch.use {
                        val buf = ByteBuffer.allocate(64 * 1024)
                        it.read(buf)
                        it.write(ByteBuffer.wrap(reply.toByteArray()))
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            body(path)
        } finally {
            server.close()
            thread.interrupt()
            path.deleteIfExists()
        }
    }

    @Test
    fun statusRoundTripsThroughARealUnixSocket() = withFakeDaemon(
        """{"ok":true,"state":"running","pid":7,"logTail":""}""" + "\n"
    ) { path ->
        val reply = runBlocking { TunnelDaemonClient(path).status() }
        assertIs<DaemonReply.Ok>(reply)
        assertEquals("running", reply.state)
        assertEquals(7, reply.pid)
    }

    @Test
    fun anAbsentSocketIsAFailureNotAnException() {
        // Before the daemon is approved there is no socket, and that is the
        // normal state of a fresh install — not something to throw about.
        val missing = java.nio.file.Path.of("/var/run/org.olcbox.app.tunneld.missing")
        val reply = runBlocking { TunnelDaemonClient(missing).status() }
        assertIs<DaemonReply.Failure>(reply)
        assertFalse(runBlocking { TunnelDaemonClient(missing).isReachable() })
    }
}
```

- [ ] **Step 2: Push and confirm it fails**

```bash
git add -A && git commit -m "test(macos): the daemon socket client"
git push proofkit feat/macos-tun-daemon
```
Expected: `Unresolved reference: TunnelDaemonClient`.

- [ ] **Step 3: Implement**

```kotlin
package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Talks to the root daemon over its unix socket.
 *
 * One connection per request, closed after the reply: the daemon holds the
 * tunnel, not the conversation, so a client that dies mid-sentence costs
 * nothing. That is also why nothing here retries — a caller that wants the
 * tunnel back asks for it again.
 */
internal class TunnelDaemonClient(
    private val socketPath: Path = DEFAULT_SOCKET_PATH
) {
    suspend fun start(config: String): DaemonReply = send(TunnelDaemonProtocol.startRequest(config))

    suspend fun stop(): DaemonReply = send(TunnelDaemonProtocol.stopRequest())

    suspend fun status(): DaemonReply = send(TunnelDaemonProtocol.statusRequest())

    suspend fun isReachable(): Boolean = status() is DaemonReply.Ok

    private suspend fun send(request: String): DaemonReply = withContext(Dispatchers.IO) {
        if (!socketPath.exists()) {
            return@withContext DaemonReply.Failure("the tunnel daemon is not installed", "")
        }
        runCatching {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(UnixDomainSocketAddress.of(socketPath))
                val out = ByteBuffer.wrap(request.toByteArray())
                while (out.hasRemaining()) channel.write(out)
                channel.shutdownOutput()
                readLine(channel)
            }
        }.fold(
            onSuccess = { TunnelDaemonProtocol.parseReply(it) },
            onFailure = { DaemonReply.Failure(it.message ?: "the tunnel daemon did not answer", "") }
        )
    }

    private fun readLine(channel: SocketChannel): String {
        val buffer = ByteBuffer.allocate(REPLY_LIMIT)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) break
            val seen = String(buffer.array(), 0, buffer.position())
            if ('\n' in seen) return seen
        }
        return String(buffer.array(), 0, buffer.position())
    }

    companion object {
        val DEFAULT_SOCKET_PATH: Path = Path.of("/var/run/org.olcbox.app.tunneld.sock")

        /** A reply carries a log tail, not a log. Anything larger is a daemon gone wrong. */
        private const val REPLY_LIMIT = 64 * 1024
    }
}
```

- [ ] **Step 4: Push and confirm green**

```bash
git add -A && git commit -m "feat(macos): the daemon socket client"
git push proofkit feat/macos-tun-daemon
```

---

### Task 4: The root daemon (Swift)

**Files:**
- Create: `desktopApp/tunneldaemon/main.swift`
- Create: `desktopApp/tunneldaemon/PeerAuthority.swift`
- Create: `desktopApp/tunneldaemon/TunnelChild.swift`
- Create: `desktopApp/tunneldaemon/org.olcbox.app.desktopApp.tunneld.plist`

**Interfaces:**
- Consumes: the wire protocol from Task 2 (the other end of it).
- Produces: a binary named `ProofKitTunnelDaemon` and a launchd plist. Task 5 builds,
  signs and embeds them; nothing in Kotlin references these files directly.

**This code cannot be compiled on this box.** Its first real compile is Task 5's CI run.
Write it carefully and expect the first macOS run to fix a signature or two.

- [ ] **Step 1: The peer check**

`desktopApp/tunneldaemon/PeerAuthority.swift`:

```swift
// Who is allowed to command the tunnel.
//
// This is the security boundary of the whole design: the socket is reachable by
// any local process, and behind it is root. Clash Verge shipped a local root
// escalation by not having this check, which is the reason it is the first file
// written rather than the last.
//
// The peer is identified by its *audit token*, not its pid. A pid can be reused
// between the moment it is read and the moment it is checked; the audit token
// names one specific process for as long as it exists.
import Foundation
import Security

enum PeerAuthority {
    /// `anchor apple generic` pins the chain to Apple's root, the OU pins it to
    /// our team, and the identifier pins it to our app — all three, because any
    /// two of them are satisfied by something we did not build.
    static let requirementText =
        "anchor apple generic and certificate leaf[subject.OU] = \"3QJG3J7L66\" " +
        "and identifier \"org.olcbox.app.desktopApp\""

    static func isTrusted(fd: Int32) -> Bool {
        var token = audit_token_t()
        var length = socklen_t(MemoryLayout<audit_token_t>.size)
        // LOCAL_PEERTOKEN is not surfaced in the Swift overlay; the value is from
        // <sys/un.h> and has been stable since it was introduced.
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

        var requirement: SecRequirement?
        guard SecRequirementCreateWithString(requirementText as CFString, [], &requirement) == errSecSuccess,
              let rule = requirement else { return false }

        return SecCodeCheckValidity(peer, [], rule) == errSecSuccess
    }

    /// The same rule applied to a file on disk, used before the daemon execs it.
    static func isTrustedBinary(at url: URL) -> Bool {
        var staticCode: SecStaticCode?
        guard SecStaticCodeCreateWithPath(url as CFURL, [], &staticCode) == errSecSuccess,
              let code = staticCode else { return false }
        var requirement: SecRequirement?
        let coreRule = "anchor apple generic and certificate leaf[subject.OU] = \"3QJG3J7L66\""
        guard SecRequirementCreateWithString(coreRule as CFString, [], &requirement) == errSecSuccess,
              let rule = requirement else { return false }
        return SecStaticCodeCheckValidity(code, [], rule) == errSecSuccess
    }
}
```

- [ ] **Step 2: The child process**

`desktopApp/tunneldaemon/TunnelChild.swift`:

```swift
// The sing-box the daemon runs, and where it is allowed to run it from.
//
// Never from a path the client sends, and never from a directory a non-root
// user can write: /Applications is writable by any admin, so a binary verified
// there and executed there can be swapped in between the two. The bundled core
// is verified, copied into a root-owned directory, and executed from the copy.
import Foundation

final class TunnelChild {
    static let stateDir = URL(fileURLWithPath: "/Library/Application Support/org.olcbox.app", isDirectory: true)

    private var process: Process?
    private var tail: [String] = []
    private let lock = NSLock()

    var isRunning: Bool { lock.withLock { process?.isRunning ?? false } }
    var pid: Int32? { lock.withLock { process?.isRunning == true ? process?.processIdentifier : nil } }
    var logTail: String { lock.withLock { tail.suffix(40).joined(separator: "\n") } }

    /// The bundled core, found relative to this binary: the daemon lives at
    /// `ProofKit.app/Contents/MacOS/ProofKitTunnelDaemon`, the core at
    /// `ProofKit.app/Contents/Resources/sing-box`.
    private var bundledCore: URL {
        URL(fileURLWithPath: CommandLine.arguments[0])
            .resolvingSymlinksInPath()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("Resources/sing-box")
    }

    func start(config: String) throws {
        stop()
        try FileManager.default.createDirectory(
            at: Self.stateDir, withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700, .ownerAccountID: 0]
        )

        let source = bundledCore
        guard FileManager.default.isReadableFile(atPath: source.path) else {
            throw DaemonError.message("no bundled sing-box at \(source.path)")
        }
        guard PeerAuthority.isTrustedBinary(at: source) else {
            throw DaemonError.message("the bundled sing-box is not signed by this team")
        }

        let binDir = Self.stateDir.appendingPathComponent("bin", isDirectory: true)
        try FileManager.default.createDirectory(
            at: binDir, withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700, .ownerAccountID: 0]
        )
        let core = binDir.appendingPathComponent("sing-box")
        try? FileManager.default.removeItem(at: core)
        try FileManager.default.copyItem(at: source, to: core)
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o700, .ownerAccountID: 0], ofItemAtPath: core.path
        )

        let configURL = Self.stateDir.appendingPathComponent("tun.json")
        try config.write(to: configURL, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o600, .ownerAccountID: 0], ofItemAtPath: configURL.path
        )

        let task = Process()
        task.executableURL = core
        task.arguments = ["run", "-c", configURL.path]
        let pipe = Pipe()
        task.standardOutput = pipe
        task.standardError = pipe
        pipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
            guard let text = String(data: handle.availableData, encoding: .utf8), !text.isEmpty else { return }
            self?.lock.withLock {
                self?.tail.append(contentsOf: text.split(separator: "\n").map(String.init))
                if let count = self?.tail.count, count > 200 { self?.tail.removeFirst(count - 200) }
            }
        }
        try task.run()
        lock.withLock { self.process = task; self.tail = [] }
    }

    func stop() {
        let running = lock.withLock { () -> Process? in
            defer { process = nil }
            return process?.isRunning == true ? process : nil
        }
        guard let task = running else { return }
        // SIGTERM, then wait: sing-box tears down auto_route on the way out, and
        // killing it outright leaves the machine's default route pointing at a
        // utun that no longer exists.
        task.terminate()
        let deadline = Date().addingTimeInterval(5)
        while task.isRunning && Date() < deadline { usleep(100_000) }
        if task.isRunning { kill(task.processIdentifier, SIGKILL) }
    }
}

enum DaemonError: Error {
    case message(String)
    var text: String { if case let .message(m) = self { return m }; return "unknown" }
}

private extension NSLock {
    func withLock<T>(_ body: () -> T) -> T { lock(); defer { unlock() }; return body() }
}
```

- [ ] **Step 3: The socket loop**

`desktopApp/tunneldaemon/main.swift`:

```swift
// ProofKitTunnelDaemon — the only part of ProofKit that runs as root.
//
// It has one job: run the bundled sing-box with a tun inbound, on behalf of a
// caller it has verified. It parses no links, speaks no protocol of ours, and
// keeps no state beyond the child process — everything else stays in the app,
// where it runs as the user.
import Foundation

let socketPath = "/var/run/org.olcbox.app.tunneld.sock"
let child = TunnelChild()

func reply(_ object: [String: Any]) -> Data {
    let data = (try? JSONSerialization.data(withJSONObject: object)) ?? Data("{\"ok\":false}".utf8)
    return data + Data("\n".utf8)
}

func handle(_ line: String) -> Data {
    guard let payload = line.data(using: .utf8),
          let object = try? JSONSerialization.jsonObject(with: payload) as? [String: Any],
          let verb = object["verb"] as? String else {
        return reply(["ok": false, "error": "unparseable request", "logTail": ""])
    }

    switch verb {
    case "start":
        guard let config = object["config"] as? String else {
            return reply(["ok": false, "error": "start without a config", "logTail": ""])
        }
        do {
            try child.start(config: config)
            // Report what the child is doing a moment later, not what it was
            // asked to do: sing-box fails on a bad config in well under a second,
            // and an "ok" issued before that is a lie the app will draw as green.
            Thread.sleep(forTimeInterval: 1.0)
            guard child.isRunning else {
                return reply(["ok": false, "error": "sing-box exited at once", "logTail": child.logTail])
            }
            return reply(["ok": true, "state": "running", "pid": child.pid ?? 0, "logTail": child.logTail])
        } catch {
            let text = (error as? DaemonError)?.text ?? error.localizedDescription
            return reply(["ok": false, "error": text, "logTail": child.logTail])
        }
    case "stop":
        child.stop()
        return reply(["ok": true, "state": "idle", "logTail": child.logTail])
    case "status":
        return reply([
            "ok": true,
            "state": child.isRunning ? "running" : "idle",
            "pid": child.pid ?? 0,
            "logTail": child.logTail
        ])
    default:
        return reply(["ok": false, "error": "unknown verb \(verb)", "logTail": ""])
    }
}

// launchd does not clean up a socket left by a killed daemon, and bind() on an
// existing path fails with EADDRINUSE — which reads as "already running".
unlink(socketPath)

let listener = socket(AF_UNIX, SOCK_STREAM, 0)
guard listener >= 0 else { exit(1) }
var address = sockaddr_un()
address.sun_family = sa_family_t(AF_UNIX)
_ = withUnsafeMutablePointer(to: &address.sun_path) { pointer in
    socketPath.withCString { source in
        strncpy(UnsafeMutableRawPointer(pointer).assumingMemoryBound(to: CChar.self), source, 104)
    }
}
let size = socklen_t(MemoryLayout<sockaddr_un>.size)
let bound = withUnsafePointer(to: &address) { pointer in
    pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(listener, $0, size) }
}
guard bound == 0, listen(listener, 8) == 0 else { exit(1) }
// 0666: the check that matters is the code-signature one below, and a mode that
// excluded non-admin users would only make the app fail differently for them.
chmod(socketPath, 0o666)

while true {
    let client = accept(listener, nil, nil)
    if client < 0 { continue }
    defer { close(client) }

    guard PeerAuthority.isTrusted(fd: client) else {
        _ = reply(["ok": false, "error": "unauthorized", "logTail": ""]).withUnsafeBytes {
            write(client, $0.baseAddress, $0.count)
        }
        continue
    }

    var buffer = [UInt8](repeating: 0, count: 256 * 1024)
    let read = recv(client, &buffer, buffer.count, 0)
    guard read > 0, let line = String(bytes: buffer[0..<read], encoding: .utf8) else { continue }
    let answer = handle(line.trimmingCharacters(in: .whitespacesAndNewlines))
    _ = answer.withUnsafeBytes { write(client, $0.baseAddress, $0.count) }
}
```

- [ ] **Step 4: The launchd plist**

`desktopApp/tunneldaemon/org.olcbox.app.desktopApp.tunneld.plist`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<!--
  Registered by the app through SMAppService.daemon, so BundleProgram is a path
  *inside the app bundle* — that is what makes the daemon leave with the app when
  it is dragged to the Trash, instead of outliving it in /Library.
-->
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>org.olcbox.app.desktopApp.tunneld</string>
    <key>BundleProgram</key>
    <string>Contents/MacOS/ProofKitTunnelDaemon</string>
    <key>KeepAlive</key>
    <true/>
    <key>RunAtLoad</key>
    <true/>
    <key>AssociatedBundleIdentifiers</key>
    <array>
        <string>org.olcbox.app.desktopApp</string>
    </array>
</dict>
</plist>
```

- [ ] **Step 5: Commit (no CI signal yet — Task 5 builds it)**

```bash
git add -A
git commit -m "feat(macos): the root tunnel daemon, its peer check and its child"
git push proofkit feat/macos-tun-daemon
```

---

### Task 5: Build, sign and embed the daemon

**Files:**
- Modify: `desktopApp/build.gradle.kts` (append a new `if (currentBuildOs.isMacOsX)` block at the end)
- Modify: `.github/workflows/release.yml` (the macOS "Sign the bundled cores" step's loop)

**Interfaces:**
- Consumes: Task 4's sources.
- Produces: inside the built `.app` — `Contents/MacOS/ProofKitTunnelDaemon`,
  `Contents/Library/LaunchDaemons/org.olcbox.app.desktopApp.tunneld.plist`,
  `Contents/Resources/sing-box`, all signed.

- [ ] **Step 1: Add the Gradle task**

Append to `desktopApp/build.gradle.kts`:

```kotlin
// ---------------------------------------------------------------------------
// macOS: the root tunnel daemon.
//
// Unlike the system-extension attempt this replaces, nothing here is restricted:
// no provisioning profile, no entitlement that needs Apple's blessing, no App ID.
// A Developer ID signature and notarisation — both of which every macOS build
// already has — are the whole requirement, which is why this path can ship on a
// macOS where sysextd refuses to activate anything new.
// ---------------------------------------------------------------------------
if (currentBuildOs.isMacOsX) {
    val daemonSourceDir = layout.projectDirectory.dir("tunneldaemon")
    val daemonAppImageDir = layout.buildDirectory.dir("compose/binaries/main-release/app/$desktopPackageName.app")
    val daemonStageDir = layout.buildDirectory.dir("macos/tunneldaemon")

    val embedMacosTunnelDaemon = tasks.register<Exec>("embedMacosTunnelDaemon") {
        group = "distribution"
        description = "Builds, signs and embeds the root tunnel daemon into the .app."

        dependsOn("createReleaseDistributable")
        inputs.dir(daemonSourceDir)
        outputs.dir(daemonStageDir)

        commandLine(
            "bash", "-c",
            """
            set -euo pipefail

            app_dir="${'$'}1"
            src="${'$'}2"
            stage="${'$'}3"
            core_src="${'$'}4"

            identity="${'$'}{MACOS_SIGN_IDENTITY:-}"
            if [ -z "${'$'}identity" ]; then
                echo "No signing identity — building without the tunnel daemon."
                echo "An unsigned daemon cannot pass its own peer check, and SMAppService"
                echo "will not register it, so shipping one would only fail later."
                mkdir -p "${'$'}stage"
                exit 0
            fi

            if [ ! -d "${'$'}app_dir" ]; then
                echo "no app image at ${'$'}app_dir — this task ran against the wrong output" >&2
                exit 1
            fi

            rm -rf "${'$'}stage"; mkdir -p "${'$'}stage"

            swiftc -O -target "${'$'}(uname -m)-apple-macos13.0" \
                -framework Foundation -framework Security \
                -o "${'$'}stage/ProofKitTunnelDaemon" \
                "${'$'}src/PeerAuthority.swift" "${'$'}src/TunnelChild.swift" "${'$'}src/main.swift"

            # The core the daemon execs must be a loose file in the bundle: it is
            # otherwise only inside the jar, where a root daemon cannot reach it
            # and could not verify a signature on it if it could.
            mkdir -p "${'$'}app_dir/Contents/Resources"
            cp "${'$'}core_src" "${'$'}app_dir/Contents/Resources/sing-box"
            chmod 0755 "${'$'}app_dir/Contents/Resources/sing-box"

            mkdir -p "${'$'}app_dir/Contents/Library/LaunchDaemons"
            cp "${'$'}src/org.olcbox.app.desktopApp.tunneld.plist" \
               "${'$'}app_dir/Contents/Library/LaunchDaemons/"
            cp "${'$'}stage/ProofKitTunnelDaemon" "${'$'}app_dir/Contents/MacOS/ProofKitTunnelDaemon"

            # Inside out: the outer signature seals what is nested, so anything
            # signed after the app invalidates the app.
            codesign --force --timestamp --options runtime \
                --sign "${'$'}identity" "${'$'}app_dir/Contents/Resources/sing-box"
            codesign --force --timestamp --options runtime \
                --identifier "org.olcbox.app.desktopApp.tunneld" \
                --sign "${'$'}identity" "${'$'}app_dir/Contents/MacOS/ProofKitTunnelDaemon"
            codesign --force --timestamp --options runtime \
                --entitlements "${'$'}5" \
                --sign "${'$'}identity" "${'$'}app_dir"
            codesign --verify --deep --strict --verbose=2 "${'$'}app_dir"

            # The daemon's own check refuses a peer that is not this app; a build
            # whose app signature does not satisfy that string ships a daemon
            # nothing can talk to, and the symptom is an unhelpful "unauthorized".
            if ! codesign -dr - "${'$'}app_dir" 2>&1 | grep -q "org.olcbox.app.desktopApp"; then
                echo "the app's designated requirement does not name the identifier" >&2
                echo "the daemon's PeerAuthority check pins — every command would be refused." >&2
                exit 1
            fi
            echo "embedded: tunnel daemon + core"
            """.trimIndent(),
            "bash",
            daemonAppImageDir.get().asFile.absolutePath,
            daemonSourceDir.asFile.absolutePath,
            daemonStageDir.get().asFile.absolutePath,
            layout.projectDirectory.file("src/main/resources/native/sing-box").asFile.absolutePath,
            layout.projectDirectory.file("macos-entitlements.plist").asFile.absolutePath
        )
    }

    tasks.matching { it.name == "packageReleaseDmg" }.configureEach {
        dependsOn(embedMacosTunnelDaemon)
    }
}
```

- [ ] **Step 2: Commit and dispatch a macOS build**

```bash
git add -A && git commit -m "build(macos): assemble, sign and embed the tunnel daemon"
git push proofkit feat/macos-tun-daemon
gh workflow run release.yml --ref feat/macos-tun-daemon -f platforms=macos -f publish=false
```
Expected: green, with `embedded: tunnel daemon + core` in the log. Swift compile errors
are expected on the first run — fix and re-dispatch. Confirm in the log that
`codesign --verify --deep --strict` passed *after* the daemon was added.

- [ ] **Step 3: Verify the artefact on the Mac**

```bash
# after installing the DMG
codesign -dv --verbose=4 /Applications/ProofKit.app/Contents/MacOS/ProofKitTunnelDaemon
codesign -dv --verbose=4 /Applications/ProofKit.app/Contents/Resources/sing-box
ls /Applications/ProofKit.app/Contents/Library/LaunchDaemons/
```
Expected: both report `Authority=Developer ID Application: Globvent inc (3QJG3J7L66)`,
and the plist is present.

---

### Task 6: Registration bridge (SMAppService)

**Files:**
- Create: `desktopApp/nativebridge/OlcboxTunnelDaemon.swift`
- Create: `sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/desktop/MacOsTunnelDaemon.kt`
- Test: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/MacOsTunnelDaemonTest.kt`
- Modify: `desktopApp/build.gradle.kts` (a task modelled on the existing
  `libolcboxne.dylib` one at line ~397, emitting `native/libolcboxtunneld.dylib`)

**Interfaces:**
- Consumes: Task 4's plist name.
- Produces:
  ```kotlin
  internal object MacOsTunnelDaemon {
      enum class Registration(val code: Int) {
          NotRegistered(0), RequiresApproval(1), Enabled(2), NotFound(3), Unsupported(-1);
          companion object { fun from(code: Int): Registration }
      }
      val available: Boolean
      fun register(): Registration
      fun unregister(): Registration
      fun status(): Registration
      fun openLoginItemsSettings()
      fun settingsSummary(): String?
      fun message(): String
  }
  ```

- [ ] **Step 1: Write the failing test**

```kotlin
package org.olcbox.app.vpn.desktop

import org.olcbox.app.vpn.desktop.MacOsTunnelDaemon.Registration
import kotlin.test.Test
import kotlin.test.assertEquals

class MacOsTunnelDaemonTest {

    /**
     * The Swift side returns integers and this side names them. Nothing checks
     * that the two agree at compile time, so the numbers are pinned here: change
     * one in `OlcboxTunnelDaemon.swift` without changing the other and the app
     * reads "enabled" off a status that means something else.
     */
    @Test
    fun registrationCodesMatchTheNativeContract() {
        assertEquals(Registration.NotRegistered, Registration.from(0))
        assertEquals(Registration.RequiresApproval, Registration.from(1))
        assertEquals(Registration.Enabled, Registration.from(2))
        assertEquals(Registration.NotFound, Registration.from(3))
        assertEquals(Registration.Unsupported, Registration.from(-1))
    }

    @Test
    fun anUnknownCodeIsNeverEnabled() {
        assertEquals(Registration.NotRegistered, Registration.from(99))
    }
}
```

- [ ] **Step 2: Push and confirm it fails**

```bash
git add -A && git commit -m "test(macos): the daemon registration contract"
git push proofkit feat/macos-tun-daemon
```

- [ ] **Step 3: Write the Swift bridge**

`desktopApp/nativebridge/OlcboxTunnelDaemon.swift`:

```swift
// Registering the root daemon, from inside the JVM.
//
// SMAppService is the only supported way to install a root daemon since macOS 13
// and the reason this design has no installer script: the plist ships inside the
// bundle, the user approves it once in Login Items, and deleting the app removes
// it. No /Library litter to clean up and nothing to leave behind.
//
// Polled rather than called back into, exactly as the old system-extension bridge
// was: JNA callbacks arrive on whatever thread the JVM lends, and marshalling one
// into an Apple main-queue callback is a class of crash worth more than an
// integer read once a second.
import Foundation
import ServiceManagement
import AppKit

private let plistName = "org.olcbox.app.desktopApp.tunneld.plist"
private var lastMessage = ""

private func statusCode() -> Int32 {
    guard #available(macOS 13.0, *) else { return -1 }
    switch SMAppService.daemon(plistName: plistName).status {
    case .notRegistered: return 0
    case .requiresApproval: return 1
    case .enabled: return 2
    case .notFound: return 3
    @unknown default: return 0
    }
}

@_cdecl("olcbox_tunneld_status")
public func olcbox_tunneld_status() -> Int32 { statusCode() }

@_cdecl("olcbox_tunneld_register")
public func olcbox_tunneld_register() -> Int32 {
    guard #available(macOS 13.0, *) else { lastMessage = "macOS 13 or newer is required"; return -1 }
    do {
        try SMAppService.daemon(plistName: plistName).register()
        lastMessage = ""
    } catch {
        // Verbatim. "Operation not permitted" and "already registered" both mean
        // nothing happened, and only Apple's own text says which.
        lastMessage = error.localizedDescription
    }
    return statusCode()
}

@_cdecl("olcbox_tunneld_unregister")
public func olcbox_tunneld_unregister() -> Int32 {
    guard #available(macOS 13.0, *) else { return -1 }
    do { try SMAppService.daemon(plistName: plistName).unregister(); lastMessage = "" }
    catch { lastMessage = error.localizedDescription }
    return statusCode()
}

@_cdecl("olcbox_tunneld_open_settings")
public func olcbox_tunneld_open_settings() {
    guard #available(macOS 13.0, *) else { return }
    SMAppService.openSystemSettingsLoginItems()
}

@_cdecl("olcbox_tunneld_message")
public func olcbox_tunneld_message(_ buffer: UnsafeMutablePointer<CChar>, _ capacity: Int32) -> Int32 {
    let bytes = Array(lastMessage.utf8.prefix(Int(capacity) - 1))
    bytes.withUnsafeBufferPointer { source in
        buffer.withMemoryRebound(to: UInt8.self, capacity: bytes.count) { destination in
            destination.update(from: source.baseAddress!, count: bytes.count)
        }
    }
    return Int32(bytes.count)
}
```

- [ ] **Step 4: Write the Kotlin side**

`sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/desktop/MacOsTunnelDaemon.kt`:

```kotlin
package org.olcbox.app.vpn.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Installs the root tunnel daemon, and reports where that got to.
 *
 * Absent is a normal state, not an error: every non-macOS build has no such
 * library, and this object is reachable from shared code.
 */
internal object MacOsTunnelDaemon {

    enum class Registration(val code: Int) {
        NotRegistered(0),
        RequiresApproval(1),
        Enabled(2),

        /** The plist is not in the bundle — a build made without the daemon. */
        NotFound(3),

        /** Not macOS, macOS older than 13, or a build without the bridge. */
        Unsupported(-1);

        companion object {
            /**
             * An unrecognised code becomes [NotRegistered], never [Enabled]: a
             * status this side does not understand is not evidence that a root
             * daemon is installed, and the only safe direction to round is down.
             */
            fun from(code: Int): Registration = entries.firstOrNull { it.code == code } ?: NotRegistered
        }
    }

    private interface Bridge : Library {
        fun olcbox_tunneld_status(): Int
        fun olcbox_tunneld_register(): Int
        fun olcbox_tunneld_unregister(): Int
        fun olcbox_tunneld_open_settings()
        fun olcbox_tunneld_message(buffer: ByteArray, capacity: Int): Int
    }

    private const val LIBRARY_RESOURCE = "native/libolcboxtunneld.dylib"
    private const val MESSAGE_CAPACITY = 1024

    private val bridge: Bridge? by lazy {
        runCatching {
            val stream = MacOsTunnelDaemon::class.java.classLoader
                ?.getResourceAsStream(LIBRARY_RESOURCE) ?: return@runCatching null
            val temp = Files.createTempFile("olcboxtunneld-", ".dylib")
            temp.toFile().deleteOnExit()
            stream.use { Files.copy(it, temp, StandardCopyOption.REPLACE_EXISTING) }
            Native.load(temp.toAbsolutePath().toString(), Bridge::class.java)
        }.getOrNull()
    }

    val available: Boolean get() = bridge != null

    fun register(): Registration =
        bridge?.let { Registration.from(it.olcbox_tunneld_register()) } ?: Registration.Unsupported

    fun unregister(): Registration =
        bridge?.let { Registration.from(it.olcbox_tunneld_unregister()) } ?: Registration.Unsupported

    fun status(): Registration =
        bridge?.let { Registration.from(it.olcbox_tunneld_status()) } ?: Registration.Unsupported

    fun openLoginItemsSettings() { bridge?.olcbox_tunneld_open_settings() }

    /**
     * The wording says what the person reading it has to do next, because for
     * most of these states that is the only useful thing to say.
     */
    fun settingsSummary(): String? {
        if (DesktopPaths.os != DesktopOs.MacOS) return null
        return when (status()) {
            Registration.Unsupported -> null
            Registration.NotRegistered -> "Not installed — tap to install"
            Registration.RequiresApproval -> "Approve in System Settings › General › Login Items"
            Registration.Enabled -> "Installed"
            Registration.NotFound -> "Missing from this build — reinstall ProofKit"
        }
    }

    fun message(): String {
        val lib = bridge ?: return "tunnel daemon bridge not present in this build"
        val buffer = ByteArray(MESSAGE_CAPACITY)
        val written = lib.olcbox_tunneld_message(buffer, MESSAGE_CAPACITY)
        return if (written <= 0) "" else String(buffer, 0, written, Charsets.UTF_8)
    }
}
```

- [ ] **Step 5: Add the dylib build task**

In `desktopApp/build.gradle.kts`, copy the block that emits `native/libolcboxne.dylib`
(around line 397 on the `feat/macos-system-extension` branch — on this branch write it
fresh) as a sibling that compiles `nativebridge/OlcboxTunnelDaemon.swift` with
`-framework ServiceManagement -framework AppKit -framework Foundation` to
`native/libolcboxtunneld.dylib`, signs it when `MACOS_SIGN_IDENTITY` is set, and adds
`"native/libolcboxtunneld.dylib"` to the generated-resources list (the list at line ~480
that already carries the olcRTC libraries).

- [ ] **Step 6: Push and confirm green**

```bash
git add -A && git commit -m "feat(macos): register the root daemon through SMAppService"
git push proofkit feat/macos-tun-daemon
```

---

### Task 7: The macOS TUN controller

**Files:**
- Create: `sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/desktop/MacOsTunController.kt`
- Test: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/MacOsTunControllerTest.kt`

**Interfaces:**
- Consumes: `SingBoxConfig.buildDesktopTun` (Task 1), `TunnelDaemonClient`/`DaemonReply`
  (Tasks 2–3).
- Produces:
  ```kotlin
  internal class MacOsTunController(
      private val addLog: (String) -> Unit,
      private val client: TunnelDaemonClient = TunnelDaemonClient(),
      private val resolve: (String) -> List<String> = ::resolveAllAddresses,
  ) {
      suspend fun start(
          corePort: Int, verifyPort: Int, username: String, password: String,
          serverHost: String?, upstreamUdpIsLossy: Boolean
      )
      suspend fun stop()
      suspend fun isRunning(): Boolean
      companion object { fun excludeCidrs(addresses: List<String>): List<String> }
  }
  internal fun resolveAllAddresses(host: String): List<String>
  ```

- [ ] **Step 1: Write the failing test**

```kotlin
package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MacOsTunControllerTest {

    @Test
    fun everyResolvedServerAddressBecomesAnExclusion() {
        // A server that resolves to four addresses and is excluded on one is a
        // tunnel that works until the core redials and picks another.
        assertEquals(
            listOf("203.0.113.7/32", "198.51.100.9/32", "2001:db8::1/128"),
            MacOsTunController.excludeCidrs(listOf("203.0.113.7", "198.51.100.9", "2001:db8::1"))
        )
    }

    @Test
    fun aHostThatDoesNotResolveIsNotSilentlyLeftUnexcluded() {
        val logs = mutableListOf<String>()
        val controller = MacOsTunController(
            addLog = { logs += it },
            client = TunnelDaemonClient(java.nio.file.Path.of("/nonexistent")),
            resolve = { emptyList() }
        )
        assertFailsWith<IllegalStateException> {
            runBlocking {
                controller.start(10810, 10811, "", "", "de1.example.org", false)
            }
        }
        assertTrue(logs.any { "de1.example.org" in it })
    }
}
```

- [ ] **Step 2: Push and confirm it fails**

```bash
git add -A && git commit -m "test(macos): the tun controller's exclusion rules"
git push proofkit feat/macos-tun-daemon
```

- [ ] **Step 3: Implement**

```kotlin
package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.net.SingBoxConfig
import java.net.InetAddress

/**
 * macOS TUN: builds the daemon's config and asks the daemon to run it.
 *
 * The routes are not this class's business. `auto_route` installs them and
 * removes them with the process, which is why there is nothing here resembling
 * the up/down scripts the Linux controller needs — on macOS the core does that
 * job and does it on the way out too.
 */
internal class MacOsTunController(
    private val addLog: (String) -> Unit,
    private val client: TunnelDaemonClient = TunnelDaemonClient(),
    private val resolve: (String) -> List<String> = ::resolveAllAddresses,
) {
    suspend fun start(
        corePort: Int,
        verifyPort: Int,
        username: String,
        password: String,
        serverHost: String?,
        upstreamUdpIsLossy: Boolean,
    ) {
        val addresses = serverHost?.let { resolve(it) }.orEmpty()
        if (serverHost != null && addresses.isEmpty()) {
            // Starting anyway would put the core's own packets into the tunnel
            // the core is building. That does not degrade — it deadlocks, and it
            // looks like a broken server rather than a missing route.
            addLog("cannot resolve $serverHost, so its traffic cannot be kept out of the tunnel")
            error("cannot resolve $serverHost")
        }

        val config = SingBoxConfig.buildDesktopTun(
            corePort = corePort,
            verifyPort = verifyPort,
            username = username,
            password = password,
            excludeAddresses = excludeCidrs(addresses),
            directDnsDomains = listOfNotNull(serverHost?.takeIf { !it.isIpLiteral() }),
            upstreamUdpIsLossy = upstreamUdpIsLossy,
        )

        when (val reply = client.start(config)) {
            is DaemonReply.Ok -> addLog("macOS TUN running (sing-box pid ${reply.pid})")
            is DaemonReply.Failure -> {
                addLog("macOS TUN failed: ${reply.message}")
                if (reply.logTail.isNotBlank()) addLog(reply.logTail)
                error(reply.message)
            }
        }
    }

    suspend fun stop() {
        when (val reply = client.stop()) {
            is DaemonReply.Ok -> addLog("macOS TUN stopped")
            is DaemonReply.Failure -> addLog("macOS TUN stop failed: ${reply.message}")
        }
    }

    suspend fun isRunning(): Boolean =
        (client.status() as? DaemonReply.Ok)?.state == DaemonReply.STATE_RUNNING

    companion object {
        fun excludeCidrs(addresses: List<String>): List<String> =
            addresses.map { if (':' in it) "$it/128" else "$it/32" }
    }
}

private fun String.isIpLiteral(): Boolean =
    ':' in this || split('.').let { it.size == 4 && it.all { part -> part.toIntOrNull() != null } }

internal fun resolveAllAddresses(host: String): List<String> =
    runCatching { InetAddress.getAllByName(host).map { it.hostAddress.substringBefore('%') } }
        .getOrDefault(emptyList())
```

- [ ] **Step 4: Push and confirm green**

```bash
git add -A && git commit -m "feat(macos): the tun controller"
git push proofkit feat/macos-tun-daemon
```

---

### Task 8: Wire `DesktopMode.MacTun` into the manager

**Files:**
- Modify: `sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/DesktopVpnManager.kt`
  (the `DesktopMode` enum at :423, `startDesktopMode` at :240, `stopDesktopMode` at :558,
  the transport labels at :349)
- Test: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/DesktopModeSelectionTest.kt` (create)

**Interfaces:**
- Consumes: `MacOsTunController` (Task 7), `MacOsTunnelDaemon` (Task 6).
- Produces: `DesktopMode.MacTun`, and
  `internal fun macOsModeFor(daemon: MacOsTunnelDaemon.Registration): DesktopMode`
  as an internal top-level function in `DesktopVpnManager.kt` so it is testable without
  a manager instance.

- [ ] **Step 1: Write the failing test**

```kotlin
package org.olcbox.app.vpn.desktop

import org.olcbox.app.vpn.DesktopMode
import org.olcbox.app.vpn.macOsModeFor
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopModeSelectionTest {

    @Test
    fun onlyAnApprovedDaemonEarnsTunMode() {
        assertEquals(DesktopMode.MacTun, macOsModeFor(MacOsTunnelDaemon.Registration.Enabled))
    }

    @Test
    fun everyOtherDaemonStateKeepsTodaysProxyBehaviour() {
        // A user who never installs the daemon must see no change at all — not a
        // failure, not a prompt on connect.
        for (state in listOf(
            MacOsTunnelDaemon.Registration.NotRegistered,
            MacOsTunnelDaemon.Registration.RequiresApproval,
            MacOsTunnelDaemon.Registration.NotFound,
            MacOsTunnelDaemon.Registration.Unsupported,
        )) {
            assertEquals(DesktopMode.SystemProxy, macOsModeFor(state), "state $state")
        }
    }
}
```

- [ ] **Step 2: Push and confirm it fails**

```bash
git add -A && git commit -m "test(macos): mode selection follows the daemon's state"
git push proofkit feat/macos-tun-daemon
```

- [ ] **Step 3: Implement**

In `DesktopVpnManager.kt`, make the enum internal (it is `private` today) and add the case:

```kotlin
internal enum class DesktopMode {
    LinuxTun,
    WindowsTun,
    MacTun,
    SystemProxy;

    companion object {
        fun current(): DesktopMode {
            return when (DesktopPaths.os) {
                DesktopOs.Linux -> LinuxTun
                DesktopOs.Windows -> WindowsTun
                DesktopOs.MacOS -> macOsModeFor(MacOsTunnelDaemon.status())
                DesktopOs.Other -> SystemProxy
            }
        }
    }
}

/**
 * Only an approved daemon earns TUN mode. Every other state — not installed,
 * waiting for approval, missing from the build, too old a macOS — keeps the
 * SOCKS proxy that has always worked, because a connect is the worst moment to
 * discover that a root component needs a trip to System Settings.
 */
internal fun macOsModeFor(daemon: MacOsTunnelDaemon.Registration): DesktopMode =
    if (daemon == MacOsTunnelDaemon.Registration.Enabled) DesktopMode.MacTun else DesktopMode.SystemProxy
```

Add the controller field next to `linuxTunController`:

```kotlin
    private val macOsTunController = MacOsTunController(addLog = ::addLog)
    private var macTunVerifyPort: Int? = null
```

In `startDesktopMode`'s `when (desktopMode)`, add:

```kotlin
                DesktopMode.MacTun -> startMacTun(
                    corePort = effectiveSocksPort,
                    isOlcrtc = isOlcrtc,
                    socksSettings = socksSettings,
                    location = location
                )
```

and the method:

```kotlin
    private suspend fun startMacTun(
        corePort: Int,
        isOlcrtc: Boolean,
        socksSettings: DesktopSocksProxySettings,
        location: LocationConfig
    ) {
        val verifyPort = allocateVerifyPort(corePort)
        macTunVerifyPort = verifyPort
        macOsTunController.start(
            corePort = corePort,
            verifyPort = verifyPort,
            // Only olcRTC enforces them; the cores' own inbounds have no auth.
            username = if (isOlcrtc) socksSettings.username else "",
            password = if (isOlcrtc) socksSettings.password else "",
            serverHost = serverHostOf(location),
            // olcRTC relays UDP over a lossy video carrier, so DNS takes the
            // reliable path. The native transports carry UDP themselves.
            upstreamUdpIsLossy = isOlcrtc
        )
    }

    /** The daemon's own socks inbound. Never the core's port — see [buildDesktopTun]. */
    private suspend fun allocateVerifyPort(corePort: Int): Int {
        val preferred = corePort + 1
        if (isLocalPortFree(preferred)) return preferred
        return runCatching {
            java.net.ServerSocket().use { socket ->
                socket.bind(java.net.InetSocketAddress("127.0.0.1", 0))
                socket.localPort
            }
        }.getOrNull() ?: preferred
    }

    private fun serverHostOf(location: LocationConfig): String? {
        val raw = location.rawLink ?: return null
        return org.olcbox.app.net.LinkParser.parse(raw)?.host
    }
```

In the transport label `when` at :349 add `DesktopMode.MacTun -> "Desktop macOS TUN"`.

Point the verifier at the daemon's inbound when in TUN mode — replace the
`TunnelVerifier.verify` call's port argument:

```kotlin
            val verifyPort = if (desktopMode == DesktopMode.MacTun) {
                macTunVerifyPort ?: effectiveSocksPort
            } else {
                effectiveSocksPort
            }
            val exit = org.olcbox.app.net.TunnelVerifier.verify(
                socksHost = socksSettings.host,
                socksPort = verifyPort,
                // The daemon's inbound has no auth; only the olcRTC core's has.
                username = if (isOlcrtc && desktopMode != DesktopMode.MacTun) socksSettings.username else "",
                password = if (isOlcrtc && desktopMode != DesktopMode.MacTun) socksSettings.password else ""
            )
```

In `stopDesktopMode`, alongside the other controllers:

```kotlin
        if (DesktopPaths.os == DesktopOs.MacOS) {
            runCatching { macOsTunController.stop() }
                .onFailure { addLog("macOS TUN stop failed: ${it.message}") }
            macTunVerifyPort = null
        }
```

- [ ] **Step 4: Adopt a running tunnel at launch**

A tunnel outlives the process that asked for it. Add to `DesktopVpnManager`'s init block
(or the existing startup coroutine):

```kotlin
    /**
     * The daemon keeps the tun after the app is killed, so the app must ask what
     * is true rather than assume it starts from idle. Assuming idle was the iOS
     * bug that showed "relay idle" over a live tunnel and then tore it down.
     */
    private suspend fun adoptRunningMacTun() {
        if (DesktopPaths.os != DesktopOs.MacOS) return
        if (!macOsTunController.isRunning()) return
        addLog("adopting a tunnel the daemon was already running")
        macOsTunController.stop()
    }
```

Call it once at startup. Stopping rather than adopting into `Connected` is deliberate for
this slice: the app cannot reconstruct which location that tun belongs to, and showing a
connection it cannot describe is worse than a clean restart. Note it in the docs.

- [ ] **Step 5: Push and confirm green**

```bash
git add -A && git commit -m "feat(macos): TUN mode when the daemon is approved, proxy otherwise"
git push proofkit feat/macos-tun-daemon
```

---

### Task 9: Settings row

**Files:**
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/ui/components/ApplicationSettingsSheet.kt`
- Modify: `desktopApp/src/main/kotlin/main.kt`

**Interfaces:**
- Consumes: `MacOsTunnelDaemon.settingsSummary()/register()/openLoginItemsSettings()/message()`.
- Produces: no new API.

- [ ] **Step 1: Add the row parameters**

In `ApplicationSettingsSheet`, beside `connectionModeSummary`:

```kotlin
    /**
     * How the platform's own tunnel component is doing, when it has one — today
     * the macOS root daemon, which the user installs and approves rather than
     * receiving with the app. Null everywhere else, and the row is then absent
     * rather than disabled: a platform with no such component has nothing to say.
     */
    tunnelDaemonSummary: String? = null,
    onTunnelDaemonClick: () -> Unit = {},
```

Thread both through `SharedConnectionSettingsContent` and render before the SOCKS row:

```kotlin
            // Not behind the admin gate. macOS asks *the user* to approve this in
            // System Settings, so hiding the only way to start that behind seven
            // taps would hide the app's own instructions from the one person who
            // can follow them.
            if (tunnelDaemonSummary != null) {
                SharedNavigationRow(
                    title = "System-wide tunnel",
                    value = tunnelDaemonSummary,
                    icon = PkIcons.Public,
                    onClick = onTunnelDaemonClick
                )
            }
```

- [ ] **Step 2: Wire it in `main.kt`**

```kotlin
    // Null on every platform but macOS, and the settings row is then absent.
    var tunnelDaemonSummary by remember { mutableStateOf(MacOsTunnelDaemon.settingsSummary()) }
```

```kotlin
    // The daemon's state changes without us: the user approves it in System
    // Settings, in another application, and launchd finishes the job afterwards.
    // Polling while the sheet is open is what stops the row from still reading
    // "Approve in System Settings" once they have.
    LaunchedEffect(showDesktopSettings) {
        while (showDesktopSettings) {
            tunnelDaemonSummary = MacOsTunnelDaemon.settingsSummary()
            delay(1_000)
        }
    }
```

and on the sheet:

```kotlin
                        tunnelDaemonSummary = tunnelDaemonSummary,
                        onTunnelDaemonClick = {
                            when (MacOsTunnelDaemon.status()) {
                                MacOsTunnelDaemon.Registration.RequiresApproval ->
                                    MacOsTunnelDaemon.openLoginItemsSettings()
                                else -> MacOsTunnelDaemon.register()
                            }
                            tunnelDaemonSummary = MacOsTunnelDaemon.settingsSummary()
                            desktopNotice = MacOsTunnelDaemon.message().ifBlank { null }
                        },
```

- [ ] **Step 3: Push and confirm green**

```bash
git add -A && git commit -m "feat(macos): install the system-wide tunnel from Settings"
git push proofkit feat/macos-tun-daemon
```

---

### Task 10: Operator documentation and the on-Mac checklist

**Files:**
- Create: `docs/macos-tunnel-daemon.md`
- Modify: `docs/macos-system-extension-setup.md` (a closing pointer)

- [ ] **Step 1: Write the doc**

Cover, in this order: what the daemon is and why it exists (link the spec and the two
Apple Forum threads); how to install it (Settings → System-wide tunnel → approve in Login
Items); how to tell the three states apart; where things live
(`/Library/Application Support/org.olcbox.app/`, the socket, the plist inside the bundle);
how to read it —

```bash
sudo launchctl print system/org.olcbox.app.desktopApp.tunneld
log show --last 10m --info --predicate 'process == "ProofKitTunnelDaemon"'
sudo nc -U /var/run/org.olcbox.app.tunneld.sock <<< '{"verb":"status"}'
netstat -rn | head -20      # default via utun, server IP via the physical gateway
```

— and the known limits, stated plainly: a server hostname that re-resolves mid-session
loses its exclusion until reconnect; a tunnel left by a killed app is stopped at next
launch rather than adopted; TUN needs macOS 13+.

- [ ] **Step 2: Close out the parked NE doc**

Append to `docs/macos-system-extension-setup.md`:

```markdown
---

# Parked, 2026-08-05

Not our bug. macOS 26 rejects *new* system-extension activations with exactly
this message, for fully native, correctly signed, notarised apps — LuLu among
them; extensions activated before macOS 26 keep working. Apple DTS calls the
"no policy" line a red herring and has published no fix:
https://developer.apple.com/forums/thread/817101 and
https://developer.apple.com/forums/thread/820254.

macOS ships a TUN through a root daemon instead — see
`docs/macos-tunnel-daemon.md`. This branch stays as it is; it is the shape to
resume from if the App Store ever needs a provider, and the setup above is still
correct for that day.
```

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "docs(macos): how to install and read the tunnel daemon"
git push proofkit feat/macos-tun-daemon
```

---

### Task 11: End-to-end on the Mac

**Files:** none — this is the acceptance run.

- [ ] **Step 1: Build and install**

```bash
gh workflow run release.yml --ref feat/macos-tun-daemon -f platforms=macos -f publish=false
# install the DMG, drag to /Applications
```

- [ ] **Step 2: Install the daemon**

Settings → Connection → **System-wide tunnel** → tap → approve in System Settings ›
General › Login Items. Then:

```bash
sudo launchctl print system/org.olcbox.app.desktopApp.tunneld | head -20
ls -l /var/run/org.olcbox.app.tunneld.sock
```
Expected: the service is loaded and the socket exists.

- [ ] **Step 3: Connect and prove it**

Connect to a real location in the app. Expected: status Connected, the log line
`Desktop macOS TUN connected — exit …`, and:

```bash
ifconfig | grep -A3 utun          # a utun holding 172.19.0.1
netstat -rn | head -20            # default via that utun; the server IP via the LAN gateway
curl -s https://api.ipify.org; echo   # the exit IP, from a plain shell with no proxy set
```
The last one is the whole point: `curl` knows nothing about a SOCKS proxy, so an exit IP
there is the system-wide tunnel that SystemProxy mode could never give.

- [ ] **Step 4: Prove the failure modes**

```bash
# stop from the UI: routes come back, browsing works
netstat -rn | head -5
# kill the app mid-tunnel
pkill -9 -f ProofKit
netstat -rn | head -5   # tun still default: fail closed, no leak
# relaunch the app: the log says it adopted and stopped the orphan
```

- [ ] **Step 5: Record the result**

Update `docs/macos-tunnel-daemon.md` with the verified macOS version and anything the run
contradicted, and commit.

---

## Self-review notes

- **Spec coverage.** Config shape → T1; daemon identity/socket/verbs/peer check/binary
  trust → T4; SMAppService registration + UX → T6, T9; mode selection and fallback → T8;
  exclusion and direct DNS → T1, T7; two-port verification → T1, T7, T8; adopt-on-launch
  → T8 step 4 (narrowed to stop-the-orphan, and the narrowing is documented in T10);
  build/CI without new secrets → T5, T6; tests → T1–T3, T6–T8; manual order → T1 step 6,
  T11.
- **Deliberate divergence from the spec.** The spec said "adopts a running tunnel into the
  UI". T8 stops it instead: the app cannot name the location behind an orphaned tun, and a
  connection it cannot describe is worse than a clean restart. If this proves annoying in
  use, the fix is for the daemon to return the location tag it was started with.
- **Untested by CI.** All Swift (daemon and bridge). Its first compile is T5's dispatch;
  budget one or two extra macOS runs for signature fixes.
