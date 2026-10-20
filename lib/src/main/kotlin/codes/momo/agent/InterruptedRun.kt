package codes.momo.agent

import ai.router.sdk.chat.ChatUsage
import codes.momo.agent.internal.cutShortByRunEnd
import codes.momo.agent.internal.cutShortToolResult
import codes.momo.agent.internal.unansweredToolCalls
import kotlin.time.Duration

public fun repairInterruptedRun(openRun: List<AgentEvent>, timestampMillis: Long): List<AgentEvent> {
    require(openRun.firstOrNull() is AgentEvent.RunStarted) { "An open run starts with run_started." }
    require(openRun.none { it is AgentEvent.RunFinished }) { "An open run has no run_finished." }
    var nextSequenceId = openRun.last().sequenceId + 1
    val cutShort = openRun.unansweredToolCalls().map { call ->
        cutShortToolResult(call, call.cutShortByRunEnd(RunResult.Status.INTERRUPTED), nextSequenceId++, timestampMillis)
    }
    val turns = openRun.filterIsInstance<AgentEvent.LlmCallFinished>()
    val finished = AgentEvent.RunFinished(
        sequenceId = nextSequenceId,
        timestampMillis = timestampMillis,
        status = RunResult.Status.INTERRUPTED,
        finalMessage = null,
        usage = turns.fold(ChatUsage.ZERO) { sum, turn -> sum + turn.usage },
        turnsUsed = turns.size,
        elapsed = openRun.filterIsInstance<AgentEvent.BudgetUpdated>().lastOrNull()?.elapsed ?: Duration.ZERO,
    )
    return cutShort + finished
}
