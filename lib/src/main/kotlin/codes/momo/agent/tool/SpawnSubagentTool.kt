package codes.momo.agent.tool

import ai.router.sdk.schema.Description
import codes.momo.agent.Subagents
import codes.momo.agent.environment.ExecutionEnvironment
import kotlinx.serialization.Serializable

@Serializable
public data class SpawnSubagentArgs(
    @Description("Caller-chosen, human-readable name for the new subagent.")
    val name: String,
)

/**
 * Allocates a named child agent in the session's [Subagents]; nothing is
 * sent to it — conversing is [PromptSubagentTool]'s job.
 */
public class SpawnSubagentTool internal constructor(
    private val subagents: Subagents,
) : Tool<SpawnSubagentArgs>(
    name = NAME,
    description = SPAWN_SUBAGENT_DESCRIPTION,
    argsSerializer = SpawnSubagentArgs.serializer(),
) {

    override val timeoutExempt: Boolean = true

    override suspend fun execute(args: SpawnSubagentArgs, environment: ExecutionEnvironment): ToolResult =
        subagents.spawn(args.name)

    internal companion object {

        const val NAME: String = "spawn_subagent"
    }
}

/** LLM-facing contract of [SpawnSubagentTool] — the model only knows what this says. */
private val SPAWN_SUBAGENT_DESCRIPTION: String = """
    Creates a subagent — a fresh agent with the same instructions, tools, and workspace as you,
    working for you under the name you give it here. It starts with no conversation; send it
    work with prompt_subagent. Delegate self-contained pieces of work to subagents to keep your
    own context focused.
""".trimIndent()
