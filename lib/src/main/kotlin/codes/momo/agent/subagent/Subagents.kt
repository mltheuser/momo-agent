package codes.momo.agent.subagent

import ai.router.sdk.AiRouterClient
import ai.router.sdk.models.ReasoningEffort
import codes.momo.agent.Agent
import codes.momo.agent.RunResult
import codes.momo.agent.tool.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class SpawnedChild(
    val sessionId: String,
    val type: String?,
    val modelId: String?,
    val reasoningEffort: ReasoningEffort?,
)

internal class Subagents(
    private val parent: Agent,
    private val declaredTypes: Set<String>,
    private val client: AiRouterClient,
    spawned: Map<String, SpawnedChild>,
) {

    private val mutex = Mutex()

    private val children = LinkedHashMap<String, SpawnedChild>(spawned)

    private val loaded = HashMap<String, Agent>()

    suspend fun spawn(
        name: String,
        type: String,
        modelId: String?,
        reasoningEffort: ReasoningEffort?,
    ): ToolResult {
        val rejection = mutex.withLock { rejectSpawn(name, type, modelId) }
            ?: modelId?.let { client.spawnModelRejection(it) }?.let { ToolResult.Error(it) }
        return rejection ?: mutex.withLock {
            rejectSpawn(name, type, modelId) ?: run {
                val child = parent.spawnChild(name, type, modelId, reasoningEffort)
                children[name] = SpawnedChild(child.sessionId, type, modelId, reasoningEffort)
                loaded[name] = child
                ToolResult.Success("spawned subagent '$name'")
            }
        }
    }

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
        val (child, agent) = mutex.withLock { children[name] to resolve(name) }
        return when {
            child == null || agent == null -> ToolResult.Error(
                "no subagent named '$name' — spawn it first. Existing subagents: ${formatNames()}.",
            )

            message.isBlank() -> ToolResult.Error("the message to a subagent must not be blank.")

            else -> promptChild(agent, child, name, message)
        }
    }

    suspend fun childBySessionId(sessionId: String): Agent? = mutex.withLock {
        children.entries.firstOrNull { it.value.sessionId == sessionId }?.let { resolve(it.key) }
    }

    suspend fun loadedChildBySessionId(sessionId: String): Agent? = mutex.withLock {
        loaded.values.firstOrNull { it.sessionId == sessionId }
    }

    private suspend fun resolve(name: String): Agent? {
        val child = children[name] ?: return null
        val agent = loaded[name] ?: parent.loadChild(name, child.sessionId, child.type)
        if (agent == null) {
            children.remove(name)
        } else {
            loaded[name] = agent
        }
        return agent
    }

    private suspend fun promptChild(
        agent: Agent,
        child: SpawnedChild,
        name: String,
        message: String,
    ): ToolResult = try {
        parent.awaitingChildRun { settings ->
            val pinned = settings.copy(
                model = child.modelId ?: settings.model,
                reasoningEffort = child.reasoningEffort ?: settings.reasoningEffort,
            )
            agent.send(message, pinned)
        }.asToolResult(name)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: IllegalStateException) {
        ToolResult.Error("subagent '$name' is still working on an earlier prompt — try again once it finishes.")
    }

    private fun formatNames(): String =
        if (children.isEmpty()) "(none)" else children.keys.joinToString(", ")

    private fun formatTypes(): String =
        if (declaredTypes.isEmpty()) "(none)" else declaredTypes.joinToString(", ")
}

private fun RunResult.asToolResult(name: String): ToolResult = when (status) {
    RunResult.Status.COMPLETED -> ToolResult.Success(finalMessage.orEmpty())

    RunResult.Status.STOPPED -> ToolResult.Error(
        "subagent '$name' run ended as STOPPED — a user deliberately stopped it. The subagent received this " +
            "prompt and keeps whatever progress it made. Do not prompt it again to retry that work; report " +
            "what happened and finish your run.",
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
        "subagent '$name' run ended as ERROR — ${error ?: "its LLM call failed"}. The subagent received this " +
            "prompt and keeps whatever progress it made; prompting it again continues where it left off.",
    )

    RunResult.Status.INTERRUPTED -> ToolResult.Error(
        "subagent '$name' run ended as INTERRUPTED — the server went down while it was working. The subagent " +
            "keeps whatever progress it made; prompting it again continues where it left off.",
    )
}
