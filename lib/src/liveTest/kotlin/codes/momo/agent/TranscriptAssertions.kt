package codes.momo.agent

import ai.router.sdk.models.ChatMessage
import kotlin.test.assertTrue

/** Every tool call in [transcript] must be answered by a later tool-role message. */
internal fun assertToolCallsAnswered(transcript: List<ChatMessage>) {
    transcript.forEachIndexed { index, message ->
        message.toolCalls.orEmpty().forEach { call ->
            assertTrue(
                transcript.drop(index + 1).any { it.role == "tool" && it.toolCallId == call.id },
                "tool call '${call.id}' (${call.function.name}) has no tool result message",
            )
        }
    }
}
