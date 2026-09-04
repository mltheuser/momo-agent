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

/**
 * Runs [command] as a host subprocess per the [ExecutionEnvironment.exec]
 * contract: concurrent capped stream capture, stdin closed immediately,
 * and the process tree killed on [timeout]. Cleanup-and-rethrow: no
 * abnormal exit (cancellation included) may leak a live process.
 */
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
            // Drain both output pipes concurrently with waiting for exit —
            // a full pipe buffer would otherwise deadlock the process.
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

/**
 * Best-effort host tree kill: descendants are enumerated via
 * [ProcessHandle.descendants] before the parent is destroyed — a dead parent
 * no longer knows its children — because the JVM cannot create POSIX process
 * groups. Runs in a non-cancellable context.
 */
private fun killProcessTree(process: Process) {
    val descendants = process.toHandle().descendants().toList()
    process.destroyForcibly()
    descendants.forEach { it.destroyForcibly() }
}

/** [runProcess] for the blocking lifecycle paths, which have no coroutine to suspend in. */
internal fun runProcessBlocking(
    command: List<String>,
    workingDirectory: Path? = null,
    timeout: Duration,
): ExecResult = runBlocking { runProcess(command, workingDirectory = workingDirectory, timeout = timeout) }

/** How long a drain sleeps when its pipe is empty and the process still runs. */
private val DRAIN_POLL_INTERVAL = 10.milliseconds

/**
 * Captures at most [ExecutionEnvironment.MAX_CAPTURED_BYTES] bytes of the
 * stream, reading until [process] has exited and the pipe is empty, then
 * closes it.
 *
 * Deliberately never blocks in `read`: only what [InputStream.available]
 * reports is read, and an empty pipe is polled. Waiting for EOF instead
 * would wait for *every* holder of the pipe's write end — a background job
 * the command left behind (e.g. `cd x && server &`, whose forked subshell
 * inherits the pipes) holds it open for as long as it lives, hanging the
 * call. Once the process has exited and the pipe is empty, its complete
 * output is in hand; whatever a survivor writes later is not the command's
 * output and is cut off (the survivor sees EPIPE).
 *
 * Keeps whatever arrived if the stream is torn down mid-read (process
 * killed on timeout/cancellation).
 */
private suspend fun InputStream.drain(process: Process): CapturedStream {
    val capture = Capture()
    try {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        // Liveness before availability: the process's writes all precede
        // its exit, so an exit seen first means the next `available` sees
        // them all — the other order could break with data unread.
        while (process.isAlive || available() > 0) {
            if (!readAvailable(buffer, capture)) {
                delay(DRAIN_POLL_INTERVAL)
            }
        }
    } catch (_: IOException) {
        // Process was killed while we were reading; keep the partial output.
    } finally {
        closeQuietly()
    }
    return capture.toCapturedStream()
}

/** Reads one chunk of whatever is available without blocking into [capture]; false if there was nothing. */
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

/** Output captured so far, capped at [ExecutionEnvironment.MAX_CAPTURED_BYTES]; the rest is counted as truncation. */
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

/** Closes the process's stdin so it reads EOF, per the exec contract. */
private fun closeStdin(process: Process) {
    process.outputStream.closeQuietly()
}

private fun Closeable.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
        // Already gone — nothing to close.
    }
}
