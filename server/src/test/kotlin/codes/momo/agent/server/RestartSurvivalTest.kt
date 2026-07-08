package codes.momo.agent.server

import ai.router.sdk.AiRouterClient
import codes.momo.agent.AgentInput
import codes.momo.agent.PromptResult
import codes.momo.agent.askUserCall
import codes.momo.agent.assistantResponse
import codes.momo.agent.baseUrl
import codes.momo.agent.scriptedServer
import codes.momo.agent.toolCallResponse
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RestartSurvivalTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("A session parked on a question survives a server restart as closed, resumable state")
    fun parkedSessionSurvivesARestart() {
        val dataDir = tempDir.resolve("data")
        val harness = writeHarness(tempDir.resolve("harness")).toString()
        val workspace = EnvironmentSpec.Local(tempDir.resolve("workspace").createDirectories().toString())

        // First server process: create a session and drive its prompt onto a question.
        val id = scriptedServer(toolCallResponse(askUserCall(id = "call-1", question = "Which color?")))
            .use { llm ->
                AiRouterClient(llm.baseUrl).use { client ->
                    val registry = SessionRegistry(dataDir, client)
                    runBlocking {
                        val created = registry.create(harness, workspace)
                        val result = registry.attach(created.id)
                            .send(AgentInput.UserMessage("Ask the user which color to use."))
                        assertEquals(PromptResult.Status.AWAITING_USER, result.status, "error: ${result.error}")
                        created.id
                    }
                    // The registry is deliberately abandoned unclosed: a process death, not a clean stop.
                }
            }

        // Second server process over the same data directory.
        scriptedServer(assistantResponse(finishReason = "stop", text = "picked")).use { llm ->
            AiRouterClient(llm.baseUrl).use { client ->
                SessionRegistry(dataDir, client).use { registry ->
                    withServer(registry) { http ->
                        val info = http.get("/v1/sessions/$id").body<SessionInfo>()
                        assertEquals(SessionStatus.CLOSED, info.status)
                        assertEquals("Which color?", info.pendingQuestion)
                        assertEquals(1, info.lastPrompt?.turnsUsed)
                        assertEquals(2, info.lastPrompt?.totalTokens)

                        // Resumable: reattaching rebuilds the runtime from the stored log,
                        // still awaiting exactly that question.
                        val resumed = registry.attach(id)
                        val result = resumed.send(AgentInput.Answer("blue"))
                        assertEquals(PromptResult.Status.COMPLETED, result.status, "error: ${result.error}")
                        assertEquals("picked", result.finalMessage)

                        val after = http.get("/v1/sessions/$id").body<SessionInfo>()
                        assertEquals(SessionStatus.IDLE, after.status)
                        assertNull(after.pendingQuestion)
                        assertEquals(2, after.lastPrompt?.turnsUsed)
                    }
                }
            }
        }
    }
}
