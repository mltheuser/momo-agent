package codes.momo.agent.internal

import ai.router.sdk.models.ToolCall
import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import codes.momo.agent.harness.PROMPT_SUBAGENT_TOOL
import kotlin.time.Duration

internal class CutShortToolCall(val callId: String, val toolName: String, val started: Boolean)

internal fun List<AgentEvent>.unansweredToolCalls(): List<CutShortToolCall> = buildList {
    var turn = emptyList<ToolCall>()
    val started = mutableSetOf<String>()
    val answered = mutableSetOf<String>()

    fun settleTurn() {
        turn.filterNot { it.id in answered }
            .mapTo(this) { CutShortToolCall(it.id, it.function.name, it.id in started) }
        turn = emptyList()
        started.clear()
        answered.clear()
    }

    for (event in this@unansweredToolCalls) {
        when (event) {
            is AgentEvent.LlmCallFinished -> {
                settleTurn()
                turn = event.message.toolCalls.orEmpty()
            }

            is AgentEvent.ToolCallStarted -> started += event.callId
            is AgentEvent.ToolCallFinished -> answered += event.callId
            else -> Unit
        }
    }
    settleTurn()
}

internal fun cutShortToolResult(
    call: CutShortToolCall,
    resultText: String,
    sequenceId: Long,
    timestampMillis: Long,
): AgentEvent.ToolCallFinished = AgentEvent.ToolCallFinished(
    sequenceId = sequenceId,
    timestampMillis = timestampMillis,
    callId = call.callId,
    resultText = resultText,
    outcome = AgentEvent.ToolCallFinished.Outcome.ERROR,
    duration = Duration.ZERO,
    truncated = false,
)

internal fun CutShortToolCall.cutShortByRunEnd(runStatus: RunResult.Status): String {
    val cut = when (runStatus) {
        RunResult.Status.STOPPED -> "a user stopped the run"
        RunResult.Status.ERROR -> "the run failed"
        RunResult.Status.TURNS_EXHAUSTED -> "the run's turn budget ran out"
        RunResult.Status.TIMEOUT -> "the run's wall-clock budget ran out"
        RunResult.Status.COMPLETED -> "the run ended"
        RunResult.Status.INTERRUPTED -> "the server went down"
    }
    val prompt = toolName == PROMPT_SUBAGENT_TOOL
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

internal fun CutShortToolCall.rewoundAway(): String = when {
    started ->
        "Error: result rewound — the conversation was rewound to before this call's result. The call may have " +
            "run and taken effect, but what it returned is gone."

    else ->
        "Error: tool call not executed — the conversation was rewound before this call could execute; " +
            "it had no effect."
}
