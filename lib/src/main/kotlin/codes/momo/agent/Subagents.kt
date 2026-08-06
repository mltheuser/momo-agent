package codes.momo.agent

import ai.router.sdk.models.ReasoningEffort
import codes.momo.agent.tool.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The children a session has spawned, keyed by their caller-chosen names —
 * the session-owned collaborator behind the subagent tools. Each child is
 * of one of the parent harness's [declaredTypes], remembered together with
 * its spawn-time model and effort overrides; [spawnModels] answers whether
 * a requested model override exists. The map outlives individual runs: a
 * later run can prompt a child an earlier one spawned. A restored session
 * starts with its log's spawned children as dormant entries, revived on
 * first use.
 */
internal class Subagents(
    private val parent: Agent,
    private val declaredTypes: Set<String>,
    private val spawnModels: SpawnModels,
    spawned: Map<String, SpawnedChild>,
) {

    /**
     * One registered child: live, or dormant — known only by its spawn
     * facts until revived. [type], [modelId] and [reasoningEffort] carry
     * what the spawn's [AgentEvent.SubagentSpawned] records, nulls included.
     */
    private sealed interface Child {
        val sessionId: String

        val type: String?

        val modelId: String?

        val reasoningEffort: ReasoningEffort?
    }

    private class Live(
        val agent: Agent,
        override val type: String?,
        override val modelId: String?,
        override val reasoningEffort: ReasoningEffort?,
    ) : Child {
        override val sessionId: String
            get() = agent.sessionId
    }

    private class Dormant(
        override val sessionId: String,
        override val type: String?,
        override val modelId: String?,
        override val reasoningEffort: ReasoningEffort?,
    ) : Child

    // Guards the map so a parent-run tool call and an embedder navigating
    // by session ID cannot revive the same child twice.
    private val mutex = Mutex()

    private val children = LinkedHashMap<String, Child>()

    init {
        spawned.forEach { (name, child) ->
            children[name] = Dormant(child.sessionId, child.type, child.modelId, child.reasoningEffort)
        }
    }

    /** The live child spawned as [name], for test access into the tree. */
    operator fun get(name: String): Agent? = (children[name] as? Live)?.agent

    /**
     * Two lock takes with the catalog fetch between them, never under one:
     * [mutex] also serializes the lookups the embedder serves reads through,
     * and a held fetch would block them for its whole wait. The cheap checks
     * run first so their error priority reads unchanged, and the insert
     * re-checks the name — a concurrent spawn may have taken it meanwhile.
     */
    suspend fun spawn(
        name: String,
        type: String,
        modelId: String?,
        reasoningEffort: ReasoningEffort?,
    ): ToolResult {
        val rejection = mutex.withLock { rejectSpawn(name, type, modelId) }
            ?: modelId?.let { spawnModels.rejectionFor(it) }?.let { ToolResult.Error(it) }
        return rejection ?: mutex.withLock {
            rejectSpawn(name, type, modelId) ?: run {
                val child = parent.spawnChild(name, type, modelId, reasoningEffort)
                children[name] = Live(child, type, modelId, reasoningEffort)
                ToolResult.Success("spawned subagent '$name'")
            }
        }
    }

    /** Why the spawn cannot proceed on what this map and the harness know; null when it can. Caller holds [mutex]. */
    private fun rejectSpawn(name: String, type: String, modelId: String?): ToolResult.Error? = when {
        name.isBlank() -> ToolResult.Error("subagent name must not be blank.")

        name in children -> ToolResult.Error(
            "a subagent named '$name' already exists — pick an unused name, or prompt the existing one.",
        )

        type !in declaredTypes -> ToolResult.Error(
            "unknown subagent type '$type' — declared types: ${formatTypes()}.",
        )

        modelId != null && modelId.isBlank() -> ToolResult.Error("model_id must not be blank when given.")

        else -> null
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
                Live(revived, child.type, child.modelId, child.reasoningEffort).also { children[name] = it }
            }
        }
    }

    /**
     * One blocking child run — under the child's spawn-time model and
     * effort overrides where it has them: the child's final message is the
     * result verbatim; a run ending any other way becomes an error result
     * the parent can react to.
     */
    private suspend fun promptChild(child: Live, name: String, message: String): ToolResult = try {
        parent.awaitingChildRun { settings ->
            // The spawn-time pins: a null pin inherits the driving run's
            // setting, a set one — [ReasoningEffort.NONE] included — overrides.
            val pinned = settings.copy(
                model = child.modelId ?: settings.model,
                reasoningEffort = child.reasoningEffort ?: settings.reasoningEffort,
            )
            child.agent.send(message, pinned)
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

    // The one status whose advice inverts: a user chose this, so re-prompting
    // undoes their intent. A parent only ever reads this result for a child
    // stopped from outside — a stop of its own run cancels its loop instead.
    RunResult.Status.STOPPED -> ToolResult.Error(
        "subagent '$name' run ended as STOPPED — a user deliberately stopped it. " +
            "Do not prompt it again to retry that work; report what happened and finish your run.",
    )

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
