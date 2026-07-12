package codes.momo.agent.harness

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.isReadable
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HarnessTest {

    @TempDir
    lateinit var tempDir: Path

    // ─── Fixture helpers ──────────────────────────────────────────────

    /** Renders a harness.yaml; all string scalars quoted so blanks survive. */
    private fun manifestYaml(
        model: String = "qwen3.5:9b:local@ollama",
        tools: List<String> = listOf("bash", "read_file"),
        extraTopLevelLine: String? = null,
    ): String = buildString {
        appendLine("model: \"$model\"")
        appendLine("tools:")
        tools.forEach { appendLine("  - \"$it\"") }
        extraTopLevelLine?.let { appendLine(it) }
    }

    /** Writes a harness folder into the temp dir; pass null to omit a file. */
    private fun harnessFolder(
        manifest: String? = manifestYaml(),
        instructions: String? = "Understand the task, explore, then edit.",
    ): Path {
        val folder = tempDir.resolve("my-harness").createDirectories()
        manifest?.let { folder.resolve("harness.yaml").writeText(it) }
        instructions?.let { folder.resolve("instructions.md").writeText(it) }
        return folder
    }

    private fun assertLoadFails(folder: Path, vararg expectedMessageParts: String) {
        val exception = assertFailsWith<HarnessValidationException> { Harness.load(folder) }
        val message = exception.message.orEmpty()
        expectedMessageParts.forEach { part ->
            assertContains(message, part, message = "expected message to contain '$part', was: $message")
        }
    }

    // ─── Happy path ───────────────────────────────────────────────────

    @Test
    @DisplayName("The shipped example harness loads with the expected values")
    fun exampleHarnessLoads() {
        // Test working directory is the Gradle project dir.
        val harness = Harness.load(Path.of("examples/coder"))

        assertEquals("qwen3.5:9b:local@ollama", harness.model)
        assertEquals(listOf("bash", "read_file", "write_file", "edit_file"), harness.tools)
        assertTrue(harness.instructions.contains("Understand the task"), "instructions.md content is exposed raw")
    }

    // ─── Missing folder / files ───────────────────────────────────────

    @Test
    @DisplayName("A missing harness folder fails, naming the folder")
    fun missingFolderFails() {
        val missing = tempDir.resolve("does-not-exist")
        assertLoadFails(missing, missing.toString(), "not found")
    }

    @Test
    @DisplayName("A path that is a file, not a directory, fails")
    fun fileInsteadOfFolderFails() {
        val file = tempDir.resolve("harness-as-file")
        file.writeText("not a folder")
        assertLoadFails(file, file.toString())
    }

    @Test
    @DisplayName("A folder without harness.yaml fails, naming the manifest file")
    fun missingManifestFails() {
        val folder = harnessFolder(manifest = null)
        assertLoadFails(folder, "harness.yaml", "missing")
    }

    @Test
    @DisplayName("A folder without instructions.md fails, naming the instructions file")
    fun missingInstructionsFails() {
        val folder = harnessFolder(instructions = null)
        assertLoadFails(folder, "instructions.md", "missing")
    }

    @Test
    @DisplayName("An unreadable instructions.md fails, naming the file")
    fun unreadableInstructionsFails() {
        val folder = harnessFolder()
        val instructionsFile = folder.resolve("instructions.md")
        instructionsFile.setPosixFilePermissions(emptySet())
        try {
            // Running as root (or similar) ignores POSIX permissions; the
            // failure can't be provoked there, so skip rather than fail.
            Assumptions.assumeFalse(
                instructionsFile.isReadable(),
                "instructions.md is still readable after chmod 000 (running as root?)",
            )
            assertLoadFails(folder, instructionsFile.toString(), "could not be read")
        } finally {
            // Restore permissions so @TempDir cleanup can delete the file.
            instructionsFile.setPosixFilePermissions(PosixFilePermissions.fromString("rw-r--r--"))
        }
    }

    // ─── Unparseable / non-deliberate YAML ────────────────────────────

    @Test
    @DisplayName("Malformed YAML fails with the file path, not a raw parser stack trace")
    fun malformedYamlFails() {
        val folder = harnessFolder(manifest = "model: [unclosed")
        assertLoadFails(folder, "harness.yaml", "not a valid harness manifest")
    }

    @Test
    @DisplayName("An unknown top-level field is rejected by name")
    fun unknownTopLevelFieldFails() {
        val folder = harnessFolder(manifest = manifestYaml(extraTopLevelLine = "temperature: 0.2"))
        assertLoadFails(folder, "harness.yaml", "temperature")
    }

    @Test
    @DisplayName("A manifest missing a required key is rejected, naming the property")
    fun missingRequiredKeyFails() {
        val folder = harnessFolder(manifest = "model: \"qwen3.5:9b:local@ollama\"\n")
        assertLoadFails(folder, "harness.yaml", "not a valid harness manifest", "'tools'", "missing")
    }

    @Test
    @DisplayName("A whitespace-only harness.yaml is rejected, naming the file")
    fun whitespaceOnlyManifestFails() {
        val folder = harnessFolder(manifest = "   \n  \n")
        assertLoadFails(folder, "harness.yaml", "not a valid harness manifest", "empty")
    }

    @Test
    @DisplayName("A scalar tools value (not a list) is rejected")
    fun scalarToolsFails() {
        val manifest = """
            model: "qwen3.5:9b:local@ollama"
            tools: bash
        """.trimIndent()
        val folder = harnessFolder(manifest = manifest)
        assertLoadFails(folder, "harness.yaml", "not a valid harness manifest", "'tools'", "list")
    }

    // ─── Field validation ─────────────────────────────────────────────

    @Test
    @DisplayName("A blank model string is rejected")
    fun blankModelFails() {
        val folder = harnessFolder(manifest = manifestYaml(model = "   "))
        assertLoadFails(folder, "harness.yaml", "model", "blank")
    }

    @Test
    @DisplayName("An empty tool list is rejected")
    fun emptyToolListFails() {
        val manifest = """
            model: "qwen3.5:9b:local@ollama"
            tools: []
        """.trimIndent()
        val folder = harnessFolder(manifest = manifest)
        assertLoadFails(folder, "harness.yaml", "tools", "at least one")
    }

    @Test
    @DisplayName("A blank tool name is rejected")
    fun blankToolNameFails() {
        val folder = harnessFolder(manifest = manifestYaml(tools = listOf("bash", "  ")))
        assertLoadFails(folder, "harness.yaml", "tools", "blank")
    }

    @Test
    @DisplayName("A whitespace-padded tool name is rejected, quoting the offender")
    fun whitespacePaddedToolNameFails() {
        val folder = harnessFolder(manifest = manifestYaml(tools = listOf("bash ", "read_file")))
        assertLoadFails(folder, "harness.yaml", "tools", "whitespace", "'bash '")
    }

    @Test
    @DisplayName("Duplicate tool names are rejected by name")
    fun duplicateToolNameFails() {
        val folder = harnessFolder(manifest = manifestYaml(tools = listOf("bash", "read_file", "bash")))
        assertLoadFails(folder, "harness.yaml", "duplicate", "bash")
    }

    // ─── Direct construction ──────────────────────────────────────────

    /** A valid Harness built directly, bypassing the loader. */
    private fun directHarness(): Harness = Harness(
        model = "qwen3.5:9b:local@ollama",
        tools = listOf("bash", "read_file"),
        instructions = "Understand the task, explore, then edit.",
    )

    @Test
    @DisplayName("copy() re-runs validation, so no invalid Harness can be built")
    fun copyRevalidates() {
        val exception = assertFailsWith<HarnessValidationException> {
            directHarness().copy(model = "   ")
        }
        assertContains(exception.message.orEmpty(), "model")
        assertContains(exception.message.orEmpty(), "blank")
    }

    // ─── Tool registry seam ───────────────────────────────────────────

    @Test
    @DisplayName("requireToolsKnown passes when every tool is known")
    fun requireToolsKnownPassesWhenAllKnown() {
        val harness = Harness.load(harnessFolder())
        harness.requireToolsKnown(setOf("bash", "read_file", "write_file"))
    }

    @Test
    @DisplayName("requireToolsKnown fails listing ALL unknown tools")
    fun requireToolsKnownListsAllUnknownTools() {
        val harness = Harness.load(
            harnessFolder(manifest = manifestYaml(tools = listOf("bash", "banana", "teleport"))),
        )

        val exception = assertFailsWith<HarnessValidationException> {
            harness.requireToolsKnown(setOf("bash", "read_file"))
        }
        val message = exception.message.orEmpty()
        assertContains(message, "banana")
        assertContains(message, "teleport")
    }
}
