package codes.momo.agent

import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.harness.Harness
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Exercises the whole agent loop against a live model: tool use followed
 * by a final answer, and multi-prompt continuation.
 */
class AgentLiveTest {

    @TempDir
    lateinit var workspace: Path

    // ─── Fixture helpers ──────────────────────────────────────────────

    private fun harness(): Harness = Harness(
        tools = listOf("bash"),
        instructions = "You are a careful coding agent working in a project workspace. " +
            "Use your bash tool to inspect files when a question concerns them, and keep final answers short.",
    )

    // ─── Scenarios ────────────────────────────────────────────────────

    @Test
    @DisplayName("Tool-then-finish: the model reads a planted file through a tool, then answers")
    fun toolThenFinish() = runBlocking {
        workspace.resolve("token.txt").writeText("The token is: $TOKEN\n")
        liveAiRouterClient().use { client ->
            val environment = ExecutionEnvironment(workspace)
            val agent = liveAgent(harness(), client, environment, "Live test session")

            val result = agent.send(
                "Read the file ${environment.workspacePath}/token.txt with the bash tool " +
                    "and reply with the token it contains.",
                liveRunSettings,
            )

            assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
            val finalMessage = assertNotNull(result.finalMessage)
            assertContains(finalMessage, TOKEN, ignoreCase = true, message = "the planted token must reach the answer")

            val transcript = result.transcript
            assertEquals("system", transcript[0].role)
            assertEquals("user", transcript[1].role)
            assertTrue(
                transcript.any { it.role == "assistant" && !it.toolCalls.isNullOrEmpty() },
                "expected at least one tool-calling assistant message",
            )
            assertToolCallsAnswered(transcript)
            val last = transcript.last()
            assertEquals("assistant", last.role)
            assertTrue(last.toolCalls.isNullOrEmpty(), "the final message must not carry tool calls")

            assertTrue(result.turnsUsed >= 2, "expected a tool turn plus an answer turn, was ${result.turnsUsed}")
            assertTrue(result.usage.promptTokens > 0, "expected prompt tokens to be counted")
            assertTrue(result.usage.completionTokens > 0, "expected completion tokens to be counted")
            assertTrue(result.usage.totalTokens > 0, "expected total tokens to be counted")
            assertTrue(result.elapsed > Duration.ZERO)
        }
    }

    @Test
    @DisplayName("Multi-prompt: a follow-up prompt continues the conversation with reset budget counters")
    fun multiPromptContinuation() = runBlocking {
        val tokenFile = workspace.resolve("token.txt")
        tokenFile.writeText("$TOKEN\n")
        liveAiRouterClient().use { client ->
            val environment = ExecutionEnvironment(workspace)
            val agent = liveAgent(harness(), client, environment, "Live test session")

            val first = agent.send(
                "Read the file ${environment.workspacePath}/token.txt with the bash tool " +
                    "and reply with the token it contains.",
                liveRunSettings,
            )
            assertEquals(RunResult.Status.COMPLETED, first.status, "error: ${first.error}")

            // Deleting the file leaves the conversation as the token's only
            // remaining source: recalling it is what continuation means here.
            tokenFile.deleteExisting()

            val second = agent.send(
                "Remind me of the exact token you just read — you already have it in this conversation.",
                liveRunSettings,
            )

            assertEquals(RunResult.Status.COMPLETED, second.status, "error: ${second.error}")
            val answer = assertNotNull(second.finalMessage)
            assertContains(answer, TOKEN, ignoreCase = true, message = "the token must be recalled from the first run")
            // The first run's transcript is a strict prefix of the second's.
            assertEquals(first.transcript, second.transcript.subList(0, first.transcript.size))
            assertTrue(second.transcript.size > first.transcript.size)
            // Reset budgets: the second result counts only its own turns.
            val secondRunMessages = second.transcript.drop(first.transcript.size)
            assertEquals(
                secondRunMessages.count { it.role == "assistant" },
                second.turnsUsed,
                "turnsUsed must count only the second run's LLM calls",
            )
            assertTrue(second.turnsUsed >= 1)
            assertTrue(second.elapsed > Duration.ZERO)
        }
    }
}

/** The planted needle no prompt can answer without actually reading the file. */
private const val TOKEN: String = "plugh-7194"
