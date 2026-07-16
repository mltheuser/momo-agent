package codes.momo.agent

import codes.momo.agent.tool.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The children a session has spawned, keyed by their caller-chosen names —
 * the session-owned collaborator behind the subagent tools. The map
 * outlives individual runs: a later run can prompt a child an earlier one
 * spawned. A restored session starts with its log's spawned children as
 * dormant entries, revived on first use.
 */
internal class Subagents(private val parent: Agent, spawned: Map<String, String>) {

    /** One registered child: live, or dormant — known only by session identity until revived. */
    private sealed interface Child {
        val sessionId: String
    }

    private class Live(val agent: Agent) : Child {
        override val sessionId: String
            get() = agent.sessionId
    }

    private class Dormant(override val sessionId: String) : Child

    // Guards the map so a parent-run tool call and an embedder navigating
    // by session ID cannot revive the same child twice.
    private val mutex = Mutex()

    private val children = LinkedHashMap<String, Child>()

    init {
        spawned.forEach { (name, sessionId) -> children[name] = Dormant(sessionId) }
    }

    /** The live child spawned as [name], for test access into the tree. */
    operator fun get(name: String): Agent? = (children[name] as? Live)?.agent

    suspend fun spawn(name: String): ToolResult = mutex.withLock {
        when {
            name.isBlank() -> ToolResult.Error("subagent name must not be blank.")

            name in children -> ToolResult.Error(
                "a subagent named '$name' already exists — pick an unused name, or prompt the existing one.",
            )

            else -> {
                children[name] = Live(parent.spawnChild(name))
                ToolResult.Success("spawned subagent '$name'")
            }
        }
    }

    suspend fun prompt(name: String, message: String): ToolResult {
        val child = mutex.withLock { resolve(name) }
        return when {
            child == null -> ToolResult.Error(
                "no subagent named '$name' — spawn it first. Existing subagents: ${formatNames()}.",
            )

            message.isBlank() -> ToolResult.Error("the message to a subagent must not be blank.")

            else -> promptChild(child, name, message)
        }
    }

    /** The child registered under [sessionId], revived when dormant; null when unknown. */
    suspend fun childBySessionId(sessionId: String): Agent? = mutex.withLock {
        children.entries.firstOrNull { it.value.sessionId == sessionId }?.let { resolve(it.key) }
    }

    /** The child registered under [sessionId] while it is live; never revives. */
    suspend fun liveChildBySessionId(sessionId: String): Agent? = mutex.withLock {
        children.values.firstNotNullOfOrNull { child -> (child as? Live)?.agent?.takeIf { it.sessionId == sessionId } }
    }

    /**
     * The live child registered as [name], reviving a dormant one; the
     * caller holds [mutex].
     */
    private suspend fun resolve(name: String): Agent? = when (val child = children[name]) {
        null -> null

        is Live -> child.agent

        is Dormant -> parent.reviveChild(name, child.sessionId).also { revived ->
            if (revived == null) children.remove(name) else children[name] = Live(revived)
        }
    }

    /**
     * One blocking child run: the child's final message is the result
     * verbatim; a run ending any other way becomes an error result the
     * parent can react to.
     */
    private suspend fun promptChild(child: Agent, name: String, message: String): ToolResult = try {
        parent.awaitingChildRun { override -> child.send(message, override) }.asToolResult(name)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: IllegalStateException) {
        // The child's own busy guard, reaching the model as data — possible
        // once children are promptable from outside the parent's loop.
        ToolResult.Error("subagent '$name' is still working on an earlier prompt — try again once it finishes.")
    }

    private fun formatNames(): String =
        if (children.isEmpty()) "(none)" else children.keys.joinToString(", ")
}

private fun RunResult.asToolResult(name: String): ToolResult = when (status) {
    RunResult.Status.COMPLETED -> ToolResult.Success(finalMessage.orEmpty())

    RunResult.Status.TURNS_EXHAUSTED -> ToolResult.Error(
        "subagent '$name' run ended as TURNS_EXHAUSTED — it spent its turn budget before answering. " +
            "Prompting it again continues where it left off with a fresh budget.",
    )

    RunResult.Status.TIMEOUT -> ToolResult.Error(
        "subagent '$name' run ended as TIMEOUT — it spent its wall-clock budget before answering. " +
            "Prompting it again continues where it left off with a fresh budget.",
    )

    RunResult.Status.ERROR -> ToolResult.Error(
        "subagent '$name' run ended as ERROR — ${error ?: "its LLM call failed"}.",
    )
}
