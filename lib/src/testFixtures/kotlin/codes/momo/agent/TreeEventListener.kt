package codes.momo.agent

/** Listener collecting one session's events and handing every spawned subagent a child of itself. */
public open class TreeEventListener : AgentEventListener {

    public val events: MutableList<AgentEvent> = mutableListOf()
    public val children: MutableMap<String, TreeEventListener> = mutableMapOf()

    override fun onEvent(event: AgentEvent) {
        events += event
    }

    override fun listenerForSubagent(name: String, sessionId: String): AgentEventListener =
        TreeEventListener().also { children[name] = it }
}
