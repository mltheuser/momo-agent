package codes.momo.agent.environment

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal suspend fun runProcess(
    command: List<String>,
    workingDirectory: Path? = null,
    timeout: Duration,
): ExecResult {
    require(command.isNotEmpty()) { "command must not be empty." }
    return withContext(Dispatchers.IO) {
        val builder = ProcessBuilder(command)
        workingDirectory?.let { builder.directory(it.toFile()) }
        val process = builder.start()
        try {
            val stdoutJob = async { process.inputStream.drain(process) }
            val stderrJob = async { process.errorStream.drain(process) }
            closeStdin(process)

            val exitedInTime = withTimeoutOrNull(timeout) { process.onExit().await() } != null
            if (!exitedInTime) {
                withContext(NonCancellable) { killProcessTree(process) }
            }
            val stdout = stdoutJob.await()
            val stderr = stderrJob.await()
            if (exitedInTime) {
                ExecResult.Completed(
                    exitCode = process.exitValue(),
                    stdout = stdout.text,
                    stderr = stderr.text,
                    stdoutTruncated = stdout.truncated,
                    stderrTruncated = stderr.truncated,
                )
            } else {
                ExecResult.TimedOut(
                    stdout = stdout.text,
                    stderr = stderr.text,
                    stdoutTruncated = stdout.truncated,
                    stderrTruncated = stderr.truncated,
                )
            }
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            withContext(NonCancellable) { killProcessTree(process) }
            throw failure
        }
    }
}

private fun killProcessTree(process: Process) {
    val descendants = process.toHandle().descendants().toList()
    process.destroyForcibly()
    descendants.forEach { it.destroyForcibly() }
}

internal fun runProcessBlocking(
    command: List<String>,
    workingDirectory: Path? = null,
    timeout: Duration,
): ExecResult = runBlocking { runProcess(command, workingDirectory = workingDirectory, timeout = timeout) }

private val DRAIN_POLL_INTERVAL = 10.milliseconds

private suspend fun InputStream.drain(process: Process): CapturedStream {
    val capture = Capture()
    try {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

        while (process.isAlive || available() > 0) {
            if (!readAvailable(buffer, capture)) {
                delay(DRAIN_POLL_INTERVAL)
            }
        }
    } catch (_: IOException) {
    } finally {
        closeQuietly()
    }
    return capture.toCapturedStream()
}

private fun InputStream.readAvailable(buffer: ByteArray, capture: Capture): Boolean {
    val available = available()
    if (available <= 0) {
        return false
    }
    val read = read(buffer, 0, minOf(available, buffer.size))
    if (read > 0) {
        capture.write(buffer, read)
    }
    return read > 0
}

private class Capture {
    private val bytes = ByteArrayOutputStream()
    private var truncated = false

    fun write(buffer: ByteArray, length: Int) {
        val kept = minOf(length, ExecutionEnvironment.MAX_CAPTURED_BYTES - bytes.size())
        if (kept > 0) {
            bytes.write(buffer, 0, kept)
        }
        if (kept < length) {
            truncated = true
        }
    }

    fun toCapturedStream(): CapturedStream = CapturedStream(bytes.toByteArray().toString(Charsets.UTF_8), truncated)
}

private class CapturedStream(val text: String, val truncated: Boolean)

private fun closeStdin(process: Process) {
    process.outputStream.closeQuietly()
}

private fun Closeable.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
    }
}
