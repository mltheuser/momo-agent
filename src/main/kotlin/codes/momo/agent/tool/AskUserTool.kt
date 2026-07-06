package codes.momo.agent.tool

import ai.router.sdk.schema.Description
import kotlinx.serialization.Serializable

@Serializable
public data class AskUserArgs(
    @Description("The question to put to the user.")
    val question: String,
) {

    init {
        require(question.isNotBlank()) { "'question' must not be blank." }
    }
}

/**
 * The agent's only channel to the user between the initial prompt and the
 * final message: a call parks the prompt until the user answers.
 */
public class AskUserTool : ExternalTool<AskUserArgs>(
    name = "ask_user",
    description = ASK_USER_DESCRIPTION,
    argsSerializer = AskUserArgs.serializer(),
) {

    override fun question(args: AskUserArgs): String = args.question
}

/** LLM-facing contract of [AskUserTool] — the model only knows what this says. */
private val ASK_USER_DESCRIPTION: String = """
    Asks the user a question and returns their answer. The user is not watching you work: this is
    your only way to reach them before your final message, and it is expensive — an answer may
    take hours or days to arrive. Ask only what you cannot work out from the workspace or your
    other tools, keep each question focused and directly answerable, and batch related questions
    into a single call instead of asking them one at a time.
""".trimIndent()
