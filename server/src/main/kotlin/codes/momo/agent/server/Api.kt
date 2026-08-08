package codes.momo.agent.server

import ai.router.sdk.AiRouterClient
import ai.router.sdk.models.ReasoningEffort
import codes.momo.agent.RunSettings
import codes.momo.agent.SubagentRevivalException
import codes.momo.agent.environment.EnvironmentStartupException
import codes.momo.agent.harness.HarnessValidationException
import codes.momo.agent.usableModels
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
import kotlinx.coroutines.flow.onSubscription
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

/** Body of a select-model request: the model the session's next prompt should carry. */
@Serializable
internal data class SelectModelRequest(
    val model: String,
    /** Null asks for the provider default. */
    val reasoningEffort: ReasoningEffort? = null,
)

/** Body of a rewind request: the sequence ID of the first event the cut deletes. */
@Serializable
internal data class RewindRequest(val firstDeletedSequenceId: Long)

/** A rewind's response: the session as cut, plus every session the cascade deleted. */
@Serializable
internal data class RewindResponse(val session: SessionInfo, val deletedSessionIds: List<String>)

/** Every failing response's body: a machine-readable [code] plus a human [message]. */
@Serializable
internal data class ApiError(val code: String, val message: String)

/**
 * The agent server's HTTP surface over [registry]; [client] backs the
 * model-catalog proxy and [templates] the template routes.
 */
internal fun Application.agentServer(registry: SessionRegistry, client: AiRouterClient, templates: TemplateStore) {
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
        exception<InvalidRewindPointException> { call, failure ->
            call.respondError(HttpStatusCode.BadRequest, "invalid_request", failure)
        }
        exception<ContentConvertException> { call, failure ->
            call.respondError(HttpStatusCode.BadRequest, "invalid_request", failure.rootMessage())
        }
        exception<UnknownTemplateException> { call, failure ->
            call.respondError(HttpStatusCode.NotFound, "unknown_template", failure)
        }
        exception<InvalidTemplateNameException> { call, failure ->
            call.respondError(HttpStatusCode.BadRequest, "invalid_request", failure)
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
        templateRoutes(templates)
    }
}

/** Mirrors ai-router's own field omission: defaulted and null catalog fields stay off the wire. */
private val catalogJson = Json {
    encodeDefaults = false
    explicitNulls = false
}

/** `GET /v1/models`: ai-router's catalog in its own response shape, filtered by [usableModels]. */
private fun Route.modelRoutes(client: AiRouterClient) {
    get("/v1/models") {
        call.respondText(catalogJson.encodeToString(client.usableModels()), ContentType.Application.Json)
    }
}

private fun Route.sessionRoutes(registry: SessionRegistry) {
    route("/v1/sessions") {
        changeStreamRoute(registry)
        post {
            val request = call.receive<CreateSessionRequest>()
            val info = registry.create(request.harnessPath, request.environment, request.title)
            call.respond(HttpStatusCode.Created, info)
        }
        get {
            call.respond(registry.list(call.requiredWorkspace()))
        }
        route("/{id}") {
            scopeToWorkspace(registry)
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
            post("/select-model") {
                val request = call.receive<SelectModelRequest>()
                if (request.model.isBlank()) {
                    // Pre-empts the lib's blank-model require: the rule must
                    // read as a 400 here, not a 500 from the failed emission.
                    throw BadRequestException("A model must not be blank.")
                }
                call.respond(registry.selectModel(call.sessionId(), request.model, request.reasoningEffort))
            }
            post("/rewind") {
                val request = call.receive<RewindRequest>()
                val id = call.sessionId()
                val deletedSessionIds = registry.rewind(id, request.firstDeletedSequenceId)
                call.respond(RewindResponse(registry.info(id), deletedSessionIds))
            }
            eventStreamRoute(registry)
            post("/stop") {
                registry.stopRun(call.sessionId())
                call.respond(registry.info(call.sessionId()))
            }
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
 * `GET /v1/sessions/changes`: one data-less `change` frame per
 * [SessionRegistry.sessionsChanged] signal, whose KDoc carries what a
 * subscriber may read into one.
 *
 * The opening frame is emitted from `onSubscription`, so it stands after the
 * subscription is registered: a client re-reads on every connect, and a
 * signal raised from then on cannot fall into the gap behind it.
 *
 * A constant path segment outranks `/{id}` whatever the registration order,
 * so `changes` can never name a session — as no generated session ID could
 * be that string anyway.
 */
private fun Route.changeStreamRoute(registry: SessionRegistry) {
    route("/changes") {
        sse {
            // A dead peer only surfaces on a failed write, and this stream can
            // sit idle for hours: the comment frame reclaims its subscribers.
            heartbeat()
            registry.sessionsChanged.onSubscription { emit(Unit) }.collect { send(event = CHANGE_EVENT) }
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

internal fun ApplicationCall.sessionId(): String = checkNotNull(parameters["id"]) { "route without {id}" }

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

/** The change stream's one frame name; its frames carry no data. */
private const val CHANGE_EVENT: String = "change"
