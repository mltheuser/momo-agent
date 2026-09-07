package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import codes.momo.agent.server.fixtures.harnessPath
import codes.momo.agent.server.fixtures.localWorkspace
import codes.momo.agent.server.rig.assertRejected
import codes.momo.agent.server.rig.awaitRunEnd
import codes.momo.agent.server.rig.createSession
import codes.momo.agent.server.rig.prompt
import codes.momo.agent.server.rig.retryResponse
import codes.momo.agent.server.rig.retryRun
import codes.momo.agent.server.rig.sessionInfo
import codes.momo.agent.server.rig.streamEvents
import codes.momo.agent.server.rig.withLiveServer
import codes.momo.agent.server.session.SessionStatus
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

        http.prompt(id, "Reply with the single word: ready.")
        val completed = assertIs<AgentEvent.RunFinished>(
            http.streamEvents(id, afterSequenceId = retried.last().id).last().event,
        )
        assertEquals(RunResult.Status.COMPLETED, completed.status, "error: ${completed.error}")
        http.awaitRunEnd(id)

        http.retryResponse(id)
            .assertRejected("conflict", "a completed run is not retryable", "did not fail", HttpStatusCode.Conflict)

        val fresh = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
        assertEquals(HttpStatusCode.Conflict, http.retryResponse(fresh).status, "a session that never ran")
    }
}

private const val UNKNOWN_MODEL: String = "no-such-model:cloud"
