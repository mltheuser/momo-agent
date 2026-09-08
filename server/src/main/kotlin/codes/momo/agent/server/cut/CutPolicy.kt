package codes.momo.agent.server.cut

import codes.momo.agent.AgentEvent
import codes.momo.agent.server.storage.InvalidRewindPointException
import codes.momo.agent.server.storage.LogLine
import kotlinx.serialization.serializer

private val PRESERVED_EVENTS: List<PreservedEvent> = listOf(
    preservedEvent<AgentEvent.SessionRenamed>(),
    preservedEvent<AgentEvent.ModelSelected>(),
)

private class PreservedEvent(val storedType: String, val matches: (AgentEvent) -> Boolean)

private inline fun <reified T : AgentEvent> preservedEvent(): PreservedEvent =
    PreservedEvent(serializer<T>().descriptor.serialName) { it is T }

private val PRESERVED_EVENT_TYPES: Set<String> = PRESERVED_EVENTS.mapTo(mutableSetOf()) { it.storedType }

internal fun AgentEvent.isPreservedByACut(): Boolean = PRESERVED_EVENTS.any { it.matches(this) }

internal fun LogLine.survivesCut(lastSurvivingSequenceId: Long): Boolean =
    sequenceId <= lastSurvivingSequenceId || type in PRESERVED_EVENT_TYPES

internal fun List<AgentEvent>.lastSurvivorOfCutFrom(sessionId: String, firstDeletedSequenceId: Long): Long {
    val named = firstOrNull { it.sequenceId == firstDeletedSequenceId }
        ?: throw InvalidRewindPointException(
            "Session $sessionId has no event with sequence ID $firstDeletedSequenceId to cut from.",
        )
    if (named !is AgentEvent.RunStarted) {
        throw InvalidRewindPointException(
            "Sequence ID $firstDeletedSequenceId names an event that is not a user message (run_started) in " +
                "session $sessionId's log; a rewind cuts from a user message.",
        )
    }
    return last { it.sequenceId < firstDeletedSequenceId }.sequenceId
}
