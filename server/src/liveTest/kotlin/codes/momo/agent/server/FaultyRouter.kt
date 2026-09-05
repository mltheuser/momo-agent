package codes.momo.agent.server

import ai.router.sdk.models.Capability
import ai.router.sdk.models.ChatMessage
import ai.router.sdk.models.ChatResponse
import ai.router.sdk.models.ChatUsage
import ai.router.sdk.models.ContentPart
import ai.router.sdk.models.ContentPartType
import ai.router.sdk.models.ModelInfo
import ai.router.sdk.models.ModelList
import ai.router.sdk.models.ProviderType
import codes.momo.agent.liveBaseUrl
import codes.momo.agent.liveChatModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes

/**
 * The one permitted fake: a network stand-in for router *failures*, never
 * for model behaviour. A real server process is pointed at it over
 * `--ai-router-base-url`; each `POST /v1/chat/completions` it receives
 * consumes the next scripted [Reply], and once the script is spent every
 * request is [Reply.Forward]ed verbatim to the live router — so a case
 * plants a failure and the real model answers whatever follows it. It
 * scripts no conversations: what the model says is never its business.
 */
internal class FaultyRouter private constructor() : AutoCloseable {

    private val script = ConcurrentLinkedQueue<Reply>()

    private val received = AtomicInteger()

    private val server: EmbeddedServer<*, *> =
        embeddedServer(io.ktor.server.cio.CIO, host = "127.0.0.1", port = 0) { standIn() }

    /** How the stand-in answers one chat completion request. */
    sealed interface Reply {

        /** An HTTP error with ai-router's error envelope — 503 reads as transient to the lib, 404 as terminal. */
        data class Status(val code: Int) : Reply

        /** A 200 whose body is not JSON at all. */
        data object Malformed : Reply

        /** A 200 carrying a well-formed completion whose `finish_reason` is `error`. */
        data object FinishReasonError : Reply

        /** The request proxied verbatim to the live router, its response returned as is. */
        data object Forward : Reply
    }

    val baseUrl: String get() = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().single().port }}"

    /** Chat completion requests received so far, scripted and forwarded alike. */
    val chatRequests: Int get() = received.get()

    /** Queues [replies] for the next chat completion requests, in order; anything after them forwards. */
    fun script(vararg replies: Reply) {
        script.addAll(replies)
    }

    override fun close() {
        server.stop()
        upstream.close()
    }

    private fun Application.standIn() {
        routing {
            get("/v1/models") {
                call.respondText(wire.encodeToString(ModelList.serializer(), CATALOG), ContentType.Application.Json)
            }
            post("/v1/chat/completions") {
                received.incrementAndGet()
                val body = call.receiveText()
                when (val reply = script.poll() ?: Reply.Forward) {
                    is Reply.Status -> call.respondText(
                        """{"error":{"type":"scripted_error","message":"scripted ${reply.code}"}}""",
                        ContentType.Application.Json,
                        HttpStatusCode.fromValue(reply.code),
                    )
                    Reply.Malformed -> call.respondText("this is not json", ContentType.Application.Json)
                    Reply.FinishReasonError -> call.respondText(
                        wire.encodeToString(ChatResponse.serializer(), FAILED_COMPLETION),
                        ContentType.Application.Json,
                    )
                    Reply.Forward -> {
                        val response = upstream.post("$liveBaseUrl/v1/chat/completions") {
                            contentType(ContentType.Application.Json)
                            setBody(body)
                        }
                        call.respondBytes(response.bodyAsBytes(), response.contentType(), response.status)
                    }
                }
            }
        }
    }

    companion object {

        /** Starts a stand-in on a free loopback port. */
        fun start(): FaultyRouter = FaultyRouter().also { it.server.start(wait = false) }

        /** ai-router's own field omission, so the stand-in's JSON reads like the router's. */
        private val wire = Json {
            encodeDefaults = false
            explicitNulls = false
        }

        private val upstream = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 5.minutes.inWholeMilliseconds
            }
        }

        /** A minimal usable catalog: the one model the tier converses with, chat plus tools. */
        private val CATALOG = ModelList(
            `object` = "list",
            data = listOf(
                ModelInfo(
                    id = liveChatModel.substringBefore(':'),
                    model = liveChatModel,
                    provider = liveChatModel.substringAfterLast('@'),
                    providerType = ProviderType.CLOUD,
                    capabilities = listOf(Capability.CHAT, Capability.TOOLS),
                ),
            ),
        )

        private val FAILED_COMPLETION = ChatResponse(
            model = liveChatModel,
            message = ChatMessage(
                role = "assistant",
                content = listOf(ContentPart(ContentPartType.TEXT, text = "provider-side failure")),
            ),
            finishReason = "error",
            usage = ChatUsage(
                promptTokens = 0,
                completionTokens = 0,
                totalTokens = 0,
                reasoningTokens = 0,
                cacheReadTokens = 0,
            ),
        )
    }
}
