package codes.momo.agent.tool

import ai.router.sdk.schema.Description
import codes.momo.agent.Subagents
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.harness.SubagentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class SpawnSubagentArgs(
    @Description("Caller-chosen, human-readable name for the new subagent.")
    val name: String,
    @Description("The subagent type to spawn — one of the declared types.")
    val type: String,
    @SerialName("model_id")
    @Description("Model the subagent's runs use when you prompt it; omit to use your own run's model.")
    val modelId: String? = null,
)

/**
 * Allocates a child agent of a declared subagent type in the session's
 * [Subagents]; nothing is sent to it — conversing is [PromptSubagentTool]'s
 * job. The description enumerates the declaring harness's types, so it is
 * generated per instance.
 */
public class SpawnSubagentTool internal constructor(
    private val subagents: Subagents,
    subagentTypes: Map<String, SubagentType>,
) : Tool<SpawnSubagentArgs>(
    name = NAME,
    description = spawnSubagentDescription(subagentTypes),
    argsSerializer = SpawnSubagentArgs.serializer(),
) {

    override val timeoutExempt: Boolean = true

    override suspend fun execute(args: SpawnSubagentArgs, environment: ExecutionEnvironment): ToolResult =
        subagents.spawn(args.name, args.type, args.modelId)

    internal companion object {

        const val NAME: String = "spawn_subagent"
    }
}

/** LLM-facing contract of [SpawnSubagentTool] — the model only knows what this says. */
private fun spawnSubagentDescription(subagentTypes: Map<String, SubagentType>): String = buildString {
    append(
        """
        Creates a subagent — a fresh agent of the type you pick, working for you under the name
        you give it here. It starts with no conversation; send it work with prompt_subagent.
        Delegate self-contained pieces of work to subagents to keep your own context focused.
        Runs you drive through prompt_subagent use your own run's model, unless model_id names
        another. Available types:
        """.trimIndent(),
    )
    subagentTypes.forEach { (type, entry) -> append("\n- ").append(type).append(": ").append(entry.description) }
}
