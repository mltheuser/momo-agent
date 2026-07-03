package codes.momo.agent.environment

/**
 * Outcome of one [ExecutionEnvironment.exec] call: a command that ran to its
 * end — successfully or not — is a [Completed]; one killed because its
 * timeout elapsed is a [TimedOut].
 */
public sealed interface ExecResult {

    /** Captured standard output, decoded as UTF-8. */
    public val stdout: String

    /** Captured standard error, decoded as UTF-8. */
    public val stderr: String

    /** Whether [stdout] hit [ExecutionEnvironment.MAX_CAPTURED_BYTES] and lost the excess. */
    public val stdoutTruncated: Boolean

    /** Whether [stderr] hit [ExecutionEnvironment.MAX_CAPTURED_BYTES] and lost the excess. */
    public val stderrTruncated: Boolean

    /** The process exited within the timeout. */
    public data class Completed(
        val exitCode: Int,
        override val stdout: String,
        override val stderr: String,
        override val stdoutTruncated: Boolean,
        override val stderrTruncated: Boolean,
    ) : ExecResult

    /** The timeout elapsed and the process tree was killed mid-run. */
    public data class TimedOut(
        override val stdout: String,
        override val stderr: String,
        override val stdoutTruncated: Boolean,
        override val stderrTruncated: Boolean,
    ) : ExecResult
}
