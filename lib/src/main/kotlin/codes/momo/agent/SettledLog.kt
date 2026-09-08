package codes.momo.agent

import codes.momo.agent.internal.ZERO_USAGE
import codes.momo.agent.internal.cutShortByRunEnd
import codes.momo.agent.internal.cutShortToolResult
import codes.momo.agent.internal.plus
import codes.momo.agent.internal.unansweredToolCalls
import kotlin.time.Duration

public fun repairInterruptedRun(openRun: List<AgentEvent>, timestampMillis: Long): List<AgentEvent> {
    require(openRun.firstOrNull().let { it is AgentEvent.RunStarted || it is AgentEvent.RunResumed }) {
        "An open run starts with run_started or run_resumed."
    }
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
        usage = turns.fold(ZERO_USAGE) { sum, turn -> sum + turn.usage },
        turnsUsed = turns.size,
        elapsed = openRun.filterIsInstance<AgentEvent.BudgetUpdated>().lastOrNull()?.elapsed ?: Duration.ZERO,
    )
    return cutShort + finished
}
