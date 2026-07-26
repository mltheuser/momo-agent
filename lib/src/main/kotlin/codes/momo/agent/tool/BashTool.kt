package codes.momo.agent.tool

import ai.router.sdk.schema.Description
import codes.momo.agent.Budgets
import codes.momo.agent.environment.ExecResult
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.environment.Privilege
import kotlinx.serialization.Serializable

@Serializable
public data class BashArgs(
    @Description("The bash command to run.")
    val command: String,
)

/**
 * general-purpose shell tool: one bash command per call.
 *
 * [workspacePath] and [privilege] are the absolute workspace root commands
 * run in and the rights they run with, both named in the description: the
 * model learns them from the tool that owns them, so both must come from
 * the environment this tool is executed against.
 */
public class BashTool(workspacePath: String, privilege: Privilege) : Tool<BashArgs>(
    name = "bash",
    description = bashDescription(workspacePath, privilege),
    argsSerializer = BashArgs.serializer(),
) {

    override suspend fun execute(args: BashArgs, environment: ExecutionEnvironment): ToolResult {
        val result = environment.exec(listOf("bash", "-c", args.command), timeout = Budgets.TOOL_TIMEOUT)
        return when (result) {
            is ExecResult.Completed ->
                ToolResult.Success("exit code: ${result.exitCode}\n" + result.formatStreams())

            is ExecResult.TimedOut ->
                ToolResult.TimedOut(
                    partialOutput = if (result.hasOutput) result.formatStreams() else null,
                )
        }
    }

    /**
     * Whether either raw stream captured anything — [formatStreams] is never
     * empty (it emits `(empty)` markers), so emptiness is tested here.
     */
    private val ExecResult.hasOutput: Boolean
        get() = stdout.isNotEmpty() || stderr.isNotEmpty()

    /**
     * stderr leads: the dispatch bound keeps the head of the result, and
     * diagnostics must survive an oversized stdout, not the other way round.
     */
    private fun ExecResult.formatStreams(): String =
        section("stderr", stderr, stderrTruncated) + section("stdout", stdout, stdoutTruncated)

    /**
     * One labeled stream section, always ending in exactly the newline that
     * puts the next header at the start of a line. `truncated` is the exec
     * primitive's per-stream capture cap, not the (far smaller) dispatch
     * bound.
     */
    private fun section(label: String, content: String, truncated: Boolean): String {
        val header = if (truncated) "$label (truncated)" else label
        return when {
            content.isEmpty() -> "$header: (empty)\n"
            content.endsWith("\n") -> "$header:\n$content"
            else -> "$header:\n$content\n"
        }
    }
}

/**
 * LLM-facing contract of [BashTool] — the model only knows what this says,
 * so the workspace root and the privilege wording are stated here and
 * nowhere else: one place to read where commands run, with what rights, and
 * how to name files.
 */
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

        Commands are killed after ${Budgets.TOOL_TIMEOUT} and report a timeout error with any partial output. stdout
        and stderr come back in one result (stderr first) and share a budget of ${ToolRegistry.MAX_RESULT_CHARS} characters;
        truncation keeps the beginning and drops the end, so to see the end of long output, filter
        it (e.g. `tail`, `grep`) instead of dumping it. Long-running processes (e.g. servers) MUST
        be backgrounded with BOTH stdout and stderr redirected (to a file or /dev/null) — a
        backgrounded process still holding either stream hangs the call until the timeout.
    """.trimIndent()
    return listOf(
        "Runs a bash command (via `bash -c`) from the workspace root, $workspacePath.",
        rights,
        contract,
    ).joinToString("\n\n")
}
