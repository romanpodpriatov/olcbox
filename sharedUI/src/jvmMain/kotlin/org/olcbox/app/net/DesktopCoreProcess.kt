package org.olcbox.app.net

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Spawns and supervises a bundled core binary (sing-box / xray) running in SOCKS
 * mode with a generated config file. Mirrors the process handling in
 * WindowsTunController (redirectErrorStream + destroy/destroyForcibly teardown).
 * The tun→SOCKS bridge / PAC points at the core's SOCKS port; this class only owns
 * the child process.
 */
internal class DesktopCoreProcess(
    private val binaryPath: () -> Path,
    private val label: String,
    /** Builds the argv given the binary path and the written config file path. */
    private val argv: (bin: String, config: Path) -> List<String>,
) {
    @Volatile private var process: Process? = null
    private val workDir: Path = Files.createTempDirectory("olcbox-$label")

    @Synchronized
    fun start(configJson: String) {
        stop()
        val config = workDir.resolve("config.json")
        Files.writeString(config, configJson)
        process = ProcessBuilder(argv(binaryPath().toString(), config))
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(workDir.resolve("$label.log").toFile())
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
