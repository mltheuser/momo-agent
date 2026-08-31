package codes.momo.agent.tool

import codes.momo.agent.Budgets
import codes.momo.agent.environment.ExecResult
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.environment.Privilege
import codes.momo.agent.environment.completed
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BashToolTest {

    @TempDir
    lateinit var tempDir: Path

    // ─── Helpers ──────────────────────────────────────────────────────

    /** The tool under test, told the temp workspace is where its commands run. */
    private fun bashTool(): BashTool = BashTool(tempDir.toString(), Privilege.UNPRIVILEGED)

    /** The description a tool over [privilege] hands the model. */
    private fun descriptionFor(privilege: Privilege): String =
        assertNotNull(BashTool("/some/workspace", privilege).definition.description)

    /** Runs [command] through the tool against a real environment over the temp workspace. */
    private fun run(command: String): ToolResult = runBlocking {
        bashTool().execute(BashArgs(command), ExecutionEnvironment(tempDir))
    }

    private fun runStubbed(execResult: ExecResult): ToolResult = runBlocking {
        bashTool().execute(BashArgs("true"), FixedResultRunner(execResult).environment(tempDir))
    }

    // ─── Definition ───────────────────────────────────────────────────

    @Test
    @DisplayName("The definition is named bash and documents output filtering, the timeout, and the output bound")
    fun definitionDocumentsTheContract() {
        val definition = bashTool().definition

        assertEquals("bash", definition.name)
        val description = assertNotNull(definition.description)
        assertContains(description, "grep")
        assertContains(description, Budgets.TOOL_TIMEOUT.toString())
        assertContains(description, ToolRegistry.MAX_RESULT_CHARS.toString())
    }

    @Test
    @DisplayName("The definition states the workspace root commands run from")
    fun definitionStatesTheWorkingDirectory() {
        val description = descriptionFor(Privilege.UNPRIVILEGED)

        assertContains(description, "/some/workspace")
        assertContains(description, "workspace root")
    }

    // ─── Privilege wording ────────────────────────────────────────────

    // One distinctive phrase per state, so no state's paragraph can be
    // swapped for another's — the wording itself is free to be iterated on.

    @Test
    @DisplayName("A root environment's description says commands already run as root")
    fun rootDescriptionStatesCommandsAreAlreadyRoot() {
        assertContains(descriptionFor(Privilege.ROOT), "already run as root")
    }

    @Test
    @DisplayName("A passwordless-sudo environment's description tells the model to prefix a command with sudo")
    fun passwordlessSudoDescriptionInstructsPrefixingSudo() {
        assertContains(descriptionFor(Privilege.PASSWORDLESS_SUDO), SUDO_INSTRUCTION)
    }

    @Test
    @DisplayName("An unprivileged environment's description assumes no way up")
    fun unprivilegedDescriptionAssumesNoWayUp() {
        assertContains(descriptionFor(Privilege.UNPRIVILEGED), "unprivileged user with no way up")
    }

    @Test
    @DisplayName("Only the passwordless-sudo description instructs the model to use sudo")
    fun onlyPasswordlessSudoInstructsUsingSudo() {
        // The other two mention sudo to warn against it, so the pin is the
        // affirmative instruction, not the word.
        listOf(Privilege.ROOT, Privilege.UNPRIVILEGED).forEach { privilege ->
            val description = descriptionFor(privilege)
            assertFalse(
                SUDO_INSTRUCTION in description,
                "$privilege must not instruct the model to elevate: $description",
            )
        }
    }

    @Test
    @DisplayName("The parameters schema declares command as a required, described string property")
    fun parametersSchemaDeclaresCommand() {
        val schema = assertNotNull(bashTool().definition.parameters)

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
        val runner = FixedResultRunner(completed())

        bashTool().execute(BashArgs("echo hi"), runner.environment(tempDir))

        assertEquals(listOf("bash", "-c", "echo hi"), runner.lastCommand)
        assertEquals(Budgets.TOOL_TIMEOUT, runner.lastTimeout)
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
        val marker = ToolRegistry.truncationMarker(ToolRegistry.MAX_RESULT_CHARS)
        val registry = ToolRegistry(listOf(bashTool()))
        val arguments = buildJsonObject {
            put("command", "head -c ${ToolRegistry.MAX_RESULT_CHARS + 1} /dev/zero | tr '\\0' x")
        }

        val result = registry.execute("bash", arguments, ExecutionEnvironment(tempDir)).result

        val success = assertIs<ToolResult.Success>(result)
        assertTrue(
            success.text.endsWith(marker),
            "expected the registry's truncation marker, text ends with: ${success.text.takeLast(80)}",
        )
        assertEquals(ToolRegistry.MAX_RESULT_CHARS + marker.length, success.text.length)
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

    private companion object {

        /** The one affirmative "use sudo" instruction, which only the passwordless variant may carry. */
        const val SUDO_INSTRUCTION = "prefix the command with `sudo`"
    }
}
