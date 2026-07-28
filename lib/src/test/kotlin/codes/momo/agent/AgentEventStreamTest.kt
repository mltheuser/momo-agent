package codes.momo.agent

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

class AgentEventStreamTest {

    @TempDir
    lateinit var workspace: Path

    // ─── Retries ──────────────────────────────────────────────────────

    @Test
    @DisplayName("A transient LLM failure emits a retry event carrying the cause and backoff")
    fun transientFailureEmitsRetryEvent() {
        val listener = CollectingEventListener()

        val result = workspace.runAgainstFake(
            listener,
            transientFailure("a fake overload"),
            onOpeningTurn(assistantResponse(finishReason = "stop", text = "ok")),
            harness = TEST_HARNESS,
        )

        assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
        val retry = listener.events.filterIsInstance<AgentEvent.LlmCallRetried>().single()
        assertEquals(1, retry.attempt)
        assertEquals(INITIAL_RETRY_BACKOFF, retry.backoff)
        assertContains(retry.cause, "a fake overload")
    }

    // ─── Timeout ──────────────────────────────────────────────────────

    @Test
    @DisplayName("A timed-out run emits its RunFinished at the boundary that ended it")
    fun timedOutRunEmitsRunFinished() {
        val listener = CollectingEventListener()

        val result = workspace.runAgainstFake(
            listener,
            onOpeningTurn(toolCallResponse(bashCall(id = "call-1", command = "sleep 30"))),
            budgets = RunBudgets(maxWallClock = 500.milliseconds),
            harness = TEST_HARNESS,
        )

        assertEquals(RunResult.Status.TIMEOUT, result.status)
        val finished = assertIs<AgentEvent.RunFinished>(listener.events.last())
        assertEquals(RunResult.Status.TIMEOUT, finished.status)
        assertNull(finished.finalMessage)
    }
}
