package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import kotlinx.serialization.json.JsonPrimitive

/**
 * Everything one rewind touches, as [rewindPlan]'s cascade derives it from
 * stored logs. [cuts] pairs each session whose log the rewind truncates
 * with its last surviving sequence ID — every ancestor before its
 * descendants, the rewind target first. [deletedSubtreeRoots] are the
 * roots of the subtrees the rewind deletes outright.
 */
internal class RewindPlan(
    val cuts: List<Pair<String, Long>>,
    val deletedSubtreeRoots: List<String>,
)

/**
 * The plan for cutting [targetId]'s log back to [lastSurvivingSequenceId],
 * reading logs through [storedEvents] (null for a log that is gone). The
 * cascade follows what the deleted range caused, recursively: a deleted
 * [AgentEvent.SubagentSpawned] deletes that child's subtree exactly as a
 * delete would, and a deleted `prompt_subagent` [AgentEvent.ToolCallStarted]
 * cuts the child's log strictly before the run the call drove — taking
 * everything after it, directly prompted runs included. A descendant the
 * deleted ranges never touch appears in neither list.
 */
internal fun rewindPlan(
    targetId: String,
    lastSurvivingSequenceId: Long,
    storedEvents: (sessionId: String) -> List<AgentEvent>?,
): RewindPlan {
    val builder = RewindPlanBuilder(storedEvents)
    builder.cut(targetId, lastSurvivingSequenceId)
    return builder.build()
}

private class RewindPlanBuilder(private val storedEvents: (String) -> List<AgentEvent>?) {

    private val cuts = LinkedHashMap<String, Long>()

    private val deletedRoots = LinkedHashSet<String>()

    /** Records the cut of [sessionId] back to [lastSurviving] and cascades into what it deletes. */
    fun cut(sessionId: String, lastSurviving: Long) {
        val existing = cuts[sessionId]
        if (existing != null && existing <= lastSurviving) {
            return // Already cut at least as deep.
        }
        val events = storedEvents(sessionId) ?: return // A log already gone has nothing to cut.
        cuts[sessionId] = lastSurviving
        cascade(events, lastSurviving)
    }

    fun build(): RewindPlan = RewindPlan(
        // A child can end up cut by an earlier, shallower pass and deleted
        // by a later, deeper one — the deletion wins, taking its subtree.
        cuts = cuts.filterKeys { it !in deletedRoots }.toList(),
        deletedSubtreeRoots = deletedRoots.toList(),
    )

    /** Applies the two cascade rules to the deleted range of [events] — everything after [lastSurviving]. */
    private fun cascade(events: List<AgentEvent>, lastSurviving: Long) {
        for (event in events) {
            when {
                event.sequenceId <= lastSurviving -> Unit

                event is AgentEvent.SubagentSpawned -> deletedRoots += event.sessionId

                event is AgentEvent.ToolCallStarted && event.toolName == PROMPT_SUBAGENT_TOOL ->
                    childCut(events, lastSurviving, event)?.let { (childId, childLastSurviving) ->
                        cut(childId, childLastSurviving)
                    }

                else -> Unit
            }
        }
    }

    /**
     * The cut a deleted prompt call inflicts on the child it names —
     * strictly before the run it drove — or null when it cuts nothing: the
     * name binds to no surviving spawn (a spawn inside the deleted range is
     * already a subtree deletion), the child's log is gone, or the call
     * started no run (unknown name, blank message, busy child).
     */
    private fun childCut(
        events: List<AgentEvent>,
        lastSurviving: Long,
        call: AgentEvent.ToolCallStarted,
    ): Pair<String, Long>? {
        val name = (call.arguments["name"] as? JsonPrimitive)?.content
        // The name binds to the latest spawn before the call: a name freed
        // and reused points at the child it named at the time of the call.
        val spawn = events.filterIsInstance<AgentEvent.SubagentSpawned>()
            .lastOrNull { it.name == name && it.sequenceId < call.sequenceId }
            ?.takeIf { it.sequenceId <= lastSurviving }
            ?: return null
        return storedEvents(spawn.sessionId)?.let { childEvents ->
            drivenRunStart(events, call, childEvents)?.let { driven ->
                childEvents.lastOrNull { it.sequenceId < driven.sequenceId }
                    ?.let { spawn.sessionId to it.sequenceId }
            }
        }
    }
}

/**
 * The child [AgentEvent.RunStarted] the deleted [call] drove, correlated by
 * timestamps: both logs are stamped by the same process clock,
 * `prompt_subagent` blocks for exactly the run's duration, and a busy child
 * rejects other prompts — so at most one *driven* run can start inside the
 * call's window. A call without a recorded finish (cut short by a close)
 * takes the first run at or after its start; no match means the call
 * started no run at all. The correlation is deliberately timestamp-only,
 * and inexact at its edges: a human prompt racing the call into the child's
 * busy claim, or a wall-clock step between the two logs' stamps, degrades
 * to an extra or a missed cut.
 */
private fun drivenRunStart(
    parentEvents: List<AgentEvent>,
    call: AgentEvent.ToolCallStarted,
    childEvents: List<AgentEvent>,
): AgentEvent.RunStarted? {
    val finish = parentEvents.filterIsInstance<AgentEvent.ToolCallFinished>()
        .firstOrNull { it.callId == call.callId && it.sequenceId > call.sequenceId }
    return childEvents.filterIsInstance<AgentEvent.RunStarted>().firstOrNull { run ->
        run.timestampMillis >= call.timestampMillis &&
            (finish == null || run.timestampMillis <= finish.timestampMillis)
    }
}

/** The stored wire name of the lib's prompt-subagent tool, as `tool_call_started` records it. */
private const val PROMPT_SUBAGENT_TOOL: String = "prompt_subagent"
