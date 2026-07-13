package codes.momo.agent

import ai.router.sdk.AiRouterClient
import ai.router.sdk.models.ToolCall
import ai.router.sdk.models.ToolCallFunction
import codes.momo.agent.environment.LocalExecutionEnvironment
import codes.momo.agent.harness.Harness
import codes.momo.agent.harness.HarnessValidationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.nio.file.Path
import kotlin.io.path.notExists
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AgentTest {

    @TempDir
    lateinit var workspace: Path

    // ─── Fixture helpers ──────────────────────────────────────────────

    private fun agent(client: AiRouterClient, budgets: RunBudgets = RunBudgets()): Agent = Agent(
        harness = TEST_HARNESS,
        client = client,
        environment = LocalExecutionEnvironment(workspace),
        eventListener = NoOpAgentEventListener,
        budgets = budgets,
        session = SessionState.Fresh("Test session"),
    )

    /** A URL with nothing listening: the port is reserved once, then released. */
    private fun refusingBaseUrl(): String {
        val port = ServerSocket(0).use { it.localPort }
        return "http://127.0.0.1:$port"
    }

    // ─── Construction ─────────────────────────────────────────────────

    @Test
    @DisplayName("Construction rejects a harness naming a tool the library does not provide")
    fun constructionRejectsUnknownHarnessTool() {
        val invalid = Harness(model = "m", tools = listOf("bash", "teleport"), instructions = "i")

        AiRouterClient(refusingBaseUrl()).use { client ->
            val failure = assertFailsWith<HarnessValidationException> {
                Agent(invalid, client, LocalExecutionEnvironment(workspace), "Test session")
            }
            assertContains(failure.message.orEmpty(), "teleport")
        }
    }

    @Test
    @DisplayName("The system prompt keeps the instructions and states the absolute workspace path")
    fun systemPromptStatesWorkspaceAndPathContract() {
        val prompt = systemPromptFor(TEST_HARNESS, "/some/workspace", subagent = false)

        assertTrue(prompt.startsWith("Unit-test instructions."))
        assertContains(prompt, "/some/workspace")
        assertContains(prompt, "absolute")
    }

    // ─── Input validation ─────────────────────────────────────────────

    @Test
    @DisplayName("A blank user message is rejected with IllegalArgumentException")
    fun blankUserMessageIsRejected() =
        workspace.withScriptedAgent(assistantResponse(finishReason = "stop", text = "never reached")) { agent ->
            assertFailsWith<IllegalArgumentException> { agent.send("   ") }
        }

    // ─── Concurrency guard ────────────────────────────────────────────

    @Test
    @DisplayName("A send while another is running is rejected with IllegalStateException")
    fun concurrentSendIsRejected() {
        hangingServer().use { server ->
            AiRouterClient(server.baseUrl).use { client ->
                val agent = agent(client)
                runBlocking {
                    // UNDISPATCHED runs the first send up to its first
                    // suspension — the in-flight LLM call — before launch
                    // returns; nothing here suspends before the assertion,
                    // so the first send cannot have resumed.
                    val first = launch(start = CoroutineStart.UNDISPATCHED) {
                        agent.send("first")
                    }

                    val rejected = assertFailsWith<IllegalStateException> {
                        agent.send("second")
                    }

                    assertContains(rejected.message.orEmpty(), "already running")
                    first.cancelAndJoin()
                }
            }
        }
    }

    // ─── Terminal failures as data ────────────────────────────────────

    @Test
    @DisplayName("A terminal LLM failure ends the run as an ERROR result and leaves the agent usable")
    fun terminalLlmFailureBecomesErrorResult() {
        AiRouterClient(refusingBaseUrl()).use { client ->
            val agent = agent(client)
            runBlocking {
                val first = agent.send("hello")

                assertEquals(RunResult.Status.ERROR, first.status)
                assertNull(first.finalMessage)
                assertNotNull(first.error)
                assertEquals(0, first.turnsUsed)
                assertEquals(ZERO_USAGE, first.usage)
                assertEquals(listOf("system", "user"), first.transcript.map { it.role })

                val second = agent.send("again")

                assertEquals(RunResult.Status.ERROR, second.status)
                assertEquals(listOf("system", "user", "user"), second.transcript.map { it.role })
            }
        }
    }

    @Test
    @DisplayName("A response reporting finish_reason 'error' ends the run as ERROR, not COMPLETED")
    fun reportedFinishErrorBecomesErrorResult() =
        workspace.withScriptedAgent(assistantResponse(finishReason = "error")) { agent ->
            val result = agent.send("hello")

            assertEquals(RunResult.Status.ERROR, result.status)
            assertNull(result.finalMessage)
            assertContains(assertNotNull(result.error).message.orEmpty(), "finish_reason")
            assertEquals(1, result.turnsUsed)
            assertEquals(listOf("system", "user", "assistant"), result.transcript.map { it.role })
        }

    // ─── Unlisted tool calls ──────────────────────────────────────────

    @Test
    @DisplayName("A call to any tool the harness does not list errors as unknown")
    fun unlistedToolCallsGetErrorResults() = workspace.withScriptedAgent(
        toolCallResponse(
            ToolCall(id = "call-1", function = ToolCallFunction("write_file", buildJsonObject { })),
            ToolCall(id = "call-2", function = ToolCallFunction("edit_file", buildJsonObject { })),
        ),
        assistantResponse(finishReason = "stop", text = "done"),
        harness = Harness(model = "test-model", tools = listOf("bash"), instructions = "i"),
    ) { agent ->
        val result = agent.send("go")

        assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
        val toolMessages = result.transcript.filter { it.role == "tool" }
        assertContains(toolMessages[0].text, "unknown tool 'write_file'")
        assertContains(toolMessages[1].text, "unknown tool 'edit_file'")
        // From the model's view only the harness's tools exist.
        toolMessages.forEach { assertContains(it.text, "available tools: bash.") }
    }

    // ─── Budgets and abrupt exits ─────────────────────────────────────

    @Test
    @DisplayName("Turn exhaustion leaves the pending tool calls unexecuted and repairs the transcript")
    fun turnExhaustionLeavesPendingCallsUnexecuted() = workspace.withScriptedAgent(
        toolCallResponse(bashCall(id = "call-1", command = "echo never-run")),
        budgets = RunBudgets(maxTurns = 1),
    ) { agent ->
        val result = agent.send("go")

        assertEquals(RunResult.Status.TURNS_EXHAUSTED, result.status)
        assertNull(result.finalMessage)
        assertEquals(1, result.turnsUsed)
        assertEquals(listOf("system", "user", "assistant", "tool"), result.transcript.map { it.role })
        val aborted = result.transcript.last()
        assertEquals("call-1", aborted.toolCallId)
        // The aborted text also proves the call never ran: an executed echo
        // would have produced its output here.
        assertEquals(ABORTED_TOOL_RESULT_TEXT, aborted.text)
    }

    @Test
    @DisplayName("Wall-clock expiry mid-batch times out the remaining dispatches and ends the run at the LLM boundary")
    fun wallClockExpiryDrainsBatchWithTimedOutResults() = workspace.withScriptedAgent(
        toolCallResponse(
            bashCall(id = "call-1", command = "sleep 30"),
            bashCall(id = "call-2", command = "echo never-reached"),
        ),
        // Generous enough that a cold first HTTP round-trip cannot eat the
        // budget before the tool batch starts; the sleep still dwarfs it.
        budgets = RunBudgets(maxWallClock = 1.seconds),
    ) { agent ->
        val result = agent.send("go")

        assertEquals(RunResult.Status.TIMEOUT, result.status)
        assertEquals(1, result.turnsUsed)
        assertEquals(
            listOf("system", "user", "assistant", "tool", "tool"),
            result.transcript.map { it.role },
        )
        // The batch drained fully: both calls carry real timed-out results
        // naming their actual bound, not synthesized aborted ones — the
        // second call's bound was the already-exhausted (zero) remainder.
        val toolMessages = result.transcript.filter { it.role == "tool" }
        assertEquals(listOf("call-1", "call-2"), toolMessages.map { it.toolCallId })
        toolMessages.forEach { assertContains(it.text, "timed out") }
        assertContains(toolMessages.last().text, "0s")
        assertTrue(result.elapsed >= 1.seconds, "expected elapsed >= 1s, was ${result.elapsed}")
    }

    @Test
    @DisplayName("External cancellation mid-tool propagates, repairs the transcript, and leaves the agent usable")
    fun externalCancellationRepairsTranscriptAndAgentStaysUsable() {
        val marker = workspace.resolve("tool-started")
        val listener = CollectingEventListener()
        workspace.withScriptedAgent(
            toolCallResponse(bashCall(id = "call-1", command = "touch '$marker' && sleep 30")),
            assistantResponse(finishReason = "stop", text = "done"),
            listener = listener,
        ) { agent ->
            val first = launch { agent.send("first") }
            withTimeout(5.seconds) {
                while (marker.notExists()) {
                    delay(10.milliseconds)
                }
            }

            first.cancelAndJoin()

            assertTrue(first.isCancelled)
            val second = agent.send("second")
            assertEquals(RunResult.Status.COMPLETED, second.status)
            assertEquals("done", second.finalMessage)
            assertEquals(
                listOf("system", "user", "assistant", "tool", "user", "assistant"),
                second.transcript.map { it.role },
            )
            val aborted = second.transcript.single { it.role == "tool" }
            assertEquals("call-1", aborted.toolCallId)
            assertEquals(ABORTED_TOOL_RESULT_TEXT, aborted.text)

            // The cancelled run logs no RunFinished — the sole carve-out from run-end emission.
            val finished = listener.events.filterIsInstance<AgentEvent.RunFinished>().single()
            assertEquals("done", finished.finalMessage)
        }
    }
}
