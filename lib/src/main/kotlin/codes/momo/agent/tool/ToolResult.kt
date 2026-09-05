package codes.momo.agent.tool

import codes.momo.agent.Budgets
import kotlin.time.Duration

public sealed interface ToolResult {

    public val text: String

    public data class Success(override val text: String) : ToolResult

    public data class Image(val mimeType: String, val base64Data: String) : ToolResult {

        override val text: String
            get() = "[image: $mimeType]"
    }

    public data class Error(val message: String) : ToolResult {

        override val text: String
            get() = "Error: $message"
    }

    public data class TimedOut(
        val partialOutput: String? = null,

        val timeout: Duration = Budgets.TOOL_TIMEOUT,
    ) : ToolResult {

        override val text: String
            get() = buildString {
                append("Error: tool execution timed out after $timeout")
                if (timeout < Budgets.TOOL_TIMEOUT) {
                    append(" (the run's remaining wall-clock budget)")
                }
                append(".")
                if (!partialOutput.isNullOrEmpty()) {
                    append("\nPartial output before the timeout:\n")
                    append(partialOutput)
                }
            }
    }
}
