package codes.momo.agent.internal

import codes.momo.agent.AgentEvent
import codes.momo.agent.AgentEventListener

internal class AgentEventEmitter(
    private val listener: AgentEventListener,
    private var nextSequenceId: Long,
) {

    @Synchronized
    fun emit(event: (sequenceId: Long, timestampMillis: Long) -> AgentEvent) {
        val stamped = event(nextSequenceId++, System.currentTimeMillis())
        try {
            listener.onEvent(stamped)
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
        }
    }
}
