package codes.momo.agent.server

import ai.router.sdk.AiRouterClient
import ai.router.sdk.models.ReasoningEffort
import codes.momo.agent.RunSettings
import codes.momo.agent.SubagentRevivalException
import codes.momo.agent.environment.EnvironmentStartupException
import codes.momo.agent.harness.HarnessValidationException
import codes.momo.agent.server.session.SessionInfo
import codes.momo.agent.server.session.SessionRegistry
import codes.momo.agent.server.session.closeSession
import codes.momo.agent.server.session.create
import codes.momo.agent.server.session.delete
import codes.momo.agent.server.session.info
import codes.momo.agent.server.session.list
import codes.momo.agent.server.session.rename
import codes.momo.agent.server.session.retryRun
import codes.momo.agent.server.session.rewind
import codes.momo.agent.server.session.selectModel
import codes.momo.agent.server.session.startRun
import codes.momo.agent.server.session.stopRun
import codes.momo.agent.server.storage.BEFORE_FIRST_EVENT
import codes.momo.agent.server.storage.CorruptSessionException
import codes.momo.agent.server.storage.EventLogFailedException
import codes.momo.agent.server.storage.InvalidRewindPointException
import codes.momo.agent.server.storage.InvalidTemplateNameException
import codes.momo.agent.server.storage.SessionConflictException
import codes.momo.agent.server.storage.TemplateStore
import codes.momo.agent.server.storage.UnknownSessionException
import codes.momo.agent.server.storage.UnknownTemplateException
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

@Serializable
internal data class CreateSessionRequest(

    val harnessPath: String,
    val workspace: String,

    val title: String? = null,
)

@Serializable
internal data class PromptRequest(
    val prompt: String,
    val model: String,
    val reasoningEffort: ReasoningEffort? = null,
)

@Serializable
internal data class RenameRequest(val title: String)

@Serializable
internal data class SelectModelRequest(
    val model: String,

    val reasoningEffort: ReasoningEffort? = null,
)

@Serializable
internal data class RewindRequest(val firstDeletedSequenceId: Long)

@Serializable
internal data class RewindResponse(val session: SessionInfo, val deletedSessionIds: List<String>)

@Serializable
internal data class ApiError(val code: String, val message: String)

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
            call.respondError(HttpStatusCode.BadRequest, "invalid_request", failure.rootMessage)
        }
        exception<InvalidRewindPointException> { call, failure ->
            call.respondError(HttpStatusCode.BadRequest, "invalid_request", failure)
        }
        exception<ContentConvertException> { call, failure ->
            call.respondError(HttpStatusCode.BadRequest, "invalid_request", failure.rootMessage)
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

private val catalogJson = Json {
    encodeDefaults = false
    explicitNulls = false
}

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
            val info = registry.create(request.harnessPath, request.workspace, request.title)
            call.respond(HttpStatusCode.Created, info)
        }
        get {
            call.respond(registry.list(call.requiredWorkspace()))
        }
        route("/{id}") {
            singleSessionRoutes(registry)
        }
    }
}

private fun Route.singleSessionRoutes(registry: SessionRegistry) {
    scopeToWorkspace(registry)
    get {
        call.respond(registry.info(call.sessionId()))
    }
    post("/prompt") {
        val request = call.receive<PromptRequest>()
        val prompt = request.prompt.requireNotBlank("prompt")
        val settings = RunSettings(request.model.requireNotBlank("model"), request.reasoningEffort)
        val id = call.sessionId()
        registry.startRun(id, prompt, settings)
        call.respond(HttpStatusCode.Accepted, registry.info(id))
    }
    post("/rename") {
        val title = call.receive<RenameRequest>().title.requireNotBlank("title")
        call.respond(registry.rename(call.sessionId(), title))
    }
    post("/select-model") {
        val request = call.receive<SelectModelRequest>()
        val model = request.model.requireNotBlank("model")
        call.respond(registry.selectModel(call.sessionId(), model, request.reasoningEffort))
    }
    post("/retry") {
        val id = call.sessionId()
        registry.retryRun(id)
        call.respond(HttpStatusCode.Accepted, registry.info(id))
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
        registry.closeSession(call.sessionId())
        call.respond(registry.info(call.sessionId()))
    }
    delete {
        registry.delete(call.sessionId())
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun Route.changeStreamRoute(registry: SessionRegistry) {
    route("/changes") {
        sse {
            heartbeat()
            registry.changes.flow.onSubscription { emit(Unit) }.collect { send(event = CHANGE_EVENT) }
        }
    }
}

private fun Route.eventStreamRoute(registry: SessionRegistry) {
    // A plugin, not a handler check: once the SSE handler runs the 200 is committed and a 404 is impossible.
    val knownSessionGuard = createRouteScopedPlugin("KnownSessionGuard") {
        onCall { call -> registry.requireKnown(call.sessionId()) }
    }
    route("/events") {
        install(knownSessionGuard)
        sse {
            heartbeat()
            val afterSequenceId = call.request.header("Last-Event-ID")?.toLongOrNull() ?: BEFORE_FIRST_EVENT
            registry.eventsAfter(call.sessionId(), afterSequenceId).collect { event ->
                send(data = event.json, id = event.sequenceId.toString())
            }
        }
    }
}

internal fun String.requireNotBlank(what: String): String {
    if (isBlank()) {
        throw BadRequestException("A $what must not be blank.")
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

private val Throwable.rootMessage: String
    get() {
        val root = generateSequence(this) { it.cause }.last()
        return root.message ?: root.javaClass.simpleName
    }

private const val CHANGE_EVENT: String = "change"
