package codes.momo.agent.harness

import codes.momo.agent.tool.SUBAGENT_TOOL_NAMES
import java.nio.file.Path

public class Harness internal constructor(

    public val tools: List<String>,

    public val instructions: String,

    public val subagents: Map<String, SubagentType>,

    public val folder: Path? = null,
) {

    public constructor(tools: List<String>, instructions: String) : this(tools, instructions, emptyMap())

    init {
        validateTools()
        validateSubagents()
    }

    private fun validateTools() {
        if (tools.isEmpty()) {
            fail("'tools' must name at least one tool.")
        }
        if (tools.any { it.isBlank() }) {
            fail("'tools' must not contain blank tool names.")
        }
        val whitespaceName = tools.firstOrNull { name -> name.any { it.isWhitespace() } }
        if (whitespaceName != null) {
            fail("'tools' contains a tool name with whitespace: '$whitespaceName'.")
        }
        val duplicates = tools.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) {
            fail("'tools' contains duplicate tool names: ${duplicates.joinToString(", ")}.")
        }
        val subagentTools = tools.filter { it in SUBAGENT_TOOL_NAMES }
        if (subagentTools.isNotEmpty()) {
            fail(
                "'tools' lists ${subagentTools.joinToString(", ") { "'$it'" }}; the subagent tools are " +
                    "not listed in 'tools' but offered to a harness that declares a 'subagents' map.",
            )
        }
    }

    private fun validateSubagents() {
        if (subagents.keys.any { it.isBlank() }) {
            fail("'subagents' must not contain blank type names.")
        }
        val whitespaceName = subagents.keys.firstOrNull { name -> name.any { it.isWhitespace() } }
        if (whitespaceName != null) {
            fail("'subagents' contains a type name with whitespace: '$whitespaceName'.")
        }
        val blankDescription = subagents.entries.firstOrNull { it.value.description.isBlank() }
        if (blankDescription != null) {
            fail("subagent type '${blankDescription.key}' must have a non-blank description.")
        }
    }

    public fun requireToolsKnown(knownTools: Set<String>) {
        val unknown = tools.filterNot { it in knownTools }
        if (unknown.isNotEmpty()) {
            fail(
                "Harness uses unknown tools: ${unknown.joinToString(", ")}. " +
                    "Available tools: ${formatKnownTools(knownTools)}.",
            )
        }
    }

    private fun formatKnownTools(knownTools: Set<String>): String =
        if (knownTools.isEmpty()) "(none)" else knownTools.sorted().joinToString(", ")

    public companion object {

        public fun load(folder: Path): Harness = HarnessLoader.load(folder)

        private fun fail(message: String): Nothing = throw HarnessValidationException(message)
    }
}

public class SubagentType internal constructor(
    public val description: String,
) {

    @Volatile // Assigned once the whole load pass finished, so a composition may reference itself.
    private var resolved: Harness? = null

    public val harness: Harness
        get() = checkNotNull(resolved) { "unresolved subagent type — Harness.load wires every type before returning." }

    internal fun resolveTo(child: Harness) {
        resolved = child
    }
}
