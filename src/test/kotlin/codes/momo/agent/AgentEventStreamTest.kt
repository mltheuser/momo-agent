package codes.momo.agent

import ai.router.sdk.AiRouterClient
import codes.momo.agent.environment.LocalExecutionEnvironment
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class AgentEventStreamTest {

    @TempDir
    lateinit var workspace: Path

    // ─── Fixture helpers ──────────────────────────────────────────────

    /** Runs one "go" prompt against a scripted LLM, reporting to [listener]. */
    private fun runScripted(listener: AgentEventListener, vararg replies: ScriptedReply): PromptResult =
        scriptedServer(*replies).use { server ->
            AiRouterClient(server.baseUrl).use { client ->
                runBlocking { agent(client, listener).prompt("go") }
            }
        }

    private fun agent(
        client: AiRouterClient,
        listener: AgentEventListener,
        budgets: RunBudgets = RunBudgets(),
    ): Agent = Agent(
        harness = TEST_HARNESS,
        client = client,
        environment = LocalExecutionEnvironment(workspace),
        eventListener = listener,
        budgets = budgets,
        session = SessionState.Fresh("Test session"),
    )

    // ─── Full event sequence ──────────────────────────────────────────

    @Test
    @DisplayName("A multi-turn run with a tool call emits the full event sequence with verbatim payloads")
    fun multiTurnRunEmitsFullEventSequence() {
        val listener = CollectingEventListener()

        val result = runScripted(
            listener,
            toolCallResponse(bashCall(id = "call-1", command = "echo hi")).asReply(),
            assistantResponse(finishReason = "stop", text = "all done").asReply(),
        )

        assertEquals(PromptResult.Status.COMPLETED, result.status, "error: ${result.error}")
        val events = listener.events
        assertEquals(
            listOf(
                AgentEvent.SessionStarted::class,
                AgentEvent.RunStarted::class,
                AgentEvent.LlmCallStarted::class,
                AgentEvent.LlmCallFinished::class,
                AgentEvent.BudgetUpdated::class,
                AgentEvent.ToolCallStarted::class,
                AgentEvent.ToolCallFinished::class,
                AgentEvent.LlmCallStarted::class,
                AgentEvent.LlmCallFinished::class,
                AgentEvent.BudgetUpdated::class,
                AgentEvent.RunFinished::class,
            ),
            events.map { it::class },
        )
        // Sequence IDs count up from 0, one per event; timestamps never go backwards.
        assertEquals(List(events.size) { it.toLong() }, events.map { it.sequenceId })
        assertTrue(events.zipWithNext().all { (a, b) -> a.timestampMillis <= b.timestampMillis })

        val started = assertIs<AgentEvent.SessionStarted>(events[0])
        assertEquals("Test session", started.title)
        assertEquals("go", assertIs<AgentEvent.RunStarted>(events[1]).userMessage)
        assertEquals(1, assertIs<AgentEvent.LlmCallStarted>(events[2]).turn)

        val firstCall = assertIs<AgentEvent.LlmCallFinished>(events[3])
        assertEquals("tool_calls", firstCall.finishReason)
        assertEquals(result.transcript[2], firstCall.message)

        val budget = assertIs<AgentEvent.BudgetUpdated>(events[4])
        assertEquals(1, budget.turnsUsed)
        assertEquals(Budgets.MAX_TURNS - 1, budget.turnsRemaining)
        assertTrue(budget.elapsed > Duration.ZERO)

        val toolStarted = assertIs<AgentEvent.ToolCallStarted>(events[5])
        assertEquals("call-1", toolStarted.callId)
        assertEquals("bash", toolStarted.toolName)
        assertEquals(bashCall(id = "call-1", command = "echo hi").function.arguments, toolStarted.arguments)

        val toolFinished = assertIs<AgentEvent.ToolCallFinished>(events[6])
        assertEquals("call-1", toolFinished.callId)
        assertEquals(AgentEvent.ToolCallFinished.Outcome.SUCCESS, toolFinished.outcome)
        assertEquals(result.transcript[3].content.single().text, toolFinished.resultText)
        assertContains(toolFinished.resultText, "hi")
        assertTrue(toolFinished.duration > Duration.ZERO)

        assertEquals(2, assertIs<AgentEvent.LlmCallStarted>(events[7]).turn)
        val finished = assertIs<AgentEvent.RunFinished>(events[10])
        assertEquals(PromptResult.Status.COMPLETED, finished.status)
        assertEquals("all done", finished.finalMessage)
        assertEquals(2, finished.turnsUsed)
        assertEquals(result.usage, finished.usage)
        assertTrue(finished.elapsed > Duration.ZERO)
    }

    // ─── Retries ──────────────────────────────────────────────────────

    @Test
    @DisplayName("A transient LLM failure emits a retry event carrying the cause and backoff")
    fun transientFailureEmitsRetryEvent() {
        val listener = CollectingEventListener()

        val result = runScripted(
            listener,
            transientFailure("scripted overload"),
            assistantResponse(finishReason = "stop", text = "ok").asReply(),
        )

        assertEquals(PromptResult.Status.COMPLETED, result.status, "error: ${result.error}")
        assertEquals(
            listOf(
                AgentEvent.SessionStarted::class,
                AgentEvent.RunStarted::class,
                AgentEvent.LlmCallStarted::class,
                AgentEvent.LlmCallRetried::class,
                AgentEvent.LlmCallFinished::class,
                AgentEvent.BudgetUpdated::class,
                AgentEvent.RunFinished::class,
            ),
            listener.events.map { it::class },
        )
        val retry = listener.events.filterIsInstance<AgentEvent.LlmCallRetried>().single()
        assertEquals(1, retry.attempt)
        assertEquals(INITIAL_RETRY_BACKOFF, retry.backoff)
        assertContains(retry.cause, "scripted overload")
    }

    // ─── Timeout ──────────────────────────────────────────────────────

    @Test
    @DisplayName("A timed-out run still emits its RunFinished, outside the cancelled loop")
    fun timedOutRunEmitsRunFinished() {
        val listener = CollectingEventListener()
        hangingServer().use { server ->
            AiRouterClient(server.baseUrl).use { client ->
                runBlocking {
                    agent(client, listener, RunBudgets(maxWallClock = 100.milliseconds)).prompt("hello")
                }
            }
        }

        val finished = assertIs<AgentEvent.RunFinished>(listener.events.last())
        assertEquals(PromptResult.Status.TIMEOUT, finished.status)
        assertNull(finished.finalMessage)
    }

    // ─── Listener isolation ───────────────────────────────────────────

    @Test
    @DisplayName("A listener that throws never alters the run's outcome")
    fun throwingListenerDoesNotAffectTheRun() {
        val throwing = AgentEventListener { error("listener exploded") }

        val result = runScripted(throwing, assistantResponse(finishReason = "stop", text = "done").asReply())

        assertEquals(PromptResult.Status.COMPLETED, result.status, "error: ${result.error}")
        assertEquals("done", result.finalMessage)
    }
}
