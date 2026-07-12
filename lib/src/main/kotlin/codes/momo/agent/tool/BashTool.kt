package codes.momo.agent.tool

import ai.router.sdk.schema.Description
import codes.momo.agent.Budgets
import codes.momo.agent.environment.ExecResult
import codes.momo.agent.environment.ExecutionEnvironment
import kotlinx.serialization.Serializable

@Serializable
public data class BashArgs(
    @Description("The bash command to run.")
    val command: String,
)

/**
 * general-purpose shell tool: one bash command per call.
 */
public class BashTool : Tool<BashArgs>(
    name = "bash",
    description = BASH_DESCRIPTION,
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

/** LLM-facing contract of [BashTool] — the model only knows what this says. */
private val BASH_DESCRIPTION: String = """
    Runs a bash command (via `bash -c`) with the workspace root as the working directory. This is
    the tool for searching and listing files — use `grep`, `find`, and `ls` here; there are no
    dedicated tools for that.

    Each call is a fresh shell and nothing persists between calls — no `cd`, exported variables,
    or shell functions. Chain dependent steps with `&&` and use paths relative to the workspace
    root.

    Commands are killed after ${Budgets.TOOL_TIMEOUT} and report a timeout error with any partial output. Output
    over ${ToolRegistry.MAX_RESULT_CHARS} characters is truncated. For long-running processes (e.g. servers), background
    them with BOTH stdout and stderr redirected (to a file or /dev/null) — a backgrounded
    process still holding either stream hangs the call until the timeout.
""".trimIndent()
