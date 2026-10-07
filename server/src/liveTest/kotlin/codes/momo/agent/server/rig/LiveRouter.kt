package codes.momo.agent.server.rig

import ai.router.sdk.AiRouterClient
import ai.router.sdk.chat.ChatFeature
import ai.router.sdk.chat.ReasoningEffort
import codes.momo.agent.RunSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal val liveBaseUrl: String
    get() = requiredSystemProperty("aiRouter.baseUrl")

/** What every live prompt runs with unless a test says otherwise; its tool models cover every harness's tools. */
internal val liveSettings: RunSettings = RunSettings(
    "claude-sonnet-5:cloud@anthropic",
    ReasoningEffort.LOW,
    mapOf("web_search" to "fast:cloud@exa", "page_contents" to "auto:cloud@exa"),
)

internal val liveChatModel: String
    get() = liveSettings.model

internal fun requireLiveAiRouter() {
    reachable.getOrElse { failure -> throw IllegalStateException(unreachableMessage(failure), failure) }
}

private val reachable: Result<Unit> by lazy {
    runCatching {
        val available = runBlocking {
            AiRouterClient(liveBaseUrl, probeHttpClient()).use { client ->
                client.chat.listModels().data.filter { it.has(ChatFeature.TOOLS) }.map { it.model }
            }
        }
        check(liveChatModel in available) {
            "it serves no chat model '$liveChatModel' that can call tools — it offers " +
                available.sorted().joinToString(", ")
        }
    }
}

private fun probeHttpClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = false
                isLenient = true
            },
        )
    }
    install(HttpTimeout) {
        requestTimeoutMillis = PROBE_TIMEOUT.inWholeMilliseconds
        connectTimeoutMillis = PROBE_TIMEOUT.inWholeMilliseconds
    }
}

private val PROBE_TIMEOUT: Duration = 10.seconds

private fun unreachableMessage(failure: Throwable): String =
    "The live tests need a running ai-router at $liveBaseUrl serving '$liveChatModel', and it is " +
        "unusable: ${failure.message ?: failure.toString()}\n" +
        "Start one from an ai-router checkout with: set -a && source .env && set +a && ./bin/ai-router serve\n" +
        "Point the tests elsewhere with -PaiRouterBaseUrl=... (or AI_ROUTER_BASE_URL)."

private fun requiredSystemProperty(name: String): String =
    checkNotNull(System.getProperty(name)) {
        "System property '$name' is not set — run via ./gradlew liveTest, or set -DaiRouter.baseUrl=..."
    }
