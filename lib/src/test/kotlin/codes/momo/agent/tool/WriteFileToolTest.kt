package codes.momo.agent.tool

import codes.momo.agent.Budgets
import codes.momo.agent.environment.ExecResult
import codes.momo.agent.environment.LocalExecutionEnvironment
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WriteFileToolTest {

    @TempDir
    lateinit var tempDir: Path

    // ─── Helpers ──────────────────────────────────────────────────────

    /** Writes [content] to [path], resolved under the temp workspace, against a real local environment. */
    private fun write(path: String, content: String): ToolResult = runBlocking {
        WriteFileTool().execute(
            WriteFileArgs(tempDir.resolve(path).toString(), content),
            LocalExecutionEnvironment(tempDir),
        )
    }

    private fun runStubbed(execResult: ExecResult): ToolResult = runBlocking {
        WriteFileTool().execute(WriteFileArgs("/file.txt", "content"), FixedResultEnvironment(execResult))
    }

    private fun assertRoundTrips(content: String, path: String = "file.txt") {
        val result = write(path, content)

        assertIs<ToolResult.Success>(result, "write failed: ${result.text}")
        assertEquals(content, catBack(tempDir, path))
    }

    // ─── Definition ───────────────────────────────────────────────────

    @Test
    @DisplayName("The definition documents create semantics, parent creation, and edit_file steering")
    fun definitionDocumentsTheContract() {
        val definition = WriteFileTool().definition

        assertEquals("write_file", definition.name)
        val description = assertNotNull(definition.description)
        assertContains(description, "Creates")
        assertContains(description, "ABSOLUTE path")
        assertContains(description, "parent directories")
        assertContains(description, "edit_file")
    }

    @Test
    @DisplayName("The parameters schema requires path and content as string properties")
    fun parametersSchemaShape() {
        val schema = assertNotNull(WriteFileTool().definition.parameters)

        val properties = schema.getValue("properties").jsonObject
        assertEquals("string", properties.getValue("path").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("string", properties.getValue("content").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(
            setOf("path", "content"),
            schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
    }

    // ─── Create-only semantics ────────────────────────────────────────

    @Test
    @DisplayName("Writing a new file reports created and lands the exact content")
    fun createReportsCreatedWithExactContent() {
        val result = write("notes.txt", "alpha\nbeta\n")

        assertEquals("created '${tempDir.resolve("notes.txt")}'", assertIs<ToolResult.Success>(result).text)
        assertEquals("alpha\nbeta\n", catBack(tempDir, "notes.txt"))
    }

    @Test
    @DisplayName("An existing target is refused with steering to edit_file or a bash delete, file untouched")
    fun existingTargetIsRefused() {
        val path = tempDir.resolve("notes.txt")
        path.writeText("precious\n")

        val result = write("notes.txt", "clobbered\n")

        val error = assertIs<ToolResult.Error>(result)
        assertContains(error.message, path.toString())
        assertContains(error.message, "already exists")
        assertContains(error.message, "edit_file")
        assertContains(error.message, "bash")
        assertEquals("precious\n", catBack(tempDir, "notes.txt"))
    }

    @Test
    @DisplayName("Deleting a written file via bash frees the path for a fresh write")
    fun bashDeleteThenWriteSucceeds() = runBlocking {
        write("notes.txt", "first\n")

        val deleted = BashTool().execute(
            BashArgs("rm '${tempDir.resolve("notes.txt")}'"),
            LocalExecutionEnvironment(tempDir),
        )
        assertIs<ToolResult.Success>(deleted, "delete failed: ${deleted.text}")

        val result = write("notes.txt", "second\n")

        assertEquals("created '${tempDir.resolve("notes.txt")}'", assertIs<ToolResult.Success>(result).text)
        assertEquals("second\n", catBack(tempDir, "notes.txt"))
    }

    @Test
    @DisplayName("A dangling symlink target is an error — nothing is written through the link")
    fun danglingSymlinkTargetIsError() {
        val target = tempDir.resolve("missing-target.txt")
        Files.createSymbolicLink(tempDir.resolve("link.txt"), target)

        val result = write("link.txt", "smuggled\n")

        assertIs<ToolResult.Error>(result)
        assertFalse(Files.exists(target), "content must not land at the link's target")
    }

    // ─── Content robustness ───────────────────────────────────────────

    @Test
    @DisplayName("Shell-hostile, control-character, and multibyte content round-trips byte-identically")
    fun hostileContentRoundTrips() {
        assertRoundTrips(
            "it's \"quoted\" `backticked` \$(subbed) \${braced} \\back\\slash %s %d\n" +
                "bell\u0007 escape\u001b[31m tab\there cr\r\u0001\u0002 émoji 🚀 ünïcødé\n",
        )
    }

    @Test
    @DisplayName("Newlines round-trip exactly, whether the content ends without one or with several")
    fun trailingNewlineVariantsRoundTrip() {
        assertRoundTrips("first\nsecond, no trailing newline", path = "none.txt")
        assertRoundTrips("several trailing\n\n\n", path = "several.txt")
    }

    @Test
    @DisplayName("Empty content creates an empty file")
    fun emptyContentCreatesEmptyFile() {
        val result = write("empty.txt", "")

        assertIs<ToolResult.Success>(result)
        assertEquals("", catBack(tempDir, "empty.txt"))
    }

    @Test
    @DisplayName("Multi-MiB content round-trips byte-identically")
    fun largeContentRoundTrips() {
        val content = buildString { repeat(100_000) { append("line $it with 'some' \"hostile\" \$parts\n") } }
        assertTrue(content.length > 3 * 1024 * 1024, "test content must be a few MiB")

        assertRoundTrips(content, path = "large.txt")
    }

    // ─── Hostile paths & parent creation ──────────────────────────────

    @Test
    @DisplayName("Hostile path characters — quotes, dollar, newline, globs, dashes — are taken literally")
    fun hostilePathCharactersWork() {
        val name = "-dash dir/a b'c\"d\$(x)*?[abc]\ne.txt"

        val result = write(name, "survived\n")

        assertIs<ToolResult.Success>(result, "write failed: ${result.text}")
        assertEquals("survived\n", catBack(tempDir, name))
    }

    @Test
    @DisplayName("Missing parent directories are created automatically, several levels deep")
    fun parentDirectoriesAreCreated() {
        val result = write("a/b/c/d/notes.txt", "nested\n")

        assertIs<ToolResult.Success>(result, "write failed: ${result.text}")
        assertEquals("nested\n", catBack(tempDir, "a/b/c/d/notes.txt"))
    }

    // ─── Errors ───────────────────────────────────────────────────────

    @Test
    @DisplayName("A directory target is an error naming the path and the problem")
    fun directoryTargetIsError() {
        tempDir.resolve("subdir").createDirectories()

        val result = write("subdir", "content\n")

        val error = assertIs<ToolResult.Error>(result)
        assertContains(error.message, tempDir.resolve("subdir").toString())
        assertContains(error.message, "directory")
    }

    @Test
    @DisplayName("A write into an unwritable directory is a permission error naming the path")
    fun permissionDeniedIsError() {
        val locked = tempDir.resolve("locked").createDirectories()
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            assumeFalse(Files.isWritable(locked), "running as root — permission checks do not apply")

            val result = write("locked/notes.txt", "content\n")

            val error = assertIs<ToolResult.Error>(result)
            assertContains(error.message, tempDir.resolve("locked/notes.txt").toString())
            assertContains(error.message, "permission denied", ignoreCase = true)
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    @DisplayName("A failure without stderr falls back to naming the exit code")
    fun failureWithoutStderrNamesExitCode() {
        val result = runStubbed(completed(exitCode = 1))

        val error = assertIs<ToolResult.Error>(result)
        assertContains(error.message, "cannot write '/file.txt'")
        assertContains(error.message, "exited with code 1")
    }

    @Test
    @DisplayName("Invalid paths — relative or trailing-slash — are rejected before any exec")
    fun invalidPathsAreRejectedBeforeExec() = runBlocking {
        val environment = FixedResultEnvironment(completed())

        val relativeError = WriteFileTool().execute(WriteFileArgs("src/Foo.kt", "content"), environment)
        val slashError = WriteFileTool().execute(WriteFileArgs("/workspace/dir/", "content"), environment)

        assertContains(assertIs<ToolResult.Error>(relativeError).message, "absolute")
        assertContains(assertIs<ToolResult.Error>(slashError).message, "end with '/'")
        assertNull(environment.lastCommand, "rejected arguments must never reach exec")
    }

    // ─── exec invocation ──────────────────────────────────────────────

    @Test
    @DisplayName("One bash -c call: fixed script, path as argument, content as stdin, under the budget")
    fun execInvocationShape() = runBlocking {
        val environment = FixedResultEnvironment(completed())
        val content = "hostile 'content' \$(x)\n"

        WriteFileTool().execute(WriteFileArgs("/workspace/file.txt", content), environment)

        val command = assertNotNull(environment.lastCommand)
        assertEquals(5, command.size)
        assertEquals(listOf("bash", "-c"), command.take(2))
        assertFalse(command[2].contains("/workspace"), "the path must never be interpolated into the script")
        assertEquals("write_file", command[3])
        assertEquals("/workspace/file.txt", command[4])
        assertContentEquals(content.toByteArray(), environment.lastStdin)
        assertEquals(Budgets.TOOL_TIMEOUT, environment.lastTimeout)
    }

    // ─── Timeout mapping ──────────────────────────────────────────────

    @Test
    @DisplayName("An exec timeout maps to TimedOut with no partial output, even when stdout was captured")
    fun execTimeoutCarriesNoPartialOutput() {
        val result = runStubbed(
            ExecResult.TimedOut(stdout = "stray\n", stderr = "", stdoutTruncated = false, stderrTruncated = false),
        )

        assertNull(assertIs<ToolResult.TimedOut>(result).partialOutput)
    }
}
