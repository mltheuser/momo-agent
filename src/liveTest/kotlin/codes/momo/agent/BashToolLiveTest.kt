package codes.momo.agent

import ai.router.sdk.AiRouterClient
import ai.router.sdk.dsl.chatRequest
import ai.router.sdk.models.ChatMessage
import ai.router.sdk.models.ContentPart
import ai.router.sdk.models.ContentPartType
import codes.momo.agent.environment.LocalExecutionEnvironment
import codes.momo.agent.tool.BashTool
import codes.momo.agent.tool.ToolRegistry
import codes.momo.agent.tool.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies the bash tool round-trips through a live model: definition →
 * tool call → dispatched execution over a real workspace → tool message →
 * follow-up answer.
 */
class BashToolLiveTest {

    @TempDir
    lateinit var workspace: Path

    @Test
    @DisplayName("Tool round-trip: the model calls bash, the registry executes it, and the model answers")
    fun bashToolRoundTrip() = runBlocking {
        workspace.resolve("notes.txt").writeText("the magic word is plugh\n")
        val registry = ToolRegistry(listOf(BashTool()))
        val environment = LocalExecutionEnvironment(workspace)

        AiRouterClient(liveBaseUrl).use { client ->
            // The DSL's ToolsBuilder.addTool(ToolDefinition) is internal, so
            // pre-built definitions travel via the ChatRequest data class.
            val request = chatRequest(liveChatModel) {
                messages {
                    system { text("You are a coding agent working in a workspace.") }
                    user { text("What is the content of notes.txt in the workspace? Check with the bash tool.") }
                }
            }.copy(tools = registry.definitions(listOf("bash")))

            val first = client.chat(request)
            val toolCall = first.message.toolCalls?.firstOrNull()
            // Local models occasionally decline to call the tool; report a JUnit
            // skip rather than silently passing or flaking the test.
            assumeTrue(
                toolCall != null,
                "model produced no tool call (finish_reason=${first.finishReason})",
            )
            requireNotNull(toolCall)

            // Hard assertions from here: the prompt directs the model to the
            // bash tool, and any bash command over the workspace completes as
            // Success (non-zero exits included) — a hallucinated tool name or
            // rejected arguments must fail red, not skip.
            assertEquals("bash", toolCall.function.name)
            val toolResult = registry.execute(toolCall.function.name, toolCall.function.arguments, environment)
            assertIs<ToolResult.Success>(toolResult, "dispatch did not succeed, result text: ${toolResult.text}")

            val followUp = client.chat(
                request.copy(
                    messages = request.messages + first.message + toolMessage(toolCall.id, toolResult.text),
                ),
            )

            // A model that reacts to the result by chaining another tool call
            // (with no text) is valid agentic behaviour, not a wiring failure.
            assumeTrue(
                followUp.message.toolCalls.isNullOrEmpty() || followUp.textContent.isNotBlank(),
                "model chained another tool call instead of answering (finish_reason=${followUp.finishReason})",
            )
            assertTrue(
                followUp.textContent.isNotBlank(),
                "expected a non-empty follow-up reply (finish_reason=${followUp.finishReason})",
            )
        }
    }

    private fun toolMessage(callId: String, text: String): ChatMessage =
        ChatMessage(
            role = "tool",
            content = listOf(ContentPart(type = ContentPartType.TEXT, text = text)),
            toolCallId = callId,
        )
}
