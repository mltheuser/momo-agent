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
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HarnessTest {

    @TempDir
    lateinit var tempDir: Path

    // ─── Fixture helpers ──────────────────────────────────────────────

    /** Renders a subagents entry line block for [manifestYaml]. */
    private fun subagentEntry(type: String, path: String? = ".", description: String? = "A helper agent."): String =
        buildString {
            appendLine("  \"$type\":")
            path?.let { appendLine("    path: \"$it\"") }
            description?.let { appendLine("    description: \"$it\"") }
        }.trimEnd('\n')

    /** Renders a harness.yaml; all string scalars quoted so blanks survive. */
    private fun manifestYaml(
        tools: List<String> = listOf("bash", "read_file"),
        extraTopLevelLine: String? = null,
        subagentEntries: List<String>? = null,
    ): String = buildString {
        appendLine("tools:")
        tools.forEach { appendLine("  - \"$it\"") }
        subagentEntries?.let { entries ->
            appendLine("subagents:")
            entries.forEach { appendLine(it) }
        }
        extraTopLevelLine?.let { appendLine(it) }
    }

    /** Writes a harness folder named [name] into the temp dir; pass null to omit a file. */
    private fun harnessFolder(
        manifest: String? = manifestYaml(),
        instructions: String? = "Understand the task, explore, then edit.",
        name: String = "my-harness",
    ): Path {
        val folder = tempDir.resolve(name).createDirectories()
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

        assertEquals(listOf("bash", "read_file", "write_file", "edit_file"), harness.tools)
        assertTrue(harness.instructions.contains("Understand the task"), "instructions.md content is exposed raw")
        // Its one subagent type is itself: `self: .` resolves to the same loaded instance.
        val self = harness.subagents.getValue("self")
        assertTrue(self.description.isNotBlank())
        assertSame(harness, self.harness)
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
        val folder = harnessFolder(manifest = "tools: [unclosed")
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
        val folder = harnessFolder(manifest = "{}\n")
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
        val folder = harnessFolder(manifest = "tools: bash\n")
        assertLoadFails(folder, "harness.yaml", "not a valid harness manifest", "'tools'", "list")
    }

    @Test
    @DisplayName("A manifest still carrying a model key is rejected with the retirement guidance")
    fun modelKeyFails() {
        val folder = harnessFolder(manifest = manifestYaml(extraTopLevelLine = "model: \"qwen3.5:9b:local@ollama\""))
        assertLoadFails(folder, "harness.yaml", "'model' key", "no longer part of harness.yaml", "each prompt")
    }

    @Test
    @DisplayName("A manifest carrying a model key with a null value is rejected with the retirement guidance")
    fun nullModelKeyFails() {
        val folder = harnessFolder(manifest = manifestYaml(extraTopLevelLine = "model:"))
        assertLoadFails(folder, "harness.yaml", "'model' key", "no longer part of harness.yaml", "each prompt")
    }

    @Test
    @DisplayName("A manifest still listing a subagent tool in tools is rejected with the retirement guidance")
    fun subagentToolInToolsFails() {
        val folder = harnessFolder(manifest = manifestYaml(tools = listOf("bash", "spawn_subagent")))
        assertLoadFails(folder, "harness.yaml", "'spawn_subagent'", "no longer listed in 'tools'", "'subagents' map")
    }

    @Test
    @DisplayName("Both retired subagent tools in tools are named in the retirement guidance")
    fun bothSubagentToolsInToolsFail() {
        val folder = harnessFolder(
            manifest = manifestYaml(tools = listOf("bash", "spawn_subagent", "prompt_subagent")),
        )
        assertLoadFails(folder, "harness.yaml", "'spawn_subagent'", "'prompt_subagent'", "'subagents' map")
    }

    @Test
    @DisplayName("Direct construction with a subagent tool in tools is rejected with the same guidance")
    fun directConstructionWithSubagentToolFails() {
        val exception = assertFailsWith<HarnessValidationException> {
            Harness(tools = listOf("bash", "prompt_subagent"), instructions = "Test.")
        }
        assertContains(exception.message.orEmpty(), "no longer listed in 'tools'")
    }

    // ─── Field validation ─────────────────────────────────────────────

    @Test
    @DisplayName("An empty tool list is rejected")
    fun emptyToolListFails() {
        val folder = harnessFolder(manifest = "tools: []\n")
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

    // ─── Subagents map validation ─────────────────────────────────────

    @Test
    @DisplayName("A subagents entry without a path is rejected")
    fun subagentEntryWithoutPathFails() {
        val folder = harnessFolder(
            manifest = manifestYaml(subagentEntries = listOf(subagentEntry("helper", path = null))),
        )
        assertLoadFails(folder, "harness.yaml", "not a valid harness manifest", "'path'")
    }

    @Test
    @DisplayName("A subagents entry without a description is rejected")
    fun subagentEntryWithoutDescriptionFails() {
        val folder = harnessFolder(
            manifest = manifestYaml(subagentEntries = listOf(subagentEntry("helper", description = null))),
        )
        assertLoadFails(folder, "harness.yaml", "not a valid harness manifest", "'description'")
    }

    @Test
    @DisplayName("A blank subagent description is rejected, naming the type")
    fun blankSubagentDescriptionFails() {
        val folder = harnessFolder(
            manifest = manifestYaml(subagentEntries = listOf(subagentEntry("helper", description = "  "))),
        )
        assertLoadFails(folder, "harness.yaml", "'helper'", "non-blank description")
    }

    @Test
    @DisplayName("A blank subagent type name is rejected")
    fun blankSubagentTypeNameFails() {
        val folder = harnessFolder(manifest = manifestYaml(subagentEntries = listOf(subagentEntry("  "))))
        assertLoadFails(folder, "harness.yaml", "subagents", "blank")
    }

    @Test
    @DisplayName("A subagent type name with whitespace is rejected, quoting the offender")
    fun whitespaceSubagentTypeNameFails() {
        val folder = harnessFolder(manifest = manifestYaml(subagentEntries = listOf(subagentEntry("my helper"))))
        assertLoadFails(folder, "harness.yaml", "subagents", "whitespace", "'my helper'")
    }

    // ─── Recursive loading ────────────────────────────────────────────

    @Test
    @DisplayName("A referenced harness loads relative to the declaring folder and resolves at spawn time")
    fun referencedHarnessLoadsRelativeToTheDeclaringFolder() {
        val child = harnessFolder(
            manifest = manifestYaml(tools = listOf("bash")),
            instructions = "Child instructions.",
            name = "child",
        )
        val parent = harnessFolder(
            manifest = manifestYaml(subagentEntries = listOf(subagentEntry("helper", path = "../${child.fileName}"))),
            name = "parent",
        )

        val harness = Harness.load(parent)

        val helper = harness.subagents.getValue("helper")
        assertEquals("A helper agent.", helper.description)
        assertEquals("Child instructions.", helper.harness.instructions)
        assertEquals(listOf("bash"), helper.harness.tools)
    }

    @Test
    @DisplayName("A subagents entry with an absolute path is rejected, naming the type and path")
    fun absoluteSubagentPathFails() {
        val child = harnessFolder(manifest = manifestYaml(tools = listOf("bash")), name = "child")
        val folder = harnessFolder(
            manifest = manifestYaml(subagentEntries = listOf(subagentEntry("helper", path = child.toString()))),
        )
        assertLoadFails(folder, "harness.yaml", "'helper'", "absolute path", "relative to the declaring harness folder")
    }

    @Test
    @DisplayName("A subagents entry referencing a missing folder fails, naming the type and folder")
    fun missingReferencedFolderFails() {
        val folder = harnessFolder(
            manifest = manifestYaml(subagentEntries = listOf(subagentEntry("helper", path = "../nowhere"))),
        )
        assertLoadFails(folder, "harness.yaml", "'helper'", "does not exist", "nowhere")
    }

    @Test
    @DisplayName("A broken referenced harness fails the parent's load, naming the reference chain")
    fun brokenReferencedHarnessFailsPathQualified() {
        val child = harnessFolder(
            manifest = "tools: []\n",
            name = "broken-child",
        )
        val parent = harnessFolder(
            manifest = manifestYaml(subagentEntries = listOf(subagentEntry("helper", path = "../${child.fileName}"))),
            name = "parent",
        )

        assertLoadFails(
            parent,
            parent.resolve("harness.yaml").toString(),
            "referenced as subagent type 'helper'",
            child.resolve("harness.yaml").toString(),
            "at least one",
        )
    }

    @Test
    @DisplayName("A self-referencing harness loads once, its type resolving to the same instance")
    fun selfReferenceLoadsOnce() {
        val folder = harnessFolder(manifest = manifestYaml(subagentEntries = listOf(subagentEntry("self"))))

        val harness = Harness.load(folder)

        assertSame(harness, harness.subagents.getValue("self").harness)
    }

    @Test
    @DisplayName("A mutual reference cycle loads each folder once and terminates")
    fun mutualCycleLoadsOnceAndTerminates() {
        val a = tempDir.resolve("a").createDirectories()
        val b = tempDir.resolve("b").createDirectories()
        a.resolve("harness.yaml").writeText(manifestYaml(subagentEntries = listOf(subagentEntry("b", path = "../b"))))
        a.resolve("instructions.md").writeText("Instructions for a.")
        b.resolve("harness.yaml").writeText(manifestYaml(subagentEntries = listOf(subagentEntry("a", path = "../a"))))
        b.resolve("instructions.md").writeText("Instructions for b.")

        val loadedA = Harness.load(a)

        val loadedB = loadedA.subagents.getValue("b").harness
        assertEquals("Instructions for b.", loadedB.instructions)
        assertSame(loadedA, loadedB.subagents.getValue("a").harness)
    }

    // ─── Direct construction ──────────────────────────────────────────

    @Test
    @DisplayName("The constructor runs validation, so no invalid Harness can be built directly")
    fun directConstructionValidates() {
        val exception = assertFailsWith<HarnessValidationException> {
            Harness(tools = emptyList(), instructions = "Understand the task, explore, then edit.")
        }
        assertContains(exception.message.orEmpty(), "tools")
        assertContains(exception.message.orEmpty(), "at least one")
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
