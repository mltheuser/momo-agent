package codes.momo.agent

import codes.momo.agent.tool.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The children a session has spawned, keyed by their caller-chosen names —
 * the session-owned collaborator behind the subagent tools. Each child is
 * of one of the parent harness's [declaredTypes], remembered together with
 * its spawn-time model override. The map outlives individual runs: a later
 * run can prompt a child an earlier one spawned. A restored session starts
 * with its log's spawned children as dormant entries, revived on first use.
 */
internal class Subagents(
    private val parent: Agent,
    private val declaredTypes: Set<String>,
    spawned: Map<String, SpawnedChild>,
) {

    /**
     * One registered child: live, or dormant — known only by its spawn
     * facts until revived. [type] and [modelId] carry what the spawn's
     * [AgentEvent.SubagentSpawned] records, nulls included.
     */
    private sealed interface Child {
        val sessionId: String

        val type: String?

        val modelId: String?
    }

    private class Live(
        val agent: Agent,
        override val type: String?,
        override val modelId: String?,
    ) : Child {
        override val sessionId: String
            get() = agent.sessionId
    }

    private class Dormant(
        override val sessionId: String,
        override val type: String?,
        override val modelId: String?,
    ) : Child

    // Guards the map so a parent-run tool call and an embedder navigating
    // by session ID cannot revive the same child twice.
    private val mutex = Mutex()

    private val children = LinkedHashMap<String, Child>()

    init {
        spawned.forEach { (name, child) -> children[name] = Dormant(child.sessionId, child.type, child.modelId) }
    }

    /** The live child spawned as [name], for test access into the tree. */
    operator fun get(name: String): Agent? = (children[name] as? Live)?.agent

    suspend fun spawn(name: String, type: String, modelId: String?): ToolResult = mutex.withLock {
        when {
            name.isBlank() -> ToolResult.Error("subagent name must not be blank.")

            name in children -> ToolResult.Error(
                "a subagent named '$name' already exists — pick an unused name, or prompt the existing one.",
            )

            type !in declaredTypes -> ToolResult.Error(
                "unknown subagent type '$type' — declared types: ${formatTypes()}.",
            )

            modelId != null && modelId.isBlank() -> ToolResult.Error("model_id must not be blank when given.")

            else -> {
                children[name] = Live(parent.spawnChild(name, type, modelId), type, modelId)
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
        children.entries.firstOrNull { it.value.sessionId == sessionId }?.let { resolve(it.key)?.agent }
    }

    /** The child registered under [sessionId] while it is live; never revives. */
    suspend fun liveChildBySessionId(sessionId: String): Agent? = mutex.withLock {
        children.values.firstNotNullOfOrNull { child -> (child as? Live)?.agent?.takeIf { it.sessionId == sessionId } }
    }

    /**
     * The live child registered as [name], reviving a dormant one; the
     * caller holds [mutex].
     */
    private suspend fun resolve(name: String): Live? = when (val child = children[name]) {
        null -> null

        is Live -> child

        is Dormant -> {
            val revived = parent.reviveChild(name, child.sessionId, child.type)
            if (revived == null) {
                children.remove(name)
                null
            } else {
                Live(revived, child.type, child.modelId).also { children[name] = it }
            }
        }
    }

    /**
     * One blocking child run — under the child's spawn-time model override
     * when it has one: the child's final message is the result verbatim; a
     * run ending any other way becomes an error result the parent can
     * react to.
     */
    private suspend fun promptChild(child: Live, name: String, message: String): ToolResult = try {
        parent.awaitingChildRun { settings ->
            child.agent.send(message, child.modelId?.let { settings.copy(model = it) } ?: settings)
        }.asToolResult(name)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: IllegalStateException) {
        // The child's own busy guard, reaching the model as data — possible
        // once children are promptable from outside the parent's loop.
        ToolResult.Error("subagent '$name' is still working on an earlier prompt — try again once it finishes.")
    }

    private fun formatNames(): String =
        if (children.isEmpty()) "(none)" else children.keys.joinToString(", ")

    private fun formatTypes(): String =
        if (declaredTypes.isEmpty()) "(none)" else declaredTypes.joinToString(", ")
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
