package codes.momo.agent.environment

import java.io.File
import java.io.IOException
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
 * Timeout and cancellation do a best-effort tree kill. Processes that
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
        val missing = USERLAND_BASELINE.filterNot { isOnSearchPath(it, searchPath) }
        if (missing.isNotEmpty()) {
            throw EnvironmentStartupException(
                "Host userland baseline is incomplete — required binaries not found on PATH: " +
                    "${missing.joinToString(", ")}. Install them (or fix PATH) and retry.",
            )
        }
    }

    public override val workspacePath: String = workspace.toAbsolutePath().normalize().toString()

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
    ): ExecResult = runProcess(command, workingDirectory = workspace, stdin = stdin, timeout = timeout)

    /** No-op: the local environment sets nothing up, so there is nothing to tear down. */
    public override fun close() {
        // Nothing owned.
    }

    private companion object {

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
