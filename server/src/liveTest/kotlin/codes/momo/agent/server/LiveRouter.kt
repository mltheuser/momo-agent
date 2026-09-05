package codes.momo.agent.server

import ai.router.sdk.AiRouterClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/*
 * The live router the suite runs against — its coordinates, and the one
 * check that turns an absent backend into an actionable message instead of
 * a connection-refused stack trace from wherever the first call landed.
 * Configuration arrives as system properties set by the `liveTest` Gradle
 * task (see the module build script).
 */

/** Base URL of the running ai-router the live tests talk to. */
internal val liveBaseUrl: String
    get() = requiredSystemProperty("aiRouter.baseUrl")

/** Model the live tests converse with. */
internal val liveChatModel: String
    get() = requiredSystemProperty("aiRouter.chatModel")

/**
 * Fails, once per JVM and loudly, unless the configured router is reachable
 * and serves the configured model. The live tier never skips: this is the
 * one place that names the URL, the model, what the router does offer, and
 * the command that starts one.
 */
internal fun requireLiveAiRouter() {
    reachable.getOrElse { failure -> throw IllegalStateException(unreachableMessage(failure), failure) }
}

/** Probed once per JVM: every class in the suite shares the verdict. */
private val reachable: Result<Unit> by lazy {
    runCatching {
        val available = runBlocking {
            AiRouterClient(liveBaseUrl, probeHttpClient()).use { client ->
                client.listModels().data.map { it.model }
            }
        }
        check(liveChatModel in available) {
            "it serves no model '$liveChatModel' — it offers ${available.sorted().joinToString(", ")}"
        }
    }
}

/**
 * The SDK's default client waits ten minutes on a call, long enough to look
 * like a hang rather than a failure; the probe gets one that answers in
 * seconds. Supplying a client also takes over encoding, so this reproduces
 * the SDK's own settings.
 */
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
        "Point the tests elsewhere with -PaiRouterBaseUrl=... / -PaiRouterChatModel=... " +
        "(or AI_ROUTER_BASE_URL / AI_ROUTER_CHAT_MODEL)."

private fun requiredSystemProperty(name: String): String =
    checkNotNull(System.getProperty(name)) {
        "System property '$name' is not set — run via ./gradlew liveTest, " +
            "or set -DaiRouter.baseUrl=... / -DaiRouter.chatModel=..."
    }
