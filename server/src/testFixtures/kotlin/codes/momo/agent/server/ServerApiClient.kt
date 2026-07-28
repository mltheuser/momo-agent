package codes.momo.agent.server

import ai.router.sdk.models.ReasoningEffort
import codes.momo.agent.AgentEvent
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.AttributeKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/*
 * The server's HTTP API as its tests speak it: one client, the request and
 * response wrappers, and the waits over them. Every suite drives the same
 * API over the same wire, so what stays in a suite is what differs — which
 * server the client points at, how long a wait there may take, and which
 * model its prompts default to.
 */

/** Creates a workspace folder named [name] under [tempDir] and returns it as a local environment spec. */
internal fun localWorkspace(tempDir: Path, name: String = "workspace"): EnvironmentSpec.Local =
    EnvironmentSpec.Local(tempDir.resolve(name).createDirectories().toString())

/**
 * A client for the server at [baseUrl], with the SSE and JSON plugins the API
 * needs and [wait] as its ceiling: it bounds one request, so a wedged call
 * fails the suite instead of stalling it, and — read back as [waitCeiling] —
 * every wait run over the client.
 */
internal fun serverHttpClient(baseUrl: String, wait: Duration): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json()
    }
    install(SSE)
    install(HttpTimeout) {
        requestTimeoutMillis = wait.inWholeMilliseconds
        connectTimeoutMillis = CONNECT_TIMEOUT.inWholeMilliseconds
    }
    defaultRequest {
        url(baseUrl)
    }
}.also { it.attributes.put(WAIT_CEILING, wait) }

/** The ceiling the client was built with — how long a wait over it may take. */
internal val HttpClient.waitCeiling: Duration
    get() = attributes[WAIT_CEILING]

// ─── API calls ────────────────────────────────────────────────────────

/** POSTs a create-session request, asserting 201. */
internal suspend fun HttpClient.createSession(
    harnessPath: String,
    environment: EnvironmentSpec,
    title: String? = null,
): SessionInfo {
    val response = createSessionResponse(CreateSessionRequest(harnessPath, environment, title))
    assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    return response.body()
}

internal suspend fun HttpClient.createSessionResponse(request: CreateSessionRequest): HttpResponse =
    post("/v1/sessions") {
        contentType(ContentType.Application.Json)
        setBody(request)
    }

/** POSTs a prompt, asserting the 202 that says the run was accepted, not finished. */
internal suspend fun HttpClient.prompt(
    sessionId: String,
    prompt: String,
    model: String,
    reasoningEffort: ReasoningEffort? = null,
): SessionInfo {
    val response = promptResponse(sessionId, prompt, model, reasoningEffort)
    assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
    return response.body()
}

internal suspend fun HttpClient.promptResponse(
    sessionId: String,
    prompt: String,
    model: String,
    reasoningEffort: ReasoningEffort? = null,
): HttpResponse =
    post("/v1/sessions/$sessionId/prompt") {
        contentType(ContentType.Application.Json)
        setBody(PromptRequest(prompt, model, reasoningEffort))
    }

/** POSTs a prompt whose body is [body] verbatim, for the shapes the request type cannot express. */
internal suspend fun HttpClient.rawPromptResponse(sessionId: String, body: String): HttpResponse =
    post("/v1/sessions/$sessionId/prompt") {
        setBody(TextContent(body, ContentType.Application.Json))
    }

internal suspend fun HttpClient.sessionInfo(sessionId: String): SessionInfo =
    get("/v1/sessions/$sessionId").body()

/** The server's root-session listing. */
internal suspend fun HttpClient.sessions(): List<SessionInfo> = get("/v1/sessions").body()

/** POSTs a rename, asserting 200, and returns the updated session info. */
internal suspend fun HttpClient.renameSession(sessionId: String, title: String): SessionInfo {
    val response = renameResponse(sessionId, title)
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return response.body()
}

internal suspend fun HttpClient.renameResponse(sessionId: String, title: String): HttpResponse =
    post("/v1/sessions/$sessionId/rename") {
        contentType(ContentType.Application.Json)
        setBody(RenameRequest(title))
    }

/** POSTs a favorite flag, asserting 200, and returns the updated session info. */
internal suspend fun HttpClient.setFavorite(sessionId: String, favorite: Boolean): SessionInfo {
    val response = favoriteResponse(sessionId, favorite)
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return response.body()
}

internal suspend fun HttpClient.favoriteResponse(sessionId: String, favorite: Boolean): HttpResponse =
    post("/v1/sessions/$sessionId/favorite") {
        contentType(ContentType.Application.Json)
        setBody(FavoriteRequest(favorite))
    }

/** POSTs a rewind, asserting 200, and returns the session as cut plus the cascade's deletions. */
internal suspend fun HttpClient.rewindSession(sessionId: String, sequenceId: Long): RewindResponse {
    val response = rewindResponse(sessionId, sequenceId)
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return response.body()
}

internal suspend fun HttpClient.rewindResponse(sessionId: String, sequenceId: Long): HttpResponse =
    post("/v1/sessions/$sessionId/rewind") {
        contentType(ContentType.Application.Json)
        setBody(RewindRequest(sequenceId))
    }

/** POSTs a stop — no request body — whose response carries the session info as of the stop's return. */
internal suspend fun HttpClient.stopResponse(sessionId: String): HttpResponse = post("/v1/sessions/$sessionId/stop")

/** POSTs a close, asserting 200, and returns the parked session info. */
internal suspend fun HttpClient.closeSession(sessionId: String): SessionInfo {
    val response = closeResponse(sessionId)
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return response.body()
}

internal suspend fun HttpClient.closeResponse(sessionId: String): HttpResponse = post("/v1/sessions/$sessionId/close")

// ─── Waits ────────────────────────────────────────────────────────────

/** Waits until [sessionId]'s active run ends, however it ends, polling over HTTP. */
internal suspend fun HttpClient.awaitRunEnd(sessionId: String) {
    val ended = withTimeoutOrNull(waitCeiling) {
        while (sessionInfo(sessionId).status == SessionStatus.RUNNING) {
            delay(POLL_INTERVAL)
        }
        true
    }
    if (ended == null) {
        failWait(sessionId, "the run never ended")
    }
}

/** One received SSE frame, decoded: its `id:` sequence number plus its `data:` event. */
internal data class SseEvent(val id: Long, val event: AgentEvent)

/**
 * Subscribes to [sessionId]'s SSE event stream — strictly after
 * [afterSequenceId] when given, via `Last-Event-ID` — and collects until
 * [until] matches (that event included), then disconnects. A stream the
 * server itself ends — the session deleted under it — returns whatever
 * arrived instead. [onSubscribed] runs once the subscription is open, for
 * a case that must not act until this stream is standing.
 */
internal suspend fun HttpClient.streamEvents(
    sessionId: String,
    afterSequenceId: Long? = null,
    onSubscribed: () -> Unit = {},
    until: (AgentEvent) -> Boolean = { it is AgentEvent.RunFinished },
): List<SseEvent> {
    val received = CopyOnWriteArrayList<SseEvent>()
    val completed = withTimeoutOrNull(waitCeiling) {
        sse(
            "/v1/sessions/$sessionId/events",
            request = { afterSequenceId?.let { header("Last-Event-ID", it.toString()) } },
        ) {
            onSubscribed()
            incoming
                .filter { it.data != null } // Heartbeat comment frames carry no data.
                .map { frame ->
                    SseEvent(
                        id = checkNotNull(frame.id) { "every event frame carries an id" }.toLong(),
                        event = Json.decodeFromString(checkNotNull(frame.data) { "every event frame carries data" }),
                    )
                }
                .transformWhile { decoded ->
                    emit(decoded)
                    !until(decoded.event)
                }
                .collect { received += it }
        }
        true
    }
    if (completed == null) {
        failWait(sessionId, "the event stream never reached its end condition", received)
    }
    return received.toList()
}

/** Reports what the session was doing, which is the useful half of a wait that ran out. */
private suspend fun HttpClient.failWait(
    sessionId: String,
    problem: String,
    seen: List<SseEvent> = emptyList(),
): Nothing {
    val info = runCatching { sessionInfo(sessionId) }.getOrNull()
    fail(
        "$problem within $waitCeiling. Session $sessionId: status=${info?.status}, lastRun=${info?.lastRun}. " +
            "Events seen: ${seen.takeLast(EVENT_TAIL).map { "${it.id}:${it.event::class.simpleName}" }}",
    )
}

/**
 * How often a wait re-asks: brief enough that a mocked run's end is never
 * what a case waits on, long enough that following a live run for a minute
 * is not traffic worth counting.
 */
internal val POLL_INTERVAL: Duration = 20.milliseconds

private val WAIT_CEILING: AttributeKey<Duration> = AttributeKey("momo.waitCeiling")

/** Every server a suite talks to is on loopback, so a connect is either instant or never. */
private val CONNECT_TIMEOUT: Duration = 10.seconds

private const val EVENT_TAIL: Int = 10
