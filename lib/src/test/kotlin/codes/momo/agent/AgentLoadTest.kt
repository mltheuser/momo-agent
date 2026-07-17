package codes.momo.agent

import ai.router.sdk.AiRouterClient
import ai.router.sdk.models.ToolCall
import ai.router.sdk.models.ToolCallFunction
import codes.momo.agent.environment.LocalExecutionEnvironment
import codes.momo.agent.harness.HarnessValidationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AgentLoadTest {

    @TempDir
    lateinit var workspace: Path

    // ─── Fixture helpers ──────────────────────────────────────────────

    private fun environment(): LocalExecutionEnvironment = LocalExecutionEnvironment(workspace)

    /**
     * The event log recorded by sending [prompts] in order under [budgets],
     * consuming [replies] across runs, with [after] applied to the agent
     * once the sends finished.
     */
    private fun recordedSession(
        replies: List<ScriptedReply> = listOf(
            toolCallResponse(bashCall(id = "call-1", command = "echo hi")).asReply(),
            assistantResponse(finishReason = "stop", text = "first answer").asReply(),
        ),
        prompts: List<String> = listOf("first question"),
        budgets: RunBudgets = RunBudgets(),
        after: (Agent) -> Unit = {},
    ): List<AgentEvent> {
        val listener = CollectingEventListener()
        scriptedServer(*replies.toTypedArray()).use { server ->
            AiRouterClient(server.baseUrl).use { client ->
                val agent = Agent(
                    TEST_HARNESS,
                    client,
                    environment(),
                    listener,
                    budgets,
                    SessionState.Fresh("Recorded session"),
                )
                runBlocking { prompts.forEach { agent.send(it, TEST_RUN_SETTINGS) } }
                after(agent)
            }
        }
        return listener.events.toList()
    }

    // ─── Continuing a loaded session ──────────────────────────────────

    @Test
    @DisplayName("A loaded session continues the conversation with continuous history and sequence IDs")
    fun loadContinuesConversationAndSequenceIds() {
        val logged = recordedSession()

        val listener = CollectingEventListener()
        scriptedServer(assistantResponse(finishReason = "stop", text = "second answer")).use { server ->
            AiRouterClient(server.baseUrl).use { client ->
                val agent = Agent.load(logged, TEST_HARNESS, client, environment(), listener)

                assertEquals((logged.first() as AgentEvent.SessionStarted).sessionId, agent.sessionId)
                assertEquals("Recorded session", agent.title)
                assertTrue(listener.events.isEmpty(), "loading must emit nothing")

                val result = runBlocking { agent.send("second question", TEST_RUN_SETTINGS) }

                assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
                assertEquals("second answer", result.finalMessage)
                // The recorded conversation, verbatim, under the current harness's system prompt.
                assertEquals(
                    listOf("system", "user", "assistant", "tool", "assistant", "user", "assistant"),
                    result.transcript.map { it.role },
                )
                assertContains(result.transcript[0].text, "Unit-test instructions.")
                assertEquals("first question", result.transcript[1].text)
                assertEquals("call-1", result.transcript[3].toolCallId)
                assertContains(result.transcript[3].text, "hi")
                assertEquals("first answer", result.transcript[4].text)

                // The stored log plus the new events forms one gaplessly numbered log again.
                val combined = logged + listener.events
                assertEquals(List(combined.size) { it.toLong() }, combined.map { it.sequenceId })
                assertTrue(listener.events.none { it is AgentEvent.SessionStarted })
            }
        }
    }

    // ─── Tool validation ──────────────────────────────────────────────

    @Test
    @DisplayName("Loading into a harness missing a used tool fails, naming the tool")
    fun loadIntoHarnessMissingUsedToolFails() {
        val logged = recordedSession()
        val slim = TEST_HARNESS.copy(tools = listOf("read_file"))

        withUnusedClient { client ->
            val failure = assertFailsWith<HarnessValidationException> {
                Agent.load(logged, slim, client, environment())
            }
            assertContains(failure.message.orEmpty(), "bash")
        }
    }

    @Test
    @DisplayName("A hallucinated tool call the run answered with an error does not block loading")
    fun hallucinatedToolCallDoesNotBlockLoading() {
        val logged = recordedSession(
            replies = listOf(
                toolCallResponse(
                    ToolCall(id = "call-1", function = ToolCallFunction("teleport", buildJsonObject { })),
                ).asReply(),
                assistantResponse(finishReason = "stop", text = "ok").asReply(),
            ),
        )

        withUnusedClient { client ->
            Agent.load(logged, TEST_HARNESS, client, environment())
        }
    }

    // ─── Repair, title, and log validation ────────────────────────────

    @Test
    @DisplayName("A log cut mid-run loads with the dangling tool call repaired")
    fun cutLogLoadsWithRepairedTranscript() {
        val logged = recordedSession()
        val cut = logged.subList(0, logged.indexOfFirst { it is AgentEvent.ToolCallStarted } + 1)

        scriptedServer(assistantResponse(finishReason = "stop", text = "recovered")).use { server ->
            AiRouterClient(server.baseUrl).use { client ->
                val agent = Agent.load(cut, TEST_HARNESS, client, environment())

                val result = runBlocking { agent.send("continue", TEST_RUN_SETTINGS) }

                assertEquals(
                    listOf("system", "user", "assistant", "tool", "user", "assistant"),
                    result.transcript.map { it.role },
                )
                val aborted = result.transcript.single { it.role == "tool" }
                assertEquals("call-1", aborted.toolCallId)
                assertEquals(ABORTED_TOOL_RESULT_TEXT, aborted.text)
            }
        }
    }

    @Test
    @DisplayName("An interrupted run's repaired tool call stays at that run's end after load")
    fun interruptedRunRepairsAtItsBoundaryAfterLoad() {
        val logged = recordedSession(
            replies = listOf(
                toolCallResponse(bashCall(id = "call-1", command = "echo hi")).asReply(),
                assistantResponse(finishReason = "stop", text = "second answer").asReply(),
            ),
            prompts = listOf("first question", "second question"),
            budgets = RunBudgets(maxTurns = 1),
        )

        scriptedServer(assistantResponse(finishReason = "stop", text = "third answer")).use { server ->
            AiRouterClient(server.baseUrl).use { client ->
                val agent = Agent.load(logged, TEST_HARNESS, client, environment())

                val result = runBlocking { agent.send("third question", TEST_RUN_SETTINGS) }

                assertEquals(
                    listOf("system", "user", "assistant", "tool", "user", "assistant", "user", "assistant"),
                    result.transcript.map { it.role },
                )
                assertEquals("call-1", result.transcript[3].toolCallId)
                assertEquals(ABORTED_TOOL_RESULT_TEXT, result.transcript[3].text)
            }
        }
    }

    @Test
    @DisplayName("Every interrupted run is repaired on load, not just the last one")
    fun everyInterruptedRunIsRepairedOnLoad() {
        val logged = recordedSession(
            replies = listOf(
                toolCallResponse(bashCall(id = "call-1", command = "echo one")).asReply(),
                toolCallResponse(bashCall(id = "call-2", command = "echo two")).asReply(),
            ),
            prompts = listOf("first question", "second question"),
            budgets = RunBudgets(maxTurns = 1),
        )

        scriptedServer(assistantResponse(finishReason = "stop", text = "done")).use { server ->
            AiRouterClient(server.baseUrl).use { client ->
                val agent = Agent.load(logged, TEST_HARNESS, client, environment())

                val result = runBlocking { agent.send("third question", TEST_RUN_SETTINGS) }

                assertEquals(
                    listOf("system", "user", "assistant", "tool", "user", "assistant", "tool", "user", "assistant"),
                    result.transcript.map { it.role },
                )
                assertEquals("call-1", result.transcript[3].toolCallId)
                assertEquals(ABORTED_TOOL_RESULT_TEXT, result.transcript[3].text)
                assertEquals("call-2", result.transcript[6].toolCallId)
                assertEquals(ABORTED_TOOL_RESULT_TEXT, result.transcript[6].text)
            }
        }
    }

    @Test
    @DisplayName("Loading recovers the latest title")
    fun loadRecoversLatestTitle() {
        val logged = recordedSession(after = { it.title = "Renamed session" })

        withUnusedClient { client ->
            val agent = Agent.load(logged, TEST_HARNESS, client, environment())

            assertEquals("Renamed session", agent.title)
        }
    }

    @Test
    @DisplayName("A log not starting with SessionStarted is rejected")
    fun logWithoutSessionStartedIsRejected() {
        val logged = recordedSession()

        withUnusedClient { client ->
            assertFailsWith<IllegalArgumentException> {
                Agent.load(emptyList(), TEST_HARNESS, client, environment())
            }
            assertFailsWith<IllegalArgumentException> {
                Agent.load(logged.drop(1), TEST_HARNESS, client, environment())
            }
        }
    }

    // ─── Serialization round trip ─────────────────────────────────────

    @Test
    @DisplayName("Every event type survives a kotlinx JSON round trip")
    fun everyEventTypeRoundTripsThroughJson() {
        val assistant = toolCallResponse(bashCall(id = "call-1", command = "echo hi"))
        val arguments = assistant.message.toolCalls!!.single().function.arguments
        val events: List<AgentEvent> = listOf(
            AgentEvent.SessionStarted(0, 1, "session-1", "Untitled"),
            AgentEvent.SessionRenamed(1, 2, "Renamed"),
            AgentEvent.RunStarted(2, 3, "question"),
            AgentEvent.LlmCallStarted(3, 4, turn = 1),
            AgentEvent.LlmCallRetried(4, 5, cause = "HTTP 503", attempt = 1, backoff = 1.seconds),
            AgentEvent.LlmCallFinished(5, 6, assistant.message, assistant.usage, "tool_calls"),
            AgentEvent.ToolCallStarted(6, 7, "call-1", "bash", arguments),
            AgentEvent.ToolCallFinished(
                sequenceId = 7,
                timestampMillis = 8,
                callId = "call-1",
                resultText = "Error: tool execution timed out after 5m.",
                outcome = AgentEvent.ToolCallFinished.Outcome.TIMED_OUT,
                duration = 5.seconds,
                truncated = true,
            ),
            AgentEvent.SubagentSpawned(8, 9, name = "helper", sessionId = "child-session-1"),
            AgentEvent.BudgetUpdated(9, 10, turnsUsed = 1, turnsRemaining = 39, elapsed = 2.seconds),
            AgentEvent.RunFinished(
                sequenceId = 10,
                timestampMillis = 11,
                status = RunResult.Status.TIMEOUT,
                finalMessage = null,
                usage = ZERO_USAGE,
                turnsUsed = 1,
                elapsed = 3.seconds,
            ),
        )

        val json = Json.encodeToString(events)
        val decoded = Json.decodeFromString<List<AgentEvent>>(json)

        assertEquals(events, decoded)
        // The wire strings are the stored-log compatibility contract: a
        // rename that changes them must fail here, not corrupt stored logs.
        assertContains(json, "\"type\":\"run_finished\"")
        assertContains(json, "\"type\":\"subagent_spawned\"")
        assertContains(json, "\"status\":\"timeout\"")
        assertContains(json, "\"outcome\":\"timed_out\"")
    }
}
