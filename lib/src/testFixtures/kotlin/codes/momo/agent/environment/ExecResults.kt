package codes.momo.agent.environment

/** An [ExecResult.Completed] with quiet defaults, so tests spell out only what they assert on. */
public fun completed(
    exitCode: Int = 0,
    stdout: String = "",
    stderr: String = "",
    stdoutTruncated: Boolean = false,
    stderrTruncated: Boolean = false,
): ExecResult.Completed = ExecResult.Completed(exitCode, stdout, stderr, stdoutTruncated, stderrTruncated)
