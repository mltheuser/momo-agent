package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

private val PRESERVED_EVENTS: List<PreservedEvent> = listOf(
    preservedEvent<AgentEvent.SessionRenamed>(),
    preservedEvent<AgentEvent.ModelSelected>(),
)

private class PreservedEvent(val storedType: String, val matches: (AgentEvent) -> Boolean)

private inline fun <reified T : AgentEvent> preservedEvent(): PreservedEvent =
    PreservedEvent(serializer<T>().descriptor.serialName) { it is T }

internal val PRESERVED_EVENT_TYPES: Set<String> = PRESERVED_EVENTS.mapTo(mutableSetOf()) { it.storedType }

internal fun AgentEvent.isPreservedByACut(): Boolean = PRESERVED_EVENTS.any { it.matches(this) }

internal fun List<AgentEvent>.lastSurvivorOfCutFrom(sessionId: String, firstDeletedSequenceId: Long): Long {
    val named = firstOrNull { it.sequenceId == firstDeletedSequenceId }
    val below = filter { it.sequenceId < firstDeletedSequenceId }
    val refusal = when {
        named == null -> "Session $sessionId has no event with sequence ID $firstDeletedSequenceId to cut from."
        named.isPreservedByACut() ->
            "Sequence ID $firstDeletedSequenceId names an event a cut preserves in session $sessionId's log, " +
                "so it cannot be an event to cut from."
        below.isEmpty() ->
            "Sequence ID $firstDeletedSequenceId opens session $sessionId's log, and a cut must leave it standing."
        else -> null
    }
    if (refusal != null) {
        throw InvalidRewindPointException(refusal)
    }
    return below.last().sequenceId
}

internal class RewindPlan(
    val cuts: List<Pair<String, Long>>,
    val deletedSubtreeRoots: List<String>,
)

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

    fun cut(sessionId: String, lastSurviving: Long) {
        val existing = cuts[sessionId]
        if (existing != null && existing <= lastSurviving) {
            return
        }
        val events = storedEvents(sessionId) ?: return
        cuts[sessionId] = lastSurviving
        cascade(events, lastSurviving)
    }

    fun build(): RewindPlan = RewindPlan(

        cuts = cuts.filterKeys { it !in deletedRoots }.toList(),
        deletedSubtreeRoots = deletedRoots.toList(),
    )

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

    private fun childCut(
        events: List<AgentEvent>,
        lastSurviving: Long,
        call: AgentEvent.ToolCallStarted,
    ): Pair<String, Long>? {
        val name = (call.arguments["name"] as? JsonPrimitive)?.content

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

private const val PROMPT_SUBAGENT_TOOL: String = "prompt_subagent"
