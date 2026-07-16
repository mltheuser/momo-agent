package codes.momo.agent.server

import ai.router.sdk.models.Capability
import ai.router.sdk.models.ChatRequest
import ai.router.sdk.models.ModelInfo
import ai.router.sdk.models.ModelList
import ai.router.sdk.models.ProviderType
import ai.router.sdk.models.ReasoningEffort
import codes.momo.agent.AgentEvent
import codes.momo.agent.ScriptedReply
import codes.momo.agent.asReply
import codes.momo.agent.assistantResponse
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelOverrideTest {

    @TempDir
    lateinit var tempDir: Path

    // ─── Per-run overrides ────────────────────────────────────────────

    @Test
    @DisplayName("A prompt's model and effort overrides reach the LLM call and are recorded on run_started")
    fun overridesReachTheLlmCallAndTheLog() {
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
            // The overrides are per run: session info keeps the harness default.
            assertEquals("test-model", http.get("/v1/sessions/$id").body<SessionInfo>().model)
        }
    }

    @Test
    @DisplayName("Without overrides a run calls and records the harness model, with no effort")
    fun defaultRunRecordsTheHarnessModel() {
        val requests = CopyOnWriteArrayList<ChatRequest>()
        withScriptedSessionServer(
            tempDir,
            requests,
            assistantResponse(finishReason = "stop", text = "done").asReply(),
        ) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id

            http.prompt(id, "go")
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
    @DisplayName("A blank model override is a 400 invalid_request")
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
                setBody(TextContent("""{"prompt": "go", "reasoningEffort": "ultra"}""", ContentType.Application.Json))
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalid_request", response.body<ApiError>().code)
        }
    }

    // ─── The model catalog ────────────────────────────────────────────

    @Test
    @DisplayName("GET /v1/models serves only chat+tools models, in ai-router's response shape")
    fun modelsEndpointFiltersToChatPlusTools() {
        val catalog = ModelList(
            `object` = "list",
            data = listOf(
                ModelInfo(
                    id = "qwen3.5:27b",
                    provider = "ollama",
                    providerType = ProviderType.LOCAL,
                    capabilities = listOf(Capability.CHAT, Capability.TOOLS, Capability.REASONING),
                ),
                ModelInfo(
                    id = "chat-only",
                    provider = "ollama",
                    providerType = ProviderType.LOCAL,
                    capabilities = listOf(Capability.CHAT),
                ),
                ModelInfo(
                    id = "cloud-coder",
                    provider = "anthropic",
                    providerType = ProviderType.CLOUD,
                    capabilities = listOf(Capability.CHAT, Capability.TOOLS),
                ),
            ),
        )
        val reply = ScriptedReply.Raw(statusCode = 200, body = Json.encodeToString(ModelList.serializer(), catalog))
        withScriptedSessionServer(tempDir, reply) { http ->
            val response = http.get("/v1/models")

            assertEquals(HttpStatusCode.OK, response.status)
            // ai-router's shape survives the proxy: envelope plus snake_case item fields.
            assertContains(response.bodyAsText(), "\"provider_type\"")
            val served = response.body<ModelList>()
            assertEquals("list", served.`object`)
            assertEquals(listOf("qwen3.5:27b", "cloud-coder"), served.data.map { it.id })
            assertEquals(ProviderType.CLOUD, served.data.last().providerType)
        }
    }
}
