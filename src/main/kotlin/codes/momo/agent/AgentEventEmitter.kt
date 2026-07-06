package codes.momo.agent

/** Stamps each event with the session's next sequence ID and the current wall-clock time. */
internal class AgentEventEmitter(
    private val listener: AgentEventListener,
    private var nextSequenceId: Long,
) {

    // Synchronized so emissions racing across threads (a title rename during
    // an active run) cannot duplicate or reorder sequence IDs.
    @Synchronized
    fun emit(event: (sequenceId: Long, timestampMillis: Long) -> AgentEvent) {
        val stamped = event(nextSequenceId++, System.currentTimeMillis())
        try {
            listener.onEvent(stamped)
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            // Deliberately includes CancellationException — onEvent never
            // carries the run's own cancellation (it is not a suspension
            // point), so rethrowing would only let a listener cancel a run.
        }
    }
}
