package codes.momo.agent.tool

import ai.router.sdk.schema.Description
import codes.momo.agent.Budgets
import codes.momo.agent.environment.ExecResult
import codes.momo.agent.environment.ExecutionEnvironment
import kotlinx.serialization.Serializable

@Serializable
public data class ReadFileArgs(
    @Description("Absolute path of the text file to read.")
    val path: String,
    @Description("1-based line number to start reading from.")
    val offset: Int,
    @Description("Maximum number of lines to return (at most ${ReadFileTool.MAX_WINDOW_LINES} per call).")
    val limit: Int,
)

/**
 * Windowed text-file reader: returns file content verbatim — byte-exact, so
 * output can be matched back against the file as exact strings — plus a
 * footer saying how the window ended and how to continue. Every successful
 * read marks the path in the session's [FileReadTracker].
 */
public class ReadFileTool(private val tracker: FileReadTracker) : DispatchedTool<ReadFileArgs>(
    name = "read_file",
    description = READ_FILE_DESCRIPTION,
    argsSerializer = ReadFileArgs.serializer(),
) {

    override suspend fun execute(args: ReadFileArgs, environment: ExecutionEnvironment): ToolResult = when {
        !args.path.startsWith("/") -> ToolResult.Error(
            "path must be absolute, got '${args.path}'",
        )

        args.offset < 1 -> ToolResult.Error("offset must be at least 1, got ${args.offset}.")
        args.limit < 1 -> ToolResult.Error("limit must be at least 1, got ${args.limit}.")
        else -> read(args, environment)
    }

    private suspend fun read(args: ReadFileArgs, environment: ExecutionEnvironment): ToolResult {
        // One line past the window: its presence is how "file continues" is detected.
        // The quit command stops sed at the sentinel, so it never scans to EOF.
        val sentinelLine = args.offset.toLong() + args.windowLines
        val result = environment.exec(
            listOf("sed", "-n", "${args.offset},${sentinelLine}p;${sentinelLine}q", args.path),
            timeout = Budgets.TOOL_TIMEOUT,
        )
        return when (result) {
            is ExecResult.Completed -> {
                val outcome = interpret(args, result)
                if (outcome is ToolResult.Success) tracker.markRead(args.path)
                outcome
            }

            is ExecResult.TimedOut -> ToolResult.TimedOut(partialOutput = result.stdout.ifEmpty { null })
        }
    }

    /**
     * A non-zero exit means the read itself failed (missing file, directory,
     * unreadable) — unlike bash, where the exit code is the command's own
     * business. An empty window from a successful read is an empty file only
     * when it starts at line 1; otherwise the offset overshot the file.
     */
    private fun interpret(args: ReadFileArgs, result: ExecResult.Completed): ToolResult = when {
        result.exitCode != 0 ->
            ToolResult.Error("cannot read '${args.path}': ${result.diagnostic("read")}")

        result.stdout.isEmpty() && args.offset == 1 ->
            ToolResult.Success(EMPTY_FILE_NOTE)

        result.stdout.isEmpty() ->
            ToolResult.Error("offset ${args.offset} is past the end of '${args.path}' — use a smaller offset.")

        else ->
            ToolResult.Success(render(args, result))
    }

    private fun render(args: ReadFileArgs, result: ExecResult.Completed): String {
        val window = windowOf(result.stdout, args.windowLines)
        return when {
            // A capture-capped stream only affects the window itself when the
            // window is incomplete: with the limit-th newline present, only
            // the sentinel line was cut, and the window is whole.
            window.content.length > MAX_CONTENT_CHARS || (result.stdoutTruncated && !window.fileContinues) ->
                renderCapped(window.content, args.offset)

            window.fileContinues ->
                window.content.withFooter(
                    "[read ${args.windowLines} lines — file continues; " +
                        "call again with offset=${args.offset.toLong() + args.windowLines} to keep reading]",
                )

            else ->
                window.content.withFooter(
                    if (window.content.endsWith("\n")) "[end of file]" else "[end of file — no trailing newline]",
                )
        }
    }

    private fun renderCapped(content: String, offset: Int): String {
        val kept = content.capped()
        val footer = if (kept.endsWith("\n")) {
            val nextOffset = offset.toLong() + kept.count { it == '\n' }
            "[output capped at $MAX_CONTENT_CHARS characters — file continues; " +
                "call again with offset=$nextOffset to keep reading]"
        } else {
            "[output capped at $MAX_CONTENT_CHARS characters — line $offset continues beyond the cap]"
        }
        return kept.withFooter(footer)
    }

    /**
     * Cuts to at most [MAX_CONTENT_CHARS], preferring the last line boundary
     * before the cap; a single line longer than the cap is cut mid-line,
     * never through a UTF-16 surrogate pair.
     */
    private fun String.capped(): String = when {
        length <= MAX_CONTENT_CHARS -> this
        else -> {
            val lastNewline = lastIndexOf('\n', MAX_CONTENT_CHARS - 1)
            if (lastNewline >= 0) {
                substring(0, lastNewline + 1)
            } else {
                val boundary = MAX_CONTENT_CHARS - 1
                substring(0, if (this[boundary].isHighSurrogate()) boundary else MAX_CONTENT_CHARS)
            }
        }
    }

    /** The window content with the sentinel line (anything past the limit-th newline) dropped. */
    private fun windowOf(stdout: String, limit: Int): Window {
        var newlines = 0
        stdout.forEachIndexed { index, char ->
            // A limit-th newline as the very last character means the sentinel
            // line never arrived: the file ends exactly with the window.
            if (char == '\n' && ++newlines == limit && index < stdout.length - 1) {
                return Window(stdout.substring(0, index + 1), fileContinues = true)
            }
        }
        return Window(stdout, fileContinues = false)
    }

    private class Window(val content: String, val fileContinues: Boolean)

    /** Lines this call may return: the requested limit, bounded by the per-call cap. */
    private val ReadFileArgs.windowLines: Int
        get() = minOf(limit, MAX_WINDOW_LINES)

    /**
     * Content is passed through verbatim; the footer only gets the newline
     * that puts it on its own line.
     */
    private fun String.withFooter(footer: String): String =
        if (endsWith("\n")) this + footer else this + "\n" + footer

    public companion object {

        /** Hard per-call cap on returned lines; larger `limit` values are clamped to it. */
        public const val MAX_WINDOW_LINES: Int = 200

        /** Space reserved under [ToolRegistry.MAX_RESULT_CHARS] for the footer line. */
        private const val FOOTER_HEADROOM: Int = 512

        /**
         * Total cap on returned content, in chars: subtracts tool's footer so it survives dispatch untouched.
         */
        public const val MAX_CONTENT_CHARS: Int = ToolRegistry.MAX_RESULT_CHARS - FOOTER_HEADROOM

        /** Explicit note instead of a bare empty string, so the outcome is legible. */
        private const val EMPTY_FILE_NOTE: String = "[file is empty]"
    }
}

/** LLM-facing contract of [ReadFileTool] — the model only knows what this says. */
private val READ_FILE_DESCRIPTION: String = """
    Reads a UTF-8 text file from the workspace and returns its content verbatim — no line
    numbers, nothing reflowed — so the output can be copied exactly as it is in the file.
    Binary content does not round-trip; use this tool only on text files. `path` is the
    ABSOLUTE path of the text file to read. Searching and listing files is the `bash`
    tool's job (grep/find/ls).
""".trimIndent()
