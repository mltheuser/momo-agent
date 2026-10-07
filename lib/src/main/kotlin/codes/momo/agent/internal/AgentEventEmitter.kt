package codes.momo.agent.internal

import codes.momo.agent.AgentEvent
import codes.momo.agent.AgentEventListener
import codes.momo.agent.SessionSelection
import codes.momo.agent.after

/** Stamps and writes the agent's events, and keeps the selection they record: log and memory always agree. */
internal class AgentEventEmitter(
    private val listener: AgentEventListener,
    private var nextSequenceId: Long,
    selection: SessionSelection,
) {

    @Volatile
    var selection: SessionSelection = selection
        private set

    @Synchronized
    fun <T : AgentEvent> emit(event: (sequenceId: Long, timestampMillis: Long) -> T): T {
        val stamped = event(nextSequenceId++, System.currentTimeMillis())
        selection = selection.after(stamped)
        try {
            listener.onEvent(stamped)
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
        }
        return stamped
    }
}
