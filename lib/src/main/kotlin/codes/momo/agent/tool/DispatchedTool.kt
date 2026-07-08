package codes.momo.agent.tool

import codes.momo.agent.environment.ExecutionEnvironment
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject

/** A tool the agent runs in-process: [ToolRegistry.execute] dispatches calls to [execute]. */
public abstract class DispatchedTool<A : Any> protected constructor(
    name: String,
    description: String,
    argsSerializer: KSerializer<A>,
) : Tool<A>(name, description, argsSerializer) {

    /**
     * Decodes [arguments] and returns the bound invocation. Decoding
     * throws here, before any tool code runs, so the dispatcher can tell
     * bad arguments from tool failures.
     */
    internal fun bind(
        arguments: JsonObject,
        environment: ExecutionEnvironment,
    ): suspend () -> ToolResult {
        val decoded = decode(arguments)
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
