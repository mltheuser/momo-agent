package codes.momo.agent

import ai.router.sdk.models.ChatMessage
import ai.router.sdk.models.ChatResponse
import ai.router.sdk.models.ChatUsage
import ai.router.sdk.models.ContentPart
import ai.router.sdk.models.ContentPartType
import ai.router.sdk.models.ToolCall
import ai.router.sdk.models.ToolCallFunction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Serves [responses] as HTTP 200 chat completions, one per request, in
 * order; then answers nothing.
 *
 * Exists to script the failure turns a live model cannot produce on cue
 * (a failed finish reason, a budget expiring mid-tool-batch): the agent
 * runs through the real client and HTTP stack.
 */
internal fun scriptedServer(vararg responses: ChatResponse): ServerSocket {
    val server = ServerSocket(0, responses.size, InetAddress.getLoopbackAddress())
    thread(isDaemon = true) {
        for (response in responses) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                return@thread
            }
            socket.use { it.respondWith(Json.encodeToString(ChatResponse.serializer(), response)) }
        }
    }
    return server
}

/** A chat response with no tool calls, as a scripted server serves it. */
internal fun assistantResponse(finishReason: String, text: String = ""): ChatResponse = ChatResponse(
    model = "test-model",
    message = ChatMessage(
        role = "assistant",
        content = listOf(ContentPart(type = ContentPartType.TEXT, text = text)),
    ),
    finishReason = finishReason,
    usage = RESPONSE_USAGE,
)

/** A chat response asking for [calls], as a scripted server serves it. */
internal fun toolCallResponse(vararg calls: ToolCall): ChatResponse = ChatResponse(
    model = "test-model",
    message = ChatMessage(role = "assistant", content = emptyList(), toolCalls = calls.toList()),
    finishReason = "tool_calls",
    usage = RESPONSE_USAGE,
)

internal fun bashCall(id: String, command: String): ToolCall = ToolCall(
    id = id,
    function = ToolCallFunction(name = "bash", arguments = buildJsonObject { put("command", command) }),
)

private val RESPONSE_USAGE: ChatUsage = ChatUsage(
    promptTokens = 1,
    completionTokens = 1,
    totalTokens = 2,
    reasoningTokens = 0,
    cacheReadTokens = 0,
)

private fun Socket.respondWith(body: String) {
    consumeRequest()
    val payload = body.toByteArray()
    getOutputStream().run {
        write(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${payload.size}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(),
        )
        write(payload)
        flush()
    }
}

/** Reads the full request first, so closing after the response cannot reset the client mid-send. */
private fun Socket.consumeRequest() {
    val input = getInputStream()
    val head = StringBuilder()
    while (!head.endsWith("\r\n\r\n")) {
        val byte = input.read()
        if (byte < 0) return
        head.append(byte.toChar())
    }
    val bodyLength = Regex("content-length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
        .find(head)?.groupValues?.get(1)?.toInt() ?: 0
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var remaining = bodyLength
    while (remaining > 0) {
        val read = input.read(buffer, 0, minOf(remaining, buffer.size))
        if (read < 0) break
        remaining -= read
    }
}
