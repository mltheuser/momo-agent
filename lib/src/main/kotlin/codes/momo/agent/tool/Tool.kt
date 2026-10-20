package codes.momo.agent.tool

import ai.router.sdk.chat.ToolCall
import ai.router.sdk.chat.ToolDefinition
import ai.router.sdk.schema.SchemaGenerator
import kotlinx.serialization.KSerializer

internal abstract class Tool<A : Any>(

    val spec: ToolSpec,
    description: String,
    private val argsSerializer: KSerializer<A>,
) {

    val name: String
        get() = spec.name

    open val timeoutExempt: Boolean = false

    open val maxResultChars: Int = ToolRegistry.MAX_RESULT_CHARS

    val definition: ToolDefinition = ToolDefinition(
        name = spec.name,
        description = description,
        parameters = SchemaGenerator.generate(argsSerializer),
    )

    fun bind(
        call: ToolCall,
        context: ToolContext,
    ): suspend () -> ToolResult {
        val decoded = call.decode(argsSerializer)
        return { execute(decoded, context) }
    }

    abstract suspend fun execute(args: A, context: ToolContext): ToolResult
}
