package codes.momo.agent.harness

import codes.momo.agent.RunSettings
import codes.momo.agent.tool.ToolCatalog
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap

public class Harness internal constructor(

    internal val tools: List<String>,

    internal val instructions: String,

    internal val subagents: Map<String, SubagentType>,

    internal val folder: Path? = null,
) {

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
        val unknown = tools.filter { ToolCatalog.listed(it) == null }
        if (unknown.isNotEmpty()) {
            fail(
                "'tools' names unknown tools: ${unknown.joinToString(", ")}. " +
                    "Available tools: ${ToolCatalog.listable.joinToString(", ") { it.name }}.",
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

    /** The tools taking a model anywhere in this harness's tree (it and every reachable subagent), in catalog order. */
    public fun toolsWithModel(): List<String> {
        val visited: MutableSet<Harness> = Collections.newSetFromMap(IdentityHashMap())
        val pending = ArrayDeque(listOf(this))
        val used = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val harness = pending.removeFirst()
            if (visited.add(harness)) {
                used += harness.tools
                pending += harness.subagents.values.map { it.harness }
            }
        }
        return ToolCatalog.listable.filter { it.modelSource != null && it.name in used }.map { it.name }
    }

    /** Throws unless [settings] name a model for every tool in [toolsWithModel]; more entries are fine. */
    public fun requireToolModels(settings: RunSettings) {
        val missing = toolsWithModel().filterNot { it in settings.toolModels }
        if (missing.isNotEmpty()) {
            throw MissingToolModelException(missing)
        }
    }

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
