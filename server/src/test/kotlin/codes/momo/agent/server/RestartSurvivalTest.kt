package codes.momo.agent.server

import ai.router.sdk.AiRouterClient
import codes.momo.agent.RunResult
import codes.momo.agent.assistantResponse
import codes.momo.agent.baseUrl
import codes.momo.agent.scriptedServer
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals

class RestartSurvivalTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("A mid-conversation session survives a restart as an ordinary session answering the next send")
    fun midConversationSessionSurvivesARestart() {
        val dataDir = tempDir.resolve("data")
        val harness = writeHarness(tempDir.resolve("harness")).toString()
        val workspace = EnvironmentSpec.Local(tempDir.resolve("workspace").createDirectories().toString())

        // First server process: one run ending with a question as the final message.
        val id = scriptedServer(assistantResponse(finishReason = "stop", text = "Which color?"))
            .use { llm ->
                AiRouterClient(llm.baseUrl).use { client ->
                    val registry = SessionRegistry(dataDir, client)
                    runBlocking {
                        val created = registry.create(harness, workspace)
                        val result = registry.attach(created.id)
                            .send("Ask the user which color to use.")
                        assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
                        assertEquals("Which color?", result.finalMessage)
                        created.id
                    }
                    // The registry is deliberately abandoned unclosed: a process death, not a clean stop.
                }
            }

        // Second server process over the same data directory.
        scriptedServer(assistantResponse(finishReason = "stop", text = "picked blue")).use { llm ->
            AiRouterClient(llm.baseUrl).use { client ->
                SessionRegistry(dataDir, client).use { registry ->
                    withServer(registry) { http ->
                        val info = http.get("/v1/sessions/$id").body<SessionInfo>()
                        assertEquals(SessionStatus.CLOSED, info.status)
                        assertEquals(1, info.lastRun?.turnsUsed)
                        assertEquals(2, info.lastRun?.totalTokens)

                        // Resumable: reattaching rebuilds the runtime from the stored
                        // log; the next prompt answers the question in the same
                        // conversation.
                        val resumed = registry.attach(id)
                        val result = resumed.send("blue")
                        assertEquals(RunResult.Status.COMPLETED, result.status, "error: ${result.error}")
                        assertEquals("picked blue", result.finalMessage)
                        assertEquals(
                            listOf("system", "user", "assistant", "user", "assistant"),
                            result.transcript.map { it.role },
                        )

                        val after = http.get("/v1/sessions/$id").body<SessionInfo>()
                        assertEquals(SessionStatus.IDLE, after.status)
                        assertEquals(1, after.lastRun?.turnsUsed)
                    }
                }
            }
        }
    }
}
