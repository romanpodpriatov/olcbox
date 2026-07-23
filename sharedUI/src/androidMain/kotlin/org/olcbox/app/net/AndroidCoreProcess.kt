package org.olcbox.app.net

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Spawns and supervises a bundled core binary (sing-box / xray) on Android by
 * exec'ing it from nativeLibraryDir in SOCKS mode — the v2rayNG pattern. The core
 * binary is packaged as `lib<name>.so` in jniLibs and, with useLegacyPackaging,
 * extracted to nativeLibraryDir where it is executable. The existing
 * hev-socks5-tunnel bridge points at the core's SOCKS port; this class only owns
 * the child process.
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
        p.toHandle().descendants().forEach { it.destroy() }
        p.destroy()
        if (!p.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            p.toHandle().descendants().forEach { it.destroyForcibly() }
            p.destroyForcibly()
            p.waitFor(KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    fun isRunning(): Boolean = process?.isAlive == true

    private companion object {
        const val STOP_TIMEOUT_MS = 3_000L
        const val KILL_TIMEOUT_MS = 2_000L
    }
}
