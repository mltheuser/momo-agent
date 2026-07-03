package codes.momo.agent.tool

import codes.momo.agent.Budgets

/**
 * Outcome of one tool execution. The chat protocol's tool message has no
 * error flag, so [text] alone must make the outcome legible to the model:
 * the failure variants render an explicit `Error:` signal into it.
 */
public sealed interface ToolResult {

    /** The model-facing text, sent back as the tool message content. */
    public val text: String

    /** The tool did what was asked; [text] is its output. */
    public data class Success(override val text: String) : ToolResult

    /** The tool (or its dispatch) failed; [message] names the problem. */
    public data class Error(val message: String) : ToolResult {

        override val text: String
            get() = "Error: $message"
    }

    /**
     * [Budgets.TOOL_TIMEOUT] elapsed and execution was cut off;
     * [partialOutput] carries whatever the timed-out call preserved.
     */
    public data class TimedOut(val partialOutput: String? = null) : ToolResult {

        override val text: String
            get() = buildString {
                append("Error: tool execution timed out after ${Budgets.TOOL_TIMEOUT}.")
                if (!partialOutput.isNullOrEmpty()) {
                    append("\nPartial output before the timeout:\n")
                    append(partialOutput)
                }
            }
    }
}
