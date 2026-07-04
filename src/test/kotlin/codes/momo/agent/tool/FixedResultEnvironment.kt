package codes.momo.agent.tool

import codes.momo.agent.environment.ExecResult
import codes.momo.agent.environment.ExecutionEnvironment
import kotlin.time.Duration

/**
 * Test double for exec-backed tools: injects outcomes a real environment
 * cannot produce on demand (timeouts, capture-cap truncation) and records
 * the latest invocation, so tests can pin how a tool maps exec results and
 * exactly what command and timeout it issues. Multiple [results] are
 * replayed in order (the last one repeating), for tools that exec more
 * than once per call.
 */
internal class FixedResultEnvironment(private vararg val results: ExecResult) : ExecutionEnvironment {

    var callCount: Int = 0
        private set

    var lastCommand: List<String>? = null
        private set

    var lastStdin: ByteArray? = null
        private set

    var lastTimeout: Duration? = null
        private set

    override suspend fun exec(command: List<String>, stdin: ByteArray?, timeout: Duration): ExecResult {
        lastCommand = command
        lastStdin = stdin
        lastTimeout = timeout
        return results[minOf(callCount++, results.lastIndex)]
    }

    override fun close(): Unit = Unit
}

/** An [ExecResult.Completed] with quiet defaults, so tests spell out only what they assert on. */
internal fun completed(
    exitCode: Int = 0,
    stdout: String = "",
    stderr: String = "",
    stdoutTruncated: Boolean = false,
    stderrTruncated: Boolean = false,
): ExecResult.Completed = ExecResult.Completed(exitCode, stdout, stderr, stdoutTruncated, stderrTruncated)
