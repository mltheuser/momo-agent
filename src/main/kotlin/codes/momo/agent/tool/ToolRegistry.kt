package codes.momo.agent.tool

import ai.router.sdk.models.ToolDefinition
import codes.momo.agent.Budgets
import codes.momo.agent.environment.ExecutionEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Immutable, name-addressed set of [Tool]s and the single seam through
 * which tool calls are dispatched; the model-friendly result conventions
 * live once, in [execute].
 *
 * @throws IllegalArgumentException when [tools] contains duplicate names.
 */
public class ToolRegistry(tools: List<Tool<*>>) {

    private val toolsByName: Map<String, Tool<*>> = tools.associateBy { it.name }

    init {
        require(toolsByName.size == tools.size) {
            val duplicates = tools.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
            "Tools with duplicate names cannot be registered: ${duplicates.sorted().joinToString(", ")}."
        }
    }

    /** The known-tool set harness validation runs against. */
    public val names: Set<String>
        get() = toolsByName.keys

    /**
     * The LLM-facing definitions for [toolNames] (typically a harness's
     * tool list), in the given order.
     *
     * @throws IllegalArgumentException on a name not in the registry — a
     *   caller bug; validate against [names] up front.
     */
    public fun definitions(toolNames: List<String>): List<ToolDefinition> =
        toolNames.map { name ->
            val tool = requireNotNull(toolsByName[name]) { unknownToolMessage(name) }
            tool.definition
        }

    /**
     * Executes tool [name] with the model-provided [arguments] against
     * [environment]. Every outcome is a [ToolResult] the model can react
     * to; only coroutine cancellation and JVM [Error]s escape:
     *
     * - unknown [name] or undecodable arguments → [ToolResult.Error]
     *   naming the problem (unknown argument keys are ignored — small
     *   models often add stray fields);
     * - an unexpected exception from the tool → [ToolResult.Error];
     * - exceeding [Budgets.TOOL_TIMEOUT] → [ToolResult.TimedOut];
     * - text over [MAX_RESULT_CHARS] → truncated, with
     *   [TRUNCATION_MARKER] appended.
     */
    public suspend fun execute(
        name: String,
        arguments: JsonObject,
        environment: ExecutionEnvironment,
    ): ToolResult {
        val tool = toolsByName[name]
        val result = if (tool == null) {
            ToolResult.Error(unknownToolMessage(name))
        } else {
            dispatch(tool, arguments, environment)
        }
        return result.bounded()
    }

    private suspend fun dispatch(
        tool: Tool<*>,
        arguments: JsonObject,
        environment: ExecutionEnvironment,
    ): ToolResult {
        val invocation = try {
            tool.bind(arguments, environment)
        } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
            return ToolResult.Error("invalid arguments for tool '${tool.name}': ${exception.message ?: exception}")
        }
        return try {
            withTimeout(Budgets.TOOL_TIMEOUT + TIMEOUT_GRACE) { invocation() }
        } catch (_: TimeoutCancellationException) {
            ToolResult.TimedOut()
        } catch (exception: CancellationException) {
            throw exception
        } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
            ToolResult.Error("tool '${tool.name}' failed unexpectedly: $exception")
        }
    }

    private fun unknownToolMessage(name: String): String =
        "unknown tool '$name'; available tools: ${formatNames()}."

    private fun formatNames(): String =
        if (names.isEmpty()) "(none)" else names.sorted().joinToString(", ")

    private fun ToolResult.bounded(): ToolResult = when (this) {
        is ToolResult.Success -> ToolResult.Success(text.bounded())
        is ToolResult.Error -> ToolResult.Error(message.bounded())
        is ToolResult.TimedOut -> ToolResult.TimedOut(partialOutput?.bounded())
    }

    private fun String.bounded(): String {
        if (length <= MAX_RESULT_CHARS) return this
        // Strings are UTF-16 in memory; cutting a surrogate pair in half
        // leaves text that cannot be re-encoded to UTF-8 for the model.
        val cut = if (this[MAX_RESULT_CHARS - 1].isHighSurrogate()) MAX_RESULT_CHARS - 1 else MAX_RESULT_CHARS
        return take(cut) + TRUNCATION_MARKER
    }

    public companion object {

        /**
         * Per-result cap on model-facing text: where
         * [ExecutionEnvironment.MAX_CAPTURED_BYTES] protects the JVM
         * heap, this far smaller bound protects the model's context.
         */
        public const val MAX_RESULT_CHARS: Int = 32 * 1024

        /** Appended to capped text so the model knows output was cut short. */
        public const val TRUNCATION_MARKER: String =
            "\n[output truncated: exceeded $MAX_RESULT_CHARS characters]"

        /**
         * Headroom the dispatch timer adds over [Budgets.TOOL_TIMEOUT] so
         * a tool's own budget-bound exec call, started after the dispatch
         * timer, can report its timeout first — see [Tool.execute].
         */
        private val TIMEOUT_GRACE: Duration = 10.seconds
    }
}
