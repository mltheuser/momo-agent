package codes.momo.agent

import ai.router.sdk.AiRouterClient
import ai.router.sdk.models.ChatMessage
import ai.router.sdk.models.ContentPartType
import codes.momo.agent.environment.LocalExecutionEnvironment
import codes.momo.agent.harness.Harness
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Exercises the whole agent loop against a live model: tool use followed by
 * a final answer, turn exhaustion, and multi-prompt continuation.
 */
class AgentLiveTest {

    @TempDir
    lateinit var workspace: Path

    // ─── Fixture helpers ──────────────────────────────────────────────

    private fun harness(): Harness = Harness(
        model = liveChatModel,
        tools = listOf("bash", "read_file", "write_file", "edit_file"),
        instructions = "You are a careful coding agent working in a project workspace. " +
            "Use your tools to inspect files when a question concerns them, and keep final answers short.",
    )

    private val ChatMessage.text: String
        get() = content.filter { it.type == ContentPartType.TEXT }.mapNotNull { it.text }.joinToString("")

    /** Every tool call in [transcript] must be answered by a later tool-role message. */
    private fun assertToolCallsAnswered(transcript: List<ChatMessage>) {
        transcript.forEachIndexed { index, message ->
            message.toolCalls.orEmpty().forEach { call ->
                assertTrue(
                    transcript.drop(index + 1).any { it.role == "tool" && it.toolCallId == call.id },
                    "tool call '${call.id}' (${call.function.name}) has no tool result message",
                )
            }
        }
    }

    // ─── Scenarios ────────────────────────────────────────────────────

    @Test
    @DisplayName("Tool-then-finish: the model reads a planted file through a tool, then answers")
    fun toolThenFinish() = runBlocking {
        workspace.resolve("token.txt").writeText("The token is: $TOKEN\n")
        AiRouterClient(liveBaseUrl).use { client ->
            val environment = LocalExecutionEnvironment(workspace)
            val agent = Agent(harness(), client, environment, "Live test session")

            val result = agent.send(
                AgentInput.UserMessage(
                    "Read the file ${environment.workspacePath}/token.txt with the read_file tool " +
                        "and reply with the token it contains.",
                ),
            )

            assertEquals(PromptResult.Status.COMPLETED, result.status, "error: ${result.error}")
            val finalMessage = assertNotNull(result.finalMessage)
            assertTrue(
                finalMessage.contains(TOKEN, ignoreCase = true),
                "expected the planted token in the final message: $finalMessage",
            )

            val transcript = result.transcript
            assertEquals("system", transcript[0].role)
            assertContains(transcript[0].text, environment.workspacePath)
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
    @DisplayName("Turn exhaustion: a single-turn budget ends a tool-calling run as TURNS_EXHAUSTED, repaired")
    fun turnExhaustion() = runBlocking {
        workspace.resolve("data.txt").writeText("42\n")
        AiRouterClient(liveBaseUrl).use { client ->
            val agent = Agent(
                harness = harness(),
                client = client,
                environment = LocalExecutionEnvironment(workspace),
                eventListener = NoOpAgentEventListener,
                budgets = RunBudgets(maxTurns = 1),
                session = SessionState.Fresh("Live test session"),
            )

            val result = agent.send(
                AgentInput.UserMessage(
                    "You must inspect the workspace files with your tools before answering. " +
                        "What is the content of data.txt?",
                ),
            )

            // A model answering without any tool call is legal behaviour that
            // just cannot demonstrate exhaustion; skip instead of flaking.
            assumeTrue(
                result.status != PromptResult.Status.COMPLETED,
                "model answered without calling a tool on its only turn",
            )
            assertEquals(PromptResult.Status.TURNS_EXHAUSTED, result.status, "error: ${result.error}")
            assertNull(result.finalMessage)
            assertEquals(1, result.turnsUsed)
            assertToolCallsAnswered(result.transcript)
            val lastMessage = result.transcript.last()
            assertEquals("tool", lastMessage.role)
            assertEquals(ABORTED_TOOL_RESULT_TEXT, lastMessage.text)
        }
    }

    @Test
    @DisplayName("Multi-prompt: a follow-up prompt continues the conversation with reset budget counters")
    fun multiPromptContinuation() = runBlocking {
        workspace.resolve("token.txt").writeText("$TOKEN\n")
        AiRouterClient(liveBaseUrl).use { client ->
            val environment = LocalExecutionEnvironment(workspace)
            val agent = Agent(harness(), client, environment, "Live test session")

            val first = agent.send(
                AgentInput.UserMessage(
                    "Read the file ${environment.workspacePath}/token.txt with the read_file tool " +
                        "and reply with the token it contains.",
                ),
            )
            assertEquals(PromptResult.Status.COMPLETED, first.status, "error: ${first.error}")

            val second = agent.send(
                AgentInput.UserMessage(
                    "Repeat the exact token you found before. Answer from the conversation, without using any tool.",
                ),
            )

            assertEquals(PromptResult.Status.COMPLETED, second.status, "error: ${second.error}")
            val answer = assertNotNull(second.finalMessage)
            assertTrue(
                answer.contains(TOKEN, ignoreCase = true),
                "expected the token recalled from the first run: $answer",
            )
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
