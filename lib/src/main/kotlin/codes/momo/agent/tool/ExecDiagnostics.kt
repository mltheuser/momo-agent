package codes.momo.agent.tool

import codes.momo.agent.environment.ExecResult

/**
 * Failure detail of a completed exec for error messages: the trimmed stderr,
 * or — when the command was silent — a fallback naming the failed [verb]
 * (e.g. "read", "write") and the exit code.
 */
internal fun ExecResult.Completed.diagnostic(verb: String): String =
    stderr.trim().ifEmpty { "$verb command exited with code $exitCode" }
