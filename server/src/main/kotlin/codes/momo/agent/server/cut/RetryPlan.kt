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
    val started = events.filterIsInstance<AgentEvent.RunStarted>().last()
    val run = events.subList(events.indexOf(started), events.size)
    val failedCall = run.lastOrNull { it is AgentEvent.LlmCallStarted } ?: tail
    val lastMessage = events.last { it.sequenceId < failedCall.sequenceId && it.carriesConversation() }
    return RetryPlan(lastMessage.sequenceId, started.settingsToRerunWith())
}

private fun AgentEvent.RunStarted.settingsToRerunWith(): RunSettings = model?.let { RunSettings(it, reasoningEffort) }
    ?: throw SessionConflictException("Nothing to retry: the failed run records no model to rerun with.")

private fun AgentEvent.carriesConversation(): Boolean = when (this) {
    is AgentEvent.SessionStarted, is AgentEvent.RunStarted,
    is AgentEvent.LlmCallFinished, is AgentEvent.ToolCallFinished,
    -> true

    else -> false
}
