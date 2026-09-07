package codes.momo.agent.subagent

import ai.router.sdk.schema.Description
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.harness.PROMPT_SUBAGENT_TOOL
import codes.momo.agent.tool.Tool
import codes.momo.agent.tool.ToolResult
import kotlinx.serialization.Serializable

@Serializable
internal data class PromptSubagentArgs(
    @Description("Name of the subagent to prompt.")
    val name: String,
    @Description("The message to send it.")
    val message: String,
)

internal class PromptSubagentTool(
    private val subagents: Subagents,
) : Tool<PromptSubagentArgs>(
    name = PROMPT_SUBAGENT_TOOL,
    description = PROMPT_SUBAGENT_DESCRIPTION,
    argsSerializer = PromptSubagentArgs.serializer(),
) {

    override val timeoutExempt: Boolean = true

    override suspend fun execute(args: PromptSubagentArgs, environment: ExecutionEnvironment): ToolResult =
        subagents.prompt(args.name, args.message)
}

private val PROMPT_SUBAGENT_DESCRIPTION: String = """
    Sends a message to a subagent created with spawn_subagent, waits while it works, and returns
    the message it ends its turn with. Each prompt is one run bounded by the subagent's own turn
    and wall-clock budgets. The subagent keeps its conversation across prompts: follow up,
    answer its questions, or hand it more work by prompting the same name again.
""".trimIndent()
