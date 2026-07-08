package codes.momo.agent.environment

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Runs [command] as a host subprocess per the [ExecutionEnvironment.exec]
 * contract: concurrent capped stream capture, [stdin] written then closed
 * (a broken pipe is not an error), and the [killer] invoked on [timeout].
 * Cleanup-and-rethrow: no abnormal exit (cancellation included) may leak a
 * live process — the kill also closes the pipes, unblocking the drain and
 * stdin workers.
 */
internal suspend fun runProcess(
    command: List<String>,
    workingDirectory: Path? = null,
    stdin: ByteArray? = null,
    timeout: Duration,
    killer: ProcessKiller = HOST_PROCESS_TREE_KILLER,
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
            launch { writeStdin(process, stdin) }

            val exitedInTime = withTimeoutOrNull(timeout) { process.onExit().await() } != null
            if (!exitedInTime) {
                withContext(NonCancellable) { killer.kill(process) }
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
            withContext(NonCancellable) { killer.kill(process) }
            throw failure
        }
    }
}

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

private fun writeStdin(process: Process, stdin: ByteArray?) {
    try {
        process.outputStream.use { processStdin ->
            if (stdin != null) {
                processStdin.write(stdin)
            }
        }
    } catch (_: IOException) {
        // Broken pipe: the process exited without reading its stdin — not
        // an error, per the exec contract.
    }
}
