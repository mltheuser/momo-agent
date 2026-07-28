package codes.momo.agent

import ai.router.sdk.models.ReasoningEffort
import ai.router.sdk.models.ToolCall
import ai.router.sdk.models.ToolCallFunction
import codes.momo.agent.environment.LocalExecutionEnvironment
import codes.momo.agent.harness.Harness
import codes.momo.agent.harness.HarnessValidationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class AgentLoadTest {

    @TempDir
    lateinit var workspace: Path

    // ─── Fixture helpers ──────────────────────────────────────────────

    private fun environment(): LocalExecutionEnvironment = LocalExecutionEnvironment(workspace)

    /** The event log recorded by one "first question" send against a [FakeLlm] over [rules]. */
    private fun recordedSession(
        vararg rules: FakeLlmRule = arrayOf(
            onOpeningTurn(toolCallResponse(bashCall(id = "call-1", command = "echo hi"))),
            onToolResults(assistantResponse(finishReason = "stop", text = "first answer")),
        ),
    ): List<AgentEvent> = recordedSession(RunBudgets(), listOf("first question"), rules.toList())

    /** The event log recorded by sending each of [prompts] in turn, under [budgets], against [rules]. */
    private fun recordedSession(
        budgets: RunBudgets,
        prompts: List<String>,
        rules: List<FakeLlmRule>,
    ): List<AgentEvent> {
        val listener = CollectingEventListener()
        workspace.withFakeAgent(*rules.toTypedArray(), budgets = budgets, listener = listener) { agent ->
            prompts.forEach { agent.send(it, TEST_RUN_SETTINGS) }
        }
        return listener.events.toList()
    }

    // ─── Tool validation ──────────────────────────────────────────────

    @Test
    @DisplayName("Loading into a harness missing a used tool fails, naming the tool")
    fun loadIntoHarnessMissingUsedToolFails() {
        val logged = recordedSession()
        val slim = Harness(tools = listOf("extra_tool"), instructions = TEST_HARNESS.instructions)

        unusedAiRouterClient().use { client ->
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
            onOpeningTurn(
                toolCallResponse(
                    ToolCall(id = "call-1", function = ToolCallFunction("teleport", buildJsonObject { })),
                ),
            ),
            onToolResults(assistantResponse(finishReason = "stop", text = "ok")),
        )

        unusedAiRouterClient().use { client ->
            Agent.load(logged, TEST_HARNESS, client, environment())
        }
    }

    // ─── Repair and log validation ────────────────────────────────────

    @Test
    @DisplayName("A log cut mid-run loads with the dangling tool call repaired")
    fun cutLogLoadsWithRepairedTranscript() {
        val logged = recordedSession()
        val cut = logged.subList(0, logged.indexOfFirst { it is AgentEvent.ToolCallStarted } + 1)

        FakeLlm(onOpeningTurn(assistantResponse(finishReason = "stop", text = "recovered"))).client().use { client ->
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

    @Test
    @DisplayName("A log holding two interrupted runs loads with the dangling call of each one repaired")
    fun everyInterruptedRunInTheLogIsRepaired() {
        // A turn budget of one ends each run at the LLM boundary with its
        // requested call unexecuted, so the log carries two dangling calls —
        // one at its tail, one buried behind a later run.
        val logged = recordedSession(
            budgets = RunBudgets(maxTurns = 1),
            prompts = listOf("first question", "second question"),
            rules = listOf(
                onOpeningTurn(toolCallResponse(bashCall(id = "call-1", command = "echo one")), saying = "first"),
                onOpeningTurn(toolCallResponse(bashCall(id = "call-2", command = "echo two")), saying = "second"),
            ),
        )
        assertEquals(2, logged.filterIsInstance<AgentEvent.RunStarted>().size)

        FakeLlm(onOpeningTurn(assistantResponse(finishReason = "stop", text = "recovered"))).client().use { client ->
            val agent = Agent.load(logged, TEST_HARNESS, client, environment())

            val result = runBlocking { agent.send("continue", TEST_RUN_SETTINGS) }

            assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
            assertEquals(
                listOf("system", "user", "assistant", "tool", "user", "assistant", "tool", "user", "assistant"),
                result.transcript.map { it.role },
            )
            val aborted = result.transcript.filter { it.role == "tool" }
            assertEquals(listOf("call-1", "call-2"), aborted.map { it.toolCallId })
            aborted.forEach { assertEquals(ABORTED_TOOL_RESULT_TEXT, it.text) }
        }
    }

    @Test
    @DisplayName("A log not starting with SessionStarted is rejected")
    fun logWithoutSessionStartedIsRejected() {
        val logged = recordedSession()

        unusedAiRouterClient().use { client ->
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
        val events = everyEventType()

        val decoded = Json.decodeFromString<List<AgentEvent>>(Json.encodeToString(events))

        assertEquals(events, decoded)
    }

    @Test
    @DisplayName("Every stored wire name is pinned: a rename fails here instead of orphaning stored logs")
    fun everyStoredWireNameIsPinned() {
        // The round trip above is symmetric and so blind to a rename; these
        // literals are the stored-log compatibility contract itself. Reading
        // the enums off `entries` also fails on a value added without a pin.
        val json = Json.encodeToString(everyEventType())

        assertEquals(
            listOf(
                "session_started",
                "session_renamed",
                "run_started",
                "llm_call_started",
                "llm_call_retried",
                "llm_call_finished",
                "tool_call_started",
                "tool_call_finished",
                "subagent_spawned",
                "budget_updated",
                "run_finished",
            ),
            Json.parseToJsonElement(json).jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content },
        )
        assertEquals(
            listOf("completed", "stopped", "turns_exhausted", "timeout", "error"),
            RunResult.Status.entries.map { wireName(RunResult.Status.serializer(), it) },
        )
        assertEquals(
            listOf("success", "error", "timed_out"),
            AgentEvent.ToolCallFinished.Outcome.entries
                .map { wireName(AgentEvent.ToolCallFinished.Outcome.serializer(), it) },
        )
        // Field names are the other half of it, so every event's whole key set
        // is pinned: a rename, or a field added without a decision, fails here
        // too. The discriminator owns `type`, which is why the spawn's own type
        // travels as `subagentType`.
        assertEquals(
            listOf(
                eventKeys("sessionId", "title", "depth"),
                eventKeys("title"),
                eventKeys("userMessage", "model", "reasoningEffort"),
                eventKeys("turn"),
                eventKeys("cause", "attempt", "backoff"),
                eventKeys("message", "usage", "finishReason"),
                eventKeys("callId", "toolName", "arguments"),
                eventKeys("callId", "resultText", "outcome", "duration", "truncated"),
                eventKeys("name", "sessionId", "subagentType", "modelId"),
                eventKeys("turnsUsed", "turnsRemaining", "elapsed"),
                eventKeys("status", "finalMessage", "usage", "turnsUsed", "elapsed"),
            ),
            Json.parseToJsonElement(json).jsonArray.map { it.jsonObject.keys },
        )
    }

    /** The `@SerialName` of [value], as a stored log carries it. */
    private fun <T> wireName(serializer: KSerializer<T>, value: T): String =
        Json.encodeToJsonElement(serializer, value).jsonPrimitive.content

    /** The keys every stored event carries — the discriminator and the [AgentEvent] fields — plus [own]. */
    private fun eventKeys(vararg own: String): Set<String> =
        setOf("type", "sequenceId", "timestampMillis", *own)

    /**
     * One event of every type, for the round trip and the wire-name pins.
     * Every defaulted field carries a non-default value, since a defaulted
     * one is left out of the encoding and so pins nothing.
     */
    private fun everyEventType(): List<AgentEvent> {
        val assistant = toolCallResponse(bashCall(id = "call-1", command = "echo hi"))
        val arguments = assistant.message.toolCalls!!.single().function.arguments
        return listOf(
            AgentEvent.SessionStarted(0, 1, "session-1", "Untitled", depth = 1),
            AgentEvent.SessionRenamed(1, 2, "Renamed"),
            AgentEvent.RunStarted(2, 3, "question", model = "test-model", reasoningEffort = ReasoningEffort.HIGH),
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
            AgentEvent.SubagentSpawned(
                sequenceId = 8,
                timestampMillis = 9,
                name = "helper",
                sessionId = "child-session-1",
                type = "self",
                modelId = "pinned-model",
            ),
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
    }

    @Test
    @DisplayName("A stored subagent_spawned predating typed spawning still deserializes, with a null type")
    fun subagentSpawnedWithoutTypeStillDeserializes() {
        val stored = """
            {"type":"subagent_spawned","sequenceId":8,"timestampMillis":9,"name":"helper","sessionId":"child-1"}
        """.trimIndent()

        val decoded = Json.decodeFromString<AgentEvent>(stored)

        assertEquals(
            AgentEvent.SubagentSpawned(8, 9, name = "helper", sessionId = "child-1", type = null, modelId = null),
            decoded,
        )
    }
}
