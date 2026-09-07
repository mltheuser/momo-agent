package codes.momo.agent.harness

import java.nio.file.Path

public class Harness internal constructor(

    internal val tools: List<String>,

    internal val instructions: String,

    internal val subagents: Map<String, SubagentType>,

    internal val folder: Path? = null,
) {

    internal constructor(tools: List<String>, instructions: String) : this(tools, instructions, emptyMap())

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

    internal fun requireToolsKnown(knownTools: Set<String>) {
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

internal class SubagentType(
    val description: String,
) {

    @Volatile // Assigned once the whole load pass finished, so a composition may reference itself.
    private var resolved: Harness? = null

    val harness: Harness
        get() = checkNotNull(resolved) { "unresolved subagent type — Harness.load wires every type before returning." }

    fun resolveTo(child: Harness) {
        resolved = child
    }
}

internal const val SPAWN_SUBAGENT_TOOL: String = "spawn_subagent"

internal const val PROMPT_SUBAGENT_TOOL: String = "prompt_subagent"

internal val SUBAGENT_TOOL_NAMES: Set<String> = setOf(SPAWN_SUBAGENT_TOOL, PROMPT_SUBAGENT_TOOL)
