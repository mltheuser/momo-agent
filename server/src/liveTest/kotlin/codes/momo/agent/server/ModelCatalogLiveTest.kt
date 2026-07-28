package codes.momo.agent.server

import ai.router.sdk.models.Capability
import ai.router.sdk.models.ModelList
import codes.momo.agent.liveChatModel
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one route that transforms ai-router's own output, driven against the
 * router itself. What the catalog holds is the router's business, so this
 * asserts its shape and the presence of the model the tier converses with,
 * never a fixed set. It reaches no chat completion, so it costs the suite
 * milliseconds.
 */
class ModelCatalogLiveTest {

    @Test
    @DisplayName("GET /v1/models serves the running router's catalog, filtered to what an agent run can use")
    fun modelsEndpointServesTheRoutersCatalog() = withLiveServer { http ->
        val response = http.get("/v1/models")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status, body)
        // ai-router's shape survives the proxy: envelope plus snake_case item fields.
        assertContains(body, "\"provider_type\"")
        val served = response.body<ModelList>()
        assertEquals("list", served.`object`)
        assertContains(
            served.data.map { it.model },
            liveChatModel,
            "the model this tier converses with must survive the route's capability filter",
        )
        served.data.forEach { model ->
            assertTrue(
                model.hasCapability(Capability.CHAT) && model.hasCapability(Capability.TOOLS),
                "an agent run needs both chat and tools: $model",
            )
        }
    }
}
