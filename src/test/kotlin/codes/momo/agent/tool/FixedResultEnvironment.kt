package codes.momo.agent.tool

import codes.momo.agent.environment.ExecResult
import codes.momo.agent.environment.ExecutionEnvironment
import kotlin.time.Duration

/**
 * Test double for exec-backed tools: injects outcomes a real environment
 * cannot produce on demand (timeouts, capture-cap truncation) and records
 * the invocation, so tests can pin how a tool maps exec results and exactly
 * what command and timeout it issues.
 */
internal class FixedResultEnvironment(private val result: ExecResult) : ExecutionEnvironment {

    var lastCommand: List<String>? = null
        private set

    var lastTimeout: Duration? = null
        private set

    override suspend fun exec(command: List<String>, stdin: ByteArray?, timeout: Duration): ExecResult {
        lastCommand = command
        lastTimeout = timeout
        return result
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
