package org.olcbox.app.vpn.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Asks macOS to install the packet-tunnel system extension, and reports where
 * that request got to.
 *
 * Outside the App Store a NetworkExtension provider must be a system extension,
 * and only the signed host app running from `/Applications` may request its
 * installation — so this cannot be a helper process, and the Compose UI is a JVM
 * process. The native half (`libolcboxne.dylib`, built and embedded by
 * desktopApp's `embedMacosSystemExtension`) is the smallest bridge that answers
 * "is it installed": submit a request, then read a status.
 *
 * Polled rather than called back into. Apple delivers the delegate callbacks on
 * the main queue; JNA callbacks arrive on whatever thread the JVM lends, and
 * marshalling one into the other is a class of crash worth more than an integer
 * read once a second.
 */
object MacOsSystemExtension {

    /**
     * Must be prefixed by the host app's identifier or macOS refuses the request,
     * naming neither the rule nor the value. Kept in step with the extension's
     * own Info.plist by [BUNDLE_ID_PREFIX] below.
     */
    const val BUNDLE_ID = "org.olcbox.app.desktopApp.PacketTunnel"
    const val BUNDLE_ID_PREFIX = "org.olcbox.app.desktopApp"

    /** Mirrors the Swift `BridgeStatus`; the numbers are the contract. */
    enum class Status(val code: Int) {
        Idle(0),
        Requested(1),
        NeedsUserApproval(2),
        Activated(3),
        Failed(4),

        /** The app is not in `/Applications`, so macOS will not even consider it. */
        NotInApplications(5),

        /** No bridge in this build — a non-macOS host, or a build without it. */
        Unavailable(-1);

        companion object {
            /**
             * Unrecognised codes become [Failed], never [Activated]: a status this
             * side does not understand is not evidence that a tunnel is running,
             * and the only safe direction to round is down.
             */
            fun from(code: Int): Status = entries.firstOrNull { it.code == code } ?: Failed
        }
    }

    private interface Bridge : Library {
        fun olcbox_ne_activate(identifier: String): Int
        fun olcbox_ne_deactivate(identifier: String): Int
        fun olcbox_ne_status(): Int
        fun olcbox_ne_message(buffer: ByteArray, capacity: Int): Int
    }

    private const val MESSAGE_CAPACITY = 1024

    private const val LIBRARY_RESOURCE = "native/libolcboxne.dylib"

    /**
     * Extracted from resources and loaded by absolute path, the way the olcRTC
     * library already is.
     *
     * Not `Native.load("olcboxne")`: that searches `jna.library.path`, which this
     * app points at `user.dir/native` — a directory that exists on a developer's
     * machine and not inside a packaged `.app`. A library found by name in the
     * one place and not the other fails by making the settings row quietly not
     * appear, with nothing anywhere saying why.
     *
     * Absent is a normal state, not an error: every non-macOS desktop build has
     * no such library, and this object is reachable from shared code.
     */
    private val bridge: Bridge? by lazy {
        runCatching {
            val stream = MacOsSystemExtension::class.java.classLoader
                ?.getResourceAsStream(LIBRARY_RESOURCE)
                ?: return@runCatching null
            val temp = Files.createTempFile("olcboxne-", ".dylib")
            temp.toFile().deleteOnExit()
            stream.use { Files.copy(it, temp, StandardCopyOption.REPLACE_EXISTING) }
            Native.load(temp.toAbsolutePath().toString(), Bridge::class.java)
        }.getOrNull()
    }

    val available: Boolean get() = bridge != null

    fun activate(): Status =
        bridge?.let { Status.from(it.olcbox_ne_activate(BUNDLE_ID)) } ?: Status.Unavailable

    fun deactivate(): Status =
        bridge?.let { Status.from(it.olcbox_ne_deactivate(BUNDLE_ID)) } ?: Status.Unavailable

    fun status(): Status =
        bridge?.let { Status.from(it.olcbox_ne_status()) } ?: Status.Unavailable

    /**
     * One line for the settings row, or null on a platform with no such
     * component — which is every platform but macOS, and macOS builds made
     * before the extension was embedded.
     *
     * The wording says what the person reading it has to do next, because for
     * most of these states that is the only useful thing to say: macOS asks the
     * user to approve the extension, and an app that reports "failed" without
     * mentioning where the approval lives has told them nothing.
     */
    fun settingsSummary(): String? {
        if (DesktopPaths.os != DesktopOs.MacOS) return null
        return when (status()) {
            Status.Unavailable -> null
            Status.Idle -> "Not installed — tap to install"
            Status.Requested -> "Installing…"
            Status.NeedsUserApproval -> "Approve in System Settings › General › Login Items & Extensions"
            Status.Activated -> "Installed"
            Status.NotInApplications -> "Move ProofKit to /Applications first"
            // The reason, not the fact. "Failed — tap to retry" sent a person to
            // the system log to find out what the app already knew, and the
            // verbatim OSSystemExtensionError is the only thing that separates a
            // bad signature from a missing extension from a declined approval.
            // It is long, so it is cut here and printed in full to the notice.
            Status.Failed -> "Failed — ${message().take(MAX_SUMMARY_REASON)}"
        }
    }

    /** Long enough to carry an OSSystemExtensionError domain and code. */
    private const val MAX_SUMMARY_REASON = 200

    /** The last thing the native side had to say — an Apple error, verbatim. */
    fun message(): String {
        val lib = bridge ?: return "system extension bridge not present in this build"
        val buffer = ByteArray(MESSAGE_CAPACITY)
        val written = lib.olcbox_ne_message(buffer, MESSAGE_CAPACITY)
        return if (written <= 0) "" else String(buffer, 0, written, Charsets.UTF_8)
    }
}
