package codes.momo.agent.server.cut

import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import codes.momo.agent.RunSettings
import codes.momo.agent.server.storage.SessionConflictException

internal class RetryPlan(val lastSurvivingSequenceId: Long, val settings: RunSettings)

internal fun retryPlan(events: List<AgentEvent>): RetryPlan {
    val tail = events.lastOrNull { !it.isPreservedByACut() }
    if ((tail as? AgentEvent.RunFinished)?.status != RunResult.Status.ERROR) {
        throw SessionConflictException("Nothing to retry: the session's last run did not fail.")
    }
    val opening = events.indexOfLast { it is AgentEvent.RunStarted || it is AgentEvent.RunResumed }
    val run = if (opening == -1) emptyList() else events.subList(opening, events.size)
    if (run.count { it is AgentEvent.RunFinished } != 1) {
        throw SessionConflictException("Nothing to retry: the log records no run to resume.")
    }
    val failureTail = run.lastOrNull { it is AgentEvent.LlmCallStarted } ?: tail
    val lastSurviving = events.last { it.sequenceId < failureTail.sequenceId && it.carriesConversation() }
    return RetryPlan(lastSurviving.sequenceId, runSettingsOf(events[opening]))
}

private fun AgentEvent.carriesConversation(): Boolean = when (this) {
    is AgentEvent.SessionStarted, is AgentEvent.RunStarted,
    is AgentEvent.LlmCallFinished, is AgentEvent.ToolCallFinished,
    -> true

    else -> false
}

private fun runSettingsOf(opening: AgentEvent): RunSettings = when (opening) {
    is AgentEvent.RunStarted -> opening.model?.let { RunSettings(it, opening.reasoningEffort) }
    is AgentEvent.RunResumed -> RunSettings(opening.model, opening.reasoningEffort)
    else -> null
} ?: throw SessionConflictException("Nothing to retry: the failed run records no model to rerun with.")
