package codes.momo.agent

import ai.router.sdk.AiRouterClient
import ai.router.sdk.models.ChatResponse
import codes.momo.agent.environment.LocalExecutionEnvironment
import codes.momo.agent.harness.Harness
import codes.momo.agent.harness.HarnessValidationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    /** Runs [block] against an agent whose LLM serves [responses] in order. */
    private fun withScriptedAgent(
        vararg responses: ChatResponse,
        budgets: RunBudgets = RunBudgets(),
        block: suspend CoroutineScope.(Agent) -> Unit,
    ) {
        scriptedServer(*responses).use { server ->
            AiRouterClient(server.baseUrl).use { client ->
                runBlocking { block(agent(client, budgets)) }
            }
        }
    }

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
        val prompt = systemPromptFor(TEST_HARNESS, "/some/workspace")

        assertTrue(prompt.startsWith("Unit-test instructions."))
        assertContains(prompt, "/some/workspace")
        assertContains(prompt, "absolute")
    }

    // ─── Concurrency guard ────────────────────────────────────────────

    @Test
    @DisplayName("A prompt while another is running is rejected with IllegalStateException")
    fun concurrentPromptIsRejected() {
        hangingServer().use { server ->
            AiRouterClient(server.baseUrl).use { client ->
                val agent = agent(client)
                runBlocking {
                    // UNDISPATCHED runs the first prompt up to its first
                    // suspension — the in-flight LLM call — before launch
                    // returns; nothing here suspends before the assertion,
                    // so the first prompt cannot have resumed.
                    val first = launch(start = CoroutineStart.UNDISPATCHED) { agent.prompt("first") }

                    val failure = assertFailsWith<IllegalStateException> { agent.prompt("second") }

                    assertContains(failure.message.orEmpty(), "already running")
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
                val first = agent.prompt("hello")

                assertEquals(PromptResult.Status.ERROR, first.status)
                assertNull(first.finalMessage)
                assertNotNull(first.error)
                assertEquals(0, first.turnsUsed)
                assertEquals(ZERO_USAGE, first.usage)
                assertEquals(listOf("system", "user"), first.transcript.map { it.role })

                val second = agent.prompt("again")

                assertEquals(PromptResult.Status.ERROR, second.status)
                assertEquals(listOf("system", "user", "user"), second.transcript.map { it.role })
            }
        }
    }

    @Test
    @DisplayName("A response reporting finish_reason 'error' ends the run as ERROR, not COMPLETED")
    fun reportedFinishErrorBecomesErrorResult() =
        withScriptedAgent(assistantResponse(finishReason = "error")) { agent ->
            val result = agent.prompt("hello")

            assertEquals(PromptResult.Status.ERROR, result.status)
            assertNull(result.finalMessage)
            assertContains(assertNotNull(result.error).message.orEmpty(), "finish_reason")
            assertEquals(1, result.turnsUsed)
            assertEquals(listOf("system", "user", "assistant"), result.transcript.map { it.role })
        }

    // ─── Budgets and abrupt exits ─────────────────────────────────────

    @Test
    @DisplayName("Turn exhaustion leaves the pending tool calls unexecuted and repairs the transcript")
    fun turnExhaustionLeavesPendingCallsUnexecuted() = withScriptedAgent(
        toolCallResponse(bashCall(id = "call-1", command = "echo never-run")),
        budgets = RunBudgets(maxTurns = 1),
    ) { agent ->
        val result = agent.prompt("go")

        assertEquals(PromptResult.Status.TURNS_EXHAUSTED, result.status)
        assertNull(result.finalMessage)
        assertEquals(1, result.turnsUsed)
        assertEquals(listOf("system", "user", "assistant", "tool"), result.transcript.map { it.role })
        val aborted = result.transcript.last()
        assertEquals("call-1", aborted.toolCallId)
        // The aborted text also proves the call never ran: an executed echo
        // would have produced its output here.
        assertEquals(ABORTED_TOOL_RESULT_TEXT, aborted.content.single().text)
    }

    @Test
    @DisplayName("Wall-clock expiry mid-tool-batch aborts the batch and repairs the transcript")
    fun wallClockExpiryMidToolBatchRepairsTranscript() = withScriptedAgent(
        toolCallResponse(
            bashCall(id = "call-1", command = "sleep 30"),
            bashCall(id = "call-2", command = "echo never-reached"),
        ),
        // Generous enough that a cold first HTTP round-trip cannot eat the
        // budget before the tool batch starts; the sleep still dwarfs it.
        budgets = RunBudgets(maxWallClock = 1.seconds),
    ) { agent ->
        val result = agent.prompt("go")

        assertEquals(PromptResult.Status.TIMEOUT, result.status)
        assertEquals(1, result.turnsUsed)
        assertEquals(
            listOf("system", "user", "assistant", "tool", "tool"),
            result.transcript.map { it.role },
        )
        val toolMessages = result.transcript.filter { it.role == "tool" }
        assertEquals(listOf("call-1", "call-2"), toolMessages.map { it.toolCallId })
        toolMessages.forEach { message ->
            assertEquals(ABORTED_TOOL_RESULT_TEXT, message.content.single().text)
        }
    }

    @Test
    @DisplayName("External cancellation mid-tool propagates, repairs the transcript, and leaves the agent usable")
    fun externalCancellationRepairsTranscriptAndAgentStaysUsable() {
        val marker = workspace.resolve("tool-started")
        withScriptedAgent(
            toolCallResponse(bashCall(id = "call-1", command = "touch '$marker' && sleep 30")),
            assistantResponse(finishReason = "stop", text = "done"),
        ) { agent ->
            val first = launch { agent.prompt("first") }
            withTimeout(5.seconds) {
                while (marker.notExists()) {
                    delay(10.milliseconds)
                }
            }

            first.cancelAndJoin()

            assertTrue(first.isCancelled)
            val second = agent.prompt("second")
            assertEquals(PromptResult.Status.COMPLETED, second.status)
            assertEquals("done", second.finalMessage)
            assertEquals(
                listOf("system", "user", "assistant", "tool", "user", "assistant"),
                second.transcript.map { it.role },
            )
            val aborted = second.transcript.single { it.role == "tool" }
            assertEquals("call-1", aborted.toolCallId)
            assertEquals(ABORTED_TOOL_RESULT_TEXT, aborted.content.single().text)
        }
    }

    @Test
    @DisplayName("Wall-clock exhaustion ends the run as a TIMEOUT result")
    fun wallClockExhaustionBecomesTimeoutResult() {
        val budget = 100.milliseconds
        hangingServer().use { server ->
            AiRouterClient(server.baseUrl).use { client ->
                val agent = agent(client, budgets = RunBudgets(maxWallClock = budget))
                runBlocking {
                    val result = agent.prompt("hello")

                    assertEquals(PromptResult.Status.TIMEOUT, result.status)
                    assertNull(result.finalMessage)
                    assertNull(result.error)
                    assertEquals(0, result.turnsUsed)
                    assertEquals(listOf("system", "user"), result.transcript.map { it.role })
                    assertTrue(result.elapsed >= budget, "expected elapsed >= $budget, was ${result.elapsed}")
                }
            }
        }
    }
}
