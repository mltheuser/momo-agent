package codes.momo.agent.server.storage

import ai.router.sdk.models.ChatMessage
import ai.router.sdk.models.ChatUsage
import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class TornRunsTest {

    private val started =
        AgentEvent.SessionStarted(0, 0, sessionId = "s", title = "s", harnessPath = null, workspace = "/w")

    private fun turn(seq: Long, tokens: Int) = AgentEvent.LlmCallFinished(
        sequenceId = seq,
        timestampMillis = seq,
        message = ChatMessage(role = "assistant", content = emptyList()),
        usage = ChatUsage(tokens, 0, tokens, 0, 0),
        finishReason = "tool_calls",
    )

    private fun finished(seq: Long) = AgentEvent.RunFinished(
        sequenceId = seq,
        timestampMillis = seq,
        status = RunResult.Status.COMPLETED,
        finalMessage = "done",
        usage = ChatUsage(0, 0, 0, 0, 0),
        turnsUsed = 1,
        elapsed = 1.seconds,
    )

    private fun lines(vararg events: AgentEvent): Sequence<LogLine> =
        events.reversed().map { parseLogLine(encodeLogLine(it)) }.asSequence()

    @Test
    @DisplayName("A run left open by a kill is finished as interrupted with the stats its events carry")
    fun anOpenRunIsFinishedAsInterrupted() {
        val torn = lines(
            started,
            AgentEvent.RunStarted(1, 1, userMessage = "first"),
            finished(2),
            AgentEvent.RunResumed(3, 3, model = "m"),
            turn(4, 10),
            AgentEvent.BudgetUpdated(5, 5, turnsUsed = 1, turnsRemaining = 9, elapsed = 3.seconds),
            turn(6, 5),
            AgentEvent.ToolCallStarted(7, 7, callId = "c", toolName = "bash", arguments = JsonObject(emptyMap())),
        )
        val openRun = assertNotNull(openRunOf(torn))
        assertEquals((3L..7L).toList(), openRun.map { it.sequenceId }, "the open run starts at the last opener")

        val repair = interruptedRunFinished(openRun, timestampMillis = 99)
        assertEquals(8L, repair.sequenceId, "the marker continues the log's gapless sequence")
        assertEquals(RunResult.Status.INTERRUPTED, repair.status)
        assertNull(repair.finalMessage)
        assertEquals(2, repair.turnsUsed)
        assertEquals(15, repair.usage.totalTokens)
        assertEquals(3.seconds, repair.elapsed)
        assertNull(repair.error)
    }

    @Test
    @DisplayName("A finished, rewound or never-run log needs no repair")
    fun aClosedTailNeedsNoRepair() {
        assertNull(openRunOf(lines(started, AgentEvent.RunStarted(1, 1, userMessage = "go"), finished(2))))
        val rewound = lines(started, AgentEvent.RunStarted(1, 1, "go"), AgentEvent.ConversationRewound(2, 2, 0))
        assertNull(openRunOf(rewound))
        assertNull(openRunOf(lines(started, AgentEvent.SessionRenamed(1, 1, "titled"))))
        assertNull(openRunOf(emptySequence()))
    }
}
