package codes.momo.agent

/**
 * The embedder's per-session hook: observer of the session's [AgentEvent]
 * log, and the session's access to logs the embedder has stored. [onEvent]
 * is called synchronously from the run for every event, in sequence order —
 * implementations must return quickly and not throw. Exceptions thrown
 * from [onEvent] or [listenerForSubagent] are swallowed or degraded to the
 * default, so observing can never alter a run's outcome; [storedEventsFor]
 * carries its own failure contract.
 */
public fun interface AgentEventListener {

    public fun onEvent(event: AgentEvent)

    /**
     * The listener observing the subagent this session registers as
     * [name], with session identity [sessionId] — asked once per child
     * construction (a spawn, or a revival from its stored log), before the
     * child emits its first event. The default observes nothing, so
     * observation covers the subagent tree only where an embedder opts in.
     */
    public fun listenerForSubagent(name: String, sessionId: String): AgentEventListener =
        NoOpAgentEventListener

    /**
     * The stored event log of [sessionId], asked when this session prompts
     * a dormant subagent it knows only from its own log: the child is
     * revived from the returned events. The default — null — means the log
     * is gone (deleted, or never persisted), so the subagent resolves as
     * unknown and its name becomes free again. A lookup failure must be
     * thrown, never returned as null: it propagates to the prompting
     * caller, and the name stays registered.
     */
    public suspend fun storedEventsFor(sessionId: String): List<AgentEvent>? = null
}

/** Listener that ignores everything — the default for embedders that do not observe. */
internal object NoOpAgentEventListener : AgentEventListener {

    override fun onEvent(event: AgentEvent): Unit = Unit
}
