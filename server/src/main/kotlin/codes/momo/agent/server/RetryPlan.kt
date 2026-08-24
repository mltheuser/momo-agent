package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import codes.momo.agent.RunSettings

/**
 * What retrying a session's failed run means: cut its stored log back to
 * [lastSurvivingSequenceId] — the failure tail never happened — and resume
 * the beheaded run under [settings], the settings its own opening recorded.
 */
internal class RetryPlan(val lastSurvivingSequenceId: Long, val settings: RunSettings)

/**
 * The plan for retrying [events]'s last run, or the
 * [SessionConflictException] naming why there is nothing to retry. The
 * log's tail — ignoring the metadata events a cut preserves anywhere — must
 * be a run failing, and the cut lands before the turn that failed: the
 * run's last `llm_call_started` and everything after it go, and so does
 * anything between it and the conversation the resume continues from — so
 * an in-band provider failure's poisoned `llm_call_finished` leaves with
 * the failure tail, and the automatic retries of a spent backoff schedule
 * are re-earned from the start.
 */
internal fun retryPlan(events: List<AgentEvent>): RetryPlan {
    val tail = events.lastOrNull { !it.isPreservedByACut() }
    if ((tail as? AgentEvent.RunFinished)?.status != RunResult.Status.ERROR) {
        throw SessionConflictException("Nothing to retry: the session's last run did not fail.")
    }
    val opening = events.indexOfLast { it is AgentEvent.RunStarted || it is AgentEvent.RunResumed }
    val run = if (opening == -1) emptyList() else events.subList(opening, events.size)
    if (run.count { it is AgentEvent.RunFinished } != 1) {
        // No opening at all, or a failure that is not the opened run's own
        // finish (a run killed before its opening event was logged): a cut
        // computed from it would behead the previous, healthy run.
        throw SessionConflictException("Nothing to retry: the log records no run to resume.")
    }
    val failureTail = run.lastOrNull { it is AgentEvent.LlmCallStarted } ?: tail
    val lastSurviving = events.last { it.sequenceId < failureTail.sequenceId && it.carriesConversation() }
    return RetryPlan(lastSurviving.sequenceId, runSettingsOf(events[opening]))
}

/**
 * Whether transcript derivation reads this event — see
 * [codes.momo.agent.Agent.load] — plus the log-opening [AgentEvent.SessionStarted]:
 * the events a retry's cut can leave as the log's last.
 */
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
