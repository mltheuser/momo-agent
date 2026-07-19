package codes.momo.agent.tool

import ai.router.sdk.schema.Description
import codes.momo.agent.Subagents
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.harness.SubagentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class SpawnSubagentArgs(
    @Description("Unique name for the new subagent.")
    val name: String,
    @Description("The subagent type — one of the type names listed in the tool description.")
    val type: String,
    @SerialName("model_id")
    @Description("Model id for the subagent's runs; omit to use the same model as your own run.")
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
        Creates a subagent: a fresh agent that works for you. It starts with no conversation
        and does nothing until you send it work with prompt_subagent. Delegate self-contained
        pieces of work to subagents to keep your own context focused. Each subagent needs a
        unique name; prompt_subagent addresses it by that name. By default the subagent's runs
        use the same model as your own run; set model_id to run it on a different model. Pass
        one of the following as `type`:
        """.trimIndent(),
    )
    subagentTypes.forEach { (type, entry) -> append("\n- ").append(type).append(": ").append(entry.description) }
}
