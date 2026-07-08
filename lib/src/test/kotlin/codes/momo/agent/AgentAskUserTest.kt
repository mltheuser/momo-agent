package codes.momo.agent

import ai.router.sdk.models.ToolCall
import ai.router.sdk.models.ToolCallFunction
import codes.momo.agent.harness.Harness
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.notExists
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AgentAskUserTest {

    @TempDir
    lateinit var workspace: Path

    // ─── Question/answer round trip ───────────────────────────────────

    @Test
    @DisplayName("An ask_user call parks the prompt; the answer resumes it verbatim to completion")
    fun questionAnswerRoundTrip() = workspace.withScriptedAgent(
        toolCallResponse(askUserCall(id = "call-1", question = "Which database?")),
        assistantResponse(finishReason = "stop", text = "done"),
    ) { agent ->
        val parked = agent.send(AgentInput.UserMessage("go"))

        assertEquals(PromptResult.Status.AWAITING_USER, parked.status)
        assertEquals("Which database?", parked.pendingQuestion)
        assertNull(parked.finalMessage)
        assertNull(parked.error)
        // The parked ask stays unanswered — never repaired, never dispatched.
        assertEquals(listOf("system", "user", "assistant"), parked.transcript.map { it.role })

        val finished = agent.send(AgentInput.Answer("Postgres, please."))

        assertEquals(PromptResult.Status.COMPLETED, finished.status, "error: ${finished.error}")
        assertEquals("done", finished.finalMessage)
        assertNull(finished.pendingQuestion)
        assertEquals(
            listOf("system", "user", "assistant", "tool", "assistant"),
            finished.transcript.map { it.role },
        )
        val answer = finished.transcript[3]
        assertEquals("call-1", answer.toolCallId)
        assertEquals("Postgres, please.", answer.text)
    }

    // ─── Batch ordering around a parked ask ───────────────────────────

    @Test
    @DisplayName("A batch [dispatched, ask, dispatched] runs the first call, parks, and resumes the rest in order")
    fun midBatchOrderingDefersCallsBehindTheAsk() = workspace.withScriptedAgent(
        toolCallResponse(
            bashCall(id = "call-1", command = "echo first"),
            askUserCall(id = "call-2", question = "Continue?"),
            bashCall(id = "call-3", command = "echo third"),
        ),
        assistantResponse(finishReason = "stop", text = "done"),
    ) { agent ->
        val parked = agent.send(AgentInput.UserMessage("go"))

        assertEquals(PromptResult.Status.AWAITING_USER, parked.status)
        // The call before the ask already ran; the one queued behind it must not have.
        val parkedTools = parked.transcript.filter { it.role == "tool" }
        assertEquals(listOf("call-1"), parkedTools.map { it.toolCallId })
        assertContains(parkedTools.single().text, "first")

        val finished = agent.send(AgentInput.Answer("yes"))

        assertEquals(PromptResult.Status.COMPLETED, finished.status, "error: ${finished.error}")
        val toolMessages = finished.transcript.filter { it.role == "tool" }
        assertEquals(listOf("call-1", "call-2", "call-3"), toolMessages.map { it.toolCallId })
        assertEquals("yes", toolMessages[1].text)
        assertContains(toolMessages[2].text, "third")
    }

    @Test
    @DisplayName("Two ask calls in one batch park twice, resuming in order")
    fun repeatedAsksParkTwice() = workspace.withScriptedAgent(
        toolCallResponse(
            askUserCall(id = "call-1", question = "First?"),
            askUserCall(id = "call-2", question = "Second?"),
        ),
        assistantResponse(finishReason = "stop", text = "done"),
    ) { agent ->
        val first = agent.send(AgentInput.UserMessage("go"))
        assertEquals(PromptResult.Status.AWAITING_USER, first.status)
        assertEquals("First?", first.pendingQuestion)

        val second = agent.send(AgentInput.Answer("one"))
        assertEquals(PromptResult.Status.AWAITING_USER, second.status)
        assertEquals("Second?", second.pendingQuestion)

        val finished = agent.send(AgentInput.Answer("two"))

        assertEquals(PromptResult.Status.COMPLETED, finished.status, "error: ${finished.error}")
        val toolMessages = finished.transcript.filter { it.role == "tool" }
        assertEquals(listOf("call-1", "call-2"), toolMessages.map { it.toolCallId })
        assertEquals(listOf("one", "two"), toolMessages.map { it.text })
    }

    // ─── Malformed and unlisted calls ─────────────────────────────────

    @Test
    @DisplayName("Malformed arguments or a blank question yield an error tool result without pausing")
    fun malformedOrBlankAskErrorsWithoutPausing() = workspace.withScriptedAgent(
        toolCallResponse(
            ToolCall(id = "call-1", function = ToolCallFunction("ask_user", buildJsonObject { })),
            askUserCall(id = "call-2", question = "   "),
        ),
        assistantResponse(finishReason = "stop", text = "done"),
    ) { agent ->
        val result = agent.send(AgentInput.UserMessage("go"))

        assertEquals(PromptResult.Status.COMPLETED, result.status, "error: ${result.error}")
        val toolMessages = result.transcript.filter { it.role == "tool" }
        assertEquals(listOf("call-1", "call-2"), toolMessages.map { it.toolCallId })
        toolMessages.forEach { assertContains(it.text, "invalid arguments for tool 'ask_user'") }
        assertContains(toolMessages[1].text, "must not be blank")
    }

    @Test
    @DisplayName("A call to any tool the harness does not list errors as unknown — an unlisted ask never parks")
    fun unlistedToolCallsGetErrorResults() = workspace.withScriptedAgent(
        toolCallResponse(
            askUserCall(id = "call-1", question = "Anyone there?"),
            ToolCall(id = "call-2", function = ToolCallFunction("edit_file", buildJsonObject { })),
        ),
        assistantResponse(finishReason = "stop", text = "done"),
        harness = Harness(model = "test-model", tools = listOf("bash"), instructions = "i"),
    ) { agent ->
        val result = agent.send(AgentInput.UserMessage("go"))

        assertEquals(PromptResult.Status.COMPLETED, result.status, "error: ${result.error}")
        val toolMessages = result.transcript.filter { it.role == "tool" }
        assertContains(toolMessages[0].text, "unknown tool 'ask_user'")
        assertContains(toolMessages[1].text, "unknown tool 'edit_file'")
        // From the model's view only the harness's tools exist.
        toolMessages.forEach { assertContains(it.text, "available tools: bash.") }
    }

    // ─── Input state machine ──────────────────────────────────────────

    @Test
    @DisplayName("A user message while parked and an answer while idle are rejected, leaving the session intact")
    fun inputsMustMatchThePendingState() = workspace.withScriptedAgent(
        toolCallResponse(askUserCall(id = "call-1", question = "Q?")),
        assistantResponse(finishReason = "stop", text = "done"),
    ) { agent ->
        assertFailsWith<IllegalStateException> { agent.send(AgentInput.Answer("nothing asked")) }

        agent.send(AgentInput.UserMessage("go"))

        assertFailsWith<IllegalStateException> { agent.send(AgentInput.UserMessage("but also")) }

        val finished = agent.send(AgentInput.Answer("fine"))
        assertEquals(PromptResult.Status.COMPLETED, finished.status, "error: ${finished.error}")
    }

    @Test
    @DisplayName("A blank answer is rejected with IllegalArgumentException")
    fun blankAnswerIsRejected() {
        assertFailsWith<IllegalArgumentException> { AgentInput.Answer("   ") }
    }

    @Test
    @DisplayName("A blank user message is rejected with IllegalArgumentException")
    fun blankUserMessageIsRejected() {
        assertFailsWith<IllegalArgumentException> { AgentInput.UserMessage("   ") }
    }

    // ─── Budgets across segments ──────────────────────────────────────

    @Test
    @DisplayName("Turns and usage accumulate across the pause-separated segments of one prompt")
    fun countersAccumulateAcrossSegments() = workspace.withScriptedAgent(
        toolCallResponse(askUserCall(id = "call-1", question = "Q?")),
        assistantResponse(finishReason = "stop", text = "done"),
    ) { agent ->
        val parked = agent.send(AgentInput.UserMessage("go"))
        assertEquals(1, parked.turnsUsed)

        val finished = agent.send(AgentInput.Answer("A"))

        assertEquals(2, finished.turnsUsed)
        assertEquals(RESPONSE_USAGE + RESPONSE_USAGE, finished.usage)
        assertTrue(finished.elapsed >= parked.elapsed)
    }

    @Test
    @DisplayName("Parked time is free: a resumed segment gets the wall clock minus only the active time")
    fun parkedTimeCostsNoBudget() = workspace.withScriptedAgent(
        toolCallResponse(askUserCall(id = "call-1", question = "Q?")),
        assistantResponse(finishReason = "stop", text = "done"),
        budgets = RunBudgets(maxWallClock = 2.seconds),
    ) { agent ->
        val parked = agent.send(AgentInput.UserMessage("go"))
        assertEquals(PromptResult.Status.AWAITING_USER, parked.status)

        delay(2500.milliseconds) // longer than the whole wall-clock budget

        val finished = agent.send(AgentInput.Answer("A"))

        assertEquals(PromptResult.Status.COMPLETED, finished.status, "error: ${finished.error}")
        assertTrue(
            finished.elapsed < 2.seconds,
            "parked time must not count as active time, was ${finished.elapsed}",
        )
    }

    @Test
    @DisplayName("The clock does not reset on resume: a segment that burned the budget leaves none for the next")
    fun remainingBudgetConstrainsTheResumedSegment() = workspace.withScriptedAgent(
        toolCallResponse(
            bashCall(id = "call-1", command = "sleep 5"),
            askUserCall(id = "call-2", question = "Q?"),
        ),
        budgets = RunBudgets(maxWallClock = 300.milliseconds),
    ) { agent ->
        val parked = agent.send(AgentInput.UserMessage("go"))

        // The slow call consumed the whole budget, yet the ask still parks: parking ignores budget.
        assertEquals(PromptResult.Status.AWAITING_USER, parked.status)
        assertTrue(parked.elapsed >= 300.milliseconds, "the slow call must burn the budget, was ${parked.elapsed}")

        val finished = agent.send(AgentInput.Answer("A"))

        // No fresh clock: the server holds no further replies, so a resumed LLM call would fail differently.
        assertEquals(PromptResult.Status.TIMEOUT, finished.status)
        assertEquals(parked.turnsUsed, finished.turnsUsed)
    }

    // ─── Pause vs. transcript repair ──────────────────────────────────

    @Test
    @DisplayName("Cancellation before the loop reaches an ask aborts it through repair, with no QuestionAsked")
    fun cancellationBeforeTheAskAbortsItThroughRepair() {
        val listener = CollectingEventListener()
        val marker = workspace.resolve("tool-started")
        workspace.withScriptedAgent(
            toolCallResponse(
                bashCall(id = "call-1", command = "touch '$marker' && sleep 30"),
                askUserCall(id = "call-2", question = "never asked"),
            ),
            assistantResponse(finishReason = "stop", text = "recovered"),
            listener = listener,
        ) { agent ->
            val first = launch { agent.send(AgentInput.UserMessage("go")) }
            withTimeout(5.seconds) {
                while (marker.notExists()) {
                    delay(10.milliseconds)
                }
            }

            first.cancelAndJoin()

            assertTrue(listener.events.none { it is AgentEvent.QuestionAsked })
            // Not parked: an answer has nothing to answer, a new prompt works.
            assertFailsWith<IllegalStateException> { agent.send(AgentInput.Answer("too late")) }
            val second = agent.send(AgentInput.UserMessage("second"))
            assertEquals(PromptResult.Status.COMPLETED, second.status, "error: ${second.error}")
            val aborted = second.transcript.filter { it.role == "tool" }
            assertEquals(listOf("call-1", "call-2"), aborted.map { it.toolCallId })
            aborted.forEach { assertEquals(ABORTED_TOOL_RESULT_TEXT, it.text) }
        }
    }

    @Test
    @DisplayName("Turn exhaustion aborts a pending ask through repair instead of parking")
    fun turnExhaustionAbortsPendingAsk() = workspace.withScriptedAgent(
        toolCallResponse(askUserCall(id = "call-1", question = "Q?")),
        budgets = RunBudgets(maxTurns = 1),
    ) { agent ->
        val result = agent.send(AgentInput.UserMessage("go"))

        assertEquals(PromptResult.Status.TURNS_EXHAUSTED, result.status)
        assertNull(result.pendingQuestion)
        val aborted = result.transcript.last()
        assertEquals("call-1", aborted.toolCallId)
        assertEquals(ABORTED_TOOL_RESULT_TEXT, aborted.text)
    }
}
