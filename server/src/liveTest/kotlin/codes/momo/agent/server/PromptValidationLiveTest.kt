package codes.momo.agent.server

import codes.momo.agent.harness.harnessPath
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * What the prompt route rejects before any run starts, against the real
 * server process: every case is answered without ever reaching the model.
 */
class PromptValidationLiveTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("A blank prompt is a 400 invalid_request")
    fun blankPromptIsRejected() {
        withLiveServer { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id

            val response = http.promptResponse(id, "   ")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalid_request", response.body<ApiError>().code)
        }
    }

    @Test
    @DisplayName("A prompt without a model is a 400 invalid_request")
    fun missingModelIsRejected() {
        withLiveServer { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id

            val response = http.rawPromptResponse(id, """{"prompt": "go"}""")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalid_request", response.body<ApiError>().code)
        }
    }

    @Test
    @DisplayName("A blank model is a 400 invalid_request")
    fun blankModelIsRejected() {
        withLiveServer { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id

            val response = http.promptResponse(id, "go", model = "   ")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalid_request", response.body<ApiError>().code)
        }
    }

    @Test
    @DisplayName("An unknown reasoning-effort value is a 400 invalid_request")
    fun unknownEffortIsRejected() {
        withLiveServer { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id

            val response = http.rawPromptResponse(
                id,
                """{"prompt": "go", "model": "some-model", "reasoningEffort": "ultra"}""",
            )

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalid_request", response.body<ApiError>().code)
        }
    }

    @Test
    @DisplayName("Prompt and events on an unknown session are 404 unknown_session")
    fun unknownSessionIs404() {
        withLiveServer { http ->
            val prompt = http.promptResponse("no-such-id", "hello")
            assertEquals(HttpStatusCode.NotFound, prompt.status)
            assertEquals("unknown_session", prompt.body<ApiError>().code)

            val events = http.get("/v1/sessions/no-such-id/events")
            assertEquals(HttpStatusCode.NotFound, events.status)
            assertEquals("unknown_session", events.body<ApiError>().code)
        }
    }
}
