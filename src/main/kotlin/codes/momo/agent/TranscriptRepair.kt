package codes.momo.agent

import ai.router.sdk.models.ChatMessage

/**
 * Tool-result messages answering every tool call in [messages] that has
 * none yet. Only the trailing turn can dangle: earlier turns completed
 * before the next LLM call.
 */
internal fun abortedToolResults(messages: List<ChatMessage>): List<ChatMessage> {
    val lastCallerIndex = messages.indexOfLast { !it.toolCalls.isNullOrEmpty() }
    if (lastCallerIndex < 0) {
        return emptyList()
    }
    val answered = messages
        .subList(lastCallerIndex + 1, messages.size)
        .mapNotNullTo(mutableSetOf()) { it.toolCallId }
    return messages[lastCallerIndex].toolCalls.orEmpty()
        .filterNot { it.id in answered }
        .map { toolResultMessage(it.id, ABORTED_TOOL_RESULT_TEXT) }
}

/** Model-facing text of a synthesized result for a call its run never finished. */
internal const val ABORTED_TOOL_RESULT_TEXT: String =
    "Error: tool execution aborted — the run ended before this call finished."
