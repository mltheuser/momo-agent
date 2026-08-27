package codes.momo.agent

import codes.momo.agent.environment.ExecResult
import codes.momo.agent.tool.FixedResultEnvironment
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PromptAttachmentsTest {

    @Test
    @DisplayName("Markdown image links resolve through the environment, deduplicated by link")
    fun resolvesMarkdownImageLinksOnce() = runBlocking {
        val environment = FixedResultEnvironment(
            completed(PNG_BASE64.length.toString()),
            completed(PNG_BASE64),
        )

        val attachments = resolvePromptAttachments(
            "Compare ![before](shots/a.png) against ![after](shots/a.png).",
            environment,
        )

        val attachment = attachments.single()
        assertEquals("shots/a.png", attachment.link)
        assertEquals("image/png", attachment.mimeType)
        assertEquals(PNG_BASE64, attachment.base64Data)
    }

    @Test
    @DisplayName("A link that fails to load resolves to nothing — silently")
    fun unloadableLinkResolvesToNothing() = runBlocking {
        val environment = FixedResultEnvironment(
            ExecResult.Completed(1, "", "wc: gone.png: No such file or directory", false, false),
        )

        assertTrue(resolvePromptAttachments("See ![missing](gone.png).", environment).isEmpty())
    }

    @Test
    @DisplayName("An unreachable http link resolves to nothing, and never touches the environment")
    fun unreachableUrlResolvesToNothing() = runBlocking {
        val environment = FixedResultEnvironment()

        assertTrue(resolvePromptAttachments("See ![shot](http://127.0.0.1:1/shot.png).", environment).isEmpty())
        assertEquals(0, environment.callCount)
    }

    @Test
    @DisplayName("A prompt without markdown image syntax resolves to nothing without an exec")
    fun plainPromptNeverExecs() = runBlocking {
        val environment = FixedResultEnvironment()

        assertTrue(resolvePromptAttachments("Just text, a [link](a.png) and a lone bang!", environment).isEmpty())
        assertEquals(0, environment.callCount)
    }
}

private fun completed(stdout: String): ExecResult =
    ExecResult.Completed(exitCode = 0, stdout = stdout, stderr = "", stdoutTruncated = false, stderrTruncated = false)

private val PNG_BASE64: String = Base64.getEncoder().encodeToString(
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(8),
)
