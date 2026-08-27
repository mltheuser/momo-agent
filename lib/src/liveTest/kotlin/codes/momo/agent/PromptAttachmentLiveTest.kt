package codes.momo.agent

import ai.router.sdk.models.ContentPartType
import codes.momo.agent.environment.LocalExecutionEnvironment
import codes.momo.agent.harness.Harness
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Prompt image attachments end to end: a markdown image link in the prompt
 * puts the picture in front of a live model — proof the resolution really
 * feeds the model's eyes, since the planted word exists nowhere in text —
 * while a link that resolves to nothing stays plain prompt text, silently.
 */
class PromptAttachmentLiveTest {

    @TempDir
    lateinit var workspace: Path

    @Test
    @DisplayName("A relative-path image link grounds the response; a dead link degrades to plain text")
    fun promptedImageReachesTheModel() = runBlocking {
        writeWordImage(workspace.resolve("secret.png"), TOKEN)
        val harness = Harness(
            tools = listOf("bash"),
            instructions = "You are a concise assistant; answer in a single short sentence.",
        )

        liveAiRouterClient().use { client ->
            val agent = liveAgent(harness, client, LocalExecutionEnvironment(workspace), "Prompt attachment live test")

            val result = agent.send(
                "Without using any tools, reply with the single word written in ![screen](secret.png). " +
                    "Ignore ![missing](no-such.png).",
                liveRunSettings,
            )

            assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
            val finalMessage = assertNotNull(result.finalMessage)
            assertContains(finalMessage, TOKEN, ignoreCase = true, message = "the word exists only inside the image")
            val userParts = result.transcript.first { it.role == "user" }.content
            assertEquals(1, userParts.count { it.type == ContentPartType.IMAGE }, "only the real image resolves")
            val text = userParts.filter { it.type == ContentPartType.TEXT }.mapNotNull { it.text }.joinToString("")
            assertContains(text, "![missing](no-such.png)", message = "the dead link stays verbatim prompt text")
            assertContains(text, "![screen](secret.png)", message = "the resolved link's markdown stays as text too")
        }
    }
}

/** A word only vision can recover — never written to any file as text. */
private const val TOKEN: String = "QUUXLE"
