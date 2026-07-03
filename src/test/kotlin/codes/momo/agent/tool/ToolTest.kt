package codes.momo.agent.tool

import ai.router.sdk.schema.Description
import codes.momo.agent.environment.ExecutionEnvironment
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ToolTest {

    @Test
    @DisplayName("The definition carries the name, the description, and the schema generated from the args class")
    fun definitionCarriesNameDescriptionAndSchema() {
        val definition = GreetTool().definition

        assertEquals("greet", definition.name)
        assertEquals("Greets a person by name.", definition.description)
        val schema = assertNotNull(definition.parameters)
        assertEquals("string", schema.property("name").text("type"))
        assertEquals("Name of the person to greet.", schema.property("name").text("description"))
        assertEquals("integer", schema.property("excitement").text("type"))
        assertEquals(listOf("name"), schema.getValue("required").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    @DisplayName("Construction rejects a blank name and a name containing whitespace")
    fun invalidNamesAreRejected() {
        assertFailsWith<IllegalArgumentException> { GreetTool(name = "  ") }
        val exception = assertFailsWith<IllegalArgumentException> { GreetTool(name = "say hi") }

        assertContains(exception.message.orEmpty(), "say hi")
    }

    private fun JsonObject.property(name: String): JsonObject =
        getValue("properties").jsonObject.getValue(name).jsonObject

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
}

// ─── Test tool ────────────────────────────────────────────────────────

@Serializable
private data class GreetArgs(
    @Description("Name of the person to greet.")
    val name: String,
    @Description("Number of exclamation marks to append.")
    val excitement: Int = 0,
)

private class GreetTool(name: String = "greet") : Tool<GreetArgs>(
    name = name,
    description = "Greets a person by name.",
    argsSerializer = GreetArgs.serializer(),
) {

    override suspend fun execute(args: GreetArgs, environment: ExecutionEnvironment): ToolResult =
        ToolResult.Success("Hello, ${args.name}${"!".repeat(args.excitement)}")
}
