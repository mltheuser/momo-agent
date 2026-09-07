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
    client: AiRouterClient,
    spawned: Map<String, SpawnedChild>,
) {

    private val spawnModels = SpawnModels(client)

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

    private val mutex = Mutex()

    private val children = LinkedHashMap<String, Child>()

    init {
        spawned.forEach { (name, child) ->
            children[name] = Dormant(child.sessionId, child.type, child.modelId, child.reasoningEffort)
        }
    }

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

    suspend fun childBySessionId(sessionId: String): Agent? = mutex.withLock {
        children.entries.firstOrNull { it.value.sessionId == sessionId }?.let { resolve(it.key)?.agent }
    }

    suspend fun liveChildBySessionId(sessionId: String): Agent? = mutex.withLock {
        children.values.firstNotNullOfOrNull { child -> (child as? Live)?.agent?.takeIf { it.sessionId == sessionId } }
    }

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

    private suspend fun promptChild(child: Live, name: String, message: String): ToolResult = try {
        parent.awaitingChildRun { settings ->

            val pinned = settings.copy(
                model = child.modelId ?: settings.model,
                reasoningEffort = child.reasoningEffort ?: settings.reasoningEffort,
            )
            child.agent.send(message, pinned)
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
}
