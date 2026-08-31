package codes.momo.agent.tool

import codes.momo.agent.environment.CommandRunner
import codes.momo.agent.environment.ExecResult
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.environment.completed
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Test double for exec-backed tools: a [CommandRunner] that injects outcomes
 * a real host cannot produce on demand (timeouts, capture-cap truncation)
 * and records the latest invocation, so tests can pin how a tool maps exec
 * results and exactly what command and timeout it issues. Multiple [results]
 * are replayed in order (the last one repeating), for tools that exec more
 * than once per call.
 */
internal class FixedResultRunner(private vararg val results: ExecResult) : CommandRunner {

    var callCount: Int = 0
        private set

    var lastCommand: List<String>? = null
        private set

    var lastTimeout: Duration? = null
        private set

    override suspend fun run(command: List<String>, timeout: Duration): ExecResult {
        lastCommand = command
        lastTimeout = timeout
        return results[minOf(callCount++, results.lastIndex)]
    }

    /** An environment over [workspace] whose every exec this runner answers; nothing probes the host. */
    fun environment(workspace: Path): ExecutionEnvironment =
        ExecutionEnvironment(workspace, System.getenv("PATH"), probe = { completed(exitCode = 1) }, runner = this)
}
