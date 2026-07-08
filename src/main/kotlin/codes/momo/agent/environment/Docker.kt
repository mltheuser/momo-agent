package codes.momo.agent.environment

import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Runs one `docker` CLI command as a subprocess. */
internal suspend fun docker(
    args: List<String>,
    stdin: ByteArray? = null,
    timeout: Duration,
    killer: ProcessKiller = HOST_PROCESS_TREE_KILLER,
): ExecResult = runProcess(listOf("docker") + args, stdin = stdin, timeout = timeout, killer = killer)

/** [docker] for the blocking lifecycle paths (construction, close, shutdown hook). */
internal fun dockerBlocking(args: List<String>, timeout: Duration): ExecResult =
    runBlocking { docker(args, timeout = timeout) }

/**
 * The one startup check covering the whole docker dependency: CLI on PATH,
 * usable without sudo, daemon reachable.
 *
 * @throws EnvironmentStartupException naming what is wrong when `docker
 *   version` cannot run or fails.
 */
internal fun checkDockerUsable() {
    val result = try {
        dockerBlocking(listOf("version"), timeout = DOCKER_QUICK_CALL_TIMEOUT)
    } catch (failure: IOException) {
        throw EnvironmentStartupException(
            "Docker CLI not found — install Docker and make sure `docker` is on PATH.",
            failure,
        )
    }
    if (!result.succeeded) {
        throw EnvironmentStartupException(
            "`docker version` failed — Docker must be usable without sudo and its daemon " +
                "must be running: ${result.problem()}",
        )
    }
}

/** Whether the container is running per the daemon, or null when the inspect itself failed. */
internal suspend fun inspectRunningState(containerName: String): Boolean? {
    val inspect = docker(
        listOf("inspect", "-f", "{{.State.Running}}", containerName),
        timeout = DOCKER_QUICK_CALL_TIMEOUT,
    )
    return (inspect as? ExecResult.Completed)?.takeIf { it.exitCode == 0 }?.let { it.stdout.trim() == "true" }
}

/** Whether the command ran to its end and exited 0. */
internal val ExecResult.succeeded: Boolean
    get() = this is ExecResult.Completed && exitCode == 0

/**
 * Failure detail of a docker CLI call for error messages: the trimmed
 * stderr, or — when it was silent — the exit code; a timeout says so.
 */
internal fun ExecResult.problem(): String = when (this) {
    is ExecResult.Completed -> stderr.trim().ifEmpty { "exited with code $exitCode" }
    is ExecResult.TimedOut -> "timed out"
}

/** Deadline for quick docker calls (version, inspect, in-container probes, rm). */
internal val DOCKER_QUICK_CALL_TIMEOUT: Duration = 30.seconds
