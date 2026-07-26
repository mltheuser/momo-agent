package codes.momo.agent

import ai.router.sdk.AiRouterClient
import ai.router.sdk.models.ChatRequest
import ai.router.sdk.models.ToolCall
import ai.router.sdk.models.ToolCallFunction
import codes.momo.agent.environment.LocalExecutionEnvironment
import codes.momo.agent.harness.Harness
import codes.momo.agent.harness.HarnessValidationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
        val invalid = Harness(tools = listOf("bash", "teleport"), instructions = "i")

        AiRouterClient(refusingBaseUrl()).use { client ->
            val failure = assertFailsWith<HarnessValidationException> {
                Agent(invalid, client, LocalExecutionEnvironment(workspace), "Test session")
            }
            assertContains(failure.message.orEmpty(), "teleport")
        }
    }

    @Test
    @DisplayName("The system prompt keeps the instructions and states the human-user contract")
    fun systemPromptStatesTheUserContract() {
        val prompt = systemPromptFor(TEST_HARNESS, subagent = false)

        assertTrue(prompt.startsWith("Unit-test instructions."))
        assertContains(prompt, "hours or days")
    }

    @Test
    @DisplayName("The workspace root and the privilege reach the model only through the bash tool's description")
    fun environmentFactsAreStatedOnlyByTheBashTool() {
        val requests = CopyOnWriteArrayList<ChatRequest>()
        scriptedServer(requests, assistantResponse(finishReason = "stop", text = "done").asReply()).use { server ->
            AiRouterClient(server.baseUrl).use { client ->
                runBlocking { agent(client).send("go", TEST_RUN_SETTINGS) }

                val request = requests.single()
                val systemPrompt = request.messages.first().text
                val description = assertNotNull(request.tools.orEmpty().single { it.name == "bash" }.description)
                // Each fact is pinned as the description actually words it, so
                // what the prompt is denied cannot drift off what the
                // description says. The test environment is the unprivileged one.
                val root = LocalExecutionEnvironment(workspace).workspacePath
                val facts = listOf(root, "unprivileged user with no way up")
                facts.forEach { fact ->
                    assertContains(description, fact)
                    assertFalse(systemPrompt.contains(fact), "the system prompt must not state: $fact")
                }
            }
        }
    }

    // ─── Input validation ─────────────────────────────────────────────

    @Test
    @DisplayName("A blank user message is rejected with IllegalArgumentException")
    fun blankUserMessageIsRejected() =
        workspace.withScriptedAgent(assistantResponse(finishReason = "stop", text = "never reached")) { agent ->
            assertFailsWith<IllegalArgumentException> { agent.send("   ", TEST_RUN_SETTINGS) }
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
                        agent.send("first", TEST_RUN_SETTINGS)
                    }

                    val rejected = assertFailsWith<IllegalStateException> {
                        agent.send("second", TEST_RUN_SETTINGS)
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
                val first = agent.send("hello", TEST_RUN_SETTINGS)

                assertEquals(RunResult.Status.ERROR, first.status)
                assertNull(first.finalMessage)
                assertNotNull(first.error)
                assertEquals(0, first.turnsUsed)
                assertEquals(ZERO_USAGE, first.usage)
                assertEquals(listOf("system", "user"), first.transcript.map { it.role })

                val second = agent.send("again", TEST_RUN_SETTINGS)

                assertEquals(RunResult.Status.ERROR, second.status)
                assertEquals(listOf("system", "user", "user"), second.transcript.map { it.role })
            }
        }
    }

    @Test
    @DisplayName("A response reporting finish_reason 'error' ends the run as ERROR, not COMPLETED")
    fun reportedFinishErrorBecomesErrorResult() =
        workspace.withScriptedAgent(assistantResponse(finishReason = "error")) { agent ->
            val result = agent.send("hello", TEST_RUN_SETTINGS)

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
            ToolCall(id = "call-1", function = ToolCallFunction("made_up_tool", buildJsonObject { })),
            ToolCall(id = "call-2", function = ToolCallFunction("other_made_up_tool", buildJsonObject { })),
        ),
        assistantResponse(finishReason = "stop", text = "done"),
        harness = Harness(tools = listOf("bash"), instructions = "i"),
    ) { agent ->
        val result = agent.send("go", TEST_RUN_SETTINGS)

        assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
        val toolMessages = result.transcript.filter { it.role == "tool" }
        assertContains(toolMessages[0].text, "unknown tool 'made_up_tool'")
        assertContains(toolMessages[1].text, "unknown tool 'other_made_up_tool'")
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
        val result = agent.send("go", TEST_RUN_SETTINGS)

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
        val result = agent.send("go", TEST_RUN_SETTINGS)

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
            val first = launch { agent.send("first", TEST_RUN_SETTINGS) }
            awaitExists(marker)

            first.cancelAndJoin()

            assertTrue(first.isCancelled)
            val second = agent.send("second", TEST_RUN_SETTINGS)
            assertEquals(RunResult.Status.COMPLETED, second.status)
            assertEquals("done", second.finalMessage)
            assertEquals(
                listOf("system", "user", "assistant", "tool", "user", "assistant"),
                second.transcript.map { it.role },
            )
            val aborted = second.transcript.single { it.role == "tool" }
            assertEquals("call-1", aborted.toolCallId)
            assertEquals(ABORTED_TOOL_RESULT_TEXT, aborted.text)

            // The cancelled run logs no RunFinished — the carve-out close,
            // delete and shutdown keep; a stop records its end instead.
            val finished = listener.events.filterIsInstance<AgentEvent.RunFinished>().single()
            assertEquals("done", finished.finalMessage)
        }
    }

    // ─── Stopping a run ───────────────────────────────────────────────

    @Test
    @DisplayName("Stopping mid-tool ends the run as STOPPED, repairs the transcript, and leaves the agent usable")
    fun stopEndsTheRunAsStoppedAndAgentStaysUsable() {
        val marker = workspace.resolve("tool-started")
        val listener = CollectingEventListener()
        workspace.withScriptedAgent(
            toolCallResponse(bashCall(id = "call-1", command = "touch '$marker' && sleep 30")),
            assistantResponse(finishReason = "stop", text = "done"),
            listener = listener,
        ) { agent ->
            val first = async { agent.send("first", TEST_RUN_SETTINGS) }
            awaitExists(marker)

            agent.stop()

            // The run reports its own end instead of throwing: the tool's
            // process tree is killed well inside the scripted 30 s sleep.
            val stopped = withTimeout(5.seconds) { first.await() }
            assertEquals(RunResult.Status.STOPPED, stopped.status)
            assertNull(stopped.finalMessage)
            assertNull(stopped.error)
            assertEquals(1, stopped.turnsUsed)
            val aborted = stopped.transcript.single { it.role == "tool" }
            assertEquals("call-1", aborted.toolCallId)
            assertEquals(ABORTED_TOOL_RESULT_TEXT, aborted.text)

            // The stopped run's end is logged like any other outcome, and the
            // agent takes the next prompt over the repaired conversation.
            assertEquals(
                RunResult.Status.STOPPED,
                listener.events.filterIsInstance<AgentEvent.RunFinished>().single().status,
            )
            val second = agent.send("second", TEST_RUN_SETTINGS)
            assertEquals(RunResult.Status.COMPLETED, second.status, "error: ${second.error}")
            assertEquals("done", second.finalMessage)
            assertEquals(
                listOf("system", "user", "assistant", "tool", "user", "assistant"),
                second.transcript.map { it.role },
            )
        }
    }

    @Test
    @DisplayName("A stop landing after the loop decided its outcome leaves the completed run standing")
    fun stopLandingOnADecidedOutcomeLeavesTheRunStanding() {
        val listener = CollectingEventListener()
        val parked = CompletableDeferred<Unit>()
        val gate = CountDownLatch(1)
        // BudgetUpdated is a turn's last event, so parking the run there
        // holds it in the window this test is about: the answer is in, the
        // loop has yet to decide on it, and nothing suspends in between.
        val parkingListener = AgentEventListener { event ->
            listener.onEvent(event)
            if (event is AgentEvent.BudgetUpdated) {
                parked.complete(Unit)
                assertTrue(gate.await(5, TimeUnit.SECONDS), "the stop must reach the parked run")
            }
        }
        workspace.withScriptedAgent(
            assistantResponse(finishReason = "stop", text = "the answer"),
            listener = parkingListener,
        ) { agent ->
            // A thread of its own: parking blocks the one the run is on.
            val run = async(Dispatchers.IO) { agent.send("go", TEST_RUN_SETTINGS) }
            withTimeout(5.seconds) { parked.await() }

            // UNDISPATCHED, so the loop is already cancelled when this
            // returns: stop() suspends only afterwards, on the run's end.
            val stopper = launch(start = CoroutineStart.UNDISPATCHED) { agent.stop() }
            gate.countDown()

            // The stop cut a live run and still lost the race, so it did
            // nothing at all: no status of its own, and the answer neither
            // dropped nor mislabelled.
            val result = withTimeout(5.seconds) { run.await() }
            withTimeout(5.seconds) { stopper.join() }
            assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
            assertEquals("the answer", result.finalMessage)
            assertNull(result.error)
            val finished = listener.events.filterIsInstance<AgentEvent.RunFinished>().single()
            assertEquals(RunResult.Status.COMPLETED, finished.status)
            assertEquals("the answer", finished.finalMessage)
        }
    }

    @Test
    @DisplayName("A stop with no run in flight is a no-op, before the first run and after one ended")
    fun stopWithNothingInFlightIsANoOp() {
        val listener = CollectingEventListener()
        workspace.withScriptedAgent(
            assistantResponse(finishReason = "stop", text = "first answer"),
            assistantResponse(finishReason = "stop", text = "second answer"),
            listener = listener,
        ) { agent ->
            agent.stop()

            val first = agent.send("first", TEST_RUN_SETTINGS)
            assertEquals(RunResult.Status.COMPLETED, first.status, "error: ${first.error}")

            agent.stop()

            val second = agent.send("second", TEST_RUN_SETTINGS)
            assertEquals(RunResult.Status.COMPLETED, second.status, "error: ${second.error}")
            assertEquals("second answer", second.finalMessage)
            assertEquals(
                listOf(RunResult.Status.COMPLETED, RunResult.Status.COMPLETED),
                listener.events.filterIsInstance<AgentEvent.RunFinished>().map { it.status },
            )
        }
    }
}
