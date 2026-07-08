package codes.momo.agent.tool

import ai.router.sdk.models.ToolDefinition
import ai.router.sdk.schema.SchemaGenerator
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * One capability the model can invoke, in exactly one of two kinds: a
 * [DispatchedTool] the agent runs in-process, or an [ExternalTool]
 * answered from outside the process. A subclass binds a `@Serializable`
 * arguments class [A] by supplying its generated serializer;
 * name-addressed lookup and dispatch are [ToolRegistry]'s job.
 *
 * @throws IllegalArgumentException when [name] is blank or contains whitespace.
 */
public sealed class Tool<A : Any>(
    /** Stable name the model addresses the tool by. */
    public val name: String,
    description: String,
    private val argsSerializer: KSerializer<A>,
) {

    init {
        require(name.isNotBlank()) { "Tool name must not be blank." }
        require(name.none { it.isWhitespace() }) { "Tool name must not contain whitespace: '$name'." }
    }

    /** The LLM-facing definition sent with chat requests. */
    public val definition: ToolDefinition = ToolDefinition(
        name = name,
        description = description,
        parameters = SchemaGenerator.generate(argsSerializer.descriptor),
    )

    /**
     * Decodes [arguments], running the argument class's own validation;
     * leniency contract on [ToolRegistry.execute].
     */
    internal fun decode(arguments: JsonObject): A =
        toolArgumentsJson.decodeFromJsonElement(argsSerializer, arguments)
}

private val toolArgumentsJson: Json = Json { ignoreUnknownKeys = true }
