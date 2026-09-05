package codes.momo.agent

public fun interface AgentEventListener {

    public fun onEvent(event: AgentEvent)

    public fun listenerForSubagent(name: String, sessionId: String): AgentEventListener =
        NoOpAgentEventListener

    public suspend fun storedEventsFor(sessionId: String): List<AgentEvent>? = null
}

internal object NoOpAgentEventListener : AgentEventListener {

    override fun onEvent(event: AgentEvent): Unit = Unit
}
