package codes.momo.agent

/** Test listener recording every event, in emission order. */
public class CollectingEventListener : AgentEventListener {

    public val events: MutableList<AgentEvent> = mutableListOf()

    override fun onEvent(event: AgentEvent) {
        events += event
    }
}
