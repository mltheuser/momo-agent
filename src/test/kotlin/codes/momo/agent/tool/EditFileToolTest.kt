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
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EditFileToolTest {

    @TempDir
    lateinit var tempDir: Path

    private val tracker = FileReadTracker()

    // ─── Helpers ──────────────────────────────────────────────────────

    /** Edits [path], resolved under the temp workspace, against a real local environment. */
    private fun edit(path: String, oldString: String, newString: String): ToolResult = runBlocking {
        EditFileTool(tracker).execute(
            EditFileArgs(tempDir.resolve(path).toString(), oldString, newString),
            LocalExecutionEnvironment(tempDir),
        )
    }

    /** Creates [path] with [content] and marks it read, as a prior read_file call would have. */
    private fun writeTracked(path: String, content: String): Path =
        tempDir.resolve(path).apply {
            writeText(content)
            tracker.markRead(toString())
        }

    /** Runs an edit of "old" → "new" on /file.txt — pre-marked as read — against the given exec outcomes, in order. */
    private fun runStubbed(vararg execResults: ExecResult): ToolResult = runBlocking {
        tracker.markRead("/file.txt")
        EditFileTool(tracker).execute(
            EditFileArgs("/file.txt", "old", "new"),
            FixedResultEnvironment(*execResults),
        )
    }

    // ─── Definition ───────────────────────────────────────────────────

    @Test
    @DisplayName("The definition documents exact single-occurrence matching, read-first, and the write_file steering")
    fun definitionDocumentsTheContract() {
        val definition = EditFileTool(tracker).definition

        assertEquals("edit_file", definition.name)
        val description = assertNotNull(definition.description)
        assertContains(description, "whitespace")
        assertContains(description, "exactly once")
        assertContains(description, "read_file")
        assertContains(description, "text files")
        assertContains(description, "write_file")
    }

    @Test
    @DisplayName("The parameters schema requires path, old_string, and new_string as string properties")
    fun parametersSchemaShape() {
        val schema = assertNotNull(EditFileTool(tracker).definition.parameters)

        val properties = schema.getValue("properties").jsonObject
        for (property in listOf("path", "old_string", "new_string")) {
            assertEquals("string", properties.getValue(property).jsonObject.getValue("type").jsonPrimitive.content)
        }
        assertEquals(
            setOf("path", "old_string", "new_string"),
            schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
    }

    // ─── Replacement ──────────────────────────────────────────────────

    @Test
    @DisplayName("A unique match is replaced and the result confirms the edit")
    fun uniqueMatchIsReplaced() {
        val path = writeTracked("notes.txt", "alpha\nbeta\ngamma\n")

        val result = edit("notes.txt", "beta", "BETA")

        assertEquals("edited '$path'", assertIs<ToolResult.Success>(result).text)
        assertEquals("alpha\nBETA\ngamma\n", catBack(tempDir, "notes.txt"))
    }

    @Test
    @DisplayName("An empty new_string deletes the matched span")
    fun emptyNewStringDeletesTheSpan() {
        writeTracked("notes.txt", "alpha\nbeta\ngamma\n")

        val result = edit("notes.txt", "beta\n", "")

        assertIs<ToolResult.Success>(result, "edit failed: ${result.text}")
        assertEquals("alpha\ngamma\n", catBack(tempDir, "notes.txt"))
    }

    @Test
    @DisplayName("An old_string spanning the whole file replaces all of it")
    fun wholeFileSpanIsReplaced() {
        writeTracked("notes.txt", "the whole old body\n")

        val result = edit("notes.txt", "the whole old body\n", "a fresh body\n")

        assertIs<ToolResult.Success>(result, "edit failed: ${result.text}")
        assertEquals("a fresh body\n", catBack(tempDir, "notes.txt"))
    }

    @Test
    @DisplayName("Hostile strings in a hostile path land byte-exactly amid multibyte content, no trailing newline")
    fun hostileStringsReplaceByteExactly() {
        val name = "it's a \"hostile\" file.txt"
        val old = "it's \"quoted\" `cmd` \$(sub) \$1 \\back\\slash *?[abc]"
        val new = "now \$2 \"double\" 'single' \\\\doubled\\\\ ;&|<> %s"
        writeTracked(name, "émoji 🚀 前文\n" + old + "\nünïcødé 🎯 後文, no trailing newline")

        val result = edit(name, old, new)

        assertIs<ToolResult.Success>(result, "edit failed: ${result.text}")
        assertEquals("émoji 🚀 前文\n" + new + "\nünïcødé 🎯 後文, no trailing newline", catBack(tempDir, name))
    }

    // ─── Match errors ─────────────────────────────────────────────────

    @Test
    @DisplayName("A missing match is an error saying the string was not found, and the file is unchanged")
    fun missingMatchIsError() {
        writeTracked("notes.txt", "alpha\n    indented beta\n")

        // The trailing space breaks the match: matching must be exact.
        val result = edit("notes.txt", "indented beta ", "gamma")

        val error = assertIs<ToolResult.Error>(result)
        assertContains(error.message, "not found")
        assertContains(error.message, "exact")
        assertEquals("alpha\n    indented beta\n", catBack(tempDir, "notes.txt"))
    }

    @Test
    @DisplayName("Multiple matches are an error naming the count, and the file is unchanged")
    fun ambiguousMatchIsError() {
        writeTracked("notes.txt", "beta\nbeta\n")

        val result = edit("notes.txt", "beta", "gamma")

        val error = assertIs<ToolResult.Error>(result)
        assertContains(error.message, "2 times")
        assertContains(error.message, "unique")
        assertEquals("beta\nbeta\n", catBack(tempDir, "notes.txt"))
    }

    @Test
    @DisplayName("Overlapping occurrences count as ambiguous")
    fun overlappingOccurrencesAreAmbiguous() {
        writeTracked("notes.txt", "aaa")

        val result = edit("notes.txt", "aa", "b")

        assertContains(assertIs<ToolResult.Error>(result).message, "2 times")
        assertEquals("aaa", catBack(tempDir, "notes.txt"))
    }

    // ─── Pre-exec validation ──────────────────────────────────────────

    @Test
    @DisplayName("Invalid arguments — relative path, empty old_string, identical strings — never reach exec")
    fun invalidArgumentsAreRejectedBeforeExec() = runBlocking {
        val environment = FixedResultEnvironment(completed())
        val tool = EditFileTool(tracker)
        tracker.markRead("/file.txt")

        val pathError = tool.execute(EditFileArgs("src/Foo.kt", "old", "new"), environment)
        val emptyError = tool.execute(EditFileArgs("/file.txt", "", "new"), environment)
        val identicalError = tool.execute(EditFileArgs("/file.txt", "same", "same"), environment)

        assertContains(assertIs<ToolResult.Error>(pathError).message, "absolute")
        assertContains(assertIs<ToolResult.Error>(emptyError).message, "write_file")
        assertContains(assertIs<ToolResult.Error>(identicalError).message, "identical")
        assertNull(environment.lastCommand, "rejected arguments must never reach exec")
    }

    @Test
    @DisplayName("Editing a file not read this session fails with the read-first guardrail before any exec")
    fun unreadFileIsGuardedBeforeExec() = runBlocking {
        val environment = FixedResultEnvironment(completed())

        val result = EditFileTool(tracker).execute(EditFileArgs("/unseen.txt", "old", "new"), environment)

        val error = assertIs<ToolResult.Error>(result)
        assertContains(error.message, "/unseen.txt")
        assertContains(error.message, "read_file")
        assertNull(environment.lastCommand, "a guarded edit must never reach exec")
    }

    // ─── Read & write-back errors ─────────────────────────────────────

    @Test
    @DisplayName("A missing file is a read error naming the path")
    fun missingFileIsError() {
        tracker.markRead(tempDir.resolve("gone.txt").toString())

        val result = edit("gone.txt", "old", "new")

        val error = assertIs<ToolResult.Error>(result)
        assertContains(error.message, "cannot read")
        assertContains(error.message, "gone.txt")
    }

    @Test
    @DisplayName("A read that hit the capture cap refuses the edit as too large instead of corrupting the file")
    fun truncatedReadRefusesEdit() {
        val result = runStubbed(completed(stdout = "old content, cut off", stdoutTruncated = true))

        assertContains(assertIs<ToolResult.Error>(result).message, "too large")
    }

    @Test
    @DisplayName("A failed write-back is an error carrying the write diagnostics")
    fun writeBackFailureIsError() {
        val result = runStubbed(
            completed(stdout = "keep old text\n"),
            completed(exitCode = 1, stderr = "disk full"),
        )

        val error = assertIs<ToolResult.Error>(result)
        assertContains(error.message, "cannot write '/file.txt'")
        assertContains(error.message, "disk full")
    }

    // ─── exec invocation ──────────────────────────────────────────────

    @Test
    @DisplayName("The read is one plain cat over the verbatim absolute path, under the tool timeout budget")
    fun catInvocationShape() = runBlocking {
        tracker.markRead("/workspace/file.txt")
        val environment = FixedResultEnvironment(completed(exitCode = 1))

        EditFileTool(tracker).execute(EditFileArgs("/workspace/file.txt", "old", "new"), environment)

        assertEquals(listOf("cat", "/workspace/file.txt"), environment.lastCommand)
        assertNull(environment.lastStdin)
        assertEquals(Budgets.TOOL_TIMEOUT, environment.lastTimeout)
    }

    @Test
    @DisplayName("The write-back is one bash -c call: fixed script, path as argument, full new content as stdin")
    fun writeBackInvocationShape() = runBlocking {
        tracker.markRead("/workspace/file.txt")
        val environment = FixedResultEnvironment(completed(stdout = "keep old value\n"), completed())

        EditFileTool(tracker).execute(EditFileArgs("/workspace/file.txt", "old", "new"), environment)

        val command = assertNotNull(environment.lastCommand)
        assertEquals(5, command.size)
        assertEquals(listOf("bash", "-c"), command.take(2))
        assertFalse(command[2].contains("/workspace"), "the path must never be interpolated into the script")
        assertEquals("edit_file", command[3])
        assertEquals("/workspace/file.txt", command[4])
        assertContentEquals("keep new value\n".toByteArray(), environment.lastStdin)
        assertEquals(Budgets.TOOL_TIMEOUT, environment.lastTimeout)
        assertEquals(2, environment.callCount, "an edit is exactly one read exec and one write-back exec")
    }

    // ─── Timeout mapping ──────────────────────────────────────────────

    @Test
    @DisplayName("A timeout while reading the file maps to TimedOut with no partial output")
    fun readTimeoutCarriesNoPartialOutput() {
        val result = runStubbed(
            ExecResult.TimedOut(stdout = "partial\n", stderr = "", stdoutTruncated = false, stderrTruncated = false),
        )

        assertNull(assertIs<ToolResult.TimedOut>(result).partialOutput)
    }

    // ─── write → read → edit round trip ───────────────────────────────

    @Test
    @DisplayName("Hostile content survives a full write_file → read_file → edit_file round trip byte-exactly")
    fun hostileWriteReadEditRoundTrip() = runBlocking {
        val environment = LocalExecutionEnvironment(tempDir)
        val path = tempDir.resolve("hostile.txt").toString()
        val firstLine = "line 'one' \$(sub) `tick` \n"
        val targetLine = "target \"two\" \\back\\ \$1 *glob?\n"
        val lastLine = "émoji 🚀 line three ;&|<>\n"

        val written = WriteFileTool(tracker).execute(
            WriteFileArgs(path, firstLine + targetLine + lastLine),
            environment,
        )
        assertIs<ToolResult.Success>(written, "write failed: ${written.text}")

        val read = ReadFileTool(tracker).execute(
            ReadFileArgs(path, offset = 1, limit = ReadFileTool.MAX_WINDOW_LINES),
            environment,
        )
        val readText = assertIs<ToolResult.Success>(read, "read failed: ${read.text}").text
        val lifted = readText.removeSuffix("[end of file]")
        assertEquals(firstLine + targetLine + lastLine, lifted, "read_file must return the exact bytes written")

        // old_string lifted from the read output, exactly as a model would copy-paste it.
        val oldString = lifted.lines()[1]
        assertEquals(targetLine.removeSuffix("\n"), oldString)
        val newString = "replaced \"two\" \\\\forward\\\\ \$2 🎯 ;;"
        val edited = EditFileTool(tracker).execute(EditFileArgs(path, oldString, newString), environment)

        assertEquals("edited '$path'", assertIs<ToolResult.Success>(edited).text)
        assertEquals(firstLine + newString + "\n" + lastLine, catBack(tempDir, "hostile.txt"))
    }
}
