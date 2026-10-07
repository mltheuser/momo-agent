package codes.momo.agent.tool

import ai.router.sdk.chat.ToolDefinition
import codes.momo.agent.AgentEvent.ToolCallFinished.Outcome
import codes.momo.agent.content.ModelContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
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

    internal suspend fun execute(
        name: String,
        arguments: JsonObject,
        context: ToolContext,
        timeout: Duration = TOOL_TIMEOUT,
    ): ToolExecution {
        val start = TimeSource.Monotonic.markNow()
        val tool = toolsByName[name]
        val result = when (tool) {
            null -> ToolResult.Error(unknownToolMessage(name))
            else -> dispatch(tool, arguments, context, timeout)
        }
        val maxChars = tool?.maxResultChars ?: MAX_RESULT_CHARS
        return ToolExecution(
            outcome = result.outcome,
            content = result.content.fittedTo(maxChars),
            truncated = !result.content.fitsIn(maxChars),
            duration = start.elapsedNow(),
        )
    }

    private suspend fun dispatch(
        tool: Tool<*>,
        arguments: JsonObject,
        context: ToolContext,
        timeout: Duration,
    ): ToolResult {
        val invocation = try {
            tool.bind(arguments, context)
        } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
            return invalidArgumentsError(tool, exception)
        }

        val backstop = if (timeout >= TOOL_TIMEOUT) timeout + TIMEOUT_GRACE else timeout
        return try {
            if (tool.timeoutExempt) invocation() else withTimeout(backstop) { invocation() }
        } catch (_: TimeoutCancellationException) {
            ToolResult.TimedOut(timeout = timeout)
        } catch (exception: CancellationException) {
            throw exception
        } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
            ToolResult.Error("tool '${tool.name}' failed unexpectedly: $exception")
        }
    }

    private fun invalidArgumentsError(tool: Tool<*>, exception: Exception): ToolResult.Error =
        ToolResult.Error("invalid arguments for tool '${tool.name}': ${exception.message ?: exception}")

    private fun unknownToolMessage(name: String): String =
        "unknown tool '$name'; available tools: ${formatNames()}."

    private fun formatNames(): String =
        if (names.isEmpty()) "(none)" else names.sorted().joinToString(", ")

    public companion object {

        public const val MAX_RESULT_CHARS: Int = 96 * 1024

        private val TIMEOUT_GRACE: Duration = 10.seconds
    }
}

internal val TOOL_TIMEOUT: Duration = 24.hours

internal data class ToolExecution(

    val outcome: Outcome,

    val content: ModelContent,

    val truncated: Boolean,

    val duration: Duration,
)
