package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A run the router refuses outright, and the retry over it. An unknown model
 * is the one terminal failure the real router produces on demand: a 404
 * `not_found_error` that must not be retried, recorded as the run's error
 * and leaving the session idle. The retry cuts the failure tail and resumes
 * the same run under the same settings — so it fails the same way — until a
 * prompt with a real model completes, after which there is nothing to retry.
 */
class FailedRunLiveTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName(
        "An unknown model fails the run with the router's 404; retry resumes it in place; completion ends retrying"
    )
    fun unknownModelFailsRetryResumesCompletionEndsRetrying() = withLiveServer { http ->
        val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
        http.prompt(id, "Reply with the single word: ready.", model = UNKNOWN_MODEL)

        val failed = http.streamEvents(id)
        val failure = assertIs<AgentEvent.RunFinished>(failed.last().event)
        assertEquals(RunResult.Status.ERROR, failure.status)
        val error = assertNotNull(failure.error, "a failed run records why")
        assertEquals(404, error.statusCode, "the router's status travels into the log: ${error.message}")
        assertEquals("not_found_error", error.type)
        assertContains(error.message, "no-such-model")
        assertTrue(failed.none { it.event is AgentEvent.LlmCallRetried }, "a 404 is terminal: never retried")
        assertNull(failure.finalMessage, "a failed run has no answer")
        http.awaitRunEnd(id)
        val info = http.sessionInfo(id)
        assertEquals(SessionStatus.IDLE, info.status, "a failed run leaves the session attached and idle")
        assertNotNull(info.lastRun, "the failed run's consumption is still reported")
        val runStart = failed.single { it.event is AgentEvent.RunStarted }.id
        val callStart = failed.single { it.event is AgentEvent.LlmCallStarted }.id

        // The retry: one request cuts the failure tail — everything from the
        // failed LLM call on — and resumes the run under its recorded model.
        // A 404 is answered in milliseconds, so the 202's own status may
        // already read idle: the run is asserted through its log instead.
        http.retryRun(id)
        val retried = http.streamEvents(id, afterSequenceId = failed.last().id)
        val rewound = assertIs<AgentEvent.ConversationRewound>(retried.first().event)
        assertEquals(runStart, rewound.lastSurvivingSequenceId, "the cut lands just before the failed LLM call")
        assertTrue(callStart > rewound.lastSurvivingSequenceId, "the failed call is inside the cut")
        val resumed = assertIs<AgentEvent.RunResumed>(retried[1].event, "the cut is followed by the resumption")
        assertEquals(UNKNOWN_MODEL, resumed.model, "the retry reruns under the failed run's own settings")
        assertTrue(retried.none { it.event is AgentEvent.RunStarted }, "a retry opens no second run")
        val failedAgain = assertIs<AgentEvent.RunFinished>(retried.last().event)
        assertEquals(RunResult.Status.ERROR, failedAgain.status, "the same model fails the same way")
        assertEquals(404, failedAgain.error?.statusCode)
        http.awaitRunEnd(id)

        // A prompt naming a real model completes over the same session.
        http.prompt(id, "Reply with the single word: ready.")
        val completed = assertIs<AgentEvent.RunFinished>(
            http.streamEvents(id, afterSequenceId = retried.last().id).last().event,
        )
        assertEquals(RunResult.Status.COMPLETED, completed.status, "error: ${completed.error}")
        http.awaitRunEnd(id)

        val nothingToRetry = http.retryResponse(id)
        assertEquals(HttpStatusCode.Conflict, nothingToRetry.status, "a completed run is not retryable")
        assertContains(nothingToRetry.bodyAsText(), "did not fail")
        assertEquals("conflict", nothingToRetry.body<ApiError>().code)

        val fresh = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
        assertEquals(HttpStatusCode.Conflict, http.retryResponse(fresh).status, "a session that never ran")
    }
}

/**
 * A model the router does not serve. The `:cloud` tag keeps the router's
 * refusal a plain not-found — an untagged id makes it ask which tag was
 * meant instead.
 */
private const val UNKNOWN_MODEL: String = "no-such-model:cloud"
