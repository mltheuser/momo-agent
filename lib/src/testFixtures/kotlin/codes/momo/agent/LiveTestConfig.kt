package codes.momo.agent

import ai.router.sdk.AiRouterClient
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.harness.Harness
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Base URL of the running ai-router the live tests talk to. */
public val liveBaseUrl: String
    get() = requiredSystemProperty("aiRouter.baseUrl")

/** Model the live tests converse with. */
public val liveChatModel: String
    get() = requiredSystemProperty("aiRouter.chatModel")

/** The [RunSettings] the live tests prompt runs with. */
public val liveRunSettings: RunSettings
    get() = RunSettings(model = liveChatModel)

/** Absolute path of the lib module's `examples/` folder holding the reference harnesses. */
public val examplesDir: String
    get() = requiredSystemProperty("momo.examplesDir")

/**
 * A client for the configured ai-router, having first established that it
 * is there and serves the configured model. The live tier never skips, so
 * this is the one place that turns an absent backend into an actionable
 * message instead of a connection-refused stack trace from wherever the
 * first call happened to land.
 */
public fun liveAiRouterClient(): AiRouterClient {
    requireLiveAiRouter()
    return AiRouterClient(liveBaseUrl, boundedHttpClient())
}

/** The reachability half of [liveAiRouterClient], for callers that reach the router indirectly. */
public fun requireLiveAiRouter() {
    reachable.getOrElse { failure -> throw IllegalStateException(unreachableMessage(failure), failure) }
}

/**
 * A fresh live agent titled [title], bounded by [LIVE_BUDGETS] rather than by
 * the library's own defaults — which are ceilings for real work, and would let
 * a model looping on tool calls, or one command that never returns, bill and
 * wait far past anything a live case asks for.
 */
public fun liveAgent(
    harness: Harness,
    client: AiRouterClient,
    environment: ExecutionEnvironment,
    title: String,
    listener: AgentEventListener = NoOpAgentEventListener,
): Agent = Agent(harness, client, environment, listener, LIVE_BUDGETS, SessionState.Fresh(title))

/** Proportionate to the real work a live case poses: a handful of turns, minutes, one short command each. */
private val LIVE_BUDGETS = RunBudgets(maxTurns = 20, maxWallClock = 5.minutes, toolTimeout = 60.seconds)

/**
 * Ceiling on one completion. A warm call is seconds, so this is a backstop
 * against a wedged generation, not a budget — the SDK's own default is ten
 * minutes, long enough to look like a hang rather than a failure.
 */
private val CALL_TIMEOUT: Duration = 90.seconds

private const val CONNECT_TIMEOUT_MILLIS: Long = 10_000

/**
 * The SDK takes its Ktor client as a constructor parameter, which is where
 * the live tier bounds a call — and, since supplying one also takes over
 * encoding, where it reproduces the SDK's own settings ([aiRouterSdkJson]).
 */
private fun boundedHttpClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(aiRouterSdkJson)
    }
    install(HttpTimeout) {
        requestTimeoutMillis = CALL_TIMEOUT.inWholeMilliseconds
        connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
    }
    install(wedgedCallReporter)
}

/** Turns Ktor's bare timeout into the diagnosis a live-tier timeout almost always has. */
private val wedgedCallReporter = createClientPlugin("WedgedCallReporter") {
    on(Send) { request ->
        try {
            proceed(request)
        } catch (cause: HttpRequestTimeoutException) {
            throw IllegalStateException(
                "The ai-router call to ${request.url.buildString()} produced no completion within " +
                    "$CALL_TIMEOUT, waiting on model '$liveChatModel'. A warm call answers in seconds, so " +
                    "this is a wedged generation: suspect a prompt that fights the completion it asks for.",
                cause,
            )
        }
    }
}

/** Probed once per JVM: every class in a live suite shares the verdict. */
private val reachable: Result<Unit> by lazy {
    runCatching {
        val available = runBlocking {
            AiRouterClient(liveBaseUrl, boundedHttpClient()).use { client ->
                client.listModels().data.map { it.model }
            }
        }
        check(liveChatModel in available) {
            "it serves no model '$liveChatModel' — it offers ${available.sorted().joinToString(", ")}"
        }
    }
}

private fun unreachableMessage(failure: Throwable): String =
    "The live tests need a running ai-router at $liveBaseUrl serving '$liveChatModel', and it is " +
        "unusable: ${failure.message ?: failure.toString()}\n" +
        "Start one from an ai-router checkout with: set -a && source .env && set +a && ./bin/ai-router serve\n" +
        "Point the tests elsewhere with -PaiRouterBaseUrl=... / -PaiRouterChatModel=... " +
        "(or AI_ROUTER_BASE_URL / AI_ROUTER_CHAT_MODEL)."

/**
 * Live-test configuration arrives as system properties set by the
 * `liveTest` Gradle tasks (see the module build scripts).
 */
private fun requiredSystemProperty(name: String): String =
    checkNotNull(System.getProperty(name)) {
        "System property '$name' is not set — run via ./gradlew liveTest, " +
            "or set -DaiRouter.baseUrl=... / -DaiRouter.chatModel=..."
    }
