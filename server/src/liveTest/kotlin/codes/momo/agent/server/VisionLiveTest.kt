package codes.momo.agent.server

import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VisionLiveTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("A prompt image link attaches, view_image carries media, and both planted words come back")
    fun promptAttachmentAndViewImage() = withLiveServer { http ->
        val workspace = localWorkspace(tempDir)
        writeWordImage(Path.of(workspace.workspace).resolve("a.png"), PROMPT_WORD)
        writeWordImage(Path.of(workspace.workspace).resolve("b.png"), TOOL_WORD)
        val harness = writeHarness(
            tempDir.resolve("harness"),
            tools = listOf("bash", "view_image"),
            instructions = "You are an assistant with eyes: use the view_image tool when asked to look at " +
                "an image file, and keep final answers to a single short sentence.",
        ).toString()
        val id = http.createSession(harness, workspace).id

        http.prompt(
            id,
            "Here is the first image: ![first](a.png) — and a link to nothing: ![missing](missing.png). " +
                "Now call view_image on the file b.png. Then reply with one sentence containing the word written " +
                "in the first image and the word written in b.png, each spelled exactly as shown.",
        )

        val events = http.streamEvents(id).map { it.event }
        val finished = assertIs<AgentEvent.RunFinished>(events.last())
        assertEquals(RunResult.Status.COMPLETED, finished.status, "error: ${finished.error}")
        val started = assertIs<AgentEvent.RunStarted>(events.single { it is AgentEvent.RunStarted })
        assertEquals(
            listOf("a.png" to "image/png"),
            started.attachments.map { it.link to it.mimeType },
            "only the real image resolves into an attachment; the dead link stays text",
        )
        assertTrue(started.attachments.single().base64Data.isNotBlank(), "the attachment carries the picture")
        assertContains(started.userMessage, "![missing](missing.png)", message = "the dead link stays verbatim")
        val viewed = events.filterIsInstance<AgentEvent.ToolCallFinished>().filter { it.media != null }
        assertEquals(1, viewed.size, "exactly one view_image result carries media")
        assertEquals("image/png", assertNotNull(viewed.single().media).mimeType)
        val finalMessage = assertNotNull(finished.finalMessage)
        assertContains(
            finalMessage,
            PROMPT_WORD,
            ignoreCase = true,
            message = "the prompt attachment reached the model"
        )
        assertContains(finalMessage, TOOL_WORD, ignoreCase = true, message = "the viewed image reached the model")
    }
}

private const val PROMPT_WORD: String = "QUUXLE"
private const val TOOL_WORD: String = "XYZZY"
