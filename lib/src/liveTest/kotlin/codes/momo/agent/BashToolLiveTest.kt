package codes.momo.agent

import ai.router.sdk.dsl.chatRequest
import codes.momo.agent.environment.ExecutionEnvironment
import codes.momo.agent.tool.BashTool
import codes.momo.agent.tool.ToolRegistry
import codes.momo.agent.tool.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The bash tool's own definition, judged by a live model: the description
 * and schema the library generates are ones a real model calls, and the
 * arguments it produces dispatch through the registry onto a real
 * workspace. What the model does with the result afterwards belongs to the
 * agent loop's case, not this one.
 */
class BashToolLiveTest {

    @TempDir
    lateinit var workspace: Path

    @Test
    @DisplayName("Tool round-trip: the model calls bash and the registry runs its command over the workspace")
    fun bashToolRoundTrip() = runBlocking {
        workspace.resolve(NOTES).writeText("the magic word is $TOKEN\n")
        val environment = ExecutionEnvironment(workspace)
        val registry = ToolRegistry(listOf(BashTool(environment.workspacePath, environment.privilege)))

        liveAiRouterClient().use { client ->
            // The DSL's ToolsBuilder.addTool(ToolDefinition) is internal, so
            // pre-built definitions travel via the ChatRequest data class.
            val request = chatRequest(liveChatModel) {
                messages {
                    system {
                        text(
                            "You are a coding agent working in a project workspace. Reach for your bash " +
                                "tool to inspect files, then say briefly what you found.",
                        )
                    }
                    user { text("Run this command with the bash tool and tell me its output: cat $NOTES") }
                }
            }.copy(tools = registry.definitions(listOf("bash")))

            val response = client.chat(request)

            val call = assertNotNull(
                response.message.toolCalls?.firstOrNull(),
                "expected a bash tool call (finish_reason=${response.finishReason}, text='${response.textContent}')",
            )
            assertEquals("bash", call.function.name)

            val result = registry
                .execute(call.function.name, call.function.arguments, environment, TOOL_TIMEOUT)
                .result

            assertIs<ToolResult.Success>(result, "dispatch did not succeed, result text: ${result.text}")
            assertContains(
                result.text,
                TOKEN,
                message = "the model's command must have read the planted file: ${call.function.arguments}",
            )
        }
    }
}

/**
 * This case dispatches without an agent, so it bounds the command itself:
 * the registry's own default is the library's day-long ceiling, and what
 * runs is whatever the model wrote — a `cat` missing its argument reads
 * stdin forever.
 */
private val TOOL_TIMEOUT: Duration = 60.seconds

private const val NOTES: String = "notes.txt"

/** The planted needle only a command that really ran can produce. */
private const val TOKEN: String = "plugh-3312"
