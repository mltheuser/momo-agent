package codes.momo.agent

import codes.momo.agent.tool.ToolResult
import kotlinx.coroutines.CancellationException

/**
 * The children a session has spawned, keyed by their caller-chosen names —
 * the session-owned collaborator behind the subagent tools. The map
 * outlives individual runs: a later run can prompt a child an earlier one
 * spawned.
 */
internal class Subagents(private val parent: Agent) {

    private val children = mutableMapOf<String, Agent>()

    /** The child spawned as [name], for test access into the tree. */
    operator fun get(name: String): Agent? = children[name]

    fun spawn(name: String): ToolResult = when {
        name.isBlank() -> ToolResult.Error("subagent name must not be blank.")

        name in children -> ToolResult.Error(
            "a subagent named '$name' already exists — pick an unused name, or prompt the existing one.",
        )

        else -> {
            children[name] = parent.spawnChild(name)
            ToolResult.Success("spawned subagent '$name'")
        }
    }

    suspend fun prompt(name: String, message: String): ToolResult {
        val child = children[name]
        return when {
            child == null -> ToolResult.Error(
                "no subagent named '$name' — spawn it first. Existing subagents: ${formatNames()}.",
            )

            message.isBlank() -> ToolResult.Error("the message to a subagent must not be blank.")

            else -> promptChild(child, name, message)
        }
    }

    /**
     * One blocking child run: the child's final message is the result
     * verbatim; a run ending any other way becomes an error result the
     * parent can react to.
     */
    private suspend fun promptChild(child: Agent, name: String, message: String): ToolResult = try {
        parent.awaitingChildRun { child.send(message) }.asToolResult(name)
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
