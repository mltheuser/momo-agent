package codes.momo.agent.server

import codes.momo.agent.harness.writeHarness
import codes.momo.agent.liveChatModel
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Ceiling on every wait in this suite. A live run is seconds of real model
 * work, so this is a backstop against a hang, not a budget — and blowing
 * it reports the session instead of the waiter's stack.
 */
private val LIVE_WAIT: Duration = 90.seconds

/** Runs [block] against the shared server process over a real HTTP client. */
internal fun withLiveServer(block: suspend (HttpClient) -> Unit): Unit = runBlocking {
    liveHttpClient(sharedLiveServer.baseUrl).use { http -> block(http) }
}

/** A client for a server at [baseUrl], waiting on it for as long as a live run may take. */
internal fun liveHttpClient(baseUrl: String): HttpClient = serverHttpClient(baseUrl, LIVE_WAIT)

/**
 * The one server process the whole suite shares: each test gets fresh
 * sessions, not a fresh JVM. The restart case runs its own pair instead.
 */
internal val sharedLiveServer: LiveServerProcess get() = sharedStart.getOrThrow()

/**
 * The single start attempt, its failure memoised along with its success:
 * `lazy` re-runs an initializer that threw, so a server that never becomes
 * ready would be waited out once per case — a quarter of an hour of what
 * looks like a hang, plus a leaked data directory per attempt — instead of
 * failing every case on the one cause, reported once.
 */
@OptIn(ExperimentalPathApi::class)
private val sharedStart: Result<LiveServerProcess> by lazy {
    val dataDir = Files.createTempDirectory("momo-live-server")
    runCatching { LiveServerProcess.start(dataDir) }
        .onSuccess { server ->
            // Nothing closes the shared process, so its exit is cleaned up
            // here, and in order: the server first, then the directory it was
            // writing into. Not being orphaned is the process's own business.
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    server.close()
                    dataDir.deleteRecursively()
                },
            )
        }
        .onFailure { dataDir.deleteRecursively() }
}

// ─── Session fixtures ─────────────────────────────────────────────────

/** A harness folder under [tempDir] whose agent is told to reach for bash. */
internal fun liveHarness(tempDir: Path): String =
    writeHarness(
        tempDir.resolve("harness"),
        instructions = "You are a terse assistant working in a project workspace. " +
            "Use the bash tool whenever a question concerns the workspace's files, " +
            "and keep your final messages to a single short sentence.",
    ).toString()

/** Prompts run the model the tier is configured for, so only a case about the model names one. */
internal suspend fun HttpClient.prompt(sessionId: String, prompt: String): SessionInfo =
    prompt(sessionId, prompt, liveChatModel)

internal suspend fun HttpClient.promptResponse(sessionId: String, prompt: String): HttpResponse =
    promptResponse(sessionId, prompt, liveChatModel)
