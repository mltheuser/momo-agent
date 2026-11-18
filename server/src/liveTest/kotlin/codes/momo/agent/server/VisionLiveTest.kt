package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import codes.momo.agent.server.fixtures.localWorkspace
import codes.momo.agent.server.fixtures.writeHarness
import codes.momo.agent.server.fixtures.writeWordImage
import codes.momo.agent.server.rig.awaitRunEnd
import codes.momo.agent.server.rig.createSession
import codes.momo.agent.server.rig.prompt
import codes.momo.agent.server.rig.withLiveServer
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VisionLiveTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName(
        "An image linked in a prompt reaches the model as an attachment, then view_image shows it a local file " +
            "and a hosted image, and every planted text comes back",
    )
    fun promptAttachmentAndViewImage() = withLiveServer { http ->
        val workspace = localWorkspace(tempDir)
        writeWordImage(Path.of(workspace).resolve("a.png"), PROMPT_WORD)
        writeWordImage(Path.of(workspace).resolve("b.png"), FILE_WORD)
        val harness = writeHarness(
            tempDir.resolve("harness"),
            tools = listOf("bash", "view_image"),
            instructions = EYES_INSTRUCTIONS,
        ).toString()
        val id = http.createSession(harness, workspace).id

        http.prompt(
            id,
            "Without using any tools, reply with the word written in this image, spelled exactly as shown: " +
                "![first](a.png) — this link leads to nothing: ![missing](missing.png).",
        )

        val attaching = http.awaitRunEnd(id)
        val attached = assertIs<AgentEvent.RunFinished>(attaching.last())
        assertEquals(RunResult.Status.COMPLETED, attached.status, "error: ${attached.error}")
        val started = assertIs<AgentEvent.RunStarted>(attaching.single { it is AgentEvent.RunStarted })
        assertEquals(
            listOf("a.png" to "image/png"),
            started.attachments.map { it.link to it.mimeType },
            "only the real image resolves into an attachment; the dead link stays text",
        )
        assertTrue(started.attachments.single().base64Data.isNotBlank(), "the attachment carries the picture")
        assertContains(started.userMessage, "![missing](missing.png)", message = "the dead link stays verbatim")
        assertEquals(0, attaching.count { it is AgentEvent.ToolCallStarted }, "no tool showed the model the image")
        assertContains(
            assertNotNull(attached.finalMessage),
            PROMPT_WORD,
            ignoreCase = true,
            message = "so its word can only have reached the model through the attachment",
        )

        http.prompt(
            id,
            "Now call view_image on the file b.png, then call view_image on $HOSTED_IMAGE. Then reply with one " +
                "sentence containing the word in b.png and the phrase in the hosted image, each spelled exactly " +
                "as shown.",
        )

        val viewing = http.awaitRunEnd(id).drop(attaching.size)
        val viewed = assertIs<AgentEvent.RunFinished>(viewing.last())
        assertEquals(RunResult.Status.COMPLETED, viewed.status, "error: ${viewed.error}")
        val views = viewing.viewImageResultsBySource()
        listOf("b.png" to "image/png", HOSTED_IMAGE to "image/jpeg").forEach { (source, mimeType) ->
            val view = assertNotNull(views[source], "view_image was called on $source: ${views.keys}")
            assertEquals(mimeType, view.media?.mimeType, "the view of $source carries its image, labelled by its bytes")
        }
        val finalMessage = assertNotNull(viewed.finalMessage)
        listOf(FILE_WORD to "the local file", HOSTED_IMAGE_PHRASE to "the hosted image").forEach { (text, source) ->
            assertContains(finalMessage, text, ignoreCase = true, message = "$source reached the model")
        }
    }

    @Test
    @DisplayName(
        "view_image answers a web page with an error and an image over its limit with a truncated note, " +
            "and the run goes on",
    )
    fun pageAndOversizedImage() = withLiveServer { http ->
        val workspace = localWorkspace(tempDir)
        Path.of(workspace).resolve("big.png").writeBytes(PNG_SIGNATURE + ByteArray(BYTES_OVER_THE_IMAGE_LIMIT))
        val harness = writeHarness(
            tempDir.resolve("harness"),
            tools = listOf("view_image"),
            instructions = EYES_INSTRUCTIONS,
        ).toString()
        val id = http.createSession(harness, workspace).id

        http.prompt(
            id,
            "Call view_image on $HOSTED_PAGE, then call view_image on the file big.png. Neither shows you an " +
                "image; that is expected, do not retry either. Then reply with one short sentence.",
        )

        val events = http.awaitRunEnd(id)
        val finished = assertIs<AgentEvent.RunFinished>(events.last())
        assertEquals(RunResult.Status.COMPLETED, finished.status, "error: ${finished.error}")
        val results = events.viewImageResultsBySource()

        val pageResult = assertNotNull(results[HOSTED_PAGE], "view_image was called on the page: ${results.keys}")
        assertEquals(AgentEvent.ToolCallFinished.Outcome.ERROR, pageResult.outcome, "a web page is no image")
        assertContains(pageResult.resultText, "is not an image", message = "the error says why")
        assertNull(pageResult.media, "the page carries no image")

        val bigResult = assertNotNull(results["big.png"], "view_image was called on big.png: ${results.keys}")
        assertEquals(
            AgentEvent.ToolCallFinished.Outcome.SUCCESS,
            bigResult.outcome,
            "the limit changes the content, not the outcome",
        )
        assertTrue(bigResult.truncated, "the oversized image is recorded as truncated")
        assertNull(bigResult.media, "the oversized image never reaches the model")
        assertContains(bigResult.resultText, "larger than the limit", message = "the note in its place says why")
    }
}

private fun List<AgentEvent>.viewImageResultsBySource(): Map<String, AgentEvent.ToolCallFinished> {
    val sources = filterIsInstance<AgentEvent.ToolCallStarted>().filter { it.toolName == "view_image" }
        .associate { it.callId to it.arguments.getValue("source").jsonPrimitive.content }
    return filterIsInstance<AgentEvent.ToolCallFinished>().filter { it.callId in sources }
        .associateBy { sources.getValue(it.callId) }
}

private const val EYES_INSTRUCTIONS: String =
    "You are an assistant with eyes: use the view_image tool when asked to look at an image, " +
        "and keep final answers to a single short sentence."

private val PNG_SIGNATURE: ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

private const val BYTES_OVER_THE_IMAGE_LIMIT: Int = 6 * 1024 * 1024

private const val PROMPT_WORD: String = "QUUXLE"
private const val FILE_WORD: String = "XYZZY"
private const val HOSTED_IMAGE: String = "https://upload.wikimedia.org/wikipedia/commons/a/a9/Example.jpg"
private const val HOSTED_IMAGE_PHRASE: String = "just an"
private const val HOSTED_PAGE: String = "https://example.com/"
