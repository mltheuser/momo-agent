package codes.momo.agent.tool

import codes.momo.agent.AgentEvent.ToolCallFinished.Outcome
import codes.momo.agent.content.ModelContent
import kotlin.time.Duration

internal sealed interface ToolResult {

    val outcome: Outcome

    val content: ModelContent

    data class Success(override val content: ModelContent) : ToolResult {

        constructor(text: String) : this(ModelContent.Text(text))

        override val outcome: Outcome get() = Outcome.SUCCESS
    }

    data class Error(val message: String) : ToolResult {

        override val outcome: Outcome get() = Outcome.ERROR

        override val content: ModelContent get() = ModelContent.Text("Error: $message")
    }

    data class TimedOut(
        val partialOutput: String? = null,

        val timeout: Duration = TOOL_TIMEOUT,
    ) : ToolResult {

        override val outcome: Outcome get() = Outcome.TIMED_OUT

        override val content: ModelContent
            get() = ModelContent.Text(
                buildString {
                    append("Error: tool execution timed out after $timeout")
                    if (timeout < TOOL_TIMEOUT) {
                        append(" (the run's remaining wall-clock budget)")
                    }
                    append(".")
                    if (!partialOutput.isNullOrEmpty()) {
                        append("\nPartial output before the timeout:\n")
                        append(partialOutput)
                    }
                },
            )
    }
}
