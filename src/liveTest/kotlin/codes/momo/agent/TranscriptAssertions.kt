package codes.momo.agent

import ai.router.sdk.models.ChatMessage
import ai.router.sdk.models.ContentPartType
import kotlin.test.assertTrue

/** The message's concatenated text parts. */
internal val ChatMessage.text: String
    get() = content.filter { it.type == ContentPartType.TEXT }.mapNotNull { it.text }.joinToString("")

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
