package codes.momo.agent.server

import ai.router.sdk.models.ChatRequest
import codes.momo.agent.AgentEvent
import codes.momo.agent.RunResult
import codes.momo.agent.ScriptedReply
import codes.momo.agent.asReply
import codes.momo.agent.assertTwoCleanRuns
import codes.momo.agent.assistantResponse
import codes.momo.agent.text
import io.ktor.client.call.body
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.setPosixFilePermissions
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InteractionApiTest {

    @TempDir
    lateinit var tempDir: Path

    // ─── Prompt + stream happy path ───────────────────────────────────

    @Test
    @DisplayName("A full conversation over HTTP: prompt, stream the run, answer the question, stream the follow-up")
    fun fullConversationOverHttp() {
        val requests = CopyOnWriteArrayList<ChatRequest>()
        withScriptedSessionServer(
            tempDir,
            requests,
            assistantResponse(finishReason = "stop", text = "Which color?").asReply(),
            assistantResponse(finishReason = "stop", text = "picked blue").asReply(),
        ) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id

            assertEquals(id, http.prompt(id, "Ask the user which color to use.").id)
            val firstRun = http.streamEvents(id)
            val question = assertIs<AgentEvent.RunFinished>(firstRun.last().event)
            assertEquals(RunResult.Status.COMPLETED, question.status)
            assertEquals("Which color?", question.finalMessage)

            http.prompt(id, "blue")
            val secondRun = http.streamEvents(id, afterSequenceId = firstRun.last().id)
            val answer = assertIs<AgentEvent.RunFinished>(secondRun.last().event)
            assertEquals("picked blue", answer.finalMessage)

            assertTwoCleanRuns((firstRun + secondRun).map { it.event }, secondUserMessage = "blue")

            // The follow-up request carried the whole first exchange.
            val followUp = requests.last().messages
            assertEquals(listOf("system", "user", "assistant", "user"), followUp.map { it.role })
            assertEquals("Which color?", followUp[2].text)
            assertEquals("blue", followUp[3].text)
        }
    }

    @Test
    @DisplayName("Reconnecting with Last-Event-ID resumes strictly after it")
    fun lastEventIdReplaysExactlyTheTail() {
        withScriptedSessionServer(tempDir, assistantResponse(finishReason = "stop", text = "done")) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            http.prompt(id, "go")

            val full = http.streamEvents(id)
            assertTrue(full.size > 2, "a run must log more than two events")

            val replay = http.streamEvents(id, afterSequenceId = full[1].id)
            assertEquals(full.drop(2), replay)
        }
    }

    @Test
    @DisplayName("Two concurrent subscribers see the same stream: same events, same order")
    fun concurrentSubscribersSeeTheSameStream() {
        withScriptedSessionServer(tempDir, assistantResponse(finishReason = "stop", text = "done")) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            coroutineScope {
                val first = async { http.streamEvents(id) }
                val second = async { http.streamEvents(id) }
                http.prompt(id, "go")
                assertEquals(first.await(), second.await())
                assertTrue(first.await().isNotEmpty())
            }
        }
    }

    @Test
    @DisplayName("Prompting a closed session resumes it; a parked subscriber sees the new run's events")
    fun promptResumesAClosedSession() {
        withScriptedSessionServer(
            tempDir,
            assistantResponse(finishReason = "stop", text = "first"),
            assistantResponse(finishReason = "stop", text = "second"),
        ) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            http.prompt(id, "one")
            val firstRun = http.streamEvents(id)

            http.post("/v1/sessions/$id/close")
            assertEquals(SessionStatus.CLOSED, http.get("/v1/sessions/$id").body<SessionInfo>().status)

            coroutineScope {
                // Subscribed while dormant: the wake-up signal outlives the runtime.
                val watcher = async { http.streamEvents(id, afterSequenceId = firstRun.last().id) }
                http.prompt(id, "two")
                val secondRun = watcher.await()
                assertEquals("second", assertIs<AgentEvent.RunFinished>(secondRun.last().event).finalMessage)
            }
            assertEquals(SessionStatus.IDLE, http.get("/v1/sessions/$id").body<SessionInfo>().status)
        }
    }

    @Test
    @DisplayName("Deleting a session ends its live event streams")
    fun deleteEndsLiveStreams() {
        withScriptedSessionServer(tempDir, assistantResponse(finishReason = "stop", text = "done")) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            http.prompt(id, "go")
            http.awaitRunEnd(id)

            withTimeout(STREAM_TIMEOUT_MILLIS) {
                coroutineScope {
                    val connected = CompletableDeferred<Unit>()
                    val watcher = async {
                        var received = 0
                        http.sse("/v1/sessions/$id/events") {
                            incoming.collect { frame ->
                                if (frame.data != null) received++
                                connected.complete(Unit)
                            }
                        }
                        received // Reached only once the server ends the stream.
                    }
                    connected.await()
                    http.delete("/v1/sessions/$id")
                    assertTrue(watcher.await() > 0)
                }
            }
        }
    }

    // ─── Conflicts & errors ───────────────────────────────────────────

    @Test
    @DisplayName("A prompt while a run is active is a 409 conflict; the held run then finishes cleanly")
    fun promptWhileRunningConflicts() {
        val held = ScriptedReply.Held(assistantResponse(finishReason = "stop", text = "done"))
        withScriptedSessionServer(tempDir, held) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id

            assertEquals(SessionStatus.RUNNING, http.prompt(id, "work").status)

            val conflict = http.promptResponse(id, "impatient follow-up")
            assertEquals(HttpStatusCode.Conflict, conflict.status)
            assertEquals("conflict", conflict.body<ApiError>().code)

            held.release()
            val events = http.streamEvents(id)
            val finished = assertIs<AgentEvent.RunFinished>(events.last().event)
            assertEquals(RunResult.Status.COMPLETED, finished.status)
            assertEquals(1, events.count { it.event is AgentEvent.RunStarted }, "the 409 started no run")
        }
    }

    @Test
    @DisplayName("A blank prompt is a 400 invalid_request")
    fun blankPromptIsRejected() {
        withSessionServer(tempDir) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id

            val response = http.promptResponse(id, "   ")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalid_request", response.body<ApiError>().code)
        }
    }

    @Test
    @DisplayName("Prompt and events on an unknown session are 404 unknown_session")
    fun unknownSessionIs404() {
        withSessionServer(tempDir) { http ->
            val prompt = http.promptResponse("no-such-id", "hello")
            assertEquals(HttpStatusCode.NotFound, prompt.status)
            assertEquals("unknown_session", prompt.body<ApiError>().code)

            val events = http.get("/v1/sessions/no-such-id/events")
            assertEquals(HttpStatusCode.NotFound, events.status)
            assertEquals("unknown_session", events.body<ApiError>().code)
        }
    }

    @Test
    @DisplayName("A session whose event log stopped persisting refuses new runs with a 500 event_log_failed")
    fun failedEventLogRefusesNewRuns() {
        withScriptedSessionServer(tempDir, assistantResponse(finishReason = "stop", text = "done")) { http ->
            val id = http.createSession(harnessPath(tempDir), localWorkspace(tempDir)).id
            http.post("/v1/sessions/$id/close") // Detaches the log's open writer.
            val events = tempDir.resolve("data/sessions/$id/events.jsonl")
            events.setPosixFilePermissions(setOf(PosixFilePermission.OWNER_READ))

            // The resumed run cannot append: its events are lost and the log is poisoned.
            http.prompt(id, "work")
            http.awaitRunEnd(id)

            val refused = http.promptResponse(id, "again")
            assertEquals(HttpStatusCode.InternalServerError, refused.status)
            assertEquals("event_log_failed", refused.body<ApiError>().code)
        }
    }
}
