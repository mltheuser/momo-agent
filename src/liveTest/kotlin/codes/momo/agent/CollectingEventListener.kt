package codes.momo.agent

/** Test listener recording every event, in emission order. */
internal class CollectingEventListener : AgentEventListener {

    val events = mutableListOf<AgentEvent>()

    override fun onEvent(event: AgentEvent) {
        events += event
    }
}
