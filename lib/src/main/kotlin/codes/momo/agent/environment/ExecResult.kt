package codes.momo.agent.environment

internal sealed interface ExecResult {

    val stdout: String

    val stderr: String

    val stdoutTruncated: Boolean

    val stderrTruncated: Boolean

    data class Completed(
        val exitCode: Int,
        override val stdout: String,
        override val stderr: String,
        override val stdoutTruncated: Boolean,
        override val stderrTruncated: Boolean,
    ) : ExecResult

    data class TimedOut(
        override val stdout: String,
        override val stderr: String,
        override val stdoutTruncated: Boolean,
        override val stderrTruncated: Boolean,
    ) : ExecResult
}

internal val ExecResult.succeeded: Boolean
    get() = this is ExecResult.Completed && exitCode == 0
