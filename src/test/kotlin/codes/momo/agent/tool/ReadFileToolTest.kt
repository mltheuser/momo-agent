package codes.momo.agent.tool

import codes.momo.agent.Budgets
import codes.momo.agent.environment.ExecResult
import codes.momo.agent.environment.LocalExecutionEnvironment
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadFileToolTest {

    @TempDir
    lateinit var tempDir: Path

    private val tracker = FileReadTracker()

    // ─── Helpers ──────────────────────────────────────────────────────

    /** Reads [path], resolved to an absolute path under the temp workspace, against a real local environment. */
    private fun read(
        path: String,
        offset: Int = 1,
        limit: Int = ReadFileTool.MAX_WINDOW_LINES,
    ): ToolResult = runBlocking {
        ReadFileTool(tracker).execute(
            ReadFileArgs(tempDir.resolve(path).toString(), offset, limit),
            LocalExecutionEnvironment(tempDir),
        )
    }

    private fun runStubbed(
        execResult: ExecResult,
        args: ReadFileArgs = ReadFileArgs("/file.txt", offset = 1, limit = ReadFileTool.MAX_WINDOW_LINES),
    ): ToolResult =
        runBlocking { ReadFileTool(tracker).execute(args, FixedResultEnvironment(execResult)) }

    private fun writeFile(name: String, content: String): Path =
        tempDir.resolve(name).apply { writeText(content) }

    /** A file of [lines] numbered lines, each newline-terminated. */
    private fun writeNumberedLines(name: String, lines: Int): Path =
        writeFile(name, (1..lines).joinToString(separator = "\n", postfix = "\n") { it.toString() })

    // ─── Definition ───────────────────────────────────────────────────

    @Test
    @DisplayName("The definition documents text scope, absolute paths, and verbatim output")
    fun definitionDocumentsTheContract() {
        val definition = ReadFileTool(tracker).definition

        assertEquals("read_file", definition.name)
        val description = assertNotNull(definition.description)
        assertContains(description, "UTF-8")
        assertContains(description, "ABSOLUTE path")
        assertContains(description, "verbatim")
        assertContains(description, "bash")
    }

    @Test
    @DisplayName("The parameters schema requires path, offset, and limit")
    fun parametersSchemaShape() {
        val schema = assertNotNull(ReadFileTool(tracker).definition.parameters)

        val properties = schema.getValue("properties").jsonObject
        assertEquals("string", properties.getValue("path").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("integer", properties.getValue("offset").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("integer", properties.getValue("limit").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(
            setOf("path", "offset", "limit"),
            schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
    }

    // ─── Complete reads ───────────────────────────────────────────────

    @Test
    @DisplayName("A full read returns the exact content followed by the end-of-file footer")
    fun fullReadReturnsExactContentWithEndOfFileFooter() {
        writeFile("notes.txt", "alpha\nbeta\n")

        val result = read("notes.txt")

        assertEquals("alpha\nbeta\n[end of file]", assertIs<ToolResult.Success>(result).text)
    }

    @Test
    @DisplayName("A file without a trailing newline says so in the end-of-file footer")
    fun missingTrailingNewlineIsCalledOut() {
        writeFile("notes.txt", "alpha\nbeta")

        val result = read("notes.txt")

        assertEquals("alpha\nbeta\n[end of file — no trailing newline]", assertIs<ToolResult.Success>(result).text)
    }

    @Test
    @DisplayName("An empty file reads as an explicit note, not a bare empty string")
    fun emptyFileReadsAsExplicitNote() {
        writeFile("empty.txt", "")

        val result = read("empty.txt")

        assertEquals("[file is empty]", assertIs<ToolResult.Success>(result).text)
    }

    @Test
    @DisplayName("CRLF line endings pass through verbatim")
    fun crlfContentPassesThroughVerbatim() {
        writeFile("dos.txt", "alpha\r\nbeta\r\n")

        val result = read("dos.txt")

        assertEquals("alpha\r\nbeta\r\n[end of file]", assertIs<ToolResult.Success>(result).text)
    }

    @Test
    @DisplayName("Hostile path characters — spaces, quotes, dollar, parens — read fine")
    fun hostilePathCharactersWork() {
        val name = "a b'c\$(x).txt"
        writeFile(name, "survived\n")

        val result = read(name)

        assertEquals("survived\n[end of file]", assertIs<ToolResult.Success>(result).text)
    }

    // ─── Errors ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Invalid arguments — relative path, non-positive offset or limit — never reach exec")
    fun invalidArgumentsAreRejectedBeforeExec() = runBlocking {
        val environment = FixedResultEnvironment(completed())

        val pathError = ReadFileTool(tracker).execute(ReadFileArgs("src/Foo.kt", offset = 1, limit = 10), environment)
        val offsetError = ReadFileTool(tracker).execute(ReadFileArgs("/notes.txt", offset = 0, limit = 10), environment)
        val limitError = ReadFileTool(tracker).execute(ReadFileArgs("/notes.txt", offset = 1, limit = 0), environment)

        assertContains(assertIs<ToolResult.Error>(pathError).message, "absolute")
        assertContains(assertIs<ToolResult.Error>(offsetError).message, "offset")
        assertContains(assertIs<ToolResult.Error>(limitError).message, "limit")
        assertNull(environment.lastCommand, "rejected arguments must never reach exec")
    }

    @Test
    @DisplayName("A missing file is an error naming the path")
    fun missingFileIsErrorNamingPath() {
        val result = read("does-not-exist.txt")

        assertContains(assertIs<ToolResult.Error>(result).message, "does-not-exist.txt")
    }

    @Test
    @DisplayName("A directory path is an error naming the path")
    fun directoryIsErrorNamingPath() {
        tempDir.resolve("subdir").createDirectories()

        val result = read("subdir")

        assertContains(assertIs<ToolResult.Error>(result).message, "subdir")
    }

    @Test
    @DisplayName("An offset past the end of the file is an error naming the offset and path")
    fun offsetPastEndOfFileIsError() {
        writeNumberedLines("short.txt", lines = 3)

        val result = read("short.txt", offset = 4)

        val error = assertIs<ToolResult.Error>(result)
        assertContains(error.message, "offset 4")
        assertContains(error.message, "short.txt")
    }

    // ─── Read tracking ────────────────────────────────────────────────

    @Test
    @DisplayName("Successful reads — full, partial window, empty file — mark the path read")
    fun successfulReadsMarkThePathRead() {
        writeFile("full.txt", "alpha\n")
        writeNumberedLines("long.txt", lines = 9)
        writeFile("empty.txt", "")

        read("full.txt")
        read("long.txt", offset = 3, limit = 2)
        read("empty.txt")

        assertTrue(tracker.wasRead(tempDir.resolve("full.txt").toString()))
        assertTrue(tracker.wasRead(tempDir.resolve("long.txt").toString()))
        assertTrue(tracker.wasRead(tempDir.resolve("empty.txt").toString()))
    }

    @Test
    @DisplayName("Failed reads — missing file, offset past the end, relative path — do not mark the path read")
    fun failedReadsDoNotMarkThePathRead() = runBlocking {
        writeNumberedLines("short.txt", lines = 3)

        read("does-not-exist.txt")
        read("short.txt", offset = 7)
        ReadFileTool(tracker).execute(
            ReadFileArgs("relative.txt", offset = 1, limit = 10),
            FixedResultEnvironment(completed()),
        )

        assertFalse(tracker.wasRead(tempDir.resolve("does-not-exist.txt").toString()))
        assertFalse(tracker.wasRead(tempDir.resolve("short.txt").toString()))
        assertFalse(tracker.wasRead("relative.txt"))
    }

    // ─── Range semantics ──────────────────────────────────────────────

    @Test
    @DisplayName("offset and limit select exactly that window of lines")
    fun offsetAndLimitSelectExactWindow() {
        writeNumberedLines("nine.txt", lines = 9)

        val result = read("nine.txt", offset = 3, limit = 2)

        val success = assertIs<ToolResult.Success>(result)
        assertTrue(success.text.startsWith("3\n4\n["), "expected lines 3-4 then the footer, got: ${success.text}")
        assertContains(success.text, "offset=5")
    }

    @Test
    @DisplayName("A window ending exactly at the last line reads as complete")
    fun windowEndingAtLastLineIsComplete() {
        writeNumberedLines("nine.txt", lines = 9)

        val result = read("nine.txt", offset = 8, limit = 2)

        assertEquals("8\n9\n[end of file]", assertIs<ToolResult.Success>(result).text)
    }

    @Test
    @DisplayName("A limit above the per-call cap is clamped, with intact content and the next offset")
    fun limitAboveCapIsClampedWithNextOffset() {
        writeNumberedLines("over.txt", lines = ReadFileTool.MAX_WINDOW_LINES + 1)

        val result = read("over.txt", limit = ReadFileTool.MAX_WINDOW_LINES * 10)

        val success = assertIs<ToolResult.Success>(result)
        val expectedContent = (1..ReadFileTool.MAX_WINDOW_LINES).joinToString(separator = "\n", postfix = "\n")
        assertTrue(success.text.startsWith(expectedContent), "content up to the cap must be intact")
        assertContains(success.text, "read ${ReadFileTool.MAX_WINDOW_LINES} lines")
        assertContains(success.text, "offset=${ReadFileTool.MAX_WINDOW_LINES + 1}")
    }

    // ─── Size cap ─────────────────────────────────────────────────────

    @Test
    @DisplayName("A file over the character cap is cut on a line boundary with a footer naming the next offset")
    fun sizeCapCutsOnLineBoundaryAndNamesNextOffset() {
        val line = "x".repeat(300)
        // The full line window exceeds the content cap, so the character cut fires first.
        writeFile("big.txt", (1..ReadFileTool.MAX_WINDOW_LINES).joinToString(separator = "") { "$line\n" })

        val result = read("big.txt")

        val success = assertIs<ToolResult.Success>(result)
        val content = success.text.substringBefore("[output capped")
        // Independently computed expectation: exactly as many whole lines as fit under the cap.
        val keptLines = ReadFileTool.MAX_CONTENT_CHARS / (line.length + 1)
        assertEquals(keptLines * (line.length + 1), content.length)
        assertTrue(content.endsWith("$line\n"), "the cut must land on a line boundary")
        assertContains(success.text, "offset=${keptLines + 1}")
        assertTrue(success.text.length < ToolRegistry.MAX_RESULT_CHARS, "the registry marker must never fire")
    }

    @Test
    @DisplayName("A single line longer than the cap is cut mid-line and the footer says so")
    fun singleOversizedLineIsCutMidLine() {
        writeFile("one-line.txt", "y".repeat(ReadFileTool.MAX_CONTENT_CHARS + 5000) + "\n")

        val result = read("one-line.txt")

        val success = assertIs<ToolResult.Success>(result)
        val content = success.text.substringBefore("\n[output capped")
        assertEquals(ReadFileTool.MAX_CONTENT_CHARS, content.length)
        assertContains(success.text, "line 1 continues beyond the cap")
    }

    @Test
    @DisplayName("An exec-level stdout capture cap is reported as size-cap truncation, not ignored")
    fun execCaptureCapIsTreatedAsSizeCap() {
        val result = runStubbed(completed(stdout = "partial line\n", stdoutTruncated = true))

        val success = assertIs<ToolResult.Success>(result)
        assertTrue(success.text.startsWith("partial line\n"))
        assertContains(success.text, "output capped")
        assertContains(success.text, "offset=2")
    }

    @Test
    @DisplayName("A capture cap that only cut the sentinel line reports an ordinary continuation, not a cap")
    fun captureCapOnSentinelOnlyRendersFileContinues() {
        val result = runStubbed(
            completed(stdout = "l1\nl2\npartial sent", stdoutTruncated = true),
            args = ReadFileArgs("/file.txt", offset = 1, limit = 2),
        )

        val success = assertIs<ToolResult.Success>(result)
        assertTrue(success.text.startsWith("l1\nl2\n"))
        assertContains(success.text, "file continues; call again with offset=3")
        assertFalse(success.text.contains("output capped"), "a whole window must not claim it was capped")
    }

    // ─── exec invocation ──────────────────────────────────────────────

    @Test
    @DisplayName("The window is one sed range over the verbatim absolute path, under the tool timeout budget")
    fun sedInvocationShape() = runBlocking {
        val environment = FixedResultEnvironment(completed(stdout = "3\n"))

        ReadFileTool(tracker).execute(ReadFileArgs("/workspace/file.txt", offset = 3, limit = 5), environment)

        // Lines 3..8: the window plus one sentinel line to detect a continuing
        // file, quitting at the sentinel so sed never scans to EOF.
        assertEquals(listOf("sed", "-n", "3,8p;8q", "/workspace/file.txt"), environment.lastCommand)
        assertEquals(Budgets.TOOL_TIMEOUT, environment.lastTimeout)
    }

    // ─── Timeout mapping ──────────────────────────────────────────────

    @Test
    @DisplayName("An exec timeout maps to TimedOut carrying the partial content")
    fun execTimeoutMapsToTimedOutWithPartialOutput() {
        val result = runStubbed(
            ExecResult.TimedOut(stdout = "first\n", stderr = "", stdoutTruncated = false, stderrTruncated = false),
        )

        assertEquals("first\n", assertIs<ToolResult.TimedOut>(result).partialOutput)
    }

    @Test
    @DisplayName("An exec timeout with no captured output carries no partial output")
    fun execTimeoutWithoutOutputHasNoPartialOutput() {
        val result = runStubbed(
            ExecResult.TimedOut(stdout = "", stderr = "", stdoutTruncated = false, stderrTruncated = false),
        )

        assertNull(assertIs<ToolResult.TimedOut>(result).partialOutput)
    }
}
