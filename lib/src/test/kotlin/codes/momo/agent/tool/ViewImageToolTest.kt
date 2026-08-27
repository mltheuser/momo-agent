package codes.momo.agent.tool

import codes.momo.agent.environment.ExecResult
import codes.momo.agent.environment.LocalExecutionEnvironment
import codes.momo.agent.environment.completed
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeBytes
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class ViewImageToolTest {

    @TempDir
    lateinit var tempDir: Path

    // ─── Helpers ──────────────────────────────────────────────────────

    /** Runs the tool for [path] against a real local environment over the temp workspace. */
    private fun view(path: String): ToolResult = runBlocking {
        ViewImageTool().execute(ViewImageArgs(path), LocalExecutionEnvironment(tempDir))
    }

    private fun viewStubbed(vararg results: ExecResult): ToolResult = runBlocking {
        ViewImageTool().execute(ViewImageArgs("/workspace/image.png"), FixedResultEnvironment(*results))
    }

    // ─── Definition ───────────────────────────────────────────────────

    @Test
    @DisplayName("The definition documents the visual result, the accepted path shapes, and the size limit")
    fun definitionDocumentsTheContract() {
        val definition = ViewImageTool().definition

        assertEquals("view_image", definition.name)
        val description = assertNotNull(definition.description)
        assertContains(description, "visually")
        assertContains(description, MAX_IMAGE_BYTES.toString())
        val schema = assertNotNull(definition.parameters)
        assertContains(schema.toString(), "absolute or workspace-relative")
    }

    // ─── Success ──────────────────────────────────────────────────────

    @Test
    @DisplayName("A PNG comes back as an Image result whose base64 decodes to the file's exact bytes")
    fun pngLoadsAsImage() {
        val bytes = PNG_MAGIC + ByteArray(64) { it.toByte() }
        val file = tempDir.resolve("chart.png")
        file.writeBytes(bytes)

        val result = assertIs<ToolResult.Image>(view(file.toString()))

        assertEquals("image/png", result.mimeType)
        assertContentEquals(bytes, Base64.getDecoder().decode(result.base64Data))
        assertEquals("[image: image/png]", result.text)
    }

    @Test
    @DisplayName("The MIME type follows the lowercased extension, defaulting to image/png")
    fun mimeTypeFollowsTheExtension() {
        val expectations = mapOf(
            "photo.JPG" to "image/jpeg",
            "anim.gif" to "image/gif",
            "shot.webp" to "image/webp",
            "logo.svg" to "image/svg+xml",
            "scan.tiff" to "image/tiff",
            "no-extension" to "image/png",
            "odd.xyz" to "image/png",
        )

        expectations.forEach { (name, mimeType) ->
            assertEquals(mimeType, imageMimeType(name), name)
        }
    }

    @Test
    @DisplayName("A workspace-relative path loads against the workspace root")
    fun relativePathLoadsFromWorkspace() {
        tempDir.resolve("shots").createDirectories()
        tempDir.resolve("shots/a.png").writeBytes(PNG_MAGIC)

        assertEquals("image/png", assertIs<ToolResult.Image>(view("shots/a.png")).mimeType)
    }

    @Test
    @DisplayName("A leading tilde expands to the home directory — and only a leading one")
    fun leadingTildeExpandsToHome() {
        val home = Path.of(System.getProperty("user.home"))
        val file = createTempFile(home, "view-image-test", ".png")
        try {
            file.writeBytes(PNG_MAGIC)

            assertIs<ToolResult.Image>(view("~/" + file.fileName), "a leading ~ must reach the home file")
        } finally {
            file.deleteExisting()
        }
        assertIs<ToolResult.Error>(view("mid~tilde.png"), "a mid-path ~ must stay literal")
    }

    // ─── Errors ───────────────────────────────────────────────────────

    @Test
    @DisplayName("A missing file returns an error naming the path")
    fun missingFileReturnsError() {
        val result = assertIs<ToolResult.Error>(view("$tempDir/no-such.png"))

        assertContains(result.message, "no-such.png")
    }

    @Test
    @DisplayName("An oversized file is refused before encoding, naming its size and the limit")
    fun oversizedFileIsRefusedBeforeEncoding() {
        val environment = FixedResultEnvironment(completed(stdout = " 9000000\n"))

        val result = runBlocking {
            ViewImageTool().execute(ViewImageArgs("/workspace/image.png"), environment)
        }

        val error = assertIs<ToolResult.Error>(result)
        assertContains(error.message, "9000000")
        assertContains(error.message, MAX_IMAGE_BYTES.toString())
        assertEquals(1, environment.callCount, "the refusal must come from the size check alone")
    }

    @Test
    @DisplayName("Encoded output overrunning the capture cap is an error, never truncated media")
    fun truncatedEncodingIsAnError() {
        val result = viewStubbed(
            completed(stdout = "100\n"),
            completed(stdout = "iVBORw0KGgo=", stdoutTruncated = true),
        )

        assertContains(assertIs<ToolResult.Error>(result).message, "image.png")
    }

    @Test
    @DisplayName("A timed-out exec maps to the timed-out result")
    fun timedOutExecMapsToTimedOut() {
        val timedOut = ExecResult.TimedOut(stdout = "", stderr = "", stdoutTruncated = false, stderrTruncated = false)

        assertIs<ToolResult.TimedOut>(viewStubbed(timedOut))
    }
}

private val PNG_MAGIC: ByteArray =
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
