package codes.momo.agent.tool

import codes.momo.agent.Budgets
import codes.momo.agent.environment.ExecResult
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.environment.LocalExecutionEnvironment
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class BashToolTest {

    @TempDir
    lateinit var tempDir: Path

    // ─── Helpers ──────────────────────────────────────────────────────

    /** Runs [command] through the tool against a real local environment over the temp workspace. */
    private fun run(command: String): ToolResult = runBlocking {
        BashTool().execute(BashArgs(command), LocalExecutionEnvironment(tempDir))
    }

    private fun runStubbed(execResult: ExecResult): ToolResult = runBlocking {
        BashTool().execute(BashArgs("true"), FixedResultEnvironment(execResult))
    }

    private fun completed(
        exitCode: Int = 0,
        stdout: String = "",
        stderr: String = "",
        stdoutTruncated: Boolean = false,
        stderrTruncated: Boolean = false,
    ): ExecResult.Completed = ExecResult.Completed(exitCode, stdout, stderr, stdoutTruncated, stderrTruncated)

    // ─── Definition ───────────────────────────────────────────────────

    @Test
    @DisplayName("The definition is named bash and documents file search, the timeout, and the output bound")
    fun definitionDocumentsTheContract() {
        val definition = BashTool().definition

        assertEquals("bash", definition.name)
        val description = assertNotNull(definition.description)
        assertContains(description, "grep")
        assertContains(description, Budgets.TOOL_TIMEOUT.toString())
        assertContains(description, ToolRegistry.MAX_RESULT_CHARS.toString())
    }

    @Test
    @DisplayName("The parameters schema declares command as a required, described string property")
    fun parametersSchemaDeclaresCommand() {
        val schema = assertNotNull(BashTool().definition.parameters)

        val command = schema.getValue("properties").jsonObject.getValue("command").jsonObject
        assertEquals("string", command.getValue("type").jsonPrimitive.content)
        assertEquals("The bash command to run.", command.getValue("description").jsonPrimitive.content)
        assertEquals(listOf("command"), schema.getValue("required").jsonArray.map { it.jsonPrimitive.content })
    }

    // ─── Completed commands ───────────────────────────────────────────

    @Test
    @DisplayName("stdout, stderr, and the exit code round-trip into one labeled result")
    fun outputRoundTripsIntoLabeledResult() {
        val result = run("echo out; echo err >&2")

        val success = assertIs<ToolResult.Success>(result)
        assertEquals("exit code: 0\nstderr:\nerr\nstdout:\nout\n", success.text)
    }

    @Test
    @DisplayName("A non-zero exit is a normal Success carrying the exit code, not an Error")
    fun nonZeroExitIsSuccessNotError() {
        val result = run("echo broken >&2; exit 3")

        val success = assertIs<ToolResult.Success>(result)
        assertEquals("exit code: 3\nstderr:\nbroken\nstdout: (empty)\n", success.text)
    }

    @Test
    @DisplayName("Output without a trailing newline still puts the next section header on its own line")
    fun missingTrailingNewlineIsBridged() {
        val result = run("printf no-newline")

        val success = assertIs<ToolResult.Success>(result)
        assertEquals("exit code: 0\nstderr: (empty)\nstdout:\nno-newline\n", success.text)
    }

    @Test
    @DisplayName("Commands run with the workspace root as the working directory")
    fun commandsRunInWorkspaceRoot() {
        val result = run("pwd")

        // Compare real paths: temp dirs can live behind symlinks (e.g. /tmp on macOS).
        assertEquals(
            "exit code: 0\nstderr: (empty)\nstdout:\n${tempDir.toRealPath()}\n",
            assertIs<ToolResult.Success>(result).text,
        )
    }

    // ─── exec invocation ──────────────────────────────────────────────

    @Test
    @DisplayName("The command is run as bash -c under the tool timeout budget")
    fun commandRunsAsBashDashCWithBudget() = runBlocking {
        val environment = FixedResultEnvironment(completed())

        BashTool().execute(BashArgs("echo hi"), environment)

        assertEquals(listOf("bash", "-c", "echo hi"), environment.lastCommand)
        assertEquals(Budgets.TOOL_TIMEOUT, environment.lastTimeout)
    }

    // ─── Capture-cap flags ────────────────────────────────────────────

    @Test
    @DisplayName("A stdout stream that hit the exec capture cap is flagged in its section header")
    fun stdoutCaptureCapFlagSurfacesInSectionHeader() {
        val result = runStubbed(completed(stdout = "x\n", stdoutTruncated = true))

        val success = assertIs<ToolResult.Success>(result)
        assertEquals("exit code: 0\nstderr: (empty)\nstdout (truncated):\nx\n", success.text)
    }

    @Test
    @DisplayName("A stderr stream that hit the exec capture cap is flagged in its leading section header")
    fun stderrCaptureCapFlagSurfacesInSectionHeader() {
        val result = runStubbed(completed(stderr = "boom\n", stderrTruncated = true))

        val success = assertIs<ToolResult.Success>(result)
        assertEquals("exit code: 0\nstderr (truncated):\nboom\nstdout: (empty)\n", success.text)
    }

    // ─── Dispatch truncation ──────────────────────────────────────────

    @Test
    @DisplayName("Oversized real output dispatched through the registry is truncated with the marker")
    fun oversizedOutputIsTruncatedByDispatch() = runBlocking {
        val registry = ToolRegistry(listOf(BashTool()))
        val arguments = buildJsonObject {
            put("command", "head -c ${ToolRegistry.MAX_RESULT_CHARS + 1} /dev/zero | tr '\\0' x")
        }

        val result = registry.execute("bash", arguments, LocalExecutionEnvironment(tempDir))

        val success = assertIs<ToolResult.Success>(result)
        assertTrue(
            success.text.endsWith(ToolRegistry.TRUNCATION_MARKER),
            "expected the registry's truncation marker, text ends with: ${success.text.takeLast(80)}",
        )
        assertEquals(ToolRegistry.MAX_RESULT_CHARS + ToolRegistry.TRUNCATION_MARKER.length, success.text.length)
    }

    // ─── Timeout mapping ──────────────────────────────────────────────

    @Test
    @DisplayName("An exec timeout maps to TimedOut carrying the labeled partial output")
    fun execTimeoutMapsToTimedOutWithPartialOutput() {
        val result = runStubbed(
            ExecResult.TimedOut(
                stdout = "started\n",
                stderr = "warming up\n",
                stdoutTruncated = false,
                stderrTruncated = false,
            ),
        )

        val timedOut = assertIs<ToolResult.TimedOut>(result)
        assertEquals("stderr:\nwarming up\nstdout:\nstarted\n", timedOut.partialOutput)
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

// ─── Test environment ─────────────────────────────────────────────────

/** Returns [result] for every exec, recording how it was called. */
private class FixedResultEnvironment(private val result: ExecResult) : ExecutionEnvironment {

    var lastCommand: List<String>? = null
        private set

    var lastTimeout: Duration? = null
        private set

    override suspend fun exec(command: List<String>, stdin: ByteArray?, timeout: Duration): ExecResult {
        lastCommand = command
        lastTimeout = timeout
        return result
    }

    override fun close(): Unit = Unit
}
