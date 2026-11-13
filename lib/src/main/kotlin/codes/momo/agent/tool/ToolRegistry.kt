package codes.momo.agent.tool

import ai.router.sdk.chat.ToolCall
import ai.router.sdk.chat.ToolDefinition
import codes.momo.agent.AgentEvent.ToolCallFinished.Outcome
import codes.momo.agent.content.ModelContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
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
        call: ToolCall,
        context: ToolContext,
        timeout: Duration = TOOL_TIMEOUT,
    ): ToolExecution {
        val start = TimeSource.Monotonic.markNow()
        val tool = toolsByName[call.function.name]
        val result = when (tool) {
            null -> ToolResult.Error(unknownToolMessage(call.function.name))
            else -> dispatch(tool, call, context, timeout)
        }
        return result.executed(tool?.maxResultChars ?: MAX_RESULT_CHARS, start.elapsedNow())
    }

    private fun ToolResult.executed(maxChars: Int, duration: Duration): ToolExecution {
        val unconfirmed = (this as? ToolResult.Provisional)?.takeIf { content.fitsIn(maxChars) }
        return ToolExecution(
            outcome = outcome,
            content = content.fittedTo(maxChars),
            truncated = !content.fitsIn(maxChars),
            duration = duration,
            otherwise = unconfirmed?.let { { reason -> it.otherwise(reason).executed(maxChars, duration) } },
        )
    }

    private suspend fun dispatch(
        tool: Tool<*>,
        call: ToolCall,
        context: ToolContext,
        timeout: Duration,
    ): ToolResult {
        val invocation = try {
            tool.bind(call, context)
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

internal class ToolExecution(

    val outcome: Outcome,

    val content: ModelContent,

    val truncated: Boolean,

    val duration: Duration,

    private val otherwise: ((reason: String) -> ToolExecution)? = null,
) {

    val isProvisional: Boolean
        get() = otherwise != null

    fun afterFailedModelCall(reason: String): ToolExecution =
        checkNotNull(otherwise) { "only a provisional result changes when the model call carrying it fails." }(reason)
}
