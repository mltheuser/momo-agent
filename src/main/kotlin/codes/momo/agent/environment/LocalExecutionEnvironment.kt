package codes.momo.agent.environment

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.time.Duration

/**
 * [ExecutionEnvironment] that runs commands directly on the host, with
 * [workspace] as the working directory and the host environment variables
 * inherited. This is the local-development / real-user mode.
 *
 * **Not an isolation boundary:** commands run with the invoking user's
 * rights and can touch anything that user can. Isolation is a property of
 * a container-backed environment, not a tool-level filter.
 *
 * Timeout and cancellation do a best-effort tree kill — descendants are
 * enumerated via [ProcessHandle.descendants] before the parent is
 * destroyed (the JVM cannot create POSIX process groups). Processes that
 * daemonize away, or fork during the kill, escape it and leak on the
 * host; that is accepted for this local mode, where real cleanup is a
 * container property.
 */
public class LocalExecutionEnvironment internal constructor(
    private val workspace: Path,
    searchPath: String?,
) : ExecutionEnvironment {

    /**
     * Wraps [workspace], validating it and the host userland baseline up
     * front.
     *
     * @throws EnvironmentStartupException when [workspace] is not an
     *   existing directory, or baseline binaries (see the README's
     *   platform section) are missing from `PATH` — naming everything
     *   that is missing.
     */
    public constructor(workspace: Path) : this(workspace, System.getenv("PATH"))

    init {
        if (!workspace.isDirectory()) {
            throw EnvironmentStartupException(
                "Workspace folder not found (or not a directory): $workspace",
            )
        }
        val missing = REQUIRED_BINARIES.filterNot { isOnSearchPath(it, searchPath) }
        if (missing.isNotEmpty()) {
            throw EnvironmentStartupException(
                "Host userland baseline is incomplete — required binaries not found on PATH: " +
                    "${missing.joinToString(", ")}. Install them (or fix PATH) and retry.",
            )
        }
    }

    /**
     * Runs [command] on the host per the [ExecutionEnvironment.exec]
     * contract. A program that cannot be started at all (no such
     * executable) propagates its [IOException] — a caller error, not a
     * command outcome.
     */
    public override suspend fun exec(
        command: List<String>,
        stdin: ByteArray?,
        timeout: Duration,
    ): ExecResult {
        require(command.isNotEmpty()) { "command must not be empty." }
        return withContext(Dispatchers.IO) {
            val process = ProcessBuilder(command).directory(workspace.toFile()).start()
            try {
                // Drain both output pipes concurrently with waiting for exit —
                // a full pipe buffer would otherwise deadlock the process.
                val stdoutJob = async { process.inputStream.drain() }
                val stderrJob = async { process.errorStream.drain() }
                launch { writeStdin(process, stdin) }

                val exitedInTime = withTimeoutOrNull(timeout) { process.onExit().await() } != null
                if (!exitedInTime) {
                    killProcessTree(process)
                }
                // The kill (timeout path) closes the pipes, so the drains finish.
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
                // Cleanup-and-rethrow: no abnormal exit (cancellation included)
                // may leak a live process. The kill also closes the pipes,
                // unblocking the drain/stdin workers so cancellation completes.
                killProcessTree(process)
                throw failure
            }
        }
    }

    /** No-op: the local environment sets nothing up, so there is nothing to tear down. */
    public override fun close() {
        // Nothing owned.
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

    private fun killProcessTree(process: Process) {
        // Enumerate descendants BEFORE killing the parent — a dead parent no
        // longer knows its children.
        val descendants = process.toHandle().descendants().toList()
        process.destroyForcibly()
        descendants.forEach { it.destroyForcibly() }
    }

    private companion object {

        /** Userland baseline: bash, grep, find, sed, and a representative coreutils subset. */
        val REQUIRED_BINARIES: List<String> =
            listOf("bash", "cat", "cp", "find", "grep", "ls", "mkdir", "mv", "rm", "sed")

        private fun isOnSearchPath(binary: String, searchPath: String?): Boolean =
            searchPath.orEmpty()
                .split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .any { directory ->
                    val candidate = Path.of(directory, binary)
                    Files.isRegularFile(candidate) && Files.isExecutable(candidate)
                }
    }
}
