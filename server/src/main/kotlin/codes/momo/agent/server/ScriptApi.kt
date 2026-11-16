package codes.momo.agent.server

import codes.momo.agent.server.script.ScriptRegistry
import codes.momo.agent.server.script.ScriptRunningException
import codes.momo.agent.server.script.ScriptStartException
import codes.momo.agent.server.storage.InvalidScriptTitleException
import codes.momo.agent.server.storage.UnknownScriptException
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

internal fun StatusPagesConfig.scriptFailures() {
    exception<UnknownScriptException> { call, failure ->
        call.respondError(HttpStatusCode.NotFound, "unknown_script", failure)
    }
    exception<InvalidScriptTitleException> { call, failure ->
        call.respondError(HttpStatusCode.BadRequest, "invalid_request", failure)
    }
    exception<ScriptStartException> { call, failure ->
        call.respondError(HttpStatusCode.BadRequest, "invalid_environment", failure)
    }
    exception<ScriptRunningException> { call, failure ->
        call.respondError(HttpStatusCode.Conflict, "conflict", failure)
    }
}

internal fun Route.scriptRoutes(scripts: ScriptRegistry) {
    route("/v1/scripts") {
        get {
            call.respond(scripts.list(call.requiredWorkspace()))
        }
        post {
            val title = call.receive<TitleRequest>().title
            call.respond(HttpStatusCode.Created, scripts.create(call.requiredWorkspace(), title))
        }
        route("/{id}") {
            post("/rename") {
                val title = call.receive<TitleRequest>().title
                call.respond(scripts.rename(call.requiredWorkspace(), call.idParameter(), title))
            }
            post("/start") {
                call.respond(scripts.start(call.requiredWorkspace(), call.idParameter()))
            }
            post("/stop") {
                call.respond(scripts.stop(call.requiredWorkspace(), call.idParameter()))
            }
            delete {
                scripts.delete(call.requiredWorkspace(), call.idParameter())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
