package codes.momo.agent.environment

import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.isDirectory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * [ExecutionEnvironment] that runs commands as root inside a Docker
 * container created from [image] (always resolved as `linux/amd64`, both
 * when pulling and running; fetched only when not present locally), driven
 * through the `docker` CLI. This is the isolation and
 * runtime-pinning mode: commands see only the container filesystem, and the
 * image decides the toolchain versions.
 *
 * The [hostWorkspace] contents are copied into the container's `/workspace`
 * at construction — nothing from the host is mounted. [close] copies
 * `/workspace` back out to [hostWorkspace], then removes the container;
 * closing again is a no-op. A JVM shutdown hook removes the container on
 * abnormal termination as a best effort; every container carries the
 * `codes.momo.agent` label, so leaked ones are one `docker rm -f` away
 * (see the README's container section).
 *
 * @throws EnvironmentStartupException when [hostWorkspace] is not an
 *   existing directory, docker is unusable, the container cannot be started
 *   (bad image reference included), or the image lacks the userland
 *   baseline — never leaving a container behind.
 */
public class ContainerExecutionEnvironment(
    private val image: String,
    hostWorkspace: Path,
) : ExecutionEnvironment {

    /** Normalized to absolute up front: `docker cp` misparses relative paths containing `:`. */
    private val hostWorkspace: Path = hostWorkspace.toAbsolutePath().normalize()

    private val containerName: String = "momo-agent-${UUID.randomUUID()}"

    private val closed = AtomicBoolean(false)

    private val shutdownHook: Thread = Thread {
        runCatching { destroyContainer() }
    }

    init {
        if (image.isBlank() || image.startsWith("-")) {
            // Never let an option-like string reach docker's argv as flags.
            throw EnvironmentStartupException("Not a valid image reference: '$image'")
        }
        if (!this.hostWorkspace.isDirectory()) {
            throw EnvironmentStartupException(
                "Workspace folder not found (or not a directory): ${this.hostWorkspace}",
            )
        }
        checkDockerUsable()
        startContainer()
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook)
            copyWorkspaceIn()
            probeUserlandBaseline()
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            // No leaks on failed setup: the container exists at this point.
            destroyContainer()
            throw failure
        }
    }

    public override val workspacePath: String = CONTAINER_WORKSPACE

    /**
     * Runs [command] in the container per the [ExecutionEnvironment.exec]
     * contract. Timeout and cancellation SIGKILL the in-container process
     * tree — matched via a per-exec marker environment variable that
     * descendants inherit — falling back to killing the `docker exec`
     * client when the container is unreachable. A process that scrubs its
     * environment sheds the marker and escapes the sweep; it stays
     * contained in the container and dies with it on [close].
     *
     * @throws EnvironmentFailureException when a non-zero exit comes back
     *   from a container that is no longer running: the container died or
     *   the daemon is unreachable.
     */
    public override suspend fun exec(
        command: List<String>,
        stdin: ByteArray?,
        timeout: Duration,
    ): ExecResult {
        require(command.isNotEmpty()) { "command must not be empty." }
        check(!closed.get()) { "environment is closed." }
        val marker = "$EXEC_MARKER_VARIABLE=${UUID.randomUUID()}"
        val result = docker(
            listOf("exec", "-i", "-e", marker, "-w", CONTAINER_WORKSPACE, containerName) + command,
            stdin = stdin,
            timeout = timeout,
            killer = markedProcessKiller(marker),
        )
        if (result is ExecResult.Completed && result.exitCode != 0) {
            ensureContainerStillRunning(result)
        }
        return result
    }

    /**
     * Any non-zero exit code is ambiguous between the command's own exit
     * and a docker-level failure — the docker CLI reports a dead container
     * or unreachable daemon as its own (often 1) exit code; a live
     * container disambiguates in the command's favor.
     */
    private suspend fun ensureContainerStillRunning(result: ExecResult.Completed) {
        // A failed inspect gets one retry: a transient daemon hiccup must not
        // escalate a valid command failure into environment death. A daemon
        // answer of "not running" is conclusive and is not retried.
        val running = inspectRunningState(containerName) ?: inspectRunningState(containerName)
        if (running != true) {
            throw EnvironmentFailureException(
                "Container $containerName is gone or the Docker daemon is unreachable " +
                    "(docker exec exited with ${result.exitCode}: ${result.problem()}).",
            )
        }
    }

    private fun markedProcessKiller(marker: String): ProcessKiller = ProcessKiller { client ->
        val swept = sweepMarkedProcesses(marker)
        val clientExited = withTimeoutOrNull(CLIENT_EXIT_GRACE) { client.onExit().await() } != null
        if (!swept || !clientExited) {
            HOST_PROCESS_TREE_KILLER.kill(client)
        }
    }

    /**
     * SIGKILLs every in-container process carrying [marker] in its
     * environment, rescanning until a pass finds none (a process may fork
     * during the sweep), bounded by the script's retry count and
     * [KILL_SWEEP_TIMEOUT]. False when the sweep could not verify a clean
     * scan — the caller then falls back to killing the docker client.
     */
    private suspend fun sweepMarkedProcesses(marker: String): Boolean {
        val result = runCatching {
            docker(
                listOf("exec", containerName, "bash", "-c", KILL_SWEEP_SCRIPT, "kill-sweep", marker),
                timeout = KILL_SWEEP_TIMEOUT,
            )
        }.getOrNull()
        return result != null && result.succeeded
    }

    private fun startContainer() {
        val result = dockerBlocking(
            listOf(
                "run", "-d", "--init",
                "--platform", "linux/amd64",
                // Root even when the image's Dockerfile ends in a USER directive;
                // execs inherit the run user.
                "--user", "0:0",
                "--name", containerName,
                "--label", CONTAINER_LABEL,
                "-w", CONTAINER_WORKSPACE,
                image,
                "sleep", "infinity",
            ),
            IMAGE_START_TIMEOUT,
        )
        if (!result.succeeded) {
            // A half-created container (e.g. created but failed to start) must not leak.
            destroyContainer()
            throw EnvironmentStartupException(
                "Starting a container from image '$image' failed: ${result.problem()}",
            )
        }
    }

    private fun copyWorkspaceIn() {
        // Trailing `/.` copies the directory's contents, dotfiles included.
        val result = dockerBlocking(
            listOf("cp", "$hostWorkspace/.", "$containerName:$CONTAINER_WORKSPACE"),
            WORKSPACE_TRANSFER_TIMEOUT,
        )
        if (!result.succeeded) {
            throw EnvironmentStartupException(
                "Copying the workspace into the container failed: ${result.problem()}",
            )
        }
    }

    private fun probeUserlandBaseline() {
        val result = dockerBlocking(
            listOf("exec", containerName, "bash", "-c", BASELINE_PROBE_SCRIPT, "baseline-probe") +
                USERLAND_BASELINE,
            DOCKER_QUICK_CALL_TIMEOUT,
        )
        if (result.succeeded) {
            return
        }
        val detail = when {
            result is ExecResult.Completed && result.exitCode == BASELINE_MISSING_EXIT_CODE ->
                "userland baseline is incomplete — required binaries missing: ${result.stdout.trim()}"

            result is ExecResult.Completed && result.exitCode in DOCKER_AMBIGUOUS_EXIT_CODES ->
                "does not provide the userland baseline — `bash` could not be run inside the " +
                    "container (${result.problem()})"

            else -> "failed its userland baseline probe: ${result.problem()}"
        }
        throw EnvironmentStartupException("Image '$image' $detail. Any debian-based image qualifies.")
    }

    /**
     * In-flight [exec]s must have completed or been cancelled first; an
     * exec racing this call sees unspecified results.
     *
     * @throws EnvironmentFailureException when the workspace copy-out
     *   failed — the run's results are lost; the container is removed
     *   regardless.
     */
    public override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        try {
            copyWorkspaceOut()
        } finally {
            destroyContainer()
        }
    }

    private fun copyWorkspaceOut() {
        // Works on a stopped container too, so a dead one still yields its results.
        val result = dockerBlocking(
            listOf("cp", "$containerName:$CONTAINER_WORKSPACE/.", hostWorkspace.toString()),
            WORKSPACE_TRANSFER_TIMEOUT,
        )
        if (!result.succeeded) {
            throw EnvironmentFailureException(
                "Copying the workspace back to the host failed: ${result.problem()}",
            )
        }
    }

    private fun destroyContainer() {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
        } catch (_: IllegalStateException) {
            // The JVM is already shutting down — this call is the hook's own.
        }
        dockerBlocking(listOf("rm", "-f", containerName), DOCKER_QUICK_CALL_TIMEOUT)
    }

    private companion object {

        /** Fixed in-container workspace root; also the container's working directory. */
        const val CONTAINER_WORKSPACE = "/workspace"

        /** Label on every container, making leaked ones findable with one `docker ps` filter. */
        const val CONTAINER_LABEL = "codes.momo.agent=1"

        /** Per-exec marker variable the kill sweep matches in `/proc/<pid>/environ`. */
        const val EXEC_MARKER_VARIABLE = "MOMO_EXEC_ID"

        /**
         * `docker run`/`docker exec` exit codes that may be docker's own
         * (125: docker error, 126: not runnable, 127: not found) rather
         * than the command's.
         */
        val DOCKER_AMBIGUOUS_EXIT_CODES = 125..127

        /** Deadline for `docker run`, which blocks while pulling the image. */
        val IMAGE_START_TIMEOUT = 10.minutes

        /** Deadline for copying the workspace in or out. */
        val WORKSPACE_TRANSFER_TIMEOUT = 10.minutes

        /** Deadline for the in-container kill sweep; on expiry the docker client is killed instead. */
        val KILL_SWEEP_TIMEOUT = 15.seconds

        /** How long a killed exec's docker client gets to exit on its own. */
        val CLIENT_EXIT_GRACE = 5.seconds
    }
}

/**
 * Exit code for "baseline binaries missing", distinct from docker's own
 * 125–127 range so the two failure modes stay tellable apart.
 */
private const val BASELINE_MISSING_EXIT_CODE: Int = 43

/**
 * The tool name fills the `$0` slot (as in the tool scripts); the required
 * binaries arrive as `"$@"` so none is interpolated into the script.
 */
private val BASELINE_PROBE_SCRIPT: String = """
    missing=""
    for binary in "$@"; do
      command -v "${'$'}binary" >/dev/null || missing="${'$'}missing ${'$'}binary"
    done
    [ -z "${'$'}missing" ] && exit 0
    echo "${'$'}missing"
    exit $BASELINE_MISSING_EXIT_CODE
""".trimIndent()

/**
 * `$1` is the marker to hunt; exit 0 is a verified-clean scan, exit 1
 * means the retries ran out. A clean scan is an empty match list, never
 * grep's exit status: grep reports an error whenever a `/proc` entry
 * vanishes mid-scan — routine on a busy system — even when it also found
 * matches. Uses only bash, grep, and kill — all within the userland
 * baseline.
 */
private val KILL_SWEEP_SCRIPT: String = """
    for attempt in 1 2 3 4 5; do
      marked=$(grep -Flas "$1" /proc/[0-9]*/environ)
      [ -z "${'$'}marked" ] && exit 0
      for environ in ${'$'}marked; do
        pid="${'$'}{environ#/proc/}"
        kill -9 "${'$'}{pid%/environ}" 2>/dev/null
      done
    done
    exit 1
""".trimIndent()
