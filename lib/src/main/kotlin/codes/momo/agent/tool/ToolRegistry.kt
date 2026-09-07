package codes.momo.agent.tool

import ai.router.sdk.models.ToolDefinition
import codes.momo.agent.environment.ExecutionEnvironment
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
        environment: ExecutionEnvironment,
        timeout: Duration = TOOL_TIMEOUT,
    ): ToolExecution {
        val start = TimeSource.Monotonic.markNow()
        val tool = toolsByName[name]
        val result = when (tool) {
            null -> ToolResult.Error(unknownToolMessage(name))
            else -> dispatch(tool, arguments, environment, timeout)
        }
        val bounded = result.bounded(tool?.maxResultChars ?: MAX_RESULT_CHARS)
        return ToolExecution(
            result = bounded,
            truncated = bounded.text != result.text,
            duration = start.elapsedNow(),
        )
    }

    private suspend fun dispatch(
        tool: Tool<*>,
        arguments: JsonObject,
        environment: ExecutionEnvironment,
        timeout: Duration,
    ): ToolResult {
        val invocation = try {
            tool.bind(arguments, environment)
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

    private fun ToolResult.bounded(limit: Int): ToolResult = when (this) {
        is ToolResult.Success -> ToolResult.Success(text.boundedResultText(limit))
        is ToolResult.Image -> this
        is ToolResult.Error -> ToolResult.Error(message.boundedResultText(limit))
        is ToolResult.TimedOut -> ToolResult.TimedOut(partialOutput?.boundedResultText(limit), timeout)
    }

    private fun String.boundedResultText(limit: Int): String {
        if (length <= limit) return this

        val cut = if (this[limit - 1].isHighSurrogate()) limit - 1 else limit
        return take(cut) + truncationMarker(limit)
    }

    public companion object {

        public const val MAX_RESULT_CHARS: Int = 96 * 1024

        public fun truncationMarker(limit: Int): String =
            "\n[output truncated: exceeded $limit characters]"

        private val TIMEOUT_GRACE: Duration = 10.seconds
    }
}

internal val TOOL_TIMEOUT: Duration = 24.hours

internal data class ToolExecution(

    val result: ToolResult,

    val truncated: Boolean,

    val duration: Duration,
)
