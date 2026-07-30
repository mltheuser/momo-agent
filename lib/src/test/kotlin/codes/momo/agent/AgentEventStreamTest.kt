package codes.momo.agent

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
        assertNull(finished.error)
    }

    // ─── Failures on RunFinished ──────────────────────────────────────

    @Test
    @DisplayName("A terminal router failure puts its message, type and status on RunFinished verbatim")
    fun terminalFailureCarriesTheErrorOnRunFinished() {
        val listener = CollectingEventListener()

        val result = workspace.runAgainstFake(
            listener,
            onAnyTurn(
                reply = FakeLlmReply.Failure(statusCode = 404, message = "model not found"),
                expectation = "any turn, failing terminally",
            ),
            harness = TEST_HARNESS,
        )

        assertEquals(RunResult.Status.ERROR, result.status)
        val error = assertNotNull(assertIs<AgentEvent.RunFinished>(listener.events.last()).error)
        assertEquals("$FAKE_ERROR_TYPE: model not found", error.message)
        assertEquals(FAKE_ERROR_TYPE, error.type)
        assertEquals(404, error.statusCode)
    }

    @Test
    @DisplayName("A failure whose message is null falls back to the throwable's class name, never blank")
    fun nullMessageFailureFallsBackToClassName() {
        val listener = CollectingEventListener()

        val result = workspace.runAgainstFake(
            listener,
            onAnyTurn(
                reply = FakeLlmReply.Thrown { MessagelessFailure() },
                expectation = "any turn, raising an exception carrying no message",
            ),
            harness = TEST_HARNESS,
        )

        assertEquals(RunResult.Status.ERROR, result.status)
        val error = assertNotNull(assertIs<AgentEvent.RunFinished>(listener.events.last()).error)
        assertEquals(MessagelessFailure::class.java.name, error.message)
        assertNull(error.type)
        assertNull(error.statusCode)
    }
}

/** No message and no message-carrying constructor, so no propagation step can give it one. */
private class MessagelessFailure : RuntimeException()
