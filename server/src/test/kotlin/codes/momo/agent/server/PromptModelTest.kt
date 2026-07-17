package codes.momo.agent.server

import ai.router.sdk.models.ChatRequest
import ai.router.sdk.models.ReasoningEffort
import codes.momo.agent.AgentEvent
import codes.momo.agent.asReply
import codes.momo.agent.assistantResponse
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PromptModelTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("A prompt's model and effort reach the LLM call and are recorded on run_started")
    fun modelAndEffortReachTheLlmCallAndTheLog() {
        val requests = CopyOnWriteArrayList<ChatRequest>()
        withScriptedSessionServer(
            tempDir,
            requests,
            assistantResponse(finishReason = "stop", text = "done").asReply(),
        ) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id

            http.prompt(id, "go", model = "bigger-model", reasoningEffort = ReasoningEffort.HIGH)
            val events = http.streamEvents(id).map { it.event }

            val request = requests.single()
            assertEquals("bigger-model", request.model)
            assertEquals(ReasoningEffort.HIGH, request.reasoningEffort)
            val started = events.filterIsInstance<AgentEvent.RunStarted>().single()
            assertEquals("bigger-model", started.model)
            assertEquals(ReasoningEffort.HIGH, started.reasoningEffort)
        }
    }

    @Test
    @DisplayName("Effort stays optional: a prompt without one runs its model with no effort")
    fun effortlessPromptRunsWithNoEffort() {
        val requests = CopyOnWriteArrayList<ChatRequest>()
        withScriptedSessionServer(
            tempDir,
            requests,
            assistantResponse(finishReason = "stop", text = "done").asReply(),
        ) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id

            http.prompt(id, "go", model = "test-model")
            val events = http.streamEvents(id).map { it.event }

            val request = requests.single()
            assertEquals("test-model", request.model)
            assertNull(request.reasoningEffort)
            val started = events.filterIsInstance<AgentEvent.RunStarted>().single()
            assertEquals("test-model", started.model)
            assertNull(started.reasoningEffort)
        }
    }

    @Test
    @DisplayName("A prompt without a model is a 400 invalid_request")
    fun missingModelIsRejected() {
        withSessionServer(tempDir) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id

            val response = http.post("/v1/sessions/$id/prompt") {
                setBody(TextContent("""{"prompt": "go"}""", ContentType.Application.Json))
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalid_request", response.body<ApiError>().code)
        }
    }

    @Test
    @DisplayName("A blank model is a 400 invalid_request")
    fun blankModelIsRejected() {
        withSessionServer(tempDir) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id

            val response = http.promptResponse(id, "go", model = "   ")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalid_request", response.body<ApiError>().code)
        }
    }

    @Test
    @DisplayName("An unknown reasoning-effort value is a 400 invalid_request")
    fun unknownEffortIsRejected() {
        withSessionServer(tempDir) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id

            val response = http.post("/v1/sessions/$id/prompt") {
                setBody(
                    TextContent(
                        """{"prompt": "go", "model": "test-model", "reasoningEffort": "ultra"}""",
                        ContentType.Application.Json,
                    ),
                )
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalid_request", response.body<ApiError>().code)
        }
    }
}
