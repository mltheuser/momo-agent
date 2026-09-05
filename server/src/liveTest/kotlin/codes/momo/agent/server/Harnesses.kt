package codes.momo.agent.server

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Writes a valid harness folder at [folder] and returns it; [subagents]
 * maps declared type names to the folder paths they reference.
 */
internal fun writeHarness(
    folder: Path,
    tools: List<String> = listOf("bash"),
    subagents: Map<String, String> = emptyMap(),
    instructions: String = "Test harness instructions.",
): Path {
    folder.createDirectories()
    folder.resolve("harness.yaml").writeText(
        buildString {
            appendLine("tools:")
            tools.forEach { appendLine("  - $it") }
            if (subagents.isNotEmpty()) {
                appendLine("subagents:")
                subagents.forEach { (type, path) ->
                    appendLine("  $type:")
                    appendLine("    path: $path")
                    appendLine("    description: The $type harness.")
                }
            }
        },
    )
    folder.resolve("instructions.md").writeText(instructions.trimEnd() + "\n")
    return folder
}

/** Writes a valid harness folder under [tempDir] and returns its path as a string. */
internal fun harnessPath(tempDir: Path): String = writeHarness(tempDir.resolve("harness")).toString()
