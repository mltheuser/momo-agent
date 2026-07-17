package codes.momo.agent.harness

import java.nio.file.Path

/**
 * Validated, immutable in-memory representation of a harness folder: its
 * tools and instructions. The constructor enforces the invariants, so
 * every instance — built directly, via [copy], or via [load] — is valid.
 *
 * [instructions] is exposed raw; prompt composition is owned by the agent
 * loop. Execution budgets are library-fixed — see [codes.momo.agent.Budgets].
 */
public data class Harness(
    /** Tool names in manifest order. */
    val tools: List<String>,
    /** Raw content of `instructions.md`. */
    val instructions: String,
) {

    init {
        validateTools()
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

        /** Loads and validates [folder], which must contain `harness.yaml` and `instructions.md`. */
        public fun load(folder: Path): Harness = HarnessLoader.load(folder)

        private fun fail(message: String): Nothing = throw HarnessValidationException(message)
    }
}
