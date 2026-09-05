package codes.momo.agent.tool

import ai.router.sdk.models.ReasoningEffort
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
    @Description(
        "Model id for the subagent's runs; omit to use the same model as your own run. " +
            "An unknown id's error lists the closest valid ids, so a best guess is a fine starting point.",
    )
    val modelId: String? = null,
    @SerialName("reasoning_effort")
    @Description(
        "Reasoning effort for the subagent's runs; omit to use the same effort as your own run. " +
            "'none' is a real setting for explicitly no reasoning — not the omitted default.",
    )
    val reasoningEffort: ReasoningEffort? = null,
)

public class SpawnSubagentTool internal constructor(
    private val subagents: Subagents,
    subagentTypes: Map<String, SubagentType>,
) : Tool<SpawnSubagentArgs>(
    name = NAME,
    description = spawnSubagentDescription(subagentTypes),
    argsSerializer = SpawnSubagentArgs.serializer(),
) {

    override suspend fun execute(args: SpawnSubagentArgs, environment: ExecutionEnvironment): ToolResult =
        subagents.spawn(args.name, args.type, args.modelId, args.reasoningEffort)

    internal companion object {

        const val NAME: String = "spawn_subagent"
    }
}

private fun spawnSubagentDescription(subagentTypes: Map<String, SubagentType>): String = buildString {
    append(
        """
        Creates a subagent: a fresh agent that works for you. It starts with no conversation
        and does nothing until you send it work with prompt_subagent. Delegate self-contained
        pieces of work to subagents to keep your own context focused. Each subagent needs a
        unique name; prompt_subagent addresses it by that name. By default the subagent's runs
        use the same model and reasoning effort as your own run; set model_id or
        reasoning_effort to override either. An invalid model_id is rejected with the closest
        valid ids, so guessing one and reading the error is a fine way to find it. Pass
        one of the following as `type`:
        """.trimIndent(),
    )
    subagentTypes.forEach { (type, entry) -> append("\n- ").append(type).append(": ").append(entry.description) }
}
