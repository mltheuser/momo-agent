package codes.momo.agent

import ai.router.sdk.models.ApiError
import ai.router.sdk.models.ChatMessage
import ai.router.sdk.models.ChatRequest
import ai.router.sdk.models.ChatResponse
import ai.router.sdk.models.ChatUsage
import ai.router.sdk.models.ContentPart
import ai.router.sdk.models.ContentPartType
import ai.router.sdk.models.ErrorResponse
import ai.router.sdk.models.ToolCall
import ai.router.sdk.models.ToolCallFunction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Serves [replies] as HTTP responses, one per request, in order; then
 * answers nothing.
 *
 * Exists to script the failure turns a live model cannot produce on cue
 * (a failed finish reason, a transient HTTP error, a budget expiring
 * mid-tool-batch): the agent runs through the real client and HTTP stack.
 */
public fun scriptedServer(vararg replies: ScriptedReply): ServerSocket = serveScripted(null, replies)

/** A [scriptedServer] also recording every received [ChatRequest], in order, into [requests]. */
public fun scriptedServer(requests: CopyOnWriteArrayList<ChatRequest>, vararg replies: ScriptedReply): ServerSocket =
    serveScripted(requests, replies)

private fun serveScripted(
    requests: CopyOnWriteArrayList<ChatRequest>?,
    replies: Array<out ScriptedReply>,
): ServerSocket {
    val server = ServerSocket(0, replies.size, InetAddress.getLoopbackAddress())
    thread(isDaemon = true) {
        for (reply in replies) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                return@thread
            }
            socket.use {
                val body = it.consumeRequest()
                if (requests != null && body.isNotEmpty()) {
                    requests.add(Json.decodeFromString(ChatRequest.serializer(), body))
                }
                it.respond(reply)
            }
        }
    }
    return server
}

public fun scriptedServer(vararg responses: ChatResponse): ServerSocket =
    scriptedServer(*responses.map { it.asReply() }.toTypedArray())

/** A server that never answers: every request hangs until the caller is cancelled. */
public fun scriptedServer(): ServerSocket = scriptedServer(*emptyArray<ScriptedReply>())

public val ServerSocket.baseUrl: String
    get() = "http://127.0.0.1:$localPort"

private fun Socket.respond(reply: ScriptedReply) {
    when (reply) {
        is ScriptedReply.Success ->
            respondWith(200, Json.encodeToString(ChatResponse.serializer(), reply.response))

        is ScriptedReply.Failure ->
            respondWith(
                reply.statusCode,
                Json.encodeToString(
                    ErrorResponse.serializer(),
                    ErrorResponse(ApiError(type = "scripted", message = reply.message)),
                ),
            )

        is ScriptedReply.Held ->
            respondWith(200, Json.encodeToString(ChatResponse.serializer(), reply.awaitRelease()))
    }
}

/** A chat response with no tool calls, as a scripted server serves it. */
public fun assistantResponse(finishReason: String, text: String = ""): ChatResponse = ChatResponse(
    model = "test-model",
    message = ChatMessage(
        role = "assistant",
        content = listOf(ContentPart(type = ContentPartType.TEXT, text = text)),
    ),
    finishReason = finishReason,
    usage = RESPONSE_USAGE,
)

/** A chat response asking for [calls], as a scripted server serves it. */
public fun toolCallResponse(vararg calls: ToolCall): ChatResponse = ChatResponse(
    model = "test-model",
    message = ChatMessage(role = "assistant", content = emptyList(), toolCalls = calls.toList()),
    finishReason = "tool_calls",
    usage = RESPONSE_USAGE,
)

public fun bashCall(id: String, command: String): ToolCall = ToolCall(
    id = id,
    function = ToolCallFunction(name = "bash", arguments = buildJsonObject { put("command", command) }),
)

public fun spawnSubagentCall(id: String, name: String): ToolCall = ToolCall(
    id = id,
    function = ToolCallFunction(name = "spawn_subagent", arguments = buildJsonObject { put("name", name) }),
)

public fun promptSubagentCall(id: String, name: String, message: String): ToolCall = ToolCall(
    id = id,
    function = ToolCallFunction(
        name = "prompt_subagent",
        arguments = buildJsonObject {
            put("name", name)
            put("message", message)
        },
    ),
)

/** The usage every scripted response reports. */
public val RESPONSE_USAGE: ChatUsage = ChatUsage(
    promptTokens = 1,
    completionTokens = 1,
    totalTokens = 2,
    reasoningTokens = 0,
    cacheReadTokens = 0,
)

private fun Socket.respondWith(statusCode: Int, body: String) {
    val payload = body.toByteArray()
    getOutputStream().run {
        write(
            (
                "HTTP/1.1 $statusCode Scripted\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${payload.size}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(),
        )
        write(payload)
        flush()
    }
}

/**
 * Reads the full request first — so closing after the response cannot reset
 * the client mid-send — and returns its body.
 */
private fun Socket.consumeRequest(): String {
    val input = getInputStream()
    val head = StringBuilder()
    while (!head.endsWith("\r\n\r\n")) {
        val byte = input.read()
        if (byte < 0) return ""
        head.append(byte.toChar())
    }
    val bodyLength = Regex("content-length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
        .find(head)?.groupValues?.get(1)?.toInt() ?: 0
    val body = ByteArray(bodyLength)
    var filled = 0
    while (filled < bodyLength) {
        val read = input.read(body, filled, bodyLength - filled)
        if (read < 0) break
        filled += read
    }
    return String(body, 0, filled)
}
