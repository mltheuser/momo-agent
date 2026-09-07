package codes.momo.agent.server.rig

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

internal class LiveServerProcess private constructor(
    val port: Int,
    val dataDir: Path,
    private val process: Process,
    private val transcript: StringBuilder,
) : AutoCloseable {

    private val exitHook = Thread({ terminate() }, "kill-live-server-$port")

    val baseUrl: String get() = "http://127.0.0.1:$port"

    fun output(): String = synchronized(transcript) { transcript.toString() }

    fun crash() {
        releaseExitHook()
        process.destroyForcibly()
        process.waitFor()
    }

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

    private fun respondsOk(): Boolean = try {
        val connection = URI("$baseUrl/v1/sessions?workspace=/").toURL().openConnection() as HttpURLConnection
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

        fun start(dataDir: Path, aiRouterBaseUrl: String = liveBaseUrl): LiveServerProcess {
            requireLiveAiRouter()
            repeat(START_ATTEMPTS - 1) {
                val attempt = runCatching { startOnce(dataDir, aiRouterBaseUrl) }
                attempt.onSuccess { return it }

                attempt.onFailure { failure -> if (failure !is StillbornServer) throw failure }
            }

            return startOnce(dataDir, aiRouterBaseUrl)
        }

        private fun startOnce(dataDir: Path, aiRouterBaseUrl: String): LiveServerProcess {
            val port = freePort()
            val transcript = StringBuilder()
            val builder = ProcessBuilder(
                serverBin,
                "--port=$port",
                "--data-dir=$dataDir",
                "--ai-router-base-url=$aiRouterBaseUrl",
            ).redirectErrorStream(true)

            builder.environment()["JAVA_HOME"] = System.getProperty("java.home")
            val process = builder.start()
            thread(isDaemon = true, name = "live-server-$port") {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(transcript) { transcript.appendLine(line) }
                }
            }
            val server = LiveServerProcess(port, dataDir, process, transcript)
            Runtime.getRuntime().addShutdownHook(server.exitHook)

            runCatching { server.awaitReady() }.onFailure { server.close() }.getOrThrow()
            return server
        }

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

private class StillbornServer(message: String) : IllegalStateException(message)
