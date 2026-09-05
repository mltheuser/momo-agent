package codes.momo.agent.environment

public sealed interface ExecResult {

    public val stdout: String

    public val stderr: String

    public val stdoutTruncated: Boolean

    public val stderrTruncated: Boolean

    public data class Completed(
        val exitCode: Int,
        override val stdout: String,
        override val stderr: String,
        override val stdoutTruncated: Boolean,
        override val stderrTruncated: Boolean,
    ) : ExecResult

    public data class TimedOut(
        override val stdout: String,
        override val stderr: String,
        override val stdoutTruncated: Boolean,
        override val stderrTruncated: Boolean,
    ) : ExecResult
}

internal val ExecResult.succeeded: Boolean
    get() = this is ExecResult.Completed && exitCode == 0

internal fun ExecResult.problem(): String = when (this) {
    is ExecResult.Completed -> stderr.trim().ifEmpty { "exited with code $exitCode" }
    is ExecResult.TimedOut -> "timed out"
}
