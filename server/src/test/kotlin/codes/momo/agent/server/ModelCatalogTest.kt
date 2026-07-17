package codes.momo.agent.server

import ai.router.sdk.models.Capability
import ai.router.sdk.models.ModelInfo
import ai.router.sdk.models.ModelList
import ai.router.sdk.models.ProviderType
import codes.momo.agent.ScriptedReply
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ModelCatalogTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("GET /v1/models serves only chat+tools models, in ai-router's response shape")
    fun modelsEndpointFiltersToChatPlusTools() {
        val catalog = ModelList(
            `object` = "list",
            data = listOf(
                ModelInfo(
                    id = "qwen3.5:27b",
                    model = "qwen3.5:27b:local@ollama",
                    provider = "ollama",
                    providerType = ProviderType.LOCAL,
                    capabilities = listOf(Capability.CHAT, Capability.TOOLS, Capability.REASONING),
                ),
                ModelInfo(
                    id = "chat-only",
                    model = "chat-only:local@ollama",
                    provider = "ollama",
                    providerType = ProviderType.LOCAL,
                    capabilities = listOf(Capability.CHAT),
                ),
                ModelInfo(
                    id = "cloud-coder",
                    model = "cloud-coder:cloud@anthropic",
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
            // The catalog's fully-qualified request strings survive the proxy.
            assertEquals(
                listOf("qwen3.5:27b:local@ollama", "cloud-coder:cloud@anthropic"),
                served.data.map { it.model },
            )
            assertEquals(ProviderType.CLOUD, served.data.last().providerType)
        }
    }
}
