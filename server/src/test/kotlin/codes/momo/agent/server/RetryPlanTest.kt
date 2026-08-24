package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.RESPONSE_USAGE
import codes.momo.agent.RunResult
import codes.momo.agent.assistantResponse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration

/**
 * The retry cut as pure log analysis: where the cut lands for each shape a
 * failed run leaves, and which logs refuse. The endpoint's mechanics are
 * `RetryRunTest`'s.
 */
class RetryPlanTest {

    private fun started(seq: Long) = AgentEvent.SessionStarted(seq, seq, sessionId = "s", title = "s")

    private fun run(seq: Long, model: String? = "test-model") =
        AgentEvent.RunStarted(seq, seq, userMessage = "go", model = model)

    private fun call(seq: Long) = AgentEvent.LlmCallStarted(seq, seq, turn = 1)

    private fun answered(seq: Long) = AgentEvent.LlmCallFinished(
        sequenceId = seq,
        timestampMillis = seq,
        message = assistantResponse(finishReason = "stop", text = "hm").message,
        usage = RESPONSE_USAGE,
        finishReason = "stop",
    )

    private fun toolDone(seq: Long) = AgentEvent.ToolCallFinished(
        sequenceId = seq,
        timestampMillis = seq,
        callId = "c",
        resultText = "ok",
        outcome = AgentEvent.ToolCallFinished.Outcome.SUCCESS,
        duration = Duration.ZERO,
        truncated = false,
    )

    private fun failed(seq: Long, status: RunResult.Status = RunResult.Status.ERROR) = AgentEvent.RunFinished(
        sequenceId = seq,
        timestampMillis = seq,
        status = status,
        finalMessage = null,
        usage = RESPONSE_USAGE,
        turnsUsed = 1,
        elapsed = Duration.ZERO,
    )

    @Test
    @DisplayName("The cut lands before the failed LLM call, keeping the run's earlier progress")
    fun cutLandsBeforeTheFailedCall() {
        val plan = retryPlan(
            listOf(started(0), run(1), call(2), answered(3), toolDone(4), call(5), failed(6)),
        )

        assertEquals(4, plan.lastSurvivingSequenceId)
        assertEquals("test-model", plan.settings.model)
    }

    @Test
    @DisplayName("A failure on the first turn cuts back to the run's own start")
    fun firstTurnFailureCutsToTheRunStart() {
        val plan = retryPlan(listOf(started(0), run(1), call(2), failed(3)))

        assertEquals(1, plan.lastSurvivingSequenceId)
    }

    @Test
    @DisplayName("An in-band provider failure's poisoned answer goes with the failure tail")
    fun inBandFailureCutsThePoisonedAnswer() {
        // finish_reason 'error' logs an llm_call_finished before the run ends:
        // that answer must not survive as conversation.
        val plan = retryPlan(listOf(started(0), run(1), call(2), answered(3), failed(4)))

        assertEquals(1, plan.lastSurvivingSequenceId)
    }

    @Test
    @DisplayName("Logs without a failed run to resume refuse, each naming its reason")
    fun logsWithoutARetryableFailureRefuse() {
        val completed = listOf(started(0), run(1), call(2), answered(3), failed(4, RunResult.Status.COMPLETED))
        val ghostFinish =
            listOf(started(0), run(1), call(2), answered(3), failed(4, RunResult.Status.COMPLETED), failed(5))
        val neverRan = listOf(started(0))

        assertContains(
            assertFailsWith<SessionConflictException> { retryPlan(completed) }.message.orEmpty(),
            "did not fail",
        )
        assertContains(
            assertFailsWith<SessionConflictException> { retryPlan(ghostFinish) }.message.orEmpty(),
            "no run to resume",
        )
        assertFailsWith<SessionConflictException> { retryPlan(neverRan) }
        assertContains(
            assertFailsWith<SessionConflictException> {
                retryPlan(listOf(started(0), run(1, model = null), call(2), failed(3)))
            }.message.orEmpty(),
            "no model",
        )
    }
}
