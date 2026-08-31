package codes.momo.agent.environment

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Runs [command] as a host subprocess per the [ExecutionEnvironment.exec]
 * contract: concurrent capped stream capture, stdin closed immediately,
 * and the process tree killed on [timeout]. Cleanup-and-rethrow: no
 * abnormal exit (cancellation included) may leak a live process — the kill
 * also closes the pipes, unblocking the drain workers.
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
            val stdoutJob = async { process.inputStream.drain() }
            val stderrJob = async { process.errorStream.drain() }
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
 * groups. Runs in a non-cancellable context; it leaves the process's pipes
 * closing, so the stream drains finish.
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

/**
 * Reads the stream to EOF, capturing at most
 * [ExecutionEnvironment.MAX_CAPTURED_BYTES] bytes. Keeps whatever arrived
 * if the stream is torn down mid-read (process killed on
 * timeout/cancellation).
 */
private fun InputStream.drain(): CapturedStream {
    val captured = ByteArrayOutputStream()
    var truncated = false
    try {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = read(buffer)
            if (read < 0) {
                break
            }
            val kept = minOf(read, ExecutionEnvironment.MAX_CAPTURED_BYTES - captured.size())
            if (kept > 0) {
                captured.write(buffer, 0, kept)
            }
            if (kept < read) {
                truncated = true
            }
        }
    } catch (_: IOException) {
        // Process was killed while we were reading; keep the partial output.
    }
    return CapturedStream(captured.toByteArray().toString(Charsets.UTF_8), truncated)
}

private class CapturedStream(val text: String, val truncated: Boolean)

/** Closes the process's stdin so it reads EOF, per the exec contract. */
private fun closeStdin(process: Process) {
    try {
        process.outputStream.close()
    } catch (_: IOException) {
        // The process is already gone — nothing to close.
    }
}
