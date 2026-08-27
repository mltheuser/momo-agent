package codes.momo.agent

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
import kotlin.test.assertTrue

/**
 * The vision path end to end: a harness listing `view_image` on a live
 * (vision-capable) model answers a question about a planted image — proof
 * the image content part really reaches the model's eyes, since the
 * planted word exists nowhere in text.
 */
class ViewImageLiveTest {

    @TempDir
    lateinit var workspace: Path

    @Test
    @DisplayName("The model views a planted image and reads the word rendered in it")
    fun modelReadsThePlantedImage() = runBlocking {
        val image = workspace.resolve("secret.png")
        writeWordImage(image, TOKEN)
        val harness = Harness(
            tools = listOf("bash", "view_image"),
            instructions = "You are an assistant with eyes: use the view_image tool when asked about " +
                "an image file, and keep final answers short.",
        )

        liveAiRouterClient().use { client ->
            val environment = LocalExecutionEnvironment(workspace)
            val agent = liveAgent(harness, client, environment, "View image live test")

            val result = agent.send(
                "Look at the image ${environment.workspacePath}/secret.png and reply with the single " +
                    "word written in it.",
                liveRunSettings,
            )

            assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
            val finalMessage = assertNotNull(result.finalMessage)
            assertContains(finalMessage, TOKEN, ignoreCase = true, message = "the word exists only inside the image")
            assertTrue(
                result.transcript.any { message ->
                    message.role == "tool" && message.content.any { it.base64Data != null }
                },
                "the tool message must carry the image content part",
            )
        }
    }
}

/** A word only vision can recover — never written to any file as text. */
private const val TOKEN: String = "XYZZY"
