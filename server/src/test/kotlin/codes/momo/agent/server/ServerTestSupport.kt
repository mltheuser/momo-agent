package codes.momo.agent.server

import ai.router.sdk.AiRouterClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

/** Writes a valid harness folder at [folder] and returns it. */
internal fun writeHarness(folder: Path, model: String = "test-model"): Path {
    folder.createDirectories()
    folder.resolve("harness.yaml").writeText("model: $model\ntools:\n  - bash\n  - ask_user\n")
    folder.resolve("instructions.md").writeText("Server-test instructions.\n")
    return folder
}

/** Writes a valid harness folder under [tempDir] and returns its path as a string. */
internal fun harnessPath(tempDir: Path): String = writeHarness(tempDir.resolve("harness")).toString()

/** A client for sessions that never reach the LLM: the bogus URL is never dialed. */
internal fun unusedAiRouterClient(): AiRouterClient = AiRouterClient("http://127.0.0.1:9")

/** Runs [block] against a full server over a registry on [tempDir]'s data dir, with no LLM reachable. */
internal fun withSessionServer(tempDir: Path, block: suspend (HttpClient) -> Unit) {
    unusedAiRouterClient().use { client ->
        SessionRegistry(tempDir.resolve("data"), client).use { registry ->
            withServer(registry, block)
        }
    }
}

/** Runs [block] against the real routing and serialization over [registry]. */
internal fun withServer(registry: SessionRegistry, block: suspend (HttpClient) -> Unit) {
    testApplication {
        application { agentServer(registry) }
        val http = createClient {
            install(ContentNegotiation) {
                json()
            }
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
