package codes.momo.agent.tool

import ai.router.sdk.schema.Description
import codes.momo.agent.Budgets
import codes.momo.agent.environment.ExecResult
import codes.momo.agent.environment.ExecutionEnvironment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class EditFileArgs(
    @Description("Absolute path of the file to edit.")
    val path: String,
    @SerialName("old_string")
    @Description("The exact existing text to replace.")
    val oldString: String,
    @SerialName("new_string")
    @Description("The text to replace it with.")
    val newString: String,
)

/**
 * Targeted editor: replaces the single exact occurrence of one string with
 * another, splicing in memory so every byte outside the matched span is
 * written back untouched. The session's [FileReadTracker] gates edits to
 * files the model has seen; the target is therefore already marked read,
 * and tracker state is left as is.
 */
public class EditFileTool(private val tracker: FileReadTracker) : DispatchedTool<EditFileArgs>(
    name = "edit_file",
    description = EDIT_FILE_DESCRIPTION,
    argsSerializer = EditFileArgs.serializer(),
) {

    override suspend fun execute(args: EditFileArgs, environment: ExecutionEnvironment): ToolResult = when {
        !args.path.startsWith("/") -> ToolResult.Error(
            "path must be absolute, got '${args.path}'",
        )

        args.oldString.isEmpty() -> ToolResult.Error(
            "old_string must not be empty — to create a file, use write_file.",
        )

        args.oldString == args.newString -> ToolResult.Error(
            "old_string and new_string are identical — the edit would change nothing.",
        )

        !tracker.wasRead(args.path) -> ToolResult.Error(
            "'${args.path}' has not been read this session — read it with read_file before editing.",
        )

        else -> edit(args, environment)
    }

    private suspend fun edit(args: EditFileArgs, environment: ExecutionEnvironment): ToolResult =
        when (val read = environment.exec(listOf("cat", args.path), timeout = Budgets.TOOL_TIMEOUT)) {
            is ExecResult.Completed -> interpretRead(args, read, environment)
            // The capture is an internal intermediate, not content the model
            // asked to see; a fragment of it would only invite a bad splice.
            is ExecResult.TimedOut -> ToolResult.TimedOut()
        }

    private suspend fun interpretRead(
        args: EditFileArgs,
        read: ExecResult.Completed,
        environment: ExecutionEnvironment,
    ): ToolResult = when {
        read.exitCode != 0 ->
            ToolResult.Error("cannot read '${args.path}': ${read.diagnostic("read")}")

        // A capped capture means the content in hand is not the whole file;
        // splicing and writing it back would silently drop the lost tail.
        read.stdoutTruncated ->
            ToolResult.Error("'${args.path}' is too large to edit in place — rewrite it with write_file.")

        else -> replace(args, read.stdout, environment)
    }

    private suspend fun replace(
        args: EditFileArgs,
        content: String,
        environment: ExecutionEnvironment,
    ): ToolResult = when (val occurrences = content.occurrencesOf(args.oldString)) {
        0 -> ToolResult.Error(
            "old_string was not found in '${args.path}' — matching is exact, including whitespace " +
                "and indentation; re-read the file and copy the text verbatim.",
        )

        1 -> writeBack(args, content.spliced(args), environment)

        else -> ToolResult.Error(
            "old_string occurs $occurrences times in '${args.path}' — include more surrounding " +
                "context to make the match unique.",
        )
    }

    private suspend fun writeBack(
        args: EditFileArgs,
        content: String,
        environment: ExecutionEnvironment,
    ): ToolResult {
        val result = environment.exec(
            listOf("bash", "-c", EDIT_WRITE_SCRIPT, name, args.path),
            stdin = content.toByteArray(),
            timeout = Budgets.TOOL_TIMEOUT,
        )
        return when (result) {
            is ExecResult.Completed -> when {
                result.exitCode != 0 ->
                    ToolResult.Error("cannot write '${args.path}': ${result.diagnostic("write")}")

                else -> ToolResult.Success("edited '${args.path}'")
            }

            // A killed write-back captured no output worth surfacing; partial
            // output would read as success.
            is ExecResult.TimedOut -> ToolResult.TimedOut()
        }
    }

    private fun String.spliced(args: EditFileArgs): String {
        val start = indexOf(args.oldString)
        return substring(0, start) + args.newString + substring(start + args.oldString.length)
    }

    /**
     * Occurrences at every start position, overlapping ones included — a
     * second match sharing characters with the first is just as ambiguous
     * as a disjoint one.
     */
    private fun String.occurrencesOf(needle: String): Int {
        var count = 0
        var index = indexOf(needle)
        while (index >= 0) {
            count++
            index = indexOf(needle, index + 1)
        }
        return count
    }
}

/**
 * The tool name fills the `$0` slot, as in write_file's script. The target
 * was just read, so unlike there, nothing needs creating or guarding.
 */
private val EDIT_WRITE_SCRIPT: String = """
    cat > "$1"
""".trimIndent()

/** LLM-facing contract of [EditFileTool] — the model only knows what this says. */
private val EDIT_FILE_DESCRIPTION: String = """
    Replaces one exact string in an existing file. `old_string` must match the file content
    exactly — including whitespace and indentation, with no normalization and no regex — and
    must occur exactly once. The file must have been read this session with `read_file` first.
    Binary content does not round-trip; use this tool only on text files. For creating files
    or full rewrites use `write_file`.
""".trimIndent()
