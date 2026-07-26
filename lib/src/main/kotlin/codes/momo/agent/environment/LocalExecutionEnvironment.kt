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
    public override val privilege: Privilege,
    searchPath: String?,
    probe: PrivilegeProbe = hostPrivilegeProbe(workspace),
) : ExecutionEnvironment {

    /**
     * Wraps [workspace], validating it, the host userland baseline and the
     * claimed [privilege] up front. The claim comes from the embedder
     * because posture is a deployment fact: this environment falsifies a
     * claim, it never discovers one.
     *
     * @throws EnvironmentStartupException when [workspace] is not an
     *   existing directory, baseline binaries (see the README's platform
     *   section) are missing from `PATH` — naming everything that is
     *   missing — or the host does not grant [privilege].
     */
    public constructor(
        workspace: Path,
        privilege: Privilege = Privilege.UNPRIVILEGED,
    ) : this(workspace, privilege, System.getenv("PATH"))

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
        probe.verify(privilege)
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
        timeout: Duration,
    ): ExecResult = runProcess(command, workingDirectory = workspace, timeout = timeout)

    /** No-op: the local environment sets nothing up, so there is nothing to tear down. */
    public override fun close() {
        // Nothing owned.
    }
}

private fun isOnSearchPath(binary: String, searchPath: String?): Boolean =
    searchPath.orEmpty()
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .any { directory ->
            val candidate = Path.of(directory, binary)
            Files.isRegularFile(candidate) && Files.isExecutable(candidate)
        }
