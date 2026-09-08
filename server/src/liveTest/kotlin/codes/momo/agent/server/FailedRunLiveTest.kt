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
        "An unknown model fails the run with the router's 404; every retry remakes the call; completion ends retrying"
    )
    fun unknownModelFailsEveryRetryRecreatesTheCallCompletionEndsRetrying() = withLiveServer { http ->
        val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
        http.prompt(id, "Reply with the single word: ready.", model = UNKNOWN_MODEL)

        val failed = http.awaitRunEnd(id)
        val failure = assertIs<AgentEvent.RunFinished>(failed.last())
        assertEquals(RunResult.Status.ERROR, failure.status)
        val error = assertNotNull(failure.error, "a failed run records why")
        assertEquals(404, error.statusCode, "the router's status travels into the log: ${error.message}")
        assertEquals("not_found_error", error.type)
        assertContains(error.message, "no-such-model")
        assertTrue(failed.none { it is AgentEvent.LlmCallRetried }, "a 404 is terminal: never retried")
        assertNull(failure.finalMessage, "a failed run has no answer")
        val info = http.sessionInfo(id)
        assertEquals(SessionStatus.IDLE, info.status, "a failed run leaves the session idle")
        assertNotNull(info.lastRun, "the failed run's consumption is still reported")
        val runStart = failed.single { it is AgentEvent.RunStarted }
        val survivors = failed.takeWhile { it.sequenceId <= runStart.sequenceId }

        repeat(2) { attempt ->
            http.retryRun(id)
            val retried = http.awaitRunEnd(id)
            assertEquals(survivors, retried.take(survivors.size), "retry $attempt: the log up to the prompt is kept")
            assertEquals(failed.map { it::class }, retried.map { it::class }, "retry $attempt: same call, same shape")
            assertIs<AgentEvent.LlmCallStarted>(retried[survivors.size], "retry $attempt: the call follows the prompt")
            val failedAgain = assertIs<AgentEvent.RunFinished>(retried.last())
            assertEquals(RunResult.Status.ERROR, failedAgain.status, "retry $attempt: the model fails the same way")
            assertEquals(404, failedAgain.error?.statusCode)
        }

        http.prompt(id, "Reply with the single word: ready.")
        val completed = assertIs<AgentEvent.RunFinished>(http.awaitRunEnd(id).last())
        assertEquals(RunResult.Status.COMPLETED, completed.status, "error: ${completed.error}")

        http.retryResponse(id)
            .assertRejected("conflict", "a completed run is not retryable", "did not fail", HttpStatusCode.Conflict)

        val fresh = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
        assertEquals(HttpStatusCode.Conflict, http.retryResponse(fresh).status, "a session that never ran")
    }
}

private const val UNKNOWN_MODEL: String = "no-such-model:cloud"
