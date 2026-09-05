package codes.momo.agent.server

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
internal data class PutTemplateRequest(val body: String)

@Serializable
internal data class TemplateResponse(val name: String, val body: String)

internal fun Route.templateRoutes(store: TemplateStore) {
    route("/v1/templates") {
        get {
            call.respond(store.names())
        }
        get("/{name}") {
            val name = call.templateName()
            call.respond(TemplateResponse(name, store.read(name)))
        }
        put("/{name}") {
            val request = call.receive<PutTemplateRequest>()
            if (request.body.isBlank()) {
                throw BadRequestException("A template body must not be blank.")
            }
            val name = call.templateName()
            store.write(name, request.body)
            call.respond(TemplateResponse(name, request.body))
        }
    }
}

private fun ApplicationCall.templateName(): String = checkNotNull(parameters["name"]) { "route without {name}" }
