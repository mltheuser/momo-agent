package codes.momo.agent.tool

import ai.router.sdk.models.ToolDefinition
import ai.router.sdk.schema.SchemaGenerator
import codes.momo.agent.environment.ExecutionEnvironment
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * One capability the model can invoke, run in-process by the agent. A
 * subclass binds a `@Serializable` arguments class [A] by supplying its
 * generated serializer; name-addressed lookup and dispatch are
 * [ToolRegistry]'s job.
 *
 * @throws IllegalArgumentException when [name] is blank or contains whitespace.
 */
public abstract class Tool<A : Any> protected constructor(
    /** Stable name the model addresses the tool by. */
    public val name: String,
    description: String,
    private val argsSerializer: KSerializer<A>,
) {

    init {
        require(name.isNotBlank()) { "Tool name must not be blank." }
        require(name.none { it.isWhitespace() }) { "Tool name must not contain whitespace: '$name'." }
    }

    /**
     * Exempts the tool's dispatch from the per-execution timeout — for
     * tools whose wait is bounded by the awaited work's own budgets (a
     * subagent's run), not by this agent's clock.
     */
    internal open val timeoutExempt: Boolean = false

    /** The LLM-facing definition sent with chat requests. */
    public val definition: ToolDefinition = ToolDefinition(
        name = name,
        description = description,
        parameters = SchemaGenerator.generate(argsSerializer.descriptor),
    )

    /**
     * Decodes [arguments] — running the argument class's own validation;
     * leniency contract on [ToolRegistry.execute] — and returns the bound
     * invocation. Decoding throws here, before any tool code runs, so the
     * dispatcher can tell bad arguments from tool failures.
     */
    internal fun bind(
        arguments: JsonObject,
        environment: ExecutionEnvironment,
    ): suspend () -> ToolResult {
        val decoded = toolArgumentsJson.decodeFromJsonElement(argsSerializer, arguments)
        return { execute(decoded, environment) }
    }

    /**
     * Runs the tool against already-decoded [args]. Implementer contract:
     *
     * - All workspace access goes through [environment]; tools that need
     *   no workspace ignore it.
     * - Calls to [ExecutionEnvironment.exec] pass
     *   [codes.momo.agent.Budgets.TOOL_TIMEOUT] and map
     *   [codes.momo.agent.environment.ExecResult.TimedOut] to
     *   [ToolResult.TimedOut], surfacing partial output only when it cannot
     *   be misread as success. The budget bounds the whole execution: work
     *   overrunning it across several calls is cut off by the dispatch
     *   backstop, which keeps no partial output.
     * - Expected failures return [ToolResult.Error]; truncation, the
     *   timeout backstop, and unexpected-exception mapping happen once,
     *   in [ToolRegistry.execute].
     */
    public abstract suspend fun execute(args: A, environment: ExecutionEnvironment): ToolResult
}

private val toolArgumentsJson: Json = Json { ignoreUnknownKeys = true }
