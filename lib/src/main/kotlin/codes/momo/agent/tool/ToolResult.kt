package codes.momo.agent.tool

import kotlin.time.Duration

internal sealed interface ToolResult {

    val text: String

    data class Success(override val text: String) : ToolResult

    data class Image(val mimeType: String, val base64Data: String) : ToolResult {

        override val text: String
            get() = "[image: $mimeType]"
    }

    data class Error(val message: String) : ToolResult {

        override val text: String
            get() = "Error: $message"
    }

    data class TimedOut(
        val partialOutput: String? = null,

        val timeout: Duration = TOOL_TIMEOUT,
    ) : ToolResult {

        override val text: String
            get() = buildString {
                append("Error: tool execution timed out after $timeout")
                if (timeout < TOOL_TIMEOUT) {
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
