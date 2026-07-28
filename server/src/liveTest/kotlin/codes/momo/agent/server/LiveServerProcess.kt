package codes.momo.agent.server

import codes.momo.agent.liveBaseUrl
import codes.momo.agent.requireLiveAiRouter
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The agent server as an operating-system process, started from the
 * distribution's own start script so `Main.kt` runs exactly as it does for
 * a user. Everything the process writes is drained and kept, so a failure
 * can report it.
 */
internal class LiveServerProcess private constructor(
    val port: Int,
    val dataDir: Path,
    private val process: Process,
    private val transcript: StringBuilder,
) : AutoCloseable {

    /**
     * Kills the process should this JVM exit while it is still running — a
     * cancelled build otherwise orphans a server holding a port and, for a
     * `@TempDir` data directory, racing its deletion.
     */
    private val exitHook = Thread({ terminate() }, "kill-live-server-$port")

    val baseUrl: String get() = "http://127.0.0.1:$port"

    /** Everything the process has written to stdout and stderr so far. */
    fun output(): String = synchronized(transcript) { transcript.toString() }

    /** Kills the process outright, skipping its own shutdown hook — a crash, not a stop. */
    fun crash() {
        releaseExitHook()
        process.destroyForcibly()
        process.waitFor()
    }

    /** SIGTERM, so the server's own shutdown hook closes the sessions; escalates only if it does not die. */
    override fun close() {
        releaseExitHook()
        terminate()
    }

    private fun terminate() {
        process.destroy()
        if (!process.waitFor(SHUTDOWN_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor()
        }
    }

    /**
     * A process ended on purpose leaves no hook behind. Removing one is
     * refused once a shutdown is under way, which is precisely when the hook
     * is already doing this itself.
     */
    private fun releaseExitHook() {
        runCatching { Runtime.getRuntime().removeShutdownHook(exitHook) }
    }

    private fun awaitReady() {
        val started = TimeSource.Monotonic.markNow()
        while (started.elapsedNow() < READY_TIMEOUT) {
            if (!process.isAlive) {
                throw StillbornServer(diagnostics("the server process died before it accepted requests"))
            }
            if (respondsOk()) {
                return
            }
            Thread.sleep(POLL_INTERVAL.inWholeMilliseconds)
        }
        error(diagnostics("the server did not accept requests within $READY_TIMEOUT"))
    }

    /**
     * The server ships no logging backend, so readiness is what the socket
     * answers, not what it prints. Probed over `HttpURLConnection` rather
     * than the suite's Ktor client because starting a process is not a
     * suspending business, and a blocking probe beats a `runBlocking` per
     * poll for one status code.
     */
    private fun respondsOk(): Boolean = try {
        val connection = URI("$baseUrl/v1/sessions").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = PROBE_TIMEOUT_MILLIS
        connection.readTimeout = PROBE_TIMEOUT_MILLIS
        try {
            connection.responseCode == HttpURLConnection.HTTP_OK
        } finally {
            connection.disconnect()
        }
    } catch (_: IOException) {
        false
    }

    private fun diagnostics(problem: String): String =
        "$problem (port $port, data dir $dataDir, ai-router $liveBaseUrl).\n" +
            "Process output:\n${output().ifBlank { "(nothing)" }}"

    companion object {

        /**
         * Starts a server on a free port over [dataDir], returning once it
         * answers requests. A port lost between being chosen and being bound
         * costs an attempt rather than the caller.
         */
        fun start(dataDir: Path): LiveServerProcess {
            // The server is useless without the router, and its own failure to
            // reach one is far less legible than saying so here.
            requireLiveAiRouter()
            repeat(START_ATTEMPTS - 1) {
                val attempt = runCatching { startOnce(dataDir) }
                attempt.onSuccess { return it }
                // Only a stillborn process is worth another port; nothing else
                // has a reason to go better on the next one.
                attempt.onFailure { failure -> if (failure !is StillbornServer) throw failure }
            }
            // The last attempt reports instead of retrying.
            return startOnce(dataDir)
        }

        private fun startOnce(dataDir: Path): LiveServerProcess {
            val port = freePort()
            val transcript = StringBuilder()
            val builder = ProcessBuilder(
                serverBin,
                "--port=$port",
                "--data-dir=$dataDir",
                "--ai-router-base-url=$liveBaseUrl",
            ).redirectErrorStream(true)
            // The start script resolves the JVM itself; hand it this one so the
            // suite does not depend on the launching shell's environment.
            builder.environment()["JAVA_HOME"] = System.getProperty("java.home")
            val process = builder.start()
            thread(isDaemon = true, name = "live-server-$port") {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(transcript) { transcript.appendLine(line) }
                }
            }
            val server = LiveServerProcess(port, dataDir, process, transcript)
            Runtime.getRuntime().addShutdownHook(server.exitHook)
            // A process that never became ready must not outlive the attempt.
            runCatching { server.awaitReady() }.onFailure { server.close() }.getOrThrow()
            return server
        }

        /**
         * `ServerConfig` rejects port 0, so the port is chosen here and the
         * socket closed again. It can be taken back before the child JVM binds
         * it a second later — this JVM's own loopback clients draw from the
         * same ephemeral range — which is what [start] retries for.
         */
        private fun freePort(): Int = ServerSocket(0).use { it.localPort }

        private val serverBin: String = checkNotNull(System.getProperty("momo.serverBin")) {
            "System property 'momo.serverBin' is not set — run via ./gradlew :server:liveTest."
        }

        private val READY_TIMEOUT: Duration = 30.seconds

        private val SHUTDOWN_TIMEOUT: Duration = 20.seconds

        private val POLL_INTERVAL: Duration = 50.milliseconds

        private const val PROBE_TIMEOUT_MILLIS: Int = 1000

        private const val START_ATTEMPTS: Int = 3
    }
}

/** A process that died during startup — a port taken since it was chosen being the one cause worth a retry. */
private class StillbornServer(message: String) : IllegalStateException(message)
