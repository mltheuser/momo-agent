package codes.momo.agent.tool

import ai.router.sdk.schema.Description
import codes.momo.agent.Subagents
import codes.momo.agent.environment.ExecutionEnvironment
import kotlinx.serialization.Serializable

@Serializable
public data class PromptSubagentArgs(
    @Description("Name of the subagent to prompt.")
    val name: String,
    @Description("The message to send it.")
    val message: String,
)

/**
 * One blocking conversational round with a child in the session's
 * [Subagents]: sends the message and returns the child's final message.
 */
public class PromptSubagentTool internal constructor(
    private val subagents: Subagents,
) : Tool<PromptSubagentArgs>(
    name = NAME,
    description = PROMPT_SUBAGENT_DESCRIPTION,
    argsSerializer = PromptSubagentArgs.serializer(),
) {

    override val timeoutExempt: Boolean = true

    override suspend fun execute(args: PromptSubagentArgs, environment: ExecutionEnvironment): ToolResult =
        subagents.prompt(args.name, args.message)

    internal companion object {

        const val NAME: String = "prompt_subagent"
    }
}

/** LLM-facing contract of [PromptSubagentTool] — the model only knows what this says. */
private val PROMPT_SUBAGENT_DESCRIPTION: String = """
    Sends a message to a subagent created with spawn_subagent, waits while it works, and returns
    the message it ends its turn with. The subagent keeps its conversation across prompts:
    follow up, answer its questions, or hand it more work by prompting the same name again.
""".trimIndent()
