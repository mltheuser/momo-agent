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
import kotlin.time.TimeSource

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
     * The sub-registry holding exactly [toolNames], sharing this
     * registry's tool instances — so a name outside the subset errors as
     * unknown, listing only the subset as available.
     */
    internal fun restrictedTo(toolNames: List<String>): ToolRegistry =
        ToolRegistry(toolNames.map { toolsByName.getValue(it) })

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
     * [environment], bounded by [timeout] ([Tool.timeoutExempt] tools run
     * unbounded). Every outcome is a
     * [ToolExecution] whose result the model can react to; only coroutine
     * cancellation and JVM [Error]s escape:
     *
     * - unknown [name] or undecodable arguments → [ToolResult.Error]
     *   naming the problem (unknown argument keys are ignored — small
     *   models often add stray fields);
     * - an unexpected exception from the tool → [ToolResult.Error];
     * - exceeding [timeout] → [ToolResult.TimedOut];
     * - text over the tool's [Tool.maxResultChars] (default
     *   [MAX_RESULT_CHARS]) → truncated, with a marker naming the
     *   applied limit appended.
     */
    public suspend fun execute(
        name: String,
        arguments: JsonObject,
        environment: ExecutionEnvironment,
        timeout: Duration = Budgets.TOOL_TIMEOUT,
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
        // The grace applies only when the per-tool budget is the binding
        // constraint; a smaller wall-clock remainder must fire sharp.
        val backstop = if (timeout >= Budgets.TOOL_TIMEOUT) timeout + TIMEOUT_GRACE else timeout
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
        // Strings are UTF-16 in memory; cutting a surrogate pair in half
        // leaves text that cannot be re-encoded to UTF-8 for the model.
        val cut = if (this[limit - 1].isHighSurrogate()) limit - 1 else limit
        return take(cut) + truncationMarker(limit)
    }

    public companion object {

        /**
         * Default per-result cap on model-facing text: where
         * [ExecutionEnvironment.MAX_CAPTURED_BYTES] protects the JVM
         * heap, this far smaller bound protects the model's context.
         * A tool with legitimately larger payloads overrides
         * [Tool.maxResultChars].
         */
        public const val MAX_RESULT_CHARS: Int = 96 * 1024

        /** Appended to capped text so the model knows output was cut short at [limit]. */
        public fun truncationMarker(limit: Int): String =
            "\n[output truncated: exceeded $limit characters]"

        /**
         * Headroom the dispatch timer adds over [Budgets.TOOL_TIMEOUT] so
         * a tool's own budget-bound exec call, started after the dispatch
         * timer, can report its timeout first — see [Tool.execute].
         */
        private val TIMEOUT_GRACE: Duration = 10.seconds
    }
}

/** One [ToolRegistry.execute] dispatch: the model-facing result plus the facts only dispatch knows. */
public data class ToolExecution(
    /** The outcome, its payload bounded to the tool's [Tool.maxResultChars] (failure framing adds slightly more). */
    val result: ToolResult,
    /** Whether bounding cut the payload short. */
    val truncated: Boolean,
    /** Wall-clock time the dispatch took. */
    val duration: Duration,
)
