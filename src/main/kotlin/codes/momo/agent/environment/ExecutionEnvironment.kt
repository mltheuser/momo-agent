package codes.momo.agent.environment

import kotlin.time.Duration

/**
 * Execution seam around a workspace. All workspace-touching tools run their
 * commands through this single primitive, so the same tool code serves a
 * plain local directory and, later, a container over a temp folder.
 *
 * Implementations own their lifecycle: [close] tears down whatever the
 * environment set up (e.g. a container); the environment must not be used
 * afterwards.
 */
public interface ExecutionEnvironment : AutoCloseable {

    /**
     * Absolute path of the workspace root as seen by commands run through
     * [exec] — for a container-backed environment the in-container path,
     * not the host one. POSIX notation, the form tools pass paths in.
     */
    public val workspacePath: String

    /**
     * Runs [command] with the workspace root as the working directory,
     * returning once the process exited or [timeout] elapsed.
     *
     * - [command] is an argv vector — no shell is interposed. Callers
     *   wanting shell features pass `["bash", "-c", script]`.
     * - [stdin] is written to the process unmodified, then closed; null
     *   means immediate EOF. A process that exits without reading it is
     *   not an error.
     * - Each of stdout/stderr is captured up to [MAX_CAPTURED_BYTES]; the
     *   rest is drained but discarded — the process still runs to
     *   completion — and reported via the [ExecResult] truncation flags.
     * - Only the direct child is waited for. Processes it leaves running
     *   in the background are not: their later output is lost, and they
     *   may outlive this call.
     * - On [timeout] — no default; the caller supplies the policy — the
     *   process tree is killed and [ExecResult.TimedOut] returned with
     *   the output captured so far. A timeout is never an exception or
     *   an exit code.
     * - Cancelling the calling coroutine kills the process tree the same
     *   way.
     */
    public suspend fun exec(
        command: List<String>,
        stdin: ByteArray? = null,
        timeout: Duration,
    ): ExecResult

    public companion object {

        /**
         * Per-stream cap on captured output. The typical caller is an LLM
         * issuing arbitrary commands; a runaway one must not be able to
         * exhaust the JVM heap before its timeout fires.
         */
        public const val MAX_CAPTURED_BYTES: Int = 8 * 1024 * 1024
    }
}
