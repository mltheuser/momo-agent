package codes.momo.agent.server.rig

import ai.router.sdk.models.ReasoningEffort
import codes.momo.agent.AgentEvent
import codes.momo.agent.server.ApiError
import codes.momo.agent.server.CreateSessionRequest
import codes.momo.agent.server.PromptRequest
import codes.momo.agent.server.PutTemplateRequest
import codes.momo.agent.server.RenameRequest
import codes.momo.agent.server.RewindRequest
import codes.momo.agent.server.RewindResponse
import codes.momo.agent.server.SelectModelRequest
import codes.momo.agent.server.TemplateResponse
import codes.momo.agent.server.session.SessionInfo
import codes.momo.agent.server.session.SessionStatus
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal fun liveHttpClient(baseUrl: String): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json()
    }
    install(SSE)
    install(HttpTimeout) {
        requestTimeoutMillis = LIVE_WAIT.inWholeMilliseconds
        connectTimeoutMillis = CONNECT_TIMEOUT.inWholeMilliseconds
    }
    defaultRequest {
        url(baseUrl)
    }
}

internal suspend fun HttpClient.createSession(
    harnessPath: String,
    workspace: String,
    title: String? = null,
): SessionInfo {
    val response = createSessionResponse(CreateSessionRequest(harnessPath, workspace, title))
    assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    return response.body()
}

internal suspend fun HttpClient.createSessionResponse(request: CreateSessionRequest): HttpResponse =
    post("/v1/sessions") {
        contentType(ContentType.Application.Json)
        setBody(request)
    }

internal suspend fun HttpClient.rawCreateSessionResponse(body: String): HttpResponse =
    post("/v1/sessions") {
        setBody(TextContent(body, ContentType.Application.Json))
    }

internal suspend fun HttpClient.prompt(sessionId: String, prompt: String): SessionInfo =
    prompt(sessionId, prompt, liveChatModel)

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

internal suspend fun HttpClient.promptResponse(sessionId: String, prompt: String): HttpResponse =
    promptResponse(sessionId, prompt, liveChatModel)

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

internal suspend fun HttpClient.rawPromptResponse(sessionId: String, body: String): HttpResponse =
    post("/v1/sessions/$sessionId/prompt") {
        setBody(TextContent(body, ContentType.Application.Json))
    }

internal suspend fun HttpClient.sessionInfo(sessionId: String): SessionInfo =
    get("/v1/sessions/$sessionId").body()

internal suspend fun HttpClient.sessionInfoResponse(sessionId: String, workspace: String? = null): HttpResponse =
    get("/v1/sessions/$sessionId") {
        if (workspace != null) parameter("workspace", workspace)
    }

internal suspend fun HttpClient.sessions(workspace: String): List<SessionInfo> =
    sessionsResponse(workspace).body()

internal suspend fun HttpClient.sessionsResponse(workspace: String?): HttpResponse =
    get("/v1/sessions") {
        if (workspace != null) parameter("workspace", workspace)
    }

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

internal suspend fun HttpClient.selectModel(
    sessionId: String,
    model: String,
    reasoningEffort: ReasoningEffort? = null,
): SessionInfo {
    val response = selectModelResponse(sessionId, model, reasoningEffort)
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return response.body()
}

internal suspend fun HttpClient.selectModelResponse(
    sessionId: String,
    model: String,
    reasoningEffort: ReasoningEffort? = null,
): HttpResponse =
    post("/v1/sessions/$sessionId/select-model") {
        contentType(ContentType.Application.Json)
        setBody(SelectModelRequest(model, reasoningEffort))
    }

internal suspend fun HttpClient.rawSelectModelResponse(sessionId: String, body: String): HttpResponse =
    post("/v1/sessions/$sessionId/select-model") {
        setBody(TextContent(body, ContentType.Application.Json))
    }

internal suspend fun HttpClient.rewindSession(sessionId: String, firstDeletedSequenceId: Long): RewindResponse {
    val response = rewindResponse(sessionId, firstDeletedSequenceId)
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return response.body()
}

internal suspend fun HttpClient.rewindResponse(sessionId: String, firstDeletedSequenceId: Long): HttpResponse =
    post("/v1/sessions/$sessionId/rewind") {
        contentType(ContentType.Application.Json)
        setBody(RewindRequest(firstDeletedSequenceId))
    }

internal suspend fun HttpClient.rawRewindResponse(sessionId: String, body: String): HttpResponse =
    post("/v1/sessions/$sessionId/rewind") {
        setBody(TextContent(body, ContentType.Application.Json))
    }

internal suspend fun HttpClient.stopResponse(sessionId: String): HttpResponse = post("/v1/sessions/$sessionId/stop")

internal suspend fun HttpClient.deleteSession(sessionId: String) {
    val response = deleteResponse(sessionId)
    assertEquals(HttpStatusCode.NoContent, response.status, response.bodyAsText())
}

internal suspend fun HttpClient.deleteResponse(sessionId: String): HttpResponse = delete("/v1/sessions/$sessionId")

internal suspend fun HttpClient.retryRun(sessionId: String): SessionInfo {
    val response = retryResponse(sessionId)
    assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
    return response.body()
}

internal suspend fun HttpClient.retryResponse(sessionId: String): HttpResponse = post("/v1/sessions/$sessionId/retry")

internal suspend fun HttpClient.eventsResponse(sessionId: String): HttpResponse = get("/v1/sessions/$sessionId/events")

internal suspend fun HttpClient.events(sessionId: String): List<AgentEvent> = eventsResponse(sessionId).body()

internal suspend fun HttpClient.modelsResponse(): HttpResponse = get("/v1/models")

internal suspend fun HttpClient.templateNames(): List<String> = get("/v1/templates").body()

internal suspend fun HttpClient.templateResponse(name: String): HttpResponse = get("/v1/templates/$name")

internal suspend fun HttpClient.putTemplate(name: String, body: String): TemplateResponse {
    val response = putTemplateResponse(name, body)
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return response.body()
}

internal suspend fun HttpClient.putTemplateResponse(name: String, body: String): HttpResponse =
    put("/v1/templates/$name") {
        contentType(ContentType.Application.Json)
        setBody(PutTemplateRequest(body))
    }

internal suspend fun HttpResponse.assertRejected(
    code: String,
    what: String,
    names: String? = null,
    status: HttpStatusCode = HttpStatusCode.BadRequest,
) {
    assertEquals(status, this.status, "$what: ${bodyAsText()}")
    val error = body<ApiError>()
    assertEquals(code, error.code, what)
    if (names != null) assertContains(error.message, names, message = what)
}

internal suspend fun HttpClient.awaitRunEnd(sessionId: String): List<AgentEvent> {
    val ended = withTimeoutOrNull(LIVE_WAIT) {
        while (sessionInfo(sessionId).status == SessionStatus.RUNNING) {
            delay(POLL_INTERVAL)
        }
        true
    }
    if (ended == null) {
        failWait(sessionId, "the run never ended")
    }
    return events(sessionId)
}

internal suspend inline fun <reified T : AgentEvent> HttpClient.awaitLogged(sessionId: String): List<AgentEvent> {
    val logged = withTimeoutOrNull(LIVE_WAIT) {
        var log = events(sessionId)
        while (log.none { it is T }) {
            delay(POLL_INTERVAL)
            log = events(sessionId)
        }
        log
    }
    return logged ?: failWait(sessionId, "the log never showed a ${T::class.simpleName}")
}

internal class ChangeStream(val received: () -> Int) {

    suspend fun <T> signalled(what: String, mutation: suspend () -> T): T {
        val before = received()
        val result = mutation()
        awaitMoreThan(before, what)
        return result
    }

    internal suspend fun awaitMoreThan(count: Int, what: String) {
        val arrived = withTimeoutOrNull(LIVE_WAIT) {
            while (received() <= count) {
                delay(POLL_INTERVAL)
            }
            true
        }
        if (arrived == null) {
            fail("the change stream did not signal after $what within $LIVE_WAIT")
        }
    }
}

internal suspend fun <T> HttpClient.withChangeStream(block: suspend (ChangeStream) -> T): T {
    val received = CopyOnWriteArrayList<String>()
    return coroutineScope {
        val subscription = launch {
            sse("/v1/sessions/changes") {
                incoming
                    .filter { it.event != null }
                    .collect { frame ->
                        assertNull(frame.data, "a change frame carries no data")
                        assertNull(frame.id, "a change frame carries no id")
                        received += checkNotNull(frame.event)
                    }
            }
        }
        val stream = ChangeStream { received.size }
        stream.awaitMoreThan(0, "subscribing")
        try {
            block(stream)
        } finally {
            subscription.cancel()
        }
    }
}

internal suspend fun HttpClient.failWait(sessionId: String, problem: String): Nothing {
    val info = runCatching { sessionInfo(sessionId) }.getOrNull()
    val tail = runCatching { events(sessionId) }.getOrDefault(emptyList()).takeLast(EVENT_TAIL)
    fail(
        "$problem within $LIVE_WAIT. Session $sessionId: status=${info?.status}, lastRun=${info?.lastRun}. " +
            "Log tail: ${tail.map { "${it.sequenceId}:${it::class.simpleName}" }}",
    )
}

private val LIVE_WAIT: Duration = 90.seconds

private val POLL_INTERVAL: Duration = 20.milliseconds

private val CONNECT_TIMEOUT: Duration = 10.seconds

private const val EVENT_TAIL: Int = 10
