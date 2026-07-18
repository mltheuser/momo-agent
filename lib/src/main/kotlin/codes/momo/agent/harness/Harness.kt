package codes.momo.agent.harness

import codes.momo.agent.tool.SUBAGENT_TOOL_NAMES
import java.nio.file.Path

/**
 * Validated, immutable in-memory representation of a harness folder: its
 * tools, instructions, and declared subagent types. The constructor
 * enforces the invariants, so every instance — built directly or via
 * [load] — is valid. Instances compare by identity: a composition may
 * reference itself (`self: .`), so the graph has no structural equality.
 *
 * [instructions] is exposed raw; prompt composition is owned by the agent
 * loop. Execution budgets are library-fixed — see [codes.momo.agent.Budgets].
 */
public class Harness internal constructor(
    /** Tool names in manifest order. */
    public val tools: List<String>,
    /** Raw content of `instructions.md`. */
    public val instructions: String,
    /**
     * Declared subagent types by name. A non-empty map is what offers the
     * subagent tools; loading resolves every entry, so a child harness is
     * available without filesystem access at spawn time.
     */
    public val subagents: Map<String, SubagentType>,
    /** Canonical folder this harness was loaded from; null when constructed programmatically. */
    public val folder: Path? = null,
) {

    /** A harness declaring no subagent types. */
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
        val retired = tools.filter { it in SUBAGENT_TOOL_NAMES }
        if (retired.isNotEmpty()) {
            fail(
                "'tools' lists ${retired.joinToString(", ") { "'$it'" }} — the subagent tools are " +
                    "no longer listed in 'tools'; a harness that declares a 'subagents' map is " +
                    "offered them automatically.",
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

    /**
     * Verifies every tool is contained in [knownTools]; called at agent
     * construction time so an unknown tool fails up front, not at first use.
     */
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

        /**
         * Loads and validates [folder], which must contain `harness.yaml`
         * and `instructions.md`, together with every harness folder its
         * `subagents` entries reference, recursively. Each folder loads
         * once, so self-references and reference cycles terminate.
         */
        public fun load(folder: Path): Harness = HarnessLoader.load(folder)

        private fun fail(message: String): Nothing = throw HarnessValidationException(message)
    }
}

/**
 * One declared subagent type: [description] is the model-facing one-liner
 * the spawn tool enumerates, [harness] the loaded harness a child of this
 * type runs. The loader assigns [harness] only once the whole loading
 * pass has finished — the deferral that lets a composition reference
 * itself — and the volatile hand-off keeps a loaded graph safe to share
 * across threads without synchronization.
 */
public class SubagentType internal constructor(
    public val description: String,
) {

    @Volatile
    private var resolved: Harness? = null

    /** The loaded harness a spawned child of this type runs. */
    public val harness: Harness
        get() = checkNotNull(resolved) { "unresolved subagent type — Harness.load wires every type before returning." }

    internal fun resolveTo(child: Harness) {
        resolved = child
    }
}
