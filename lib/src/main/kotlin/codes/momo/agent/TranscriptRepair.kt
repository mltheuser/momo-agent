package codes.momo.agent

import ai.router.sdk.models.ChatMessage
import ai.router.sdk.models.ToolCall
import codes.momo.agent.tool.PromptSubagentTool

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

internal fun toolCallRepairs(
    messages: List<ChatMessage>,
    startedCallIds: Set<String>,
    runStatus: RunResult.Status?,
): List<ChatMessage> = unansweredToolCalls(messages).map { call ->
    toolResultMessage(call.id, toolCallRepairText(call.function.name, call.id in startedCallIds, runStatus))
}

internal fun toolCallRepairText(toolName: String, started: Boolean, runStatus: RunResult.Status?): String {
    val cut = when (runStatus) {
        RunResult.Status.STOPPED -> "a user stopped the run"
        RunResult.Status.ERROR -> "the run failed"
        RunResult.Status.TURNS_EXHAUSTED -> "the run's turn budget ran out"
        RunResult.Status.TIMEOUT -> "the run's wall-clock budget ran out"
        RunResult.Status.COMPLETED -> "the run ended"
        null -> "the run was aborted"
    }
    val prompt = toolName == PromptSubagentTool.NAME
    return when {
        prompt && started ->
            "Error: prompt interrupted — $cut while the subagent was working on this message. The subagent " +
                "received the message and keeps whatever progress it made; prompting it again continues " +
                "that conversation."

        prompt ->
            "Error: prompt not delivered — $cut before this call could execute. The subagent never received " +
                "this message; prompt it again to deliver it."

        started ->
            "Error: tool execution cut short — $cut while this call was executing; it may have taken partial effect."

        else ->
            "Error: tool call not executed — $cut before this call could execute; it had no effect."
    }
}
