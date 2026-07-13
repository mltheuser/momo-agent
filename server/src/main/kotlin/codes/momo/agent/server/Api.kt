package codes.momo.agent.server

import codes.momo.agent.environment.EnvironmentStartupException
import codes.momo.agent.harness.HarnessValidationException
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.ContentConvertException
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import kotlinx.serialization.Serializable

/** Body of a create-session request. */
@Serializable
internal data class CreateSessionRequest(
    /** Server-local path of the harness folder. */
    val harnessPath: String,
    val environment: EnvironmentSpec,
    /** Optional session title; defaults to the harness folder's name. */
    val title: String? = null,
)

/** Body of a prompt request: the user message text the next run answers. */
@Serializable
internal data class PromptRequest(val prompt: String)

/** Every failing response's body: a machine-readable [code] plus a human [message]. */
@Serializable
internal data class ApiError(val code: String, val message: String)

/** The agent server's HTTP surface over [registry]. */
internal fun Application.agentServer(registry: SessionRegistry) {
    install(ContentNegotiation) {
        json()
    }
    install(SSE)
    install(StatusPages) {
        exception<UnknownSessionException> { call, failure ->
            call.respondError(HttpStatusCode.NotFound, "unknown_session", failure)
        }
        exception<HarnessValidationException> { call, failure ->
            call.respondError(HttpStatusCode.BadRequest, "invalid_harness", failure)
        }
        exception<EnvironmentStartupException> { call, failure ->
            call.respondError(HttpStatusCode.BadRequest, "invalid_environment", failure)
        }
        exception<BadRequestException> { call, failure ->
            call.respondError(HttpStatusCode.BadRequest, "invalid_request", failure.rootMessage())
        }
        exception<ContentConvertException> { call, failure ->
            call.respondError(HttpStatusCode.BadRequest, "invalid_request", failure.rootMessage())
        }
        exception<SessionConflictException> { call, failure ->
            call.respondError(HttpStatusCode.Conflict, "conflict", failure)
        }
        exception<CorruptSessionException> { call, failure ->
            call.respondError(HttpStatusCode.InternalServerError, "corrupt_session", failure)
        }
        exception<EventLogFailedException> { call, failure ->
            call.respondError(HttpStatusCode.InternalServerError, "event_log_failed", failure)
        }
        exception<Throwable> { call, failure ->
            call.respondError(HttpStatusCode.InternalServerError, "internal_error", failure)
        }
    }
    routing {
        sessionRoutes(registry)
    }
}

private fun Route.sessionRoutes(registry: SessionRegistry) {
    route("/v1/sessions") {
        post {
            val request = call.receive<CreateSessionRequest>()
            val info = registry.create(request.harnessPath, request.environment, request.title)
            call.respond(HttpStatusCode.Created, info)
        }
        get {
            call.respond(registry.list())
        }
        route("/{id}") {
            get {
                call.respond(registry.info(call.sessionId()))
            }
            post("/prompt") {
                val request = call.receive<PromptRequest>()
                if (request.prompt.isBlank()) {
                    throw BadRequestException("A prompt must not be blank.")
                }
                val id = call.sessionId()
                registry.startRun(id, request.prompt)
                call.respond(HttpStatusCode.Accepted, registry.info(id))
            }
            eventStreamRoute(registry)
            post("/close") {
                registry.close(call.sessionId())
                call.respond(registry.info(call.sessionId()))
            }
            delete {
                registry.delete(call.sessionId())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

/**
 * The session's event log as an SSE stream: `id:` carries the event's
 * sequenceId, `data:` the event JSON exactly as stored, and a
 * `Last-Event-ID` header resumes strictly after it.
 *
 * The unknown-session check runs as a route-scoped plugin: once the SSE
 * handler runs, the 200 is already committed, too late for a 404.
 */
private fun Route.eventStreamRoute(registry: SessionRegistry) {
    val knownSessionGuard = createRouteScopedPlugin("KnownSessionGuard") {
        onCall { call -> registry.requireKnown(call.sessionId()) }
    }
    route("/events") {
        install(knownSessionGuard)
        sse {
            // A dead peer only surfaces on a failed write: the periodic
            // comment frame reclaims subscribers parked on an idle stream.
            heartbeat()
            val afterSequenceId = call.request.header("Last-Event-ID")?.toLongOrNull() ?: BEFORE_FIRST_EVENT
            registry.eventsAfter(call.sessionId(), afterSequenceId).collect { event ->
                send(data = event.json, id = event.sequenceId.toString())
            }
        }
    }
}

private fun ApplicationCall.sessionId(): String = checkNotNull(parameters["id"]) { "route without {id}" }

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String, failure: Throwable) {
    respondError(status, code, failure.message ?: failure.javaClass.simpleName)
}

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String, message: String) {
    respond(status, ApiError(code, message))
}

/** Ktor wraps deserialization failures; the innermost message names the actual problem. */
private fun Throwable.rootMessage(): String {
    val root = generateSequence(this) { it.cause }.last()
    return root.message ?: root.javaClass.simpleName
}
