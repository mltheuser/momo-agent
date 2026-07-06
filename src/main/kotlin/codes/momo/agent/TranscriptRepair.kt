package codes.momo.agent

import ai.router.sdk.models.ChatMessage
import ai.router.sdk.models.ToolCall

/**
 * The tool calls of the trailing tool-calling message in [messages] that
 * have no result yet, in call order. Only the trailing turn can dangle:
 * earlier turns completed before the next LLM call.
 */
internal fun unansweredToolCalls(messages: List<ChatMessage>): List<ToolCall> {
    val lastCallerIndex = messages.indexOfLast { !it.toolCalls.isNullOrEmpty() }
    if (lastCallerIndex < 0) {
        return emptyList()
    }
    val answered = messages
        .subList(lastCallerIndex + 1, messages.size)
        .mapNotNullTo(mutableSetOf()) { it.toolCallId }
    return messages[lastCallerIndex].toolCalls.orEmpty().filterNot { it.id in answered }
}

/** Tool-result messages answering every call [unansweredToolCalls] finds in [messages]. */
internal fun abortedToolResults(messages: List<ChatMessage>): List<ChatMessage> =
    unansweredToolCalls(messages).map { toolResultMessage(it.id, ABORTED_TOOL_RESULT_TEXT) }

/** Model-facing text of a synthesized result for a call its run never finished. */
internal const val ABORTED_TOOL_RESULT_TEXT: String =
    "Error: tool execution aborted — the run ended before this call finished."
