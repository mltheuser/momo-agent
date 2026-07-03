package codes.momo.agent.tool

import codes.momo.agent.Budgets
import codes.momo.agent.environment.ExecResult
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.harness.Harness
import codes.momo.agent.harness.HarnessValidationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ToolRegistryTest {

    // ─── Fixture helpers ──────────────────────────────────────────────

    private fun registryOf(vararg tools: Tool<*>): ToolRegistry = ToolRegistry(tools.toList())

    private suspend fun ToolRegistry.dispatch(
        name: String,
        arguments: JsonObject = buildJsonObject { },
    ): ToolResult = execute(name, arguments, UnusedEnvironment)

    private fun assertError(result: ToolResult, vararg expectedMessageParts: String): ToolResult.Error {
        val error = assertIs<ToolResult.Error>(result)
        expectedMessageParts.forEach { part ->
            assertContains(error.message, part, message = "expected '$part' in: ${error.message}")
        }
        return error
    }

    // ─── Construction ─────────────────────────────────────────────────

    @Test
    @DisplayName("Construction rejects tools with duplicate names, naming the duplicates")
    fun duplicateToolNamesAreRejected() {
        val exception = assertFailsWith<IllegalArgumentException> {
            registryOf(EchoTool(), ScriptedTool(name = "echo"))
        }

        assertContains(exception.message.orEmpty(), "duplicate")
        assertContains(exception.message.orEmpty(), "echo")
    }

    // ─── Lookup ───────────────────────────────────────────────────────

    @Test
    @DisplayName("names exposes every registered tool name")
    fun namesExposesAllRegisteredTools() {
        val registry = registryOf(EchoTool(), ScriptedTool())

        assertEquals(setOf("echo", "scripted"), registry.names)
    }

    @Test
    @DisplayName("definitions returns the tools' definitions for a subset, in the given order")
    fun definitionsReturnsSubsetInOrder() {
        val echo = EchoTool()
        val scripted = ScriptedTool()
        val registry = registryOf(echo, scripted)

        val definitions = registry.definitions(listOf("scripted", "echo"))

        assertEquals(listOf(scripted.definition, echo.definition), definitions)
    }

    @Test
    @DisplayName("definitions for an unknown name fails, naming the tool")
    fun definitionsForUnknownNameFails() {
        val registry = registryOf(EchoTool())

        val exception = assertFailsWith<IllegalArgumentException> {
            registry.definitions(listOf("teleport"))
        }

        assertContains(exception.message.orEmpty(), "teleport")
    }

    // ─── Harness tool-list validation ─────────────────────────────────

    @Test
    @DisplayName("Harness tool lists validate against the registry's names")
    fun harnessValidationRunsAgainstRegistryNames() {
        val registry = registryOf(EchoTool())

        Harness(model = "m", tools = listOf("echo"), instructions = "i")
            .requireToolsKnown(registry.names)
        val exception = assertFailsWith<HarnessValidationException> {
            Harness(model = "m", tools = listOf("echo", "teleport"), instructions = "i")
                .requireToolsKnown(registry.names)
        }

        assertContains(exception.message.orEmpty(), "teleport")
    }

    // ─── Dispatch: happy path and unknown names ───────────────────────

    @Test
    @DisplayName("Dispatch decodes the arguments and returns the tool's result")
    fun dispatchDecodesArgumentsAndExecutes() = runTest {
        val arguments = buildJsonObject {
            put("text", "hi")
            put("repeat", 2)
        }

        val result = registryOf(EchoTool()).dispatch("echo", arguments)

        assertEquals(ToolResult.Success("hihi"), result)
    }

    @Test
    @DisplayName("Dispatching an unknown tool name yields an error result naming it and the available tools")
    fun dispatchOfUnknownToolYieldsError() = runTest {
        val result = registryOf(EchoTool()).dispatch("teleport")

        assertError(result, "unknown tool 'teleport'", "echo")
    }

    @Test
    @DisplayName("An empty registry reports its available tools as (none)")
    fun emptyRegistryReportsNoAvailableTools() = runTest {
        val result = registryOf().dispatch("teleport")

        assertError(result, "unknown tool 'teleport'", "(none)")
    }

    @Test
    @DisplayName("An unknown tool name pushing the error over the bound yields a truncated error with the marker")
    fun oversizedUnknownToolNameYieldsBoundedError() = runTest {
        val name = "t".repeat(ToolRegistry.MAX_RESULT_CHARS)

        val result = registryOf(EchoTool()).dispatch(name)

        val error = assertError(result, "unknown tool")
        assertTrue(
            error.message.endsWith(ToolRegistry.TRUNCATION_MARKER),
            "expected truncated error text to end with the marker, was: ${error.message.takeLast(80)}",
        )
    }

    // ─── Argument decoding errors ─────────────────────────────────────

    @Test
    @DisplayName("Missing or mistyped arguments yield an error result naming the tool and the problem")
    fun undecodableArgumentsYieldError() = runTest {
        val registry = registryOf(EchoTool())
        val mistyped = buildJsonObject {
            put("text", "hi")
            put("repeat", "lots")
        }

        assertError(registry.dispatch("echo", buildJsonObject { }), "invalid arguments", "echo", "text")
        assertError(registry.dispatch("echo", mistyped), "invalid arguments", "echo")
    }

    @Test
    @DisplayName("Unknown argument keys are ignored rather than failing the call")
    fun unknownArgumentKeysAreIgnored() = runTest {
        val arguments = buildJsonObject {
            put("text", "hi")
            put("bogus", true)
        }

        val result = registryOf(EchoTool()).dispatch("echo", arguments)

        assertEquals(ToolResult.Success("hi"), result)
    }

    // ─── Exception mapping ────────────────────────────────────────────

    @Test
    @DisplayName("An unexpected exception from a tool becomes an error result carrying its message")
    fun unexpectedExceptionBecomesErrorResult() = runTest {
        val registry = registryOf(ScriptedTool { throw IllegalStateException("disk exploded") })

        assertError(registry.dispatch("scripted"), "scripted", "disk exploded")
    }

    @Test
    @DisplayName("Coroutine cancellation propagates out of dispatch instead of becoming a result")
    fun cancellationPropagates() = runTest {
        val registry = registryOf(ScriptedTool { throw CancellationException("cancelled") })

        assertFailsWith<CancellationException> { registry.dispatch("scripted") }
    }

    @Test
    @DisplayName("Cancelling the dispatching coroutine mid-execution cancels dispatch instead of yielding a result")
    fun externalCancellationCancelsDispatch() = runTest {
        val registry = registryOf(
            ScriptedTool {
                delay(Budgets.TOOL_TIMEOUT)
                ToolResult.Success("never delivered")
            },
        )
        var result: ToolResult? = null

        val job = launch { result = registry.dispatch("scripted") }
        testScheduler.runCurrent()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertNull(result)
    }

    // ─── Timeout ──────────────────────────────────────────────────────

    @Test
    @DisplayName("A tool exceeding the budget yields the distinct TimedOut result")
    fun budgetOverrunYieldsTimeoutResult() = runTest {
        // runTest gives withTimeout and delay virtual time, so the
        // minutes-long budget elapses instantly.
        val registry = registryOf(
            ScriptedTool {
                delay(Budgets.TOOL_TIMEOUT * 2)
                ToolResult.Success("too late")
            },
        )

        val result = registry.dispatch("scripted")

        assertIs<ToolResult.TimedOut>(result)
    }

    @Test
    @DisplayName("A tool finishing within the grace window keeps its own TimedOut result, with partial output bounded")
    fun graceWindowPreservesToolReportedPartialOutput() = runTest {
        // Just past the budget is still inside the dispatcher's grace
        // window, so the tool's own result must win.
        val oversized = "y".repeat(ToolRegistry.MAX_RESULT_CHARS + 1)
        val registry = registryOf(
            ScriptedTool {
                delay(Budgets.TOOL_TIMEOUT + 5.seconds)
                ToolResult.TimedOut(partialOutput = oversized)
            },
        )

        val result = assertIs<ToolResult.TimedOut>(registry.dispatch("scripted"))

        val partialOutput = assertNotNull(result.partialOutput)
        assertTrue(partialOutput.endsWith(ToolRegistry.TRUNCATION_MARKER))
    }

    // ─── Result truncation ────────────────────────────────────────────

    @Test
    @DisplayName("Result text over the bound is truncated with the marker appended")
    fun oversizedResultIsTruncatedWithMarker() = runTest {
        val oversized = "x".repeat(ToolRegistry.MAX_RESULT_CHARS + 1)
        val registry = registryOf(ScriptedTool { ToolResult.Success(oversized) })

        val result = assertIs<ToolResult.Success>(registry.dispatch("scripted"))

        assertEquals(
            "x".repeat(ToolRegistry.MAX_RESULT_CHARS) + ToolRegistry.TRUNCATION_MARKER,
            result.text,
        )
    }

    @Test
    @DisplayName("Truncation never splits a surrogate pair straddling the bound")
    fun truncationIsSurrogateSafe() = runTest {
        // The emoji's two chars straddle the cut at MAX_RESULT_CHARS.
        val straddling = "x".repeat(ToolRegistry.MAX_RESULT_CHARS - 1) + "🙂x"
        val registry = registryOf(ScriptedTool { ToolResult.Success(straddling) })

        val result = assertIs<ToolResult.Success>(registry.dispatch("scripted"))

        assertEquals(
            "x".repeat(ToolRegistry.MAX_RESULT_CHARS - 1) + ToolRegistry.TRUNCATION_MARKER,
            result.text,
        )
    }

    @Test
    @DisplayName("Result text at the bound passes through untouched")
    fun resultAtTheBoundIsUntouched() = runTest {
        val atBound = "x".repeat(ToolRegistry.MAX_RESULT_CHARS)
        val registry = registryOf(ScriptedTool { ToolResult.Success(atBound) })

        assertEquals(ToolResult.Success(atBound), registry.dispatch("scripted"))
    }
}

// ─── Test tools ───────────────────────────────────────────────────────

@Serializable
private data class EchoArgs(
    val text: String,
    val repeat: Int = 1,
)

/** Echoes `text` back `repeat` times; ignores the workspace. */
private class EchoTool : Tool<EchoArgs>(
    name = "echo",
    description = "Echoes text back.",
    argsSerializer = EchoArgs.serializer(),
) {

    override suspend fun execute(args: EchoArgs, environment: ExecutionEnvironment): ToolResult =
        ToolResult.Success(args.text.repeat(args.repeat))
}

@Serializable
private class EmptyArgs

/** Runs the injected [body], letting each test script a success, failure, or delay. */
private class ScriptedTool(
    name: String = "scripted",
    private val body: suspend () -> ToolResult = { ToolResult.Success("") },
) : Tool<EmptyArgs>(
    name = name,
    description = "Scripted test behaviour.",
    argsSerializer = EmptyArgs.serializer(),
) {

    override suspend fun execute(args: EmptyArgs, environment: ExecutionEnvironment): ToolResult = body()
}

/** No dispatch under test touches the workspace. */
private object UnusedEnvironment : ExecutionEnvironment {

    override suspend fun exec(command: List<String>, stdin: ByteArray?, timeout: Duration): ExecResult =
        error("the registry tests never exec")

    override fun close(): Unit = Unit
}
