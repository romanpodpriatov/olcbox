package org.olcbox.app.net

import android.content.Context
import java.io.File

/**
 * Spawns and supervises a bundled core binary (sing-box / xray) on Android by
 * exec'ing it from nativeLibraryDir in SOCKS mode — the v2rayNG pattern. The core
 * binary is packaged as `lib<name>.so` in jniLibs and, with useLegacyPackaging,
 * extracted to nativeLibraryDir where it is executable. The existing
 * hev-socks5-tunnel bridge points at the core's SOCKS port; this class only owns
 * the child process.
 *
 * minSdk is 23, so this deliberately uses only API-23-safe process APIs:
 * `Process.destroy()` / `exitValue()` — NOT `toHandle()`, `descendants()`,
 * `destroyForcibly()`, `isAlive()`, or `waitFor(timeout, unit)` (all API 26+).
 * `destroy()` sends SIGTERM, which the Go cores handle by exiting cleanly.
 */
internal class AndroidCoreProcess(
    private val context: Context,
    /** The packaged `lib<name>.so` filename in nativeLibraryDir. */
    private val soName: String,
    private val label: String,
    private val argv: (bin: String, config: String) -> List<String>,
) {
    @Volatile private var process: Process? = null

    private fun binaryPath(): File =
        File(context.applicationInfo.nativeLibraryDir, soName)

    @Synchronized
    fun start(configJson: String) {
        stop()
        val workDir = File(context.cacheDir, "olcbox-$label").apply { mkdirs() }
        val config = File(workDir, "config.json").apply { writeText(configJson) }
        process = ProcessBuilder(argv(binaryPath().absolutePath, config.absolutePath))
            .directory(workDir)
            .redirectErrorStream(true)
            .redirectOutput(File(workDir, "$label.log"))
            .start()
    }

    @Synchronized
    fun stop() {
        val p = process ?: return
        process = null
        p.destroy() // SIGTERM — the Go cores exit cleanly on it
    }

    /** API-23-safe liveness: exitValue() throws while the process is still running. */
    fun isRunning(): Boolean {
        val p = process ?: return false
        return try {
            p.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    private companion object
}
