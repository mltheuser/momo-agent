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

/** Whole-file creator: writes a new file, refusing an existing target. */
public class WriteFileTool : Tool<WriteFileArgs>(
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
        val result = environment.exec(
            listOf("bash", "-c", WRITE_SCRIPT, name, args.path),
            stdin = args.content.toByteArray(),
            timeout = Budgets.TOOL_TIMEOUT,
        )
        return when (result) {
            is ExecResult.Completed -> interpret(args, result)
            // The script emits no stdout; there is no partial output to carry.
            is ExecResult.TimedOut -> ToolResult.TimedOut()
        }
    }

    private fun interpret(args: WriteFileArgs, result: ExecResult.Completed): ToolResult = when {
        result.exitCode == EXISTING_TARGET_EXIT_CODE -> ToolResult.Error(
            "'${args.path}' already exists — modify it with edit_file, or delete it " +
                "with bash first for a full rewrite.",
        )

        result.exitCode != 0 ->
            ToolResult.Error("cannot write '${args.path}': ${result.diagnostic("write")}")

        else -> ToolResult.Success("created '${args.path}'")
    }
}

/**
 * Script exit code reserved for "target already exists": 78 (sysexits
 * `EX_CONFIG`), which neither bash nor `mkdir` nor `cat` produces, so the
 * refusal is unambiguous from the exit code alone.
 */
private const val EXISTING_TARGET_EXIT_CODE: Int = 78

/**
 * `bash -c` assigns the argv element after the script to `$0`; the tool
 * name fills that slot and prefixes bash's own diagnostics. `-f` scopes
 * the exists-refusal to regular files, so a directory target falls through
 * to the redirect's natural "Is a directory" failure. `set -C` (noclobber)
 * makes the create itself O_EXCL: a target appearing after the check — or
 * a dangling symlink, which `-f` misses — fails the redirect instead of
 * being truncated or written through. The `:-/` fallback covers a parent
 * of `/`, where the suffix strip leaves an empty string.
 */
private val WRITE_SCRIPT: String = """
    if [ -f "$1" ]; then
      exit $EXISTING_TARGET_EXIT_CODE
    fi
    parent="${'$'}{1%/*}"
    mkdir -p -- "${'$'}{parent:-/}" || exit
    set -C
    cat > "$1"
""".trimIndent()

/** LLM-facing contract of [WriteFileTool] — the model only knows what this says. */
private val WRITE_FILE_DESCRIPTION: String = """
    Creates a new file with the given content. `path` is the ABSOLUTE path of the file to
    create; missing parent directories are created automatically. For changing an existing
    file use `edit_file`.
""".trimIndent()
