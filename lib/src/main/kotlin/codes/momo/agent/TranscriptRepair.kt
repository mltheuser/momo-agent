package codes.momo.agent

import ai.router.sdk.models.ChatMessage
import ai.router.sdk.models.ToolCall
import codes.momo.agent.tool.PromptSubagentTool

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

/**
 * Tool-result messages answering every call [unansweredToolCalls] finds in
 * [messages], each with the [toolCallRepairText] its facts produce.
 */
internal fun toolCallRepairs(
    messages: List<ChatMessage>,
    startedCallIds: Set<String>,
    runStatus: RunResult.Status?,
): List<ChatMessage> = unansweredToolCalls(messages).map { call ->
    toolResultMessage(call.id, toolCallRepairText(call.function.name, call.id in startedCallIds, runStatus))
}

/**
 * The model-facing text of a synthesized result for a call its run never
 * answered. It states what the caller needs in order to continue well: what
 * ended the run — [runStatus], null for a run with no recorded outcome, an
 * abort — and whether the call had [started] executing when it did. For a
 * prompt_subagent call that distinction decides whether the subagent ever
 * received the message, so those calls name it explicitly.
 */
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
