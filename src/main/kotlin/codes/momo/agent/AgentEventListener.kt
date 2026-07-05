package codes.momo.agent

/**
 * Observer of an agent's execution. Deliberately operation-free: it marks
 * the seam where observability plugs into the loop.
 */
public interface AgentEventListener

/** Listener that ignores everything — the default for embedders that do not observe. */
internal object NoOpAgentEventListener : AgentEventListener
