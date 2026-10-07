package codes.momo.agent.subagent

import ai.router.sdk.schema.Description
import codes.momo.agent.tool.Tool
import codes.momo.agent.tool.ToolContext
import codes.momo.agent.tool.ToolResult
import codes.momo.agent.tool.ToolSpec
import kotlinx.serialization.Serializable

@Serializable
internal data class PromptSubagentArgs(
    @Description("Name of the subagent to prompt.")
    val name: String,
    @Description("The message to send it.")
    val message: String,
)

internal class PromptSubagentTool(
    spec: ToolSpec,
    private val subagents: Subagents,
) : Tool<PromptSubagentArgs>(
    spec = spec,
    description = PROMPT_SUBAGENT_DESCRIPTION,
    argsSerializer = PromptSubagentArgs.serializer(),
) {

    override val timeoutExempt: Boolean = true

    override suspend fun execute(args: PromptSubagentArgs, context: ToolContext): ToolResult =
        subagents.prompt(args.name, args.message)
}

private val PROMPT_SUBAGENT_DESCRIPTION: String = """
    Sends a message to a subagent created with spawn_subagent, waits while it works, and returns
    the message it ends its turn with. Each prompt is one run bounded by the subagent's own turn
    and wall-clock budgets. The subagent keeps its conversation across prompts: follow up,
    answer its questions, or hand it more work by prompting the same name again.
""".trimIndent()
