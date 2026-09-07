package codes.momo.agent.tool

import ai.router.sdk.schema.Description
import codes.momo.agent.environment.ExecResult
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.environment.Privilege
import kotlinx.serialization.Serializable

@Serializable
internal data class BashArgs(
    @Description("The bash command to run.")
    val command: String,
)

internal class BashTool(workspacePath: String, privilege: Privilege) : Tool<BashArgs>(
    name = "bash",
    description = bashDescription(workspacePath, privilege),
    argsSerializer = BashArgs.serializer(),
) {

    override suspend fun execute(args: BashArgs, environment: ExecutionEnvironment): ToolResult {
        val result = environment.exec(listOf("bash", "-c", args.command), timeout = TOOL_TIMEOUT)
        return when (result) {
            is ExecResult.Completed ->
                ToolResult.Success("exit code: ${result.exitCode}\n" + result.formatStreams())

            is ExecResult.TimedOut ->
                ToolResult.TimedOut(
                    partialOutput = if (result.hasOutput) result.formatStreams() else null,
                )
        }
    }

    private val ExecResult.hasOutput: Boolean
        get() = stdout.isNotEmpty() || stderr.isNotEmpty()

    private fun ExecResult.formatStreams(): String =
        section("stderr", stderr, stderrTruncated) + section("stdout", stdout, stdoutTruncated)

    private fun section(label: String, content: String, truncated: Boolean): String {
        val header = if (truncated) "$label (truncated)" else label
        return when {
            content.isEmpty() -> "$header: (empty)\n"
            content.endsWith("\n") -> "$header:\n$content"
            else -> "$header:\n$content\n"
        }
    }
}

private fun bashDescription(workspacePath: String, privilege: Privilege): String {
    val rights = when (privilege) {
        Privilege.ROOT -> """
            Commands already run as root, so there is nothing to elevate and reaching for `sudo` only
            wastes a call — a permission-shaped failure here is never a privilege problem.
        """.trimIndent()

        Privilege.PASSWORDLESS_SUDO -> """
            Assume commands run as an unprivileged user, but `sudo` works without a password: when a
            command needs root, prefix the command with `sudo`.
        """.trimIndent()

        Privilege.UNPRIVILEGED -> """
            Assume commands run as an unprivileged user with no way up: `sudo` will not work. Treat a
            permission error as a real ceiling — report it instead of working around it.
        """.trimIndent()
    }
    val contract = """
        Each call is a fresh shell: a directory change, exported variable, or shell function from one
        call is gone by the next, and the working directory is back at the workspace root. Chain
        dependent steps into a single command with `&&`.

        Commands are killed after $TOOL_TIMEOUT and report a timeout error with any partial output. stdout
        and stderr come back in one result (stderr first) and share a budget of ${ToolRegistry.MAX_RESULT_CHARS} characters;
        truncation keeps the beginning and drops the end, so to see the end of long output, filter
        it (e.g. `tail`, `grep`) instead of dumping it. The call returns when the shell exits;
        background processes outlive it, but their stdout/stderr are closed — a later write to
        either fails (SIGPIPE). So background long-running processes (e.g. servers) with BOTH
        streams redirected to a file or /dev/null.
    """.trimIndent()
    return listOf(
        "Runs a bash command (via `bash -c`) from the workspace root, $workspacePath.",
        rights,
        contract,
    ).joinToString("\n\n")
}
