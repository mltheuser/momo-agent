package codes.momo.agent.server.fixtures

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

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

internal fun harnessPath(tempDir: Path): String = writeHarness(tempDir.resolve("harness")).toString()

internal fun liveHarness(tempDir: Path): String =
    writeHarness(
        tempDir.resolve("harness"),
        instructions = "You are a terse assistant working in a project workspace. " +
            "Use the bash tool whenever a question concerns the workspace's files, " +
            "and keep your final messages to a single short sentence.",
    ).toString()
