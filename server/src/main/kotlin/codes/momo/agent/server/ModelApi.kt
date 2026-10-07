package codes.momo.agent.server

import ai.router.sdk.AiRouterClient
import ai.router.sdk.ModelRef
import codes.momo.agent.tool.ToolCatalog
import codes.momo.agent.tool.ToolModelSource
import codes.momo.agent.usableChatModels
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val catalogJson = Json {
    encodeDefaults = false
    explicitNulls = false
}

internal fun Route.modelRoutes(client: AiRouterClient) {
    get("/v1/chat/models") {
        call.respondText(catalogJson.encodeToString(client.usableChatModels()), ContentType.Application.Json)
    }
    get("/v1/tools/{tool}/models") {
        val tool = checkNotNull(call.parameters["tool"]) { "route without {tool}" }
        val source = ToolCatalog.listed(tool)?.modelSource
        if (source == null) {
            call.respondError(HttpStatusCode.NotFound, "unknown_tool", "No tool '$tool' takes a model.")
        } else {
            call.respondText(source.encodedModels(client), ContentType.Application.Json)
        }
    }
}

private suspend fun <M : ModelRef> ToolModelSource<M>.encodedModels(client: AiRouterClient): String =
    catalogJson.encodeToString(serializer, models(client))
