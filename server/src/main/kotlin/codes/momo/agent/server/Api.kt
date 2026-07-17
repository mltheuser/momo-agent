package codes.momo.agent.server

import ai.router.sdk.AiRouterClient
import ai.router.sdk.models.Capability
import ai.router.sdk.models.ReasoningEffort
import codes.momo.agent.RunSettings
import codes.momo.agent.SubagentRevivalException
import codes.momo.agent.environment.EnvironmentStartupException
import codes.momo.agent.harness.HarnessValidationException
import io.ktor.http.ContentType
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
import io.ktor.server.response.respondText
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Body of a create-session request. */
@Serializable
internal data class CreateSessionRequest(
    /** Server-local path of the harness folder. */
    val harnessPath: String,
    val environment: EnvironmentSpec,
    /** Optional session title; defaults to the harness folder's name. */
    val title: String? = null,
)

/** Body of a prompt request: the user message text the next run answers, plus the run's [RunSettings] fields. */
@Serializable
internal data class PromptRequest(
    val prompt: String,
    val model: String,
    val reasoningEffort: ReasoningEffort? = null,
)

/** Body of a rename request: the session's new title. */
@Serializable
internal data class RenameRequest(val title: String)

/** Body of a favorite request: the flag's new value. */
@Serializable
internal data class FavoriteRequest(val favorite: Boolean)

/** Every failing response's body: a machine-readable [code] plus a human [message]. */
@Serializable
internal data class ApiError(val code: String, val message: String)

/** The agent server's HTTP surface over [registry]; [client] backs the model-catalog proxy. */
internal fun Application.agentServer(registry: SessionRegistry, client: AiRouterClient) {
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
        exception<SubagentRevivalException> { call, failure ->
            call.respondError(HttpStatusCode.Conflict, "unrevivable_subagent", failure)
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
        modelRoutes(client)
    }
}

/** Mirrors ai-router's own field omission: defaulted and null catalog fields stay off the wire. */
private val catalogJson = Json {
    encodeDefaults = false
    explicitNulls = false
}

/**
 * `GET /v1/models`: ai-router's catalog in its own response shape,
 * filtered to the models an agent run can use — capabilities including
 * both chat and tools. ai-router's server-side capability filter takes a
 * single capability, so tools is filtered here.
 */
private fun Route.modelRoutes(client: AiRouterClient) {
    get("/v1/models") {
        val catalog = client.listModels(capability = Capability.CHAT)
        val usable = catalog.copy(data = catalog.data.filter { it.hasCapability(Capability.TOOLS) })
        call.respondText(catalogJson.encodeToString(usable), ContentType.Application.Json)
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
                val request = call.receive<PromptRequest>().validated()
                val id = call.sessionId()
                registry.startRun(id, request.prompt, RunSettings(request.model, request.reasoningEffort))
                call.respond(HttpStatusCode.Accepted, registry.info(id))
            }
            post("/rename") {
                val request = call.receive<RenameRequest>()
                if (request.title.isBlank()) {
                    throw BadRequestException("A title must not be blank.")
                }
                call.respond(registry.rename(call.sessionId(), request.title))
            }
            post("/favorite") {
                val request = call.receive<FavoriteRequest>()
                call.respond(registry.setFavorite(call.sessionId(), request.favorite))
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

/** @throws BadRequestException when the prompt or the model is blank. */
private fun PromptRequest.validated(): PromptRequest {
    if (prompt.isBlank()) {
        throw BadRequestException("A prompt must not be blank.")
    }
    // Pre-empts RunSettings' own blank-model require: the rule must read
    // as a 400 here, not a 500 from the failed construction.
    if (model.isBlank()) {
        throw BadRequestException("A model must not be blank.")
    }
    return this
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
