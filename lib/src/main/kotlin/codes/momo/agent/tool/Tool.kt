package codes.momo.agent.tool

import ai.router.sdk.chat.ToolDefinition
import ai.router.sdk.schema.SchemaGenerator
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

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
        parameters = SchemaGenerator.generate(argsSerializer.descriptor),
    )

    fun bind(
        arguments: JsonObject,
        context: ToolContext,
    ): suspend () -> ToolResult {
        val decoded = toolArgumentsJson.decodeFromJsonElement(argsSerializer, arguments)
        return { execute(decoded, context) }
    }

    abstract suspend fun execute(args: A, context: ToolContext): ToolResult
}

private val toolArgumentsJson: Json = Json { ignoreUnknownKeys = true }
