package codes.momo.agent.tool

import ai.router.sdk.models.ToolDefinition
import ai.router.sdk.schema.SchemaGenerator
import codes.momo.agent.environment.ExecutionEnvironment
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

public abstract class Tool<A : Any> protected constructor(

    public val name: String,
    description: String,
    private val argsSerializer: KSerializer<A>,
) {

    init {
        require(name.isNotBlank()) { "Tool name must not be blank." }
        require(name.none { it.isWhitespace() }) { "Tool name must not contain whitespace: '$name'." }
    }

    internal open val timeoutExempt: Boolean = false

    internal open val maxResultChars: Int = ToolRegistry.MAX_RESULT_CHARS

    public val definition: ToolDefinition = ToolDefinition(
        name = name,
        description = description,
        parameters = SchemaGenerator.generate(argsSerializer.descriptor),
    )

    internal fun bind(
        arguments: JsonObject,
        environment: ExecutionEnvironment,
    ): suspend () -> ToolResult {
        val decoded = toolArgumentsJson.decodeFromJsonElement(argsSerializer, arguments)
        return { execute(decoded, environment) }
    }

    public abstract suspend fun execute(args: A, environment: ExecutionEnvironment): ToolResult
}

private val toolArgumentsJson: Json = Json { ignoreUnknownKeys = true }
