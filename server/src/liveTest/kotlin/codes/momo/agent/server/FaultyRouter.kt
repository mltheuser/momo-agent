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

internal class FaultyRouter private constructor() : AutoCloseable {

    private val script = ConcurrentLinkedQueue<Reply>()

    private val received = AtomicInteger()

    private val server: EmbeddedServer<*, *> =
        embeddedServer(io.ktor.server.cio.CIO, host = "127.0.0.1", port = 0) { standIn() }

    sealed interface Reply {

        data class Status(val code: Int) : Reply

        data object Malformed : Reply

        data object FinishReasonError : Reply

        data object Forward : Reply
    }

    val baseUrl: String get() = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().single().port }}"

    val chatRequests: Int get() = received.get()

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

        fun start(): FaultyRouter = FaultyRouter().also { it.server.start(wait = false) }

        private val wire = Json {
            encodeDefaults = false
            explicitNulls = false
        }

        private val upstream = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 5.minutes.inWholeMilliseconds
            }
        }

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
