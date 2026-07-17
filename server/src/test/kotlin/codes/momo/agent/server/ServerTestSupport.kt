package codes.momo.agent.server

import ai.router.sdk.AiRouterClient
import ai.router.sdk.models.ChatRequest
import ai.router.sdk.models.ChatResponse
import ai.router.sdk.models.ReasoningEffort
import codes.momo.agent.AgentEvent
import codes.momo.agent.ScriptedReply
import codes.momo.agent.TEST_RUN_SETTINGS
import codes.momo.agent.asReply
import codes.momo.agent.baseUrl
import codes.momo.agent.scriptedServer
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

/** Writes a valid harness folder at [folder] and returns it; [subagents] maps type names to referenced paths. */
internal fun writeHarness(
    folder: Path,
    tools: List<String> = listOf("bash"),
    subagents: Map<String, String> = emptyMap(),
): Path {
    folder.createDirectories()
    folder.resolve("harness.yaml").writeText(
        buildString {
            appendLine("tools:")
            tools.forEach { appendLine("  - $it") }
            if (subagents.isNotEmpty()) {
                appendLine("subagents:")
                subagents.forEach { (type, path) ->
                    appendLine("  $type:")
                    appendLine("    path: $path")
                    appendLine("    description: The $type harness.")
                }
            }
        },
    )
    folder.resolve("instructions.md").writeText("Server-test instructions.\n")
    return folder
}

/** Writes a valid harness folder under [tempDir] and returns its path as a string. */
internal fun harnessPath(tempDir: Path): String = writeHarness(tempDir.resolve("harness")).toString()

/** Creates a workspace folder named [name] under [tempDir] and returns it as a local environment spec. */
internal fun localWorkspace(tempDir: Path, name: String = "workspace"): EnvironmentSpec.Local =
    EnvironmentSpec.Local(tempDir.resolve(name).createDirectories().toString())

/** A client for sessions that never reach the LLM: the bogus URL is never dialed. */
internal fun unusedAiRouterClient(): AiRouterClient = AiRouterClient("http://127.0.0.1:9")

/** Runs [block] against a full server over a registry on [tempDir]'s data dir, with no LLM reachable. */
internal fun withSessionServer(tempDir: Path, block: suspend (HttpClient) -> Unit) {
    unusedAiRouterClient().use { client -> withSessionServer(tempDir, client, block) }
}

/** Runs [block] against a full server over a registry on [tempDir]'s data dir, backed by [client]'s LLM. */
internal fun withSessionServer(tempDir: Path, client: AiRouterClient, block: suspend (HttpClient) -> Unit) {
    SessionRegistry(tempDir.resolve("data"), client).use { registry ->
        withServer(registry, client, block)
    }
}

/** Runs [block] against a full server over [tempDir] whose LLM serves [replies] in order. */
internal fun withScriptedSessionServer(
    tempDir: Path,
    vararg replies: ScriptedReply,
    block: suspend (HttpClient) -> Unit,
) {
    scriptedServer(*replies).use { llm ->
        AiRouterClient(llm.baseUrl).use { client ->
            withSessionServer(tempDir, client, block)
        }
    }
}

/** A [withScriptedSessionServer] taking plain successful [responses]. */
internal fun withScriptedSessionServer(
    tempDir: Path,
    vararg responses: ChatResponse,
    block: suspend (HttpClient) -> Unit,
) {
    withScriptedSessionServer(tempDir, *responses.map { it.asReply() }.toTypedArray(), block = block)
}

/** A [withScriptedSessionServer] also recording every received [ChatRequest], in order, into [requests]. */
internal fun withScriptedSessionServer(
    tempDir: Path,
    requests: CopyOnWriteArrayList<ChatRequest>,
    vararg replies: ScriptedReply,
    block: suspend (HttpClient) -> Unit,
) {
    scriptedServer(requests, *replies).use { llm ->
        AiRouterClient(llm.baseUrl).use { client ->
            withSessionServer(tempDir, client, block)
        }
    }
}

/** Runs [block] against the real routing and serialization over [registry], with [client] backing `/v1/models`. */
internal fun withServer(registry: SessionRegistry, client: AiRouterClient, block: suspend (HttpClient) -> Unit) {
    testApplication {
        application { agentServer(registry, client) }
        val http = createClient {
            install(ContentNegotiation) {
                json()
            }
            install(SSE)
        }
        block(http)
    }
}

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

/** POSTs a prompt, asserting 202, and returns the accepted session info. */
internal suspend fun HttpClient.prompt(
    sessionId: String,
    prompt: String,
    model: String = TEST_RUN_SETTINGS.model,
    reasoningEffort: ReasoningEffort? = null,
): SessionInfo {
    val response = promptResponse(sessionId, prompt, model, reasoningEffort)
    assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
    return response.body()
}

internal suspend fun HttpClient.promptResponse(
    sessionId: String,
    prompt: String,
    model: String = TEST_RUN_SETTINGS.model,
    reasoningEffort: ReasoningEffort? = null,
): HttpResponse =
    post("/v1/sessions/$sessionId/prompt") {
        contentType(ContentType.Application.Json)
        setBody(PromptRequest(prompt, model, reasoningEffort))
    }

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

/** Waits until [id]'s active run ends, however it ends. */
internal suspend fun SessionRegistry.awaitRunEnd(id: String) {
    withTimeout(STREAM_TIMEOUT_MILLIS) {
        while (info(id).status == SessionStatus.RUNNING) {
            delay(POLL_MILLIS)
        }
    }
}

/** Waits until [sessionId]'s active run ends, however it ends, polling over HTTP. */
internal suspend fun HttpClient.awaitRunEnd(sessionId: String) {
    withTimeout(STREAM_TIMEOUT_MILLIS) {
        while (get("/v1/sessions/$sessionId").body<SessionInfo>().status == SessionStatus.RUNNING) {
            delay(POLL_MILLIS)
        }
    }
}

/** One received SSE frame, decoded: its `id:` sequence number plus its `data:` event. */
internal data class SseEvent(val id: Long, val event: AgentEvent)

/**
 * Subscribes to [sessionId]'s SSE event stream — strictly after
 * [afterSequenceId] when given, via `Last-Event-ID` — and collects until
 * [until] matches (that event included), then disconnects.
 */
internal suspend fun HttpClient.streamEvents(
    sessionId: String,
    afterSequenceId: Long? = null,
    until: (AgentEvent) -> Boolean = { it is AgentEvent.RunFinished },
): List<SseEvent> {
    var events: List<SseEvent> = emptyList()
    withTimeout(STREAM_TIMEOUT_MILLIS) {
        sse(
            "/v1/sessions/$sessionId/events",
            request = { afterSequenceId?.let { header("Last-Event-ID", it.toString()) } },
        ) {
            events = incoming
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
                .toList()
        }
    }
    return events
}

internal const val STREAM_TIMEOUT_MILLIS = 30_000L

private const val POLL_MILLIS = 10L
