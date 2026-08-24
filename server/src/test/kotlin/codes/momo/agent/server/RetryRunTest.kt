package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.FakeLlm
import codes.momo.agent.FakeLlmReply
import codes.momo.agent.RunResult
import codes.momo.agent.TEST_RUN_SETTINGS
import codes.momo.agent.assistantResponse
import codes.momo.agent.bashCall
import codes.momo.agent.harness.harnessPath
import codes.momo.agent.onAnyTurn
import codes.momo.agent.onOpeningTurn
import codes.momo.agent.onToolResults
import codes.momo.agent.toolCallResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The retry endpoint: a session whose last run failed is rewound to before
 * the failed LLM call and resumed in one request, keeping the failed run's
 * partial progress and settings; a session whose tail is anything else
 * refuses with a 409.
 */
class RetryRunTest {

    @TempDir
    lateinit var tempDir: Path

    private suspend fun HttpClient.retryResponse(sessionId: String): HttpResponse =
        post("/v1/sessions/$sessionId/retry")

    private suspend fun HttpClient.retry(sessionId: String): SessionInfo {
        val response = retryResponse(sessionId)
        assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
        return response.body()
    }

    @Test
    @DisplayName("Retrying a failed run cuts its failure tail and resumes it to completion, progress kept")
    fun retryResumesAFailedRunKeepingItsProgress() {
        // The run makes one turn of progress, then fails terminally; the
        // retry's rerun of the second turn answers with the completion.
        val fake = FakeLlm(
            onOpeningTurn(toolCallResponse(bashCall(id = "call-1", command = "echo hi"))),
            onAnyTurn(
                reply = FakeLlmReply.Failure(statusCode = 404, message = "model not found"),
                expectation = "the second turn, failing terminally once",
                uses = 1,
            ),
            onToolResults(assistantResponse(finishReason = "stop", text = "recovered")),
        )

        withFakeSessionServer(tempDir, fake) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            http.prompt(id, "go")
            http.awaitRunEnd(id)
            val failed = sessionStore(tempDir).readEvents(id)
            assertEquals(RunResult.Status.ERROR, assertIs<AgentEvent.RunFinished>(failed.last()).status)

            http.retry(id)
            http.awaitRunEnd(id)

            val events = sessionStore(tempDir).readEvents(id)
            val finished = assertIs<AgentEvent.RunFinished>(events.last())
            assertEquals(RunResult.Status.COMPLETED, finished.status)
            assertEquals("recovered", finished.finalMessage)
            val resumed = assertIs<AgentEvent.RunResumed>(
                events.first { it.sequenceId > events.first { e -> e is AgentEvent.ConversationRewound }.sequenceId },
            )
            assertEquals(TEST_RUN_SETTINGS.model, resumed.model)
            assertEquals(
                1,
                events.count { it is AgentEvent.RunStarted },
                "the retry opens no new run — the failed one's start is the only one",
            )
            assertEquals(
                1,
                events.count { it is AgentEvent.ToolCallFinished },
                "the first turn's progress survives the cut",
            )
            assertTrue(
                events.none { it is AgentEvent.RunFinished && it.status == RunResult.Status.ERROR },
                "the failure tail is gone from the log",
            )
        }
    }

    @Test
    @DisplayName("Retrying a session whose last run completed is a 409")
    fun retryOfACompletedRunConflicts() {
        withFakeSessionServer(tempDir, onOpeningTurn(assistantResponse(finishReason = "stop", text = "ok"))) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            http.prompt(id, "go")
            http.awaitRunEnd(id)

            val response = http.retryResponse(id)

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertContains(response.bodyAsText(), "did not fail")
        }
    }

    @Test
    @DisplayName("Retrying a session that never ran is a 409")
    fun retryOfAFreshSessionConflicts() {
        withSessionServer(tempDir) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id

            val response = http.retryResponse(id)

            assertEquals(HttpStatusCode.Conflict, response.status)
        }
    }
}
