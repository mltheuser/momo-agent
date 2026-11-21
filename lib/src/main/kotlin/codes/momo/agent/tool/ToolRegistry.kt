package codes.momo.agent.tool

import ai.router.sdk.chat.ToolCall
import ai.router.sdk.chat.ToolDefinition
import codes.momo.agent.AgentEvent.ToolCallFinished.Outcome
import codes.momo.agent.content.ModelContent
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.TimeSource

public class ToolRegistry internal constructor(tools: List<Tool<*>>) {

    private val toolsByName: Map<String, Tool<*>> = tools.associateBy { it.name }

    init {
        require(toolsByName.size == tools.size) {
            val duplicates = tools.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
            "Tools with duplicate names cannot be registered: ${duplicates.sorted().joinToString(", ")}."
        }
    }

    internal val names: Set<String>
        get() = toolsByName.keys

    internal fun restrictedTo(toolNames: List<String>): ToolRegistry =
        ToolRegistry(toolNames.map { toolsByName.getValue(it) })

    internal fun definitions(toolNames: List<String>): List<ToolDefinition> =
        toolNames.map { name ->
            val tool = requireNotNull(toolsByName[name]) { unknownToolMessage(name) }
            tool.definition
        }

    internal suspend fun execute(call: ToolCall, context: ToolContext): ToolExecution {
        val start = TimeSource.Monotonic.markNow()
        val tool = toolsByName[call.function.name]
        val result = when (tool) {
            null -> ToolResult.Error(unknownToolMessage(call.function.name))
            else -> dispatch(tool, call, context)
        }
        val maxChars = tool?.maxResultChars ?: MAX_RESULT_CHARS
        return ToolExecution(
            outcome = result.outcome,
            content = result.content.fittedTo(maxChars),
            truncated = !result.content.fitsIn(maxChars),
            duration = start.elapsedNow(),
        )
    }

    private suspend fun dispatch(tool: Tool<*>, call: ToolCall, context: ToolContext): ToolResult {
        val invocation = try {
            tool.bind(call, context)
        } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
            return invalidArgumentsError(tool, exception)
        }

        return try {
            invocation()
        } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
            // A cancelled run propagates. A cancellation the tool raised itself, like its own timeout, is its failure.
            coroutineContext.ensureActive()
            ToolResult.Error("tool '${tool.name}' failed unexpectedly: $exception")
        }
    }

    private fun invalidArgumentsError(tool: Tool<*>, exception: Exception): ToolResult.Error {
        val reason = exception.message?.lineSequence()?.first() ?: exception.toString()
        return ToolResult.Error(
            "invalid arguments for tool '${tool.name}': $reason\nThe tool takes: ${tool.definition.parameters}",
        )
    }

    private fun unknownToolMessage(name: String): String =
        "unknown tool '$name'; available tools: ${formatNames()}."

    private fun formatNames(): String =
        if (names.isEmpty()) "(none)" else names.sorted().joinToString(", ")

    public companion object {

        public const val MAX_RESULT_CHARS: Int = 96 * 1024
    }
}

internal data class ToolExecution(

    val outcome: Outcome,

    val content: ModelContent,

    val truncated: Boolean,

    val duration: Duration,
)
