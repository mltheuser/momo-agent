package codes.momo.agent.server

import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val LIVE_WAIT: Duration = 90.seconds

internal fun withLiveServer(block: suspend (HttpClient) -> Unit): Unit = runBlocking {
    liveHttpClient(sharedLiveServer.baseUrl).use { http -> block(http) }
}

internal fun liveHttpClient(baseUrl: String): HttpClient = serverHttpClient(baseUrl, LIVE_WAIT)

internal val sharedLiveServer: LiveServerProcess get() = sharedStart.getOrThrow()

@OptIn(ExperimentalPathApi::class)
private val sharedStart: Result<LiveServerProcess> by lazy {
    val dataDir = Files.createTempDirectory("momo-live-server")
    runCatching { LiveServerProcess.start(dataDir) }
        .onSuccess { server ->

            Runtime.getRuntime().addShutdownHook(
                Thread {
                    server.close()
                    dataDir.deleteRecursively()
                },
            )
        }
        .onFailure { dataDir.deleteRecursively() }
}

internal fun liveHarness(tempDir: Path): String =
    writeHarness(
        tempDir.resolve("harness"),
        instructions = "You are a terse assistant working in a project workspace. " +
            "Use the bash tool whenever a question concerns the workspace's files, " +
            "and keep your final messages to a single short sentence.",
    ).toString()

internal suspend fun HttpClient.prompt(sessionId: String, prompt: String): SessionInfo =
    prompt(sessionId, prompt, liveChatModel)

internal suspend fun HttpClient.promptResponse(sessionId: String, prompt: String): HttpResponse =
    promptResponse(sessionId, prompt, liveChatModel)
