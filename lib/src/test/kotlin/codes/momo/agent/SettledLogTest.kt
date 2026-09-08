package codes.momo.agent

import ai.router.sdk.models.ChatMessage
import ai.router.sdk.models.ChatUsage
import ai.router.sdk.models.ToolCall
import ai.router.sdk.models.ToolCallFunction
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class SettledLogTest {

    private fun turn(seq: Long, tokens: Int, vararg callIds: String) = AgentEvent.LlmCallFinished(
        sequenceId = seq,
        timestampMillis = seq,
        message = ChatMessage(
            role = "assistant",
            content = emptyList(),
            toolCalls = callIds.map { ToolCall(id = it, function = ToolCallFunction("bash", JsonObject(emptyMap()))) },
        ),
        usage = ChatUsage(tokens, 0, tokens, 0, 0),
        finishReason = "tool_calls",
    )

    private fun started(seq: Long, callId: String) =
        AgentEvent.ToolCallStarted(seq, seq, callId = callId, toolName = "bash", arguments = JsonObject(emptyMap()))

    @Test
    @DisplayName("A run a kill left open gets its cut-short calls answered, then a run_finished(interrupted)")
    fun anOpenRunIsSettled() {
        val openRun = listOf(
            AgentEvent.RunResumed(3, 3, model = "m"),
            turn(4, 10),
            AgentEvent.BudgetUpdated(5, 5, turnsUsed = 1, turnsRemaining = 9, elapsed = 3.seconds),
            turn(6, 5, "running", "queued"),
            started(7, "running"),
        )

        val repairs = repairInterruptedRun(openRun, timestampMillis = 99)

        assertEquals(listOf(8L, 9L, 10L), repairs.map { it.sequenceId }, "the repairs continue the gapless log")
        val running = assertIs<AgentEvent.ToolCallFinished>(repairs[0])
        assertEquals("running" to AgentEvent.ToolCallFinished.Outcome.ERROR, running.callId to running.outcome)
        assertContains(running.resultText, "cut short")
        assertContains(running.resultText, "the server went down")
        val queued = assertIs<AgentEvent.ToolCallFinished>(repairs[1])
        assertEquals("queued", queued.callId)
        assertContains(queued.resultText, "not executed")
        val finished = assertIs<AgentEvent.RunFinished>(repairs[2])
        assertEquals(RunResult.Status.INTERRUPTED, finished.status)
        assertNull(finished.finalMessage)
        assertEquals(2, finished.turnsUsed)
        assertEquals(15, finished.usage.totalTokens)
        assertEquals(3.seconds, finished.elapsed)
        assertNull(finished.error)
    }

    @Test
    @DisplayName("A rewind that lands inside a turn answers the calls whose results it cut away")
    fun aRewindInsideATurnIsSettled() {
        val surviving = listOf(AgentEvent.RunStarted(1, 1, "go"), turn(2, 1, "ran", "queued"), started(3, "ran"))

        val answers = answerCallsCutByRewind(surviving, firstSequenceId = 10, timestampMillis = 99)

        assertEquals(listOf(10L, 11L), answers.map { it.sequenceId }, "numbered where the caller says, above the cut")
        assertEquals(listOf("ran", "queued"), answers.map { it.callId })
        assertContains(answers[0].resultText, "result rewound")
        assertContains(answers[1].resultText, "not executed")
        assertEquals(emptyList(), answerCallsCutByRewind(surviving + answers, 12, 99), "a settled log needs nothing")
    }

    @Test
    @DisplayName("Only an open run can be repaired")
    fun aSettledRunIsRefused() {
        val finished =
            AgentEvent.RunFinished(2, 2, RunResult.Status.COMPLETED, "ok", ChatUsage(0, 0, 0, 0, 0), 1, 1.seconds)
        assertFailsWith<IllegalArgumentException> {
            repairInterruptedRun(listOf(AgentEvent.RunStarted(1, 1, "go"), finished), timestampMillis = 9)
        }
        assertFailsWith<IllegalArgumentException> { repairInterruptedRun(listOf(turn(4, 1)), timestampMillis = 9) }
    }
}
