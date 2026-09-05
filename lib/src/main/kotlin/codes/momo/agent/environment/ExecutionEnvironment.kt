package codes.momo.agent.environment

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.time.Duration

/**
 * Execution primitive around a workspace folder: all workspace-touching
 * tools run their commands through [exec], directly on this host, with the
 * workspace as the working directory and the host environment variables
 * inherited.
 *
 * **Not an isolation boundary:** commands run with the invoking user's
 * rights and can touch anything that user can. Where isolation is needed —
 * a benchmark harness, a cloud runner — the boundary is a container the
 * embedder owns, with this whole process running inside it.
 *
 * Timeout and cancellation do a best-effort tree kill. Processes that
 * daemonize away, or fork during the kill, escape it and leak on the host;
 * that is accepted here, and a container boundary around the process reaps
 * such escapees with itself.
 */
public class ExecutionEnvironment internal constructor(
    workspace: Path,
    searchPath: String?,
    probe: PrivilegeProbe = hostPrivilegeProbe(workspace),
    private val runner: CommandRunner = hostCommandRunner(workspace),
) {

    /**
     * Wraps [workspace], validating it and the host userland baseline up
     * front and discovering the [privilege] its commands run with.
     *
     * @throws EnvironmentStartupException when [workspace] is not an
     *   existing directory, or baseline binaries (see
     *   docs/execution-environment.md) are missing from `PATH` — naming
     *   everything that is missing.
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

    /**
     * [Privilege] the commands run through [exec] have. Discovered, never
     * declared: what a command here can elevate to is fixed by the account
     * this process runs as, so the host is asked rather than told.
     * Initialized after the init block because the probes lean on the
     * userland baseline it establishes.
     */
    public val privilege: Privilege = probe.detect()

    /** Absolute path of the workspace root, in the POSIX notation tools pass paths in. */
    public val workspacePath: String = workspace.toAbsolutePath().normalize().toString()

    /**
     * Runs [command] with the workspace root as the working directory,
     * returning once the process exited or [timeout] elapsed.
     *
     * - [command] is an argv vector — no shell is interposed. Callers
     *   wanting shell features pass `["bash", "-c", script]`.
     * - The process's stdin is closed immediately: it reads EOF.
     * - Each of stdout/stderr is captured up to [MAX_CAPTURED_BYTES]; the
     *   rest is drained but discarded — the process still runs to
     *   completion — and reported via the [ExecResult] truncation flags.
     * - Only the direct child is waited for: the call returns once it has
     *   exited and its output is read, even while background processes it
     *   left behind still hold stdout/stderr. Those may outlive the call;
     *   their later output is lost, and once the pipes are closed a write
     *   to them fails (EPIPE).
     * - On [timeout] — no default; the caller supplies the policy — the
     *   process tree is killed and [ExecResult.TimedOut] returned with
     *   the output captured so far. A timeout is never an exception or
     *   an exit code.
     * - Cancelling the calling coroutine kills the process tree the same
     *   way.
     * - A program that cannot be started at all (no such executable)
     *   propagates its [IOException] — a caller error, not a command
     *   outcome.
     */
    public suspend fun exec(
        command: List<String>,
        timeout: Duration,
    ): ExecResult = runner.run(command, timeout)

    public companion object {

        /**
         * Per-stream cap on captured output. The typical caller is an LLM
         * issuing arbitrary commands; a runaway one must not be able to
         * exhaust the JVM heap before its timeout fires.
         */
        public const val MAX_CAPTURED_BYTES: Int = 8 * 1024 * 1024
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

/**
 * How [ExecutionEnvironment.exec] reaches the host: one command in, its
 * outcome out. A seam like [PrivilegeProbe] — the public constructor runs
 * real host processes, while a test can inject outcomes (a timeout, a
 * capture-cap truncation) the real host cannot produce on demand.
 */
internal fun interface CommandRunner {

    suspend fun run(command: List<String>, timeout: Duration): ExecResult
}

/** The real runner: [command] as a host subprocess working in [workspace]. */
internal fun hostCommandRunner(workspace: Path): CommandRunner = CommandRunner { command, timeout ->
    runProcess(command, workingDirectory = workspace, timeout = timeout)
}
