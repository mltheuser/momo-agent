package codes.momo.agent.server

import ai.router.sdk.AiRouterClient
import ai.router.sdk.models.ChatRequest
import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import codes.momo.agent.TEST_RUN_SETTINGS
import codes.momo.agent.asReply
import codes.momo.agent.assistantResponse
import codes.momo.agent.baseUrl
import codes.momo.agent.scriptedServer
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RestartSurvivalTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("A mid-conversation session survives a restart as an ordinary session answering the next prompt")
    fun midConversationSessionSurvivesARestart() {
        val dataDir = tempDir.resolve("data")
        val harness = writeHarness(tempDir.resolve("harness")).toString()
        val workspace = localWorkspace(tempDir)

        // First server process: one run ending with a question as the final message.
        val id = scriptedServer(assistantResponse(finishReason = "stop", text = "Which color?"))
            .use { llm ->
                AiRouterClient(llm.baseUrl).use { client ->
                    val registry = SessionRegistry(dataDir, client)
                    runBlocking {
                        val created = registry.create(harness, workspace)
                        registry.startRun(created.id, "Ask the user which color to use.", TEST_RUN_SETTINGS)
                        registry.awaitRunEnd(created.id)
                        created.id
                    }
                    // The registry is deliberately abandoned unclosed: a process death, not a clean stop.
                }
            }

        // Second server process over the same data directory.
        val requests = CopyOnWriteArrayList<ChatRequest>()
        scriptedServer(requests, assistantResponse(finishReason = "stop", text = "picked blue").asReply())
            .use { llm ->
                AiRouterClient(llm.baseUrl).use { client ->
                    SessionRegistry(dataDir, client).use { registry ->
                        withServer(registry, client) { http ->
                            val info = http.get("/v1/sessions/$id").body<SessionInfo>()
                            assertEquals(SessionStatus.CLOSED, info.status)
                            assertEquals(1, info.lastRun?.turnsUsed)
                            assertEquals(2, info.lastRun?.totalTokens)

                            // The stored history streams without attaching the dormant session.
                            val history = http.streamEvents(id)
                            val question = assertIs<AgentEvent.RunFinished>(history.last().event)
                            assertEquals("Which color?", question.finalMessage)
                            assertEquals(SessionStatus.CLOSED, http.get("/v1/sessions/$id").body<SessionInfo>().status)

                            // Resumable: the next prompt rebuilds the runtime from the
                            // stored log and answers the question in the same conversation.
                            http.prompt(id, "blue")
                            val resumed = http.streamEvents(id, afterSequenceId = history.last().id)
                            val answer = assertIs<AgentEvent.RunFinished>(resumed.last().event)
                            assertEquals(RunResult.Status.COMPLETED, answer.status)
                            assertEquals("picked blue", answer.finalMessage)
                            assertEquals(
                                listOf("system", "user", "assistant", "user"),
                                requests.single().messages.map { it.role },
                            )

                            val after = http.get("/v1/sessions/$id").body<SessionInfo>()
                            assertEquals(SessionStatus.IDLE, after.status)
                            assertEquals(1, after.lastRun?.turnsUsed)
                        }
                    }
                }
            }
    }

    @Test
    @DisplayName("A session's renamed title and favorite flag survive a restart on a dormant session")
    fun titleAndFavoriteSurviveARestart() {
        val dataDir = tempDir.resolve("data")
        val harness = writeHarness(tempDir.resolve("harness")).toString()
        val workspace = localWorkspace(tempDir)

        // First server process: rename and favorite, then die without closing.
        val id = unusedAiRouterClient().use { client ->
            val registry = SessionRegistry(dataDir, client)
            runBlocking {
                val created = registry.create(harness, workspace)
                registry.rename(created.id, "Kept title")
                registry.setFavorite(created.id, true)
                created.id
            }
            // The registry is deliberately abandoned unclosed: a process death, not a clean stop.
        }

        // Second server process over the same data directory.
        unusedAiRouterClient().use { client ->
            SessionRegistry(dataDir, client).use { registry ->
                withServer(registry, client) { http ->
                    val info = http.get("/v1/sessions/$id").body<SessionInfo>()
                    assertEquals("Kept title", info.title)
                    assertTrue(info.favorite)
                    assertEquals(SessionStatus.CLOSED, info.status)
                }
            }
        }
    }

    @Test
    @DisplayName("A stored session whose metadata no longer parses is unreadable but still deletable")
    fun unreadableMetadataSessionCanBeDeleted() {
        val folder = tempDir.resolve("data/sessions/broken-session").createDirectories()
        folder.resolve("session.json").writeText("""{"harnessPath":"/h"}""")
        folder.resolve("events.jsonl").writeText(
            """{"type":"session_started","sequenceId":0,"timestampMillis":0,""" +
                """"sessionId":"broken-session","title":"b"}""" + "\n",
        )

        withSessionServer(tempDir) { http ->
            assertEquals(HttpStatusCode.InternalServerError, http.get("/v1/sessions/broken-session").status)
            assertEquals(HttpStatusCode.NoContent, http.delete("/v1/sessions/broken-session").status)
            assertEquals(HttpStatusCode.NotFound, http.get("/v1/sessions/broken-session").status)
            assertFalse(folder.isDirectory())
        }
    }
}
