package codes.momo.agent.tool

import ai.router.sdk.schema.Description
import codes.momo.agent.Budgets
import codes.momo.agent.environment.ExecResult
import codes.momo.agent.environment.ExecutionEnvironment
import kotlinx.serialization.Serializable

@Serializable
public data class WriteFileArgs(
    @Description("Absolute path of the file to write.")
    val path: String,
    @Description("The full content to write.")
    val content: String,
)

/**
 * Whole-file writer: creates the target or replaces it entirely. The session's
 * [FileReadTracker] guards replacement of files the model has not seen; a
 * successful write marks the path read.
 */
public class WriteFileTool(private val tracker: FileReadTracker) : DispatchedTool<WriteFileArgs>(
    name = "write_file",
    description = WRITE_FILE_DESCRIPTION,
    argsSerializer = WriteFileArgs.serializer(),
) {

    override suspend fun execute(args: WriteFileArgs, environment: ExecutionEnvironment): ToolResult = when {
        !args.path.startsWith("/") -> ToolResult.Error(
            "path must be absolute, got '${args.path}'",
        )

        // Rejected up front: the write script would mkdir the target path
        // itself as a parent directory before the redirect could fail.
        args.path.endsWith("/") -> ToolResult.Error(
            "path must not end with '/', got '${args.path}'",
        )

        else -> write(args, environment)
    }

    private suspend fun write(args: WriteFileArgs, environment: ExecutionEnvironment): ToolResult {
        val mode = if (tracker.wasRead(args.path)) MODE_OVERWRITE else MODE_CREATE_ONLY
        val result = environment.exec(
            listOf("bash", "-c", WRITE_SCRIPT, name, args.path, mode),
            stdin = args.content.toByteArray(),
            timeout = Budgets.TOOL_TIMEOUT,
        )
        return when (result) {
            is ExecResult.Completed -> interpret(args, result)
            // stdout only ever holds the created/replaced marker; surfacing it
            // as partial output would read as success for a killed write.
            is ExecResult.TimedOut -> ToolResult.TimedOut()
        }
    }

    private fun interpret(args: WriteFileArgs, result: ExecResult.Completed): ToolResult = when {
        result.exitCode == UNREAD_TARGET_EXIT_CODE -> ToolResult.Error(
            "'${args.path}' already exists but has not been read this session — " +
                "read it with read_file before overwriting.",
        )

        result.exitCode != 0 ->
            ToolResult.Error("cannot write '${args.path}': ${result.diagnostic("write")}")

        else -> {
            tracker.markRead(args.path)
            val verb = if (result.stdout.trim() == MARKER_REPLACED) MARKER_REPLACED else MARKER_CREATED
            ToolResult.Success("$verb '${args.path}'")
        }
    }
}

/** `$2` values telling [WRITE_SCRIPT] whether the tracker permits replacing an existing target. */
private const val MODE_OVERWRITE: String = "overwrite"
private const val MODE_CREATE_ONLY: String = "create-only"

/** [WRITE_SCRIPT] stdout markers distinguishing a fresh file from a replacement. */
private const val MARKER_CREATED: String = "created"
private const val MARKER_REPLACED: String = "replaced"

/**
 * Script exit code reserved for "target exists but was not read this
 * session": 78 (sysexits `EX_CONFIG`), which neither bash nor `mkdir` nor
 * `cat` produces, so the guardrail is unambiguous from the exit code alone.
 */
private const val UNREAD_TARGET_EXIT_CODE: Int = 78

/**
 * `bash -c` assigns the argv element after the script to `$0`; the tool
 * name fills that slot and prefixes bash's own diagnostics. `-f` scopes
 * the guardrail to regular files, so a directory target falls through to
 * the redirect's natural "Is a directory" failure. The `:-/` fallback
 * covers a parent of `/`, where the suffix strip leaves an empty string.
 */
private val WRITE_SCRIPT: String = """
    if [ -f "$1" ]; then
      if [ "$2" != $MODE_OVERWRITE ]; then
        exit $UNREAD_TARGET_EXIT_CODE
      fi
      echo $MARKER_REPLACED
    else
      echo $MARKER_CREATED
    fi
    parent="${'$'}{1%/*}"
    mkdir -p -- "${'$'}{parent:-/}" || exit
    cat > "$1"
""".trimIndent()

/** LLM-facing contract of [WriteFileTool] — the model only knows what this says. */
private val WRITE_FILE_DESCRIPTION: String = """
    Writes a file, creating it or fully replacing its content if it already exists. Use it for
    new files and full rewrites; for partial changes to an existing file use `edit_file`.
    `path` is the ABSOLUTE path of the file to write. Overwriting an existing file that has not
    been read this session fails — read it with `read_file` first. Missing parent directories
    are created automatically.
""".trimIndent()
