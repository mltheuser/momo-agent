package codes.momo.agent

/**
 * Observer of a session's [AgentEvent] log. Called synchronously from the
 * run for every event, in sequence order — implementations must return
 * quickly and not throw. A thrown exception is swallowed, so a listener
 * can never alter a run's outcome.
 */
public fun interface AgentEventListener {

    public fun onEvent(event: AgentEvent)

    /**
     * The listener observing the subagent this session spawns as [name],
     * with session identity [sessionId] — asked once per spawn, before the
     * child emits its first event. The default observes nothing, so
     * observation covers the subagent tree only where an embedder opts in.
     */
    public fun listenerForSubagent(name: String, sessionId: String): AgentEventListener =
        NoOpAgentEventListener
}

/** Listener that ignores everything — the default for embedders that do not observe. */
internal object NoOpAgentEventListener : AgentEventListener {

    override fun onEvent(event: AgentEvent): Unit = Unit
}
